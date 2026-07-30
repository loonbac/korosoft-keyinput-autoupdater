package com.korosoft.keyinput;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Parses the JSON manifest the update server returns at
 * {@code https://updates.korosoft.com/keyinput/manifest.json}. Keeping the wire format in one place
 * is important: the Skript-side dispatcher (see {@code scripts/sistema/mod-update.sk}) writes it, and
 * this class reads it. A typo on either side fails closed (manifest parse error -> no update).
 *
 * <p>Expected JSON:
 * <pre>{@code
 * {
 *   "version": "1.21.50",
 *   "versionEncoded": 12150,
 *   "downloadUrl": "https://updates.korosoft.com/keyinput/keyinput-1.21.50.jar",
 *   "sha256": "abc123...64 hex chars...",
 *   "message": "Soporte para teclas del mouse",
 *   "mandatory": true
 * }
 * }</pre>
 *
 * <p>{@code version} is informational (logged on the client). {@code versionEncoded} is the integer
 * that {@link #matchesClientVersion()} compares against, and it MUST match
 * {@link HelloPayload#version()} encoding ({@code major*10000 + minor*100 + patch}).
 */
public final class ModUpdateManifest {

    private final String version;
    private final int versionEncoded;
    private final String downloadUrl;
    private final byte[] sha256;
    private final String message;
    private final boolean mandatory;

    private ModUpdateManifest(String version, int versionEncoded, String downloadUrl,
                              byte[] sha256, String message, boolean mandatory) {
        this.version = version;
        this.versionEncoded = versionEncoded;
        this.downloadUrl = downloadUrl;
        this.sha256 = sha256;
        this.message = message;
        this.mandatory = mandatory;
    }

    /** Returns null if the JSON is malformed, the SHA-256 is not 64 hex chars, etc. Fails closed. */
    public static ModUpdateManifest parse(byte[] jsonBytes) {
        try {
            JsonObject root = JsonParser.parseString(new String(jsonBytes, StandardCharsets.UTF_8))
                    .getAsJsonObject();

            String version = requiredString(root, "version");
            int versionEncoded = requiredInt(root, "versionEncoded");
            String downloadUrl = requiredString(root, "downloadUrl");
            String sha256Hex = requiredString(root, "sha256").toLowerCase();
            String message = root.has("message") && !root.get("message").isJsonNull()
                    ? root.get("message").getAsString() : "Actualización disponible";
            boolean mandatory = root.has("mandatory") && root.get("mandatory").getAsBoolean();

            if (sha256Hex.length() != 64) {
                throw new IllegalArgumentException("sha256 must be 64 hex chars, got " + sha256Hex.length());
            }
            byte[] sha256 = hexToBytes(sha256Hex);
            if (!downloadUrl.startsWith("https://")) {
                throw new IllegalArgumentException("downloadUrl must be https://, got " + downloadUrl);
            }

            return new ModUpdateManifest(version, versionEncoded, downloadUrl, sha256, message, mandatory);
        } catch (Exception e) {
            ModUpdater.LOGGER.warn("[keyinput] manifest parse failed: {}", e.toString());
            return null;
        }
    }

    /**
     * Returns true if the running build is older than the manifest. Equal or newer means no update
     * needed; the client does not roll back to an older build under any circumstance.
     */
    public boolean isNewerThanRunning() {
        return versionEncoded > ModVersion.encoded();
    }

    public String version() { return version; }
    public int versionEncoded() { return versionEncoded; }
    public String downloadUrl() { return downloadUrl; }
    public byte[] sha256() { return sha256; }
    public String message() { return message; }
    public boolean mandatory() { return mandatory; }

    // --- helpers ---

    private static String requiredString(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            throw new IllegalArgumentException("missing required field: " + key);
        }
        String s = o.get(key).getAsString();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("required field is empty: " + key);
        }
        return s;
    }

    private static int requiredInt(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            throw new IllegalArgumentException("missing required field: " + key);
        }
        return o.get(key).getAsInt();
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("non-hex char in sha256 at index " + (i * 2));
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** Computes SHA-256 of the given bytes. Throws if SHA-256 is unavailable (never on Java 21). */
    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing from JRE", e);
        }
    }
}