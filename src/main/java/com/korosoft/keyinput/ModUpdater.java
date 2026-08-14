package com.korosoft.keyinput;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
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
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Server-pushed and launch-time hot-reload for the keyinput mod.
 *
 * <p>Two triggers share one flow:
 * <ul>
 *   <li><b>Server push</b>: the KoroSoft server sends {@code keyinput:mod_update} (see
 *       {@link ModUpdatePayload}); the client downloads, verifies SHA-256, replaces the jar and
 *       asks the player to restart.</li>
 *   <li><b>Launch check</b>: on every game boot (before joining any server) the client asks the
 *       GitHub releases API for the latest published jar; if it is newer than the running build it
 *       shows the same update window with a progress bar, applies the update and asks to restart.
 *       The latest GitHub release is the source of truth, so nothing needs to be re-hosted.</li>
 * </ul>
 *
 * <p>The game is always closed by the player (button or 10-second countdown on
 * {@link UpdateAppliedScreen}). Managed launchers (PrismLauncher, MultiMC) own the game process
 * and feed authentication through their bootstrap IPC, so an automatic relaunch from inside the
 * game is impossible there ("Launch aborted by the launcher"); a uniform close-and-reopen screen
 * works everywhere and guarantees the next boot loads the new jar.
 */
public final class ModUpdater {

    public static final Logger LOGGER = LoggerFactory.getLogger("korosoft-core/updater");
    public static final String GITHUB_LATEST_RELEASE_URL =
            "https://api.github.com/repos/loonbac/korosoft-keyinput-autoupdater/releases/latest";
    public static final long MAX_JAR_SIZE = 64L * 1024L * 1024L;

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    private static final ModUpdater INSTANCE = new ModUpdater();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final AtomicBoolean applying = new AtomicBoolean(false);
    private final AtomicReference<String> status = new AtomicReference<>("Iniciando...");
    private final AtomicReference<Float> progress = new AtomicReference<>(0f);
    /** "updating" while downloading/applying, "applied" once the jar is replaced, null otherwise. */
    private final AtomicReference<String> phase = new AtomicReference<>(null);
    private volatile String lastTitle = "Actualizando Korosoft-Core";
    private volatile String lastSubtitle = "";

    public static ModUpdater get() { return INSTANCE; }
    private ModUpdater() {}
    public boolean isApplying() { return applying.get(); }
    public boolean isApplied() { return "applied".equals(phase.get()); }
    public String currentStatus() { return status.get(); }
    /** Download progress in [0,1]; 0 until the download actually starts. */
    public float currentProgress() { return progress.get(); }

