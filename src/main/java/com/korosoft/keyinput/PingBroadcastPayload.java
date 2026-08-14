package com.korosoft.keyinput;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Custom payload server -> client on the "korosoft-core:ping" channel (same channel, S2C
 * direction). The Skript side re-broadcasts a {@link PingPayload} it received to every other
 * player, prepending the author's UUID:
 *
 * <pre>
 *   int     version   (1)
 *   UTF     author    (UUID string, 2-byte length prefix — matches DataOutputStream.writeUTF)
 *   double  x
 *   double  y
 *   double  z
 *   boolean hasEntity
 *   UUID    entity    (only if hasEntity)
 *   int     sequence
 *   int     dimension
 * </pre>
 *
 * <p>The decoder is deliberately LENIENT like every other S2C payload in this mod: a truncated or
 * garbled payload falls back to safe defaults and never kicks the player.
 */
public record PingBroadcastPayload(UUID author, double x, double y, double z, UUID entity,
                                   int sequence, int dimension) implements CustomPayload {

    public static final CustomPayload.Id<PingBroadcastPayload> ID =
            new CustomPayload.Id<>(Identifier.of("korosoft-core", "ping"));

    private static final int VERSION = 1;
    private static final UUID DEFAULT_AUTHOR = new UUID(0L, 0L);

    public static final PacketCodec<RegistryByteBuf, PingBroadcastPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeInt(VERSION);
                writeUtf(buf, payload.author().toString());
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
                String authorStr = readUtf(buf, "");
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
                int sequence = buf.readableBytes() >= Integer.BYTES ? buf.readInt() : 0;
                int dimension = buf.readableBytes() >= Integer.BYTES ? buf.readInt() : 0;
                buf.skipBytes(buf.readableBytes());

                UUID author = DEFAULT_AUTHOR;
                if (!authorStr.isEmpty()) {
                    try {
                        author = UUID.fromString(authorStr);
                    } catch (IllegalArgumentException ignored) {
                        author = DEFAULT_AUTHOR;
                    }
                }
                return new PingBroadcastPayload(author, x, y, z, entity, sequence, dimension);
            }
    );

    private static void writeUtf(RegistryByteBuf buf, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        buf.writeShort(bytes.length);
        buf.writeBytes(bytes);
    }

    private static String readUtf(RegistryByteBuf buf, String fallback) {
        if (buf.readableBytes() < Short.BYTES) {
            return fallback;
        }
        int len = buf.readUnsignedShort();
        if (len < 0 || buf.readableBytes() < len) {
            return fallback;
        }
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
