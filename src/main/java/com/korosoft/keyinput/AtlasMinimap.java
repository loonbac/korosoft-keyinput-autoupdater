package com.korosoft.keyinput;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.MapRenderState;
import net.minecraft.client.render.MapRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.map.MapState;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2fStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Draws the atlas minimap as a HUD element in the top-right corner, using the same
 * {@code MapRenderer.update} + {@link DrawContext#drawMap} path as the overview screen.
 *
 * <p>In 1.21.11 the HUD uses the deferred GuiRenderState pipeline: every element (texture
 * quads, items, maps) captures its matrix + scissor at submit time and is drawn later on the
 * render thread. {@code MapRenderer.update} copies the {@link MapState} colors into a
 * {@link MapRenderState} — it is cheap and safe on the render thread, exactly like vanilla's
 * CartographyTableScreen does per frame. {@code DrawContext.drawMap} then draws the cached
 * texture. This avoids the world-pass billboard entirely: a billboard in the world gets
 * occluded by terrain and depends on depth test state, which is why it never looked right.
 *
 * <p>The minimap is north-up (like the overview), drawn from the 3x3 tile neighborhood
 * around the player's tile. Purely client-side rendering of server-owned data
 * (see {@link AtlasState}).
 */
public final class AtlasMinimap {

    public static final int MINIMAP_SIZE = 64;
    private static final int MAP_PIXELS = 128;

    /** Server-owned arrow texture (shipped in the Nexo resource pack). Points NORTH (up) in the sprite. */
    public static final Identifier ARROW_TEXTURE = Identifier.of("nexo", "textures/gui/atlas_arrow.png");
    public static final float ARROW_SCREEN_PX = 22.0F;

    // User config: minimap size in pixels (default 96 — the old 64 was too small).
    private static final String CONFIG_FILE = "atlas-minimap.json";
    private static final int DEFAULT_SIZE = 96;
    public static final int MINIMAP_SIZE_MIN = 48;
    public static final int MINIMAP_SIZE_MAX = 256;
    private static volatile int size = DEFAULT_SIZE;

    private static final Map<Integer, MapRenderState> RENDER_STATES = new HashMap<>();

    private AtlasMinimap() {
    }

    /** Loads the saved minimap size from disk (best effort). Call once at client init. */
    public static void load() {
        try {
            Path file = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
            if (Files.exists(file)) {
                JsonObject obj = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
                if (obj != null && obj.has("size")) {
                    size = Math.max(MINIMAP_SIZE_MIN, Math.min(MINIMAP_SIZE_MAX, obj.get("size").getAsInt()));
                }
            }
        } catch (Exception e) {
            // Corrupt config must never break the game — fall back to the default.
        }
    }

    /** Persists the minimap size to disk. */
    public static void setSize(int newSize) {
        size = Math.max(MINIMAP_SIZE_MIN, Math.min(MINIMAP_SIZE_MAX, newSize));
        try {
            Path file = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
            Files.createDirectories(file.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("size", size);
            Files.writeString(file, new Gson().toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Non-fatal: the size still applies for this session.
        }
    }

    public static int getSize() {
        return size;
    }

    /** Called from the HUD render pass. */
    public static void render(DrawContext context, net.minecraft.client.render.RenderTickCounter tickCounter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.currentScreen != null) {
            return;
        }
        if (!AtlasState.hasMaps()) {
            return;
        }

        int tileWidth = AtlasState.getTileWidth();
        int centerX = (int) mc.player.getX();
        int centerZ = (int) mc.player.getZ();
        int tileX = Math.floorDiv(centerX, tileWidth);
        int tileZ = Math.floorDiv(centerZ, tileWidth);

        int margin = 5;
        int size = getSize();
        int x = context.getScaledWindowWidth() - size - margin;
        int y = margin;

        // Semi-transparent panel behind the minimap.
        context.fill(x - 2, y - 2, x + size + 2, y + size + 2, 0xC0000000);
        context.enableScissor(x, y, x + size, y + size);

        Matrix3x2fStack matrices = context.getMatrices();
        matrices.pushMatrix();
        // The map view is centered on the player: one tile (tileWidth blocks) renders as
        // size px. drawMap draws the 128px map texture at 128x128 in the current matrix.
        float pxPerBlock = (float) size / (float) tileWidth;
        matrices.translate(x + size / 2.0F, y + size / 2.0F);
        matrices.scale(pxPerBlock, pxPerBlock);

        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int mapId = findMapIdForTile(tileX + dx, tileZ + dz, tileWidth);
                if (mapId < 0) {
                    continue;
                }
                MapState state = AtlasState.getMapState(mapId);
                if (state == null) {
                    continue;
                }
                double tileCenterWorldX = (tileX + dx) * tileWidth + tileWidth / 2.0;
                double tileCenterWorldZ = (tileZ + dz) * tileWidth + tileWidth / 2.0;
                float tileScreenX = (float) (tileCenterWorldX - centerX);
                float tileScreenZ = (float) (tileCenterWorldZ - centerZ);

                matrices.pushMatrix();
                matrices.translate(tileScreenX - tileWidth / 2.0F, tileScreenZ - tileWidth / 2.0F);
                matrices.scale((float) tileWidth / (float) MAP_PIXELS, (float) tileWidth / (float) MAP_PIXELS);
                MapRenderer renderer = mc.getMapRenderer();
                MapRenderState renderState = RENDER_STATES.computeIfAbsent(mapId, k -> new MapRenderState());
                renderer.update(new MapIdComponent(mapId), state, renderState);
                context.drawMap(renderState);
                matrices.popMatrix();
            }
        }

        matrices.popMatrix();
        context.disableScissor();

        // Player arrow: server-owned texture, rotated to the player's yaw.
        // North-up map: yaw=0 (facing +Z/south) points DOWN (+Y), yaw=90 (-X/west) points
        // LEFT, yaw=180 (-Z/north) points UP. Rotation from the sprite's north-up vector
        // (0,-1): R(theta)*(0,-1) = (sin theta, -cos theta) must equal (-sin yaw, cos yaw)
        // -> theta = PI + yaw (radians). Verified on all four cardinals.
        float arrowAngle = (float) Math.PI + (float) Math.toRadians(mc.player.getYaw());
        float px = x + size / 2.0F;
        float py = y + size / 2.0F;
        float arrowScale = ARROW_SCREEN_PX / 32.0F;
        matrices.pushMatrix();
        matrices.translate(px, py);
        matrices.rotate(arrowAngle);
        matrices.scale(arrowScale, arrowScale);
        matrices.translate(-16.0F, -16.0F);
        context.drawTexture(RenderPipelines.GUI_TEXTURED, ARROW_TEXTURE, 0, 0, 0, 0, 32, 32, 32, 32, 0xFFFFFFFF);
        matrices.popMatrix();
    }


    private static int findMapIdForTile(int tileX, int tileZ, int tileWidth) {
        int[] ids = AtlasState.getMapIds();
        for (int id : ids) {
            // Los centers vienen del payload (el MapState del cliente puede tener 0,0).
            int centerX = AtlasState.getMapCenterX(id);
            int centerZ = AtlasState.getMapCenterZ(id);
            int stateTileX = Math.floorDiv(centerX, tileWidth);
            int stateTileZ = Math.floorDiv(centerZ, tileWidth);
            if (stateTileX == tileX && stateTileZ == tileZ) {
                return id;
            }
        }
        return -1;
    }
}
