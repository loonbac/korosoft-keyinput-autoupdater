package com.korosoft.keyinput;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Custom payload sent server -> client on the "keyinput:hudcfg" channel.
 * Carries the HUD animation settings so the sidebar height can be tuned live.
 */
public record HudConfigPayload(int sidebarYOffset, int slideMillis) implements CustomPayload {

    public static final CustomPayload.Id<HudConfigPayload> ID =
            new CustomPayload.Id<>(Identifier.of("korosoft-core", "hudcfg"));

    /** Used when an older server sends a shorter payload than this client knows how to read. */
    private static final int DEFAULT_SIDEBAR_Y_OFFSET = 0;
    private static final int DEFAULT_SLIDE_MILLIS = 180;

    // Written by hand as fixed 4-byte big-endian ints to match the Skript side, which uses
    // DataOutputStream.writeInt(). PacketCodecs.INTEGER is a VarInt and would decode those
    // bytes as garbage.
    //
    // The decoder is deliberately LENIENT, because Minecraft kicks the player outright on a
    // custom payload it cannot decode cleanly: a field the server does not send yet falls back
    // to its default, and any trailing bytes from a newer server are drained instead of left
    // over (unread bytes are a DecoderException, which reads to the player as "Conexión perdida").
    public static final PacketCodec<RegistryByteBuf, HudConfigPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeInt(payload.sidebarYOffset());
                buf.writeInt(payload.slideMillis());
            },
            buf -> {
                int sidebarYOffset = buf.readableBytes() >= Integer.BYTES ? buf.readInt() : DEFAULT_SIDEBAR_Y_OFFSET;
                int slideMillis = buf.readableBytes() >= Integer.BYTES ? buf.readInt() : DEFAULT_SLIDE_MILLIS;
                buf.skipBytes(buf.readableBytes());
                return new HudConfigPayload(sidebarYOffset, slideMillis);
            }
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
