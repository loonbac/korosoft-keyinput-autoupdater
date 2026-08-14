package com.korosoft.keyinput;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Custom payload server -> one player on the "korosoft-core:paraglider" channel. Sent when the
 * server decides whether this client may deploy its paraglider (holding the item, out of cooldown,
 * not swimming, etc.) and what the server-side config says:
 *
 * <pre>
 *   int     version   (1)
 *   boolean canUse    (the server says I may deploy — I'm holding the paraglider)
 *   boolean autoDeploy (server config: auto-deploy when falling)
 *   float   speed     (horizontal speed multiplier while gliding, default 1.0)
 * </pre>
 *
 * <p>The decoder is deliberately LENIENT like every other S2C payload in this mod: a truncated or
 * garbled payload falls back to safe defaults (cannot use, no auto-deploy, speed 1.0) and never
 * kicks the player.
 */
public record ParagliderCanUsePayload(boolean canUse, boolean autoDeploy, float speed)
        implements CustomPayload {

    public static final CustomPayload.Id<ParagliderCanUsePayload> ID =
            new CustomPayload.Id<>(Identifier.of("korosoft-core", "paraglider_canuse"));

    private static final int VERSION = 1;
    private static final float DEFAULT_SPEED = 1.0F;

    public static final PacketCodec<RegistryByteBuf, ParagliderCanUsePayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeInt(VERSION);
                buf.writeBoolean(payload.canUse());
                buf.writeBoolean(payload.autoDeploy());
                buf.writeFloat(payload.speed());
            },
            buf -> {
                int version = buf.readableBytes() >= Integer.BYTES ? buf.readInt() : 0;
                boolean canUse = buf.readableBytes() >= 1 && buf.readBoolean();
                boolean autoDeploy = buf.readableBytes() >= 1 && buf.readBoolean();
                float speed = buf.readableBytes() >= Float.BYTES ? buf.readFloat() : DEFAULT_SPEED;
                buf.skipBytes(buf.readableBytes());
                return new ParagliderCanUsePayload(canUse, autoDeploy, speed);
            }
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
