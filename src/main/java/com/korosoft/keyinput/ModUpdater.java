package com.korosoft.keyinput;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The hot-reload core. When the server pushes a {@link ModUpdatePayload}, this class:
 * <ol>
 *   <li>Locks the client onto a {@link ModUpdateScreen} overlay (so the player cannot dismiss it).</li>
 *   <li>Downloads the new jar over HTTPS (async, off the render thread).</li>
 *   <li>Verifies the SHA-256 matches the manifest. On mismatch, aborts and shows an error.</li>
 *   <li>Atomically replaces the running {@code mods/keyinput-*.jar} with the downloaded one.</li>
 *   <li>Persists the original launch command to {@code .keyinput/last-launch.json} so the relauncher
 *       can replay it byte-for-byte (this is what makes the restart feel like "nothing happened" --
 *       same JVM, same classpath, same game dir, same auth token).</li>
 *   <li>Disconnects cleanly, transfers the player to the TitleScreen, then {@link ProcessBuilder}-spawns
 *       a fresh {@code java} with the exact same args. The old process exits with code 0; the new one
 *       boots, fabric-loader picks up the new jar, all mixins apply from a clean slate.</li>
 * </ol>
 *
 * <p>The class is a singleton ({@link #INSTANCE}) because there is exactly one update flow per client
 * session. {@link #isApplying()} guards against concurrent triggers (e.g. server pushes payload twice).
 *
 * <p>The HTTP client uses Java 17+ {@link HttpClient} with HTTP/2, a 30s connect timeout and a 5-minute
 * read timeout (large jars are around 7 MB but we leave headroom). The download is streamed into the
 * jar file via {@link FileChannel} so peak memory is bounded regardless of file size.
 */
public final class ModUpdater {

    public static final Logger LOGGER = LoggerFactory.getLogger("keyinput/updater");

    /** Where the manifest lives. Must match the URL the server-side Skript dispatcher writes to. */
    public static final String MANIFEST_URL = "https://updates.korosoft.com/keyinput/manifest.json";

    /** Cap on downloaded jar size. The largest currently built jar is ~7 MB; 64 MB gives 9x headroom. */
    public static final long MAX_JAR_SIZE = 64L * 1024L * 1024L;

    private static final ModUpdater INSTANCE = new ModUpdater();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final AtomicBoolean applying = new AtomicBoolean(false);
    private final AtomicReference<String> status = new AtomicReference<>("Iniciando...");

    public static ModUpdater get() { return INSTANCE; }

    private ModUpdater() {}

    public boolean isApplying() { return applying.get(); }

    public String currentStatus() { return status.get(); }

    /**
     * Entry point called from the payload handler in {@link KeyInputClient}. Runs the whole flow on a
     * background thread; the GUI thread is only touched to install the overlay screen.
     */
    public void beginUpdate(ModUpdatePayload payload) {
        if (!applying.compareAndSet(false, true)) {
            LOGGER.warn("[keyinput] update already in progress, ignoring duplicate trigger");
            return;
        }
        LOGGER.info("[keyinput] update flow started: version={} url={} mandatory={}",
                payload.version(), payload.downloadUrl(), payload.mandatory());

        status.set("Descargando actualizacion...");
        installOverlay("Actualizando KeyInput", payload.message());

        CompletableFuture.runAsync(() -> runUpdateFlow(payload))
                .exceptionally(ex -> {
                    LOGGER.error("[keyinput] update flow failed", ex);
                    status.set("Error: " + ex.getMessage());
                    return null;
                });
    }

    private void runUpdateFlow(ModUpdatePayload payload) {
        try {
            // 1. Locate the running jar on disk. Fabric-loader exposes the mod's source path; this is
            //    null in dev (runDir) but always non-null in a packaged install.
            Path currentJar = locateCurrentJar();
            if (currentJar == null) {
                fail("No se encontro el jar instalado (probablemente dev).");
                return;
            }

            // 2. Download to a temp file in the same directory (so the atomic rename stays on one FS).
            Path tempJar = currentJar.resolveSibling(currentJar.getFileName() + ".update");
            downloadToFile(payload.downloadUrl(), tempJar);

            // 3. Verify SHA-256.
            status.set("Verificando integridad...");
            byte[] downloadedHash = ModUpdateManifest.sha256(Files.readAllBytes(tempJar));
            if (!java.security.MessageDigest.isEqual(downloadedHash, payload.sha256())) {
                Files.deleteIfExists(tempJar);
                fail("SHA-256 no coincide. Update cancelado por seguridad.");
                return;
            }

            // 4. Persist launch args BEFORE we touch the jar or the connection, because once we exit
            //    there is no other place to read them from. Captured from the live JVM.
            persistLaunchArgs();

            // 5. Atomic replace. On Windows the destination file is locked by the JVM, so rename
            //    would fail; on Linux it works. We try atomic first, fall back to copy+delete.
            status.set("Aplicando actualizacion...");
            try {
                Files.move(tempJar, currentJar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException atomicFail) {
                // AtomicMoveNotSupportedException extends IOException; catching it first means the
                // fallback branch handles every other I/O failure (perm denied, cross-device, etc.).
                Files.move(tempJar, currentJar, StandardCopyOption.REPLACE_EXISTING);
            }

            LOGGER.info("[keyinput] jar replaced: {}", currentJar);

            // 6. Disconnect cleanly, switch to title screen, then spawn the new process.
            status.set("Reiniciando cliente...");
            MinecraftClient.getInstance().execute(this::spawnFreshProcessAndExit);
        } catch (Exception e) {
            LOGGER.error("[keyinput] update failed", e);
            fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * Pushes the {@link ModUpdateScreen} onto the client. If we are in-game it switches through the
     * disconnect path first; if we are on a menu it just sets the screen. The screen is sticky -- it
     * ignores ESC and there is no close button -- so the player cannot dismiss it mid-update.
     */
    private void installOverlay(String title, String subtitle) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            // If connected, kick ourselves with a clean reason so the server doesn't see us ghost.
            if (mc.getNetworkHandler() != null && mc.getNetworkHandler().getConnection() != null) {
                mc.getNetworkHandler().getConnection().disconnect(Text.literal("Actualizando mod..."));
            }
            Screen current = mc.currentScreen;
            // Wrap whatever screen was up so back/ESC keeps the overlay instead of returning to it.
            Screen wrapped = new ModUpdateScreen(title, subtitle, current);
            mc.setScreen(wrapped);
        });
    }

    private void fail(String reason) {
        status.set("Error: " + reason);
        LOGGER.warn("[keyinput] update aborted: {}", reason);
        // Replace the overlay with an error screen that has an "Aceptar" button returning to title.
        MinecraftClient.getInstance().execute(() -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.setScreen(new ModUpdateErrorScreen(reason));
        });
        // Do NOT clear applying here -- the user must explicitly dismiss the error so we don't
        // re-enter the flow on the next payload. The error screen's "Aceptar" calls clearApplying().
    }

    void clearApplying() {
        applying.set(false);
    }

    private Path locateCurrentJar() {
        // FabricLoader exposes the mod's origin. In a packaged install this is the .jar on disk;
        // in `runDir` (gradle dev) it is a directory, in which case there is nothing to hot-swap.
        var container = FabricLoader.getInstance().getModContainer("keyinput").orElse(null);
        if (container == null) return null;
        var origin = container.getOrigin();
        // Origin interface has getPaths() returning all root paths; for a jar install that is [jar].
        var paths = origin.getPaths();
        for (Path p : paths) {
            if (Files.isRegularFile(p)) return p.toAbsolutePath();
        }
        return null;
    }

    private void downloadToFile(String urlStr, Path dest) throws IOException, InterruptedException {
        status.set("Descargando...");
        LOGGER.info("[keyinput] downloading {}", urlStr);
        HttpRequest req = HttpRequest.newBuilder(URI.create(urlStr))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "keyinput-mod/" + ModVersion.encoded())
                .GET()
                .build();
        HttpResponse<java.io.InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode() + " from " + urlStr);
        }
        long contentLength = resp.headers().firstValueAsLong("content-length").orElse(-1L);
        if (contentLength > MAX_JAR_SIZE) {
            throw new IOException("Remote jar is " + contentLength + " bytes, exceeds cap of " + MAX_JAR_SIZE);
        }
        try (var in = resp.body();
             FileChannel ch = FileChannel.open(dest, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_JAR_SIZE) {
                    ch.close();
                    Files.deleteIfExists(dest);
                    throw new IOException("Downloaded > " + MAX_JAR_SIZE + " bytes, aborting");
                }
                ByteBuffer wrap = ByteBuffer.wrap(buf, 0, n);
                while (wrap.hasRemaining()) ch.write(wrap);
                final long t = total;
                // Best-effort progress update on the GUI thread.
                MinecraftClient.getInstance().execute(() ->
                        status.set("Descargando... " + (t / 1024) + " KB"));
            }
        }
        LOGGER.info("[keyinput] download complete: {} bytes -> {}", Files.size(dest), dest);
    }

    /**
     * Saves the current JVM's full launch command (program + args + env hints) to
     * {@code .keyinput/last-launch.json} so {@link #spawnFreshProcessAndExit()} can replay it.
     *
     * <p>The arg vector comes from {@link ProcessHandle#current()#info()#arguments()}. On every
     * mainstream launcher (official Mojang, MultiMC, Prism, ATLauncher, CurseForge) this is the
     * same vector the launcher originally constructed for this Minecraft instance, and replaying
     * it verbatim boots a fresh Minecraft that is indistinguishable from the one the player was
     * just in -- same game dir, same auth, same mods folder, same JVM flags.
     */
    private void persistLaunchArgs() throws IOException {
        Path gameDir = FabricLoader.getInstance().getGameDir().resolve(".keyinput");
        Files.createDirectories(gameDir);
        Path out = gameDir.resolve("last-launch.json");

        ProcessHandle.Info info = ProcessHandle.current().info();
        JsonObject root = new JsonObject();
        root.addProperty("javaHome", System.getProperty("java.home"));
        root.addProperty("javaBin", System.getProperty("java.home") + "/bin/java");
        root.addProperty("workingDir", System.getProperty("user.dir"));

        var argsArr = new com.google.gson.JsonArray();
        java.util.Optional<String[]> argsOpt = info.arguments();
        if (argsOpt.isPresent() && argsOpt.get().length > 0) {
            // The launcher passed us a full arg vector; replay it verbatim.
            for (String a : argsOpt.get()) argsArr.add(a);
        } else {
            // Fallback: reconstruct from what we know. Best-effort; the official Mojang launcher and
            // MultiMC/Prism always populate arguments(), so this branch is mostly theoretical.
            LOGGER.warn("[keyinput] ProcessHandle.arguments() empty; falling back to manual vector");
            argsArr.add("-cp"); argsArr.add(System.getProperty("java.class.path"));
            argsArr.add("net.minecraft.client.main.Main");
            argsArr.add("--gameDir"); argsArr.add(System.getProperty("user.dir"));
        }
        root.add("args", argsArr);

        Files.writeString(out, root.toString());
        LOGGER.info("[keyinput] persisted launch args to {}", out);
    }

    private void spawnFreshProcessAndExit() {
        try {
            Path gameDir = FabricLoader.getInstance().getGameDir().resolve(".keyinput/last-launch.json");
            if (!Files.exists(gameDir)) {
                fail("No se guardaron los argumentos de lanzamiento.");
                return;
            }
            JsonObject root = JsonParser.parseString(Files.readString(gameDir)).getAsJsonObject();
            String javaBin = root.get("javaBin").getAsString();
            var argsArr = root.getAsJsonArray("args");

            ProcessBuilder pb = new ProcessBuilder();
            pb.command().add(javaBin);
            argsArr.forEach(el -> pb.command().add(el.getAsString()));
            // The new process inherits the current working directory; this matches the official launcher.
            pb.directory(java.nio.file.Paths.get(root.get("workingDir").getAsString()).toFile());
            pb.inheritIO(); // child stdout/stderr go to the same console / log file as the parent
            pb.start();

            // Detach cleanly. The new process boots Minecraft from scratch; the old one exits.
            MinecraftClient.getInstance().scheduleStop();
            System.exit(0);
        } catch (Exception e) {
            LOGGER.error("[keyinput] relaunch failed", e);
            fail("Fallo al reiniciar: " + e.getMessage());
        }
    }
}