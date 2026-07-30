package com.korosoft.keyinput;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Custom payload sent client -> server on the "keyinput:hello" channel, once per JOIN.
 * The mod announcing itself: the server gates entry on it, kicking anyone who never says hello
 * (no mod) and anyone whose version is below the minimum it accepts (a stale hand-distributed jar).
 *
 * <p>Wire format: exactly 4 bytes, one big-endian int, the encoded version from {@link ModVersion}
 * ({@code major * 10000 + minor * 100 + patch}). {@code 0} means the mod could not read its own
 * version, which sorts below every real build and so fails closed.
 *
 * <p>Sent on every JOIN, not once per session: a Velocity backend switch fires JOIN again without
 * ever firing DISCONNECT, so each backend gets its own handshake and can gate independently.
 */
public record HelloPayload(int version) implements CustomPayload {

    public static final CustomPayload.Id<HelloPayload> ID =
            new CustomPayload.Id<>(Identifier.of("keyinput", "hello"));

    /** Used only by the decoder below, which nothing on this network actually runs. */
    private static final int DEFAULT_VERSION = ModVersion.UNKNOWN;

    // Written by hand as a fixed 4-byte big-endian int because the Skript side reads it with
    // DataInputStream.readInt(). PacketCodecs.INTEGER encodes identically (it is byteBuf.writeInt,
    // not a VarInt — VarInts live in PacketCodecs.VAR_INT), so this is not about endianness: it is
    // written out so the wire format is legible at the call site, and so the decoder can be lenient
    // the way the S2C payloads' are, instead of PacketCodec.tuple's strict one.
    //
    // The decoder is vestigial on this network: the server is Paper/Skript and hands its listener
    // the raw byte array rather than running one of the mod's codecs. It exists because PacketCodec
    // needs both halves.
    public static final PacketCodec<RegistryByteBuf, HelloPayload> CODEC = PacketCodec.of(
            (payload, buf) -> buf.writeInt(payload.version()),
            buf -> {
                int version = buf.readableBytes() >= Integer.BYTES ? buf.readInt() : DEFAULT_VERSION;
                buf.skipBytes(buf.readableBytes());
                return new HelloPayload(version);
            }
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
