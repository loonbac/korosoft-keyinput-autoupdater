package com.korosoft.keyinput;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.map.MapState;

/**
 * Client-side registry of "which maps belong to my atlas", fed by {@link AtlasPayload} from the
 * server. The minimap ({@link AtlasMinimap}) and the overview screen read this every frame.
 *
 * <p>The server owns the truth (which maps exist, their scale, their world centers, what the
 * player carries); this class is a dumb cache of the last pushed state.
 *
 * <p><b>World centers are stored here, not read from {@link MapState}:</b> the vanilla map update
 * packets do not carry the map center (the client's MapState defaults to 0,0 on first sight), so
 * the server sends each map's center in the payload.
 */
public final class AtlasState {

    private static volatile byte scale = 0;
    private static volatile int centerX = 0;
    private static volatile int centerZ = 0;
    private static volatile int[] mapIds = new int[0];
    private static volatile int[] mapCenterXs = new int[0];
    private static volatile int[] mapCenterZs = new int[0];

    private AtlasState() {
    }

    public static void put(AtlasPayload payload) {
        scale = payload.scale();
        centerX = payload.centerX();
        centerZ = payload.centerZ();
        mapIds = payload.mapIds() == null ? new int[0] : payload.mapIds();
        mapCenterXs = payload.mapCenterXs() == null ? new int[mapIds.length] : payload.mapCenterXs();
        mapCenterZs = payload.mapCenterZs() == null ? new int[mapIds.length] : payload.mapCenterZs();
        org.slf4j.LoggerFactory.getLogger("korosoft-core/atlas")
                .info("[ATLAS] payload recibido: {} mapas, scale={}, center=({},{})", mapIds.length, scale, centerX, centerZ);
    }

    public static void clear() {
        mapIds = new int[0];
        mapCenterXs = new int[0];
        mapCenterZs = new int[0];
    }

    public static boolean hasMaps() {
        return mapIds.length > 0;
    }

    public static byte getScale() {
        return scale;
    }

    public static int getCenterX() {
        return centerX;
    }

    public static int getCenterZ() {
        return centerZ;
    }

    public static int[] getMapIds() {
        return mapIds;
    }

    /** World X center of a map id, or 0 if unknown. */
    public static int getMapCenterX(int mapId) {
        for (int i = 0; i < mapIds.length; i++) {
            if (mapIds[i] == mapId) {
                return mapCenterXs[i];
            }
        }
        return 0;
    }

    /** World Z center of a map id, or 0 if unknown. */
    public static int getMapCenterZ(int mapId) {
        for (int i = 0; i < mapIds.length; i++) {
            if (mapIds[i] == mapId) {
                return mapCenterZs[i];
            }
        }
        return 0;
    }

    /** Width of one map tile in blocks: 128 * 2^scale. */
    public static int getTileWidth() {
        return 128 * (1 << scale);
    }

    /** The vanilla MapState for a map id, or null if the client does not have it yet. */
    public static MapState getMapState(int mapId) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;
        if (world == null) {
            return null;
        }
        return world.getMapState(new MapIdComponent(mapId));
    }
}