    /**
     * Called once at client init, IMMEDIATELY (no delay): the player must learn about an update
     * before they can join a server, not five seconds after the menu settles. The fetch is async
     * (typically under a second) and a guardian loop re-asserts the update screen if the boot
     * sequence overwrites it, so the window cannot be lost to the boot race.
     */
    public void checkForUpdatesAtLaunch() {
        if (applying.get()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() != null || mc.world != null) {
            LOGGER.info("[keyinput] launch check skipped: already in-game (server push covers it)");
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                ModUpdatePayload payload = fetchLatestReleasePayload();
                if (payload == null) {
                    LOGGER.info("[keyinput] launch check: no newer release found");
                    return;
                }
                MinecraftClient.getInstance().execute(() ->
                        beginUpdate(payload, "Nueva actualización disponible"));
            } catch (Exception e) {
                LOGGER.info("[keyinput] launch check failed (retried next launch): {}", e.toString());
            }
        });
    }

    /**
     * Fetches the latest GitHub release and builds a payload from its keyinput-*.jar asset.
     * Returns null when the check is inconclusive (unreachable, no asset, or not newer) — the
     * caller treats that as "nothing to do".
     */
    private ModUpdatePayload fetchLatestReleasePayload() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(GITHUB_LATEST_RELEASE_URL))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "korosoft-core/" + ModVersion.encoded())
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            LOGGER.info("[keyinput] launch check: HTTP {} from GitHub", resp.statusCode());
            return null;
        }

        JsonObject rel = JsonParser.parseString(resp.body()).getAsJsonObject();
        String tag = rel.has("tag_name") && !rel.get("tag_name").isJsonNull()
                ? rel.get("tag_name").getAsString() : null;
        if (tag == null) return null;

        int version = encodeVersionFromTag(tag);
        if (version <= ModVersion.encoded()) {
            LOGGER.info("[keyinput] launch check: latest {} not newer than running {}",
                    version, ModVersion.encoded());
            return null;
        }

        JsonArray assets = rel.has("assets") && !rel.get("assets").isJsonNull()
                ? rel.getAsJsonArray("assets") : null;
        if (assets == null) return null;

        for (JsonElement el : assets) {
            JsonObject a = el.getAsJsonObject();
            String name = a.has("name") && !a.get("name").isJsonNull()
                    ? a.get("name").getAsString() : "";
            if (!name.endsWith(".jar")) continue;
            if (!name.startsWith("keyinput-") && !name.startsWith("Korosoft-Core-")) continue;

            String url = a.has("browser_download_url") && !a.get("browser_download_url").isJsonNull()
                    ? a.get("browser_download_url").getAsString() : null;
            String digest = a.has("digest") && !a.get("digest").isJsonNull()
                    ? a.get("digest").getAsString() : null;
            if (url == null || digest == null || !digest.startsWith("sha256:")) {
                LOGGER.warn("[keyinput] launch check: asset {} missing url/digest", name);
                continue;
            }

            byte[] sha = HexFormat.of().parseHex(digest.substring("sha256:".length()).trim());
            LOGGER.info("[keyinput] launch check: found {} (v{})", name, version);
            return new ModUpdatePayload(version, url, sha, "Nueva versión disponible", true);
        }
        return null;
    }

    /** "v1.21.58" -> 12158. Any unparsable component counts as 0. */
    private static int encodeVersionFromTag(String tag) {
        String v = tag.startsWith("v") ? tag.substring(1) : tag;
        String[] parts = v.split("\\.");
        return safeParse(parts, 0) * 10000 + safeParse(parts, 1) * 100 + safeParse(parts, 2);
    }

    private static int safeParse(String[] parts, int index) {
        if (index >= parts.length) return 0;
        try {
            return Integer.parseInt(parts[index].trim());
        } catch (Exception e) {
            return 0;
        }
    }

    public void beginUpdate(ModUpdatePayload payload) {
        beginUpdate(payload, "Actualizando Korosoft-Core");
    }

    private void beginUpdate(ModUpdatePayload payload, String title) {
        if (!applying.compareAndSet(false, true)) {
            LOGGER.warn("[keyinput] update already in progress, ignoring duplicate trigger");
            return;
        }

        int running = ModVersion.encoded();
        if (payload.version() <= running) {
            LOGGER.info("[keyinput] ignoring update {} — already running {}", payload.version(), running);
            applying.set(false);
            return;
        }

        if (payload.downloadUrl() == null || !payload.downloadUrl().startsWith("https://")) {
            fail("URL de descarga inválida (solo https).");
            return;
        }

        if (payload.sha256() == null || payload.sha256().length != 32) {
            fail("SHA-256 inválido en el payload.");
            return;
        }

        LOGGER.info("[keyinput] update flow started: version={} url={} mandatory={} (running={})",
                payload.version(), payload.downloadUrl(), payload.mandatory(), running);

        status.set("Descargando actualización...");
        progress.set(0f);
        phase.set("updating");
        lastTitle = title;
        lastSubtitle = payload.message();
        installOverlay(title, payload.message());
        scheduleGuardian();

        CompletableFuture.runAsync(() -> runUpdateFlow(payload))
                .exceptionally(ex -> {
                    LOGGER.error("[keyinput] update flow failed", ex);
                    status.set("Error: " + ex.getMessage());
                    return null;
                });
    }

    private void runUpdateFlow(ModUpdatePayload payload) {
        try {
            Path currentJar = locateCurrentJar();
            if (currentJar == null) {
                fail("No se encontró el jar instalado (probablemente dev).");
                return;
            }

            Path tempJar = currentJar.resolveSibling(currentJar.getFileName() + ".update");
            downloadToFile(payload.downloadUrl(), tempJar);

            status.set("Verificando integridad...");
            byte[] downloadedHash = ModUpdateManifest.sha256(Files.readAllBytes(tempJar));
            if (!java.security.MessageDigest.isEqual(downloadedHash, payload.sha256())) {
                Files.deleteIfExists(tempJar);
                fail("SHA-256 no coincide. Actualización cancelada por seguridad.");
                return;
            }

            status.set("Aplicando actualización...");
            replaceJar(currentJar, tempJar);

            if (IS_WINDOWS) {
                // The jar swap happens after this JVM exits (native helper). Close the game so
                // the swap can finish; the player restarts and boots the new version.
                status.set("Actualización aplicada. Reinicia el juego.");
                LOGGER.info("[keyinput] Windows: closing game so the helper can finish the swap");
                MinecraftClient.getInstance().execute(() ->
                        MinecraftClient.getInstance().scheduleStop());
                return;
            }

            LOGGER.info("[keyinput] jar replaced: {}", currentJar);

            status.set("Actualización aplicada. Reinicia el juego.");
            phase.set("applied");
            MinecraftClient.getInstance().execute(() ->
                    MinecraftClient.getInstance().setScreen(new UpdateAppliedScreen()));
        } catch (Exception e) {
            LOGGER.error("[keyinput] update failed", e);
            fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private void replaceJar(Path currentJar, Path newJar) throws IOException {
        if (IS_WINDOWS) {
            // Windows keeps the running jar open, so an in-process swap fails with
            // "being used by another process". Hand the swap to the bundled native helper:
            // it waits for this JVM to exit, then moves the INSTALLED jar aside to .bak,
            // moves the downloaded <jar>.update into place and writes the result into a
            // .result file the next boot (or this one, on failure) reads back.
            //
            // CONTRACT BUG FIXED (2026-08-10): the helper receives currentJar (the installed
            // jar, which EXISTS) as its replace target. It previously received <jar>.old,
            // which is never created, so the helper took the "nothing to replace" path:
            // it deleted the freshly downloaded <jar>.update and reported OK. Windows players
            // rebooted on the old jar with no error and no update, forever.
            Path resultFile = currentJar.resolveSibling(currentJar.getFileName() + ".result");
            Files.deleteIfExists(resultFile);
            Path helper = extractUpdateHelper();
            launchWindowsHelper(helper, currentJar, newJar, resultFile);
            LOGGER.info("[keyinput] Windows: native helper launched (pid={}), game will exit and "
                    + "jar swap completes after shutdown", ProcessHandle.current().pid());
        } else {
            try {
                Files.move(newJar, currentJar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(newJar, currentJar, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Called at client init (before the launch update check). Reads the result file written by
     * the Windows native helper from the previous session's swap. If the swap failed the player
     * is told what went wrong instead of silently booting the old version.
     *
     * <p>Self-healing: a missing result file with leftover artifacts means the helper never ran
     * or was killed mid-swap. A .bak next to a missing installed jar is rolled back so the game
     * still boots the old build; a leftover .update is dropped and the next launch check or
     * server push re-triggers the update.
     */
    void consumeWindowsHelperResult() {
        if (!IS_WINDOWS) return;
        try {
            Path jar = locateCurrentJar();
            if (jar == null) return;
            Path resultFile = jar.resolveSibling(jar.getFileName() + ".result");
            Path newJar = jar.resolveSibling(jar.getFileName() + ".update");
            Path bakJar = jar.resolveSibling(jar.getFileName() + ".bak");
            Path oldJar = jar.resolveSibling(jar.getFileName() + ".old");

            String result = Files.exists(resultFile) ? Files.readString(resultFile) : null;
            Files.deleteIfExists(resultFile);

            if (result == null) {
                // Helper never ran or was killed mid-swap. Recover what we can:
                // - a .bak with no installed jar means the old jar was moved aside but the
                //   swap never finished -> roll it back so the game still boots the old build.
                // - a leftover .update means a download never got applied -> drop it; the next
                //   launch check / server push re-triggers the update.
                if (Files.exists(bakJar) && !Files.exists(jar)) {
                    Files.move(bakJar, jar, StandardCopyOption.REPLACE_EXISTING);
                    LOGGER.warn("[keyinput] Windows: rolled back {} from .bak (helper did not finish)", jar);
                } else {
                    Files.deleteIfExists(bakJar);
                }
                Files.deleteIfExists(newJar);
                Files.deleteIfExists(oldJar);
                return;
            }

            if (result.startsWith("ERR:")) {
                // Swap failed: the helper already restored the installed jar (or never moved
                // it), so the running jar is valid; surface the reason on this boot.
                String reason = result.length() > 4 ? result.substring(4) : "error desconocido";
                LOGGER.warn("[keyinput] previous Windows update failed: {}", reason);
                MinecraftClient.getInstance().execute(() ->
                        MinecraftClient.getInstance().setScreen(new ModUpdateErrorScreen(
                                "La actualización anterior no se completó: " + reason)));
            } else {
                LOGGER.info("[keyinput] previous Windows update finished OK");
            }
            Files.deleteIfExists(newJar);
            Files.deleteIfExists(oldJar);
        } catch (Exception e) {
            LOGGER.warn("[keyinput] could not read Windows update result file", e);
        }
    }

    /**
     * Spawns the bundled Windows helper and returns immediately. The helper waits for the
     * parent Java process to exit, then swaps the jar and writes the result file.
     *
     * <p>Argument order matches the native contract: {@code helper <parentPid> <installedJar>
     * <newJar> <resultFile>} (see src/main/native/update_helper/main.cpp). The INSTALLED jar
     * (which exists) is the replace target — passing a never-created <jar>.old made the helper
     * take its "nothing to replace" path and silently delete the downloaded jar.
     */
    private void launchWindowsHelper(Path helper, Path installedJar, Path newJar, Path resultFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    helper.toAbsolutePath().toString(),
                    Long.toString(ProcessHandle.current().pid()),
                    installedJar.toAbsolutePath().toString(),
                    newJar.toAbsolutePath().toString(),
                    resultFile.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            pb.start();
        } catch (IOException e) {
            LOGGER.error("[keyinput] failed to launch Windows helper", e);
            fail("No se pudo lanzar el asistente de actualización en Windows.");
        }
    }

    /**
     * Extracts the bundled native helper from the mod resources into a temp file next to the
     * jar. Bundled as {@code /native/win/korosoft-update-helper.exe}.
     */
    private Path extractUpdateHelper() throws IOException {
        Path target = currentJarParent().resolve("korosoft-update-helper.exe");
        try (var in = ModUpdater.class.getResourceAsStream("/native/win/korosoft-update-helper.exe")) {
            if (in == null) {
                throw new IOException("Native update helper missing from jar resources");
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private Path currentJarParent() {
        var container = FabricLoader.getInstance().getModContainer("korosoft-core").orElse(null);
        if (container == null) return Path.of(".");
        var paths = container.getOrigin().getPaths();
        for (Path p : paths) {
            if (Files.isRegularFile(p)) return p.toAbsolutePath().getParent();
        }
        return Path.of(".");
    }

    private void installOverlay(String title, String subtitle) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            Screen current = mc.currentScreen;
            Screen wrapped = new ModUpdateScreen(title, subtitle, current);
            mc.setScreen(wrapped);
        });
    }

    private void fail(String reason) {
        status.set("Error: " + reason);
        phase.set(null);
        LOGGER.warn("[keyinput] update aborted: {}", reason);
        MinecraftClient.getInstance().execute(() -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.setScreen(new ModUpdateErrorScreen(reason));
        });
    }

    /**
     * Re-asserts the update screen every few hundred milliseconds while a flow is active and the
     * player is not in a world. The boot sequence (and any other screen change) can overwrite the
     * update window; this guardian puts it back, so an immediate launch check cannot be lost.
     * Stops as soon as the flow ends (applying latch cleared, error, or game exit).
     */
    private void scheduleGuardian() {
        CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS).execute(() -> {
            if (!applying.get()) return;
            String ph = phase.get();
            if (ph == null) return; // error screen took over; player decides
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.world != null || mc.getNetworkHandler() != null) {
                scheduleGuardian(); // in a world: server push path owns the flow; keep polling
                return;
            }
            Screen cur = mc.currentScreen;
            if ("updating".equals(ph)) {
                if (!(cur instanceof ModUpdateScreen) && !(cur instanceof ModUpdateErrorScreen)) {
                    mc.execute(() -> mc.setScreen(new ModUpdateScreen(lastTitle, lastSubtitle, mc.currentScreen)));
                }
            } else if ("applied".equals(ph)) {
                if (!(cur instanceof UpdateAppliedScreen) && !(cur instanceof ModUpdateErrorScreen)) {
                    mc.execute(() -> mc.setScreen(new UpdateAppliedScreen()));
                }
            }
            scheduleGuardian();
        });
    }

    void clearApplying() {
        applying.set(false);
    }

    private Path locateCurrentJar() {
        var container = FabricLoader.getInstance().getModContainer("korosoft-core").orElse(null);
        if (container == null) return null;
        var paths = container.getOrigin().getPaths();
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
                .header("User-Agent", "korosoft-core/" + ModVersion.encoded())
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
                final long cl = contentLength;
                MinecraftClient.getInstance().execute(() -> {
                    status.set("Descargando... " + (t / 1024) + " KB");
                    if (cl > 0) {
                        progress.set(Math.min(1f, (float) t / (float) cl));
                    }
                });
            }
        }
        progress.set(1f);
        LOGGER.info("[keyinput] download complete: {} bytes -> {}", Files.size(dest), dest);
    }
}
