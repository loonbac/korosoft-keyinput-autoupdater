package com.korosoft.keyinput;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Custom payload sent server -> client on the "keyinput:mod_update" channel when the KoroSoft server
 * has a newer keyinput.jar than the one running in this client. The payload is small on purpose: the
 * actual bytes of the new jar come from an HTTPS GET against {@link #downloadUrl()}, not over the
 * Minecraft protocol (which would be slow, would tie up the play connection, and would require the
 * server's Velocity proxy to forward arbitrary-sized blobs).
 *
 * <p>Wire format, in order:
 * <ul>
 *   <li>{@code int}    {@link #version()}      encoded as {@code major*10000 + minor*100 + patch},
 *                                            so plain integer comparison is correct ordering.
 *   <li>{@code String} {@link #downloadUrl()}  absolute HTTPS URL the client should GET.
 *   <li>{@code byte[32]} {@link #sha256()}     lowercase hex SHA-256 of the jar at that URL, raw
 *                                            32 bytes (not hex-encoded on the wire).
 *   <li>{@code String} {@link #message()}      short user-facing reason ("parche de keybinds",
 *                                            etc.). Never empty: the client renders it on the
 *                                            updating overlay so the player knows why.
 *   <li>{@code boolean} {@link #mandatory()}   if true, the client must apply and cannot continue
 *                                            playing on the old build; if false, the client may
 *                                            defer the update until the player idles.
 * </ul>
 *
 * <p>The server is the source of truth for "is the client up to date". It gates entry in the same
 * way {@link HelloPayload} gates it (and the server should compare {@link HelloPayload#version()}
 * against the latest known version on every JOIN); if it sends this payload, it also kicks on the
 * next disconnect so a player who closes the update screen instead of applying it cannot reconnect.
 */
public record ModUpdatePayload(
        int version,
        String downloadUrl,
        byte[] sha256,
        String message,
        boolean mandatory
) implements CustomPayload {

    public static final CustomPayload.Id<ModUpdatePayload> ID =
            new CustomPayload.Id<>(Identifier.of("korosoft-core", "mod_update"));

    /** Hard cap so a malicious server cannot exhaust the client's heap with a fake URL. */
    public static final int MAX_URL_LENGTH = 512;
    /** Hard cap on the user-facing reason string. */
    public static final int MAX_MESSAGE_LENGTH = 200;
    /** SHA-256 is exactly 32 bytes; anything else is corrupt. */
    public static final int SHA256_LENGTH = 32;

    public static final PacketCodec<RegistryByteBuf, ModUpdatePayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeInt(payload.version());
                writeBoundedString(buf, payload.downloadUrl(), MAX_URL_LENGTH);
                if (payload.sha256().length != SHA256_LENGTH) {
                    throw new IllegalArgumentException(
                            "sha256 must be exactly " + SHA256_LENGTH + " bytes, got " + payload.sha256().length);
                }
                buf.writeBytes(payload.sha256());
                writeBoundedString(buf, payload.message(), MAX_MESSAGE_LENGTH);
                buf.writeBoolean(payload.mandatory());
            },
            buf -> {
                int version = buf.readInt();
                String url = readBoundedString(buf, MAX_URL_LENGTH);
                byte[] sha = new byte[SHA256_LENGTH];
                buf.readBytes(sha);
                String message = readBoundedString(buf, MAX_MESSAGE_LENGTH);
                boolean mandatory = buf.readBoolean();
                return new ModUpdatePayload(version, url, sha, message, mandatory);
            }
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    /**
     * UTF-8 string with a 2-byte big-endian length prefix. Bounded so a malformed packet cannot
     * trick us into allocating gigabytes; the bound is checked at decode time and the writer refuses
     * to encode anything longer than the cap.
     */
    private static void writeBoundedString(RegistryByteBuf buf, String s, int maxLength) {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > maxLength) {
            throw new IllegalArgumentException(
                    "string of " + bytes.length + " bytes exceeds cap of " + maxLength);
        }
        buf.writeShort(bytes.length);
        buf.writeBytes(bytes);
    }

    private static String readBoundedString(RegistryByteBuf buf, int maxLength) {
        int len = buf.readUnsignedShort();
        if (len > maxLength) {
            throw new IllegalStateException(
                    "string of " + len + " bytes exceeds cap of " + maxLength + " (malformed packet?)");
        }
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}