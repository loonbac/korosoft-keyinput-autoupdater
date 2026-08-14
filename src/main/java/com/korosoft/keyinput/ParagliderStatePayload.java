package com.korosoft.keyinput;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Custom payload server -> ALL clients on the "korosoft-core:paraglider" channel (one channel, both
 * directions — exactly like ping). Broadcast whenever any player's glide state changes:
 *
 * <pre>
 *   int     version   (1)
 *   UTF     uuid      (player UUID string, 2-byte length prefix — matches DataOutputStream.writeUTF)
 *   boolean paragliding
 * </pre>
 *
 * <p>The decoder is deliberately LENIENT like every other S2C payload in this mod: a truncated or
 * garbled payload falls back to safe defaults (zero UUID, not gliding) and never kicks the player.
 * On netty the UTF is read with {@code buf.readUtf()} which uses a big-endian unsigned short length
 * — identical framing to {@code writeUTF}, hence the manual write/read helpers here.
 */
public record ParagliderStatePayload(UUID player, boolean paragliding) implements CustomPayload {

    public static final CustomPayload.Id<ParagliderStatePayload> ID =
            new CustomPayload.Id<>(Identifier.of("korosoft-core", "paraglider_state"));

    private static final int VERSION = 1;
    private static final UUID DEFAULT_PLAYER = new UUID(0L, 0L);

    public static final PacketCodec<RegistryByteBuf, ParagliderStatePayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeInt(VERSION);
                writeUtf(buf, payload.player().toString());
                buf.writeBoolean(payload.paragliding());
            },
            buf -> {
                int version = buf.readableBytes() >= Integer.BYTES ? buf.readInt() : 0;
                String playerStr = readUtf(buf, "");
                boolean paragliding = buf.readableBytes() >= 1 && buf.readBoolean();
                buf.skipBytes(buf.readableBytes());

                UUID player = DEFAULT_PLAYER;
                if (!playerStr.isEmpty()) {
                    try {
                        player = UUID.fromString(playerStr);
                    } catch (IllegalArgumentException ignored) {
                        player = DEFAULT_PLAYER;
                    }
                }
                return new ParagliderStatePayload(player, paragliding);
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
