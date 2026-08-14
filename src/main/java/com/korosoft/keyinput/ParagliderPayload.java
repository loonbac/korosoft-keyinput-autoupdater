package com.korosoft.keyinput;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Custom payload client -> server on the "korosoft-core:paraglider" channel. A right-click toggle
 * request while the server has said the player may deploy ({@link ParagliderCanUsePayload}):
 *
 * <pre>
 *   int     version   (1)
 *   boolean paragliding
 * </pre>
 *
 * <p>The client NEVER decides whether the glide is allowed — the server owns that. This payload is
 * only a request: the server replies with a {@link ParagliderStatePayload} broadcast, and
 * {@link ParagliderState} treats that echo as the truth. The decoder is LENIENT like every other
 * payload in this mod, because the server side reads this with a plain DataInputStream and a
 * malformed read must never crash or kick the client.
 */
public record ParagliderPayload(boolean paragliding) implements CustomPayload {

    public static final CustomPayload.Id<ParagliderPayload> ID =
            new CustomPayload.Id<>(Identifier.of("korosoft-core", "paraglider"));

    private static final int VERSION = 1;

    public static final PacketCodec<RegistryByteBuf, ParagliderPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeInt(VERSION);
                buf.writeBoolean(payload.paragliding());
            },
            buf -> {
                int version = buf.readableBytes() >= Integer.BYTES ? buf.readInt() : 0;
                boolean paragliding = buf.readableBytes() >= 1 && buf.readBoolean();
                buf.skipBytes(buf.readableBytes());
                return new ParagliderPayload(paragliding);
            }
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
