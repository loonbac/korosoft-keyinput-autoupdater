package com.korosoft.keyinput;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Custom payload client -> server on the "korosoft-core:atlas_action" channel. The client asks
 * the server to do something with the atlas — currently only "open the overview", so the server
 * decides whether the player may and then pushes the atlas state:
 *
 * <pre>
 *   int  action  (1 = open overview)
 * </pre>
 */
public record AtlasActionPayload(int action) implements CustomPayload {

    public static final CustomPayload.Id<AtlasActionPayload> ID =
            new CustomPayload.Id<>(Identifier.of("korosoft-core", "atlas_action"));

    public static final int ACTION_OPEN_OVERVIEW = 1;

    public static final PacketCodec<RegistryByteBuf, AtlasActionPayload> CODEC = PacketCodec.of(
            (payload, buf) -> buf.writeInt(payload.action()),
            buf -> {
                int action = buf.readableBytes() >= Integer.BYTES ? buf.readInt() : 0;
                buf.skipBytes(buf.readableBytes());
                return new AtlasActionPayload(action);
            }
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
