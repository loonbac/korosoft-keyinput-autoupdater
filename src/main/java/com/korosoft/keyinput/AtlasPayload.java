package com.korosoft.keyinput;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Custom payload server -> client on the "korosoft-core:atlas" channel. Carries the FULL state
 * of the player's atlas so the client can render the minimap/overview entirely from server data.
 *
 * <p>Each entry carries the map's world center because the vanilla map update packets do NOT
 * include the center (the client only gets colors+scale+locked and would default to 0,0):
 *
 * <pre>
 *   int     version      (1)
 *   byte    scale        (map scale, 0 = 128x128 blocks, 1 = 256, ...)
 *   int     centerX      (world X of the atlas anchor / player tile)
 *   int     centerZ      (world Z of the atlas anchor / player tile)
 *   int     count        (number of maps)
 *   int[]   mapIds       (count ids)
 *   int[]   mapCenterXs  (count world X centers)
 *   int[]   mapCenterZs  (count world Z centers)
 *   boolean open         (true = open the overview screen)
 * </pre>
 */
public record AtlasPayload(byte scale, int centerX, int centerZ, int[] mapIds, int[] mapCenterXs,
                           int[] mapCenterZs, boolean open) implements CustomPayload {

    public static final CustomPayload.Id<AtlasPayload> ID =
            new CustomPayload.Id<>(Identifier.of("korosoft-core", "atlas"));

    private static final int VERSION = 2;

    public static final PacketCodec<RegistryByteBuf, AtlasPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeInt(VERSION);
                buf.writeByte(payload.scale());
                buf.writeInt(payload.centerX());
                buf.writeInt(payload.centerZ());
                buf.writeInt(payload.mapIds().length);
                for (int i = 0; i < payload.mapIds().length; i++) {
                    buf.writeInt(payload.mapIds()[i]);
                    buf.writeInt(payload.mapCenterXs()[i]);
                    buf.writeInt(payload.mapCenterZs()[i]);
                }
                buf.writeBoolean(payload.open());
            },
            buf -> {
                int version = buf.readableBytes() >= Integer.BYTES ? buf.readInt() : 0;
                byte scale = buf.readableBytes() >= 1 ? buf.readByte() : 0;
                int centerX = buf.readableBytes() >= Integer.BYTES ? buf.readInt() : 0;
                int centerZ = buf.readableBytes() >= Integer.BYTES ? buf.readInt() : 0;
                int count = buf.readableBytes() >= Integer.BYTES ? buf.readInt() : 0;
                count = Math.max(0, Math.min(count, 256));
                int[] mapIds = new int[count];
                int[] mapCenterXs = new int[count];
                int[] mapCenterZs = new int[count];
                for (int i = 0; i < count; i++) {
                    mapIds[i] = buf.readableBytes() >= Integer.BYTES ? buf.readInt() : 0;
                    mapCenterXs[i] = buf.readableBytes() >= Integer.BYTES ? buf.readInt() : 0;
                    mapCenterZs[i] = buf.readableBytes() >= Integer.BYTES ? buf.readInt() : 0;
                }
                boolean open = buf.readableBytes() >= 1 && buf.readBoolean();
                buf.skipBytes(buf.readableBytes());
                return new AtlasPayload(scale, centerX, centerZ, mapIds, mapCenterXs, mapCenterZs, open);
            }
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
