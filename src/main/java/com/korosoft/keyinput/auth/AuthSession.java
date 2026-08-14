package com.korosoft.keyinput.auth;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/**
 * Holds the current KoroAuth session (canonical username + signed token) and persists it to disk so
 * a returning player is auto-logged-in without re-entering their password.
 *
 * <p>Flow:
 * <ul>
 *   <li>{@link #load()} runs once at mod init. If a saved session exists on disk, it is restored and
 *       a one-shot "welcome back" flag is armed (see {@link #consumeWelcome()}).</li>
 *   <li>{@link #set} (called on a successful {@code /login} or {@code /register}) stores the session
 *       in memory <b>and</b> writes it to disk, so the next launch auto-logs-in.</li>
 *   <li>{@link #clear} wipes both memory and the on-disk file (e.g. if a saved token is rejected).</li>
 * </ul>
 *
 * <p>The token file holds only this player's own session token (never the password) and is written
 * owner-only where the filesystem supports POSIX permissions. A token is valid for its full server
 * TTL (24h, see {@code KoroAuthConfig.tokenTtlSeconds}); if it has expired, the proxy rejects the
 * connect and the player simply logs in again, which overwrites the stale file.
 */
public final class AuthSession {

    private static final Logger LOGGER = LoggerFactory.getLogger("korosoft-core/koroauth");
    private static final Gson GSON = new Gson();
    private static final String SESSION_FILE_NAME = "koroauth-session.json";

    private static volatile String username;
    private static volatile String token;

    /** Armed by {@link #load()} when a session is restored from disk; read once by the menu to greet a returning player. */
    private static volatile boolean pendingWelcome = false;

    private AuthSession() {
    }

    private static Path sessionFile() {
        return FabricLoader.getInstance().getConfigDir().resolve(SESSION_FILE_NAME);
    }

    /** Restores a saved session from disk, if any. Call once at client init, before the menu opens. */
    public static synchronized void load() {
        Path file = sessionFile();
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonObject obj = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
            String u = obj != null && obj.has("username") ? obj.get("username").getAsString() : null;
            String t = obj != null && obj.has("token") ? obj.get("token").getAsString() : null;
            long savedAt = obj != null && obj.has("savedAt") ? obj.get("savedAt").getAsLong() : 0L;

            // Weekly reset: a session created before this week's Monday 00:00 is expired — the
            // player must log in again. Keeps sessions convenient within a week but forces a fresh
            // login each Monday.
            if (savedAt > 0 && savedAt < mostRecentMondayMidnightEpoch()) {
                LOGGER.info("KoroAuth: saved session expired (before this Monday) — clearing, login required");
                clear();
                return;
            }

            if (u != null && !u.isBlank() && t != null && !t.isBlank()) {
                username = u;
                token = t;
                pendingWelcome = true;
                LOGGER.info("KoroAuth: restored saved session for '{}'", u);
            }
        } catch (Exception e) {
            // A corrupt/partial file must never brick the launcher — just start unauthenticated.
            LOGGER.warn("KoroAuth: could not read saved session, ignoring it", e);
        }
    }

    public static synchronized void set(String canonicalUsername, String sessionToken) {
        username = canonicalUsername;
        token = sessionToken;
        persist();
    }

    public static synchronized void clear() {
        username = null;
        token = null;
        try {
            Files.deleteIfExists(sessionFile());
        } catch (IOException e) {
            LOGGER.warn("KoroAuth: could not delete saved session file", e);
        }
    }

    /** Epoch seconds of the most recent Monday 00:00 in the local zone (today if today is Monday). */
    private static long mostRecentMondayMidnightEpoch() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate monday = LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return monday.atStartOfDay(zone).toEpochSecond();
    }

    private static void persist() {
        JsonObject obj = new JsonObject();
        obj.addProperty("username", username);
        obj.addProperty("token", token);
        obj.addProperty("savedAt", Instant.now().getEpochSecond());
        Path file = sessionFile();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(obj), StandardCharsets.UTF_8);
            restrictOwnerOnly(file);
        } catch (Exception e) {
            // Non-fatal: the session still works this run, it just will not survive a restart.
            LOGGER.warn("KoroAuth: could not persist session to disk", e);
        }
    }

    private static void restrictOwnerOnly(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystem (Windows) — no equivalent restriction applied here, and the file
            // holds only the player's own token, so this is acceptable.
        }
    }

    public static boolean isLoggedIn() {
        return token != null;
    }

    public static String getUsername() {
        return username;
    }

    public static String getToken() {
        return token;
    }

    /** Returns true exactly once if this launch restored a saved session — used to greet the player. */
    public static synchronized boolean consumeWelcome() {
        if (pendingWelcome) {
            pendingWelcome = false;
            return true;
        }
        return false;
    }
}
