package com.korosoft.keyinput;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;

/**
 * Custom payload sent server -&gt; client on the "keyinput:cutconfig" channel.
 * Carries every ORBIT/ASCEND cinematic tunable (see {@link CutsceneConfig}) as a single
 * "key=value;key=value;..." string, so new tunables can be added later without ever touching the
 * wire format again — see {@link CutsceneConfig#apply(String)} for why unknown keys are silently
 * ignored rather than rejected.
 *
 * <p>Written by hand as a 2-byte big-endian length prefix followed by UTF-8 bytes, to match the
 * Skript side, which uses DataOutputStream.writeUTF() (Java "modified UTF-8" — byte-identical to
 * plain UTF-8 for the ASCII-only key=value content this carries). PacketByteBuf's own
 * readString()/writeString() use a VarInt length prefix instead and would misread/miswrite these
 * bytes.
 *
 * <p>The decoder is deliberately LENIENT, same reasoning as {@link CutscenePayload}: Minecraft
 * kicks the player outright on a custom payload it cannot decode cleanly, so a short or garbled
 * payload falls back to an empty spec (a no-op for {@link CutsceneConfig#apply(String)}) instead
 * of ever throwing, and any trailing bytes are drained instead of left over (unread bytes are a
 * DecoderException, which reads to the player as "Conexion perdida").
 */
public record CutsceneConfigPayload(String spec) implements CustomPayload {

    public static final CustomPayload.Id<CutsceneConfigPayload> ID =
            new CustomPayload.Id<>(Identifier.of("korosoft-core", "cutconfig"));

    /** Used when the server sends a payload too short to hold even the length prefix, or a
     * length prefix promising more bytes than are actually there. */
    private static final String DEFAULT_SPEC = "";

    public static final PacketCodec<RegistryByteBuf, CutsceneConfigPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                byte[] bytes = payload.spec().getBytes(StandardCharsets.UTF_8);
                buf.writeShort(bytes.length);
                buf.writeBytes(bytes);
            },
            buf -> {
                String spec = DEFAULT_SPEC;
                if (buf.readableBytes() >= Short.BYTES) {
                    int length = buf.readUnsignedShort();
                    if (buf.readableBytes() >= length) {
                        byte[] bytes = new byte[length];
                        buf.readBytes(bytes);
                        spec = new String(bytes, StandardCharsets.UTF_8);
                    }
                }
                // Always drain to the end regardless of which branch above fired: a promised
                // length that overruns the buffer must not leave those bytes unread, or Netty
                // raises DecoderException and the player gets disconnected over a cosmetics packet.
                buf.skipBytes(buf.readableBytes());
                return new CutsceneConfigPayload(spec);
            }
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
