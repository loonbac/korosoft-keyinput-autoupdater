package com.korosoft.keyinput;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Custom payload client -> server on the "korosoft-core:ping" channel. Sent when the player
 * presses the ping key: the target point (raycast hit or entity), the sequence number and the
 * dimension, exactly like Ping-Wheel's C2S packet.
 *
 * <p>Wire format (mirrors what the Skript side reads with DataInputStream):
 *
 * <pre>
 *   int     version   (1)
 *   double  x
 *   double  y
 *   double  z
 *   boolean hasEntity
 *   UUID    entity    (only if hasEntity)
 *   int     sequence
 *   int     dimension (registryKey hash)
 * </pre>
 *
 * <p>The server (Skript) broadcasts this to the other players with the author's UUID prepended;
 * the client decodes it with {@link PingBroadcastPayload}.
 */
public record PingPayload(double x, double y, double z, UUID entity, int sequence, int dimension)
        implements CustomPayload {

    public static final CustomPayload.Id<PingPayload> ID =
            new CustomPayload.Id<>(Identifier.of("korosoft-core", "ping"));

    private static final int VERSION = 1;
    private static final int DEFAULT_SEQUENCE = 0;
    private static final int DEFAULT_DIMENSION = 0;

    public static final PacketCodec<RegistryByteBuf, PingPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeInt(VERSION);
                buf.writeDouble(payload.x());
                buf.writeDouble(payload.y());
                buf.writeDouble(payload.z());
                buf.writeBoolean(payload.entity() != null);
                if (payload.entity() != null) {
                    buf.writeUuid(payload.entity());
                }
                buf.writeInt(payload.sequence());
                buf.writeInt(payload.dimension());
            },
            buf -> {
                int version = buf.readableBytes() >= Integer.BYTES ? buf.readInt() : 0;
                double x = buf.readableBytes() >= Double.BYTES ? buf.readDouble() : 0.0;
                double y = buf.readableBytes() >= Double.BYTES ? buf.readDouble() : 0.0;
                double z = buf.readableBytes() >= Double.BYTES ? buf.readDouble() : 0.0;
                UUID entity = null;
                if (buf.readableBytes() >= 1) {
                    boolean hasEntity = buf.readBoolean();
                    if (hasEntity && buf.readableBytes() >= 16) {
                        entity = buf.readUuid();
                    }
                }
                int sequence = buf.readableBytes() >= Integer.BYTES ? buf.readInt() : DEFAULT_SEQUENCE;
                int dimension = buf.readableBytes() >= Integer.BYTES ? buf.readInt() : DEFAULT_DIMENSION;
                buf.skipBytes(buf.readableBytes());
                return new PingPayload(x, y, z, entity, sequence, dimension);
            }
    );

    private static String writeUuid(UUID uuid) {
        return uuid.toString();
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
