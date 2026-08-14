package com.korosoft.keyinput;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.render.MapRenderState;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.MapRenderer;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.map.MapState;
import net.minecraft.text.Text;
import org.joml.Matrix3x2fStack;

import java.util.HashMap;
import java.util.Map;

/**
 * The atlas overview screen: a large, pannable/zoomable view of the atlas maps centered on the
 * player. Dragging pans, the mouse wheel zooms. The client only renders; the server owns all
 * state (which maps exist, what to sync).
 *
 * <p>Opened by pressing N with an atlas (the server pushes {@link AtlasPayload} with open=true).
 * The "Ajustes" button opens a small panel with a slider that resizes the minimap
 * (persisted in {@link AtlasMinimap}).
 */
public class AtlasOverviewScreen extends Screen {

    private static final int MAP_VIEW_SIZE = 240;
    private static final float MIN_ZOOM = 0.5F;
    private static final float MAX_ZOOM = 8.0F;

    private double centerX;
    private double centerZ;
    private float zoom = 1.0F;
    private boolean dragging = false;
    private boolean settingsOpen = false;

    private static final Map<Integer, MapRenderState> OVERVIEW_RENDER_STATES = new HashMap<>();

    public AtlasOverviewScreen() {
        super(Text.literal("Atlas"));
    }

    @Override
    protected void init() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            centerX = mc.player.getX();
            centerZ = mc.player.getZ();
        }
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cerrar"), b -> this.close())
                .dimensions(this.width / 2 - 40, this.height - 28, 80, 20)
                .build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Ajustes"), b -> this.toggleSettings())
                .dimensions(this.width / 2 + 44, this.height - 28, 80, 20)
                .build());
    }

    private void toggleSettings() {
        settingsOpen = !settingsOpen;
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (click.button() == 0) {
            dragging = true;
            // Pan: screen pixels -> world blocks (inverse of the draw scale).
            double pxPerBlock = (double) MAP_VIEW_SIZE / (AtlasState.getTileWidth() * zoom);
            centerX -= deltaX / pxPerBlock;
            centerZ -= deltaY / pxPerBlock;
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (click.button() == 0) {
            dragging = false;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!settingsOpen) {
            zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom + (verticalAmount > 0 ? 0.25F : -0.25F)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || !AtlasState.hasMaps()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("El atlas está vacío"), this.width / 2, this.height / 2 - 8, 0xFFFFFFFF);
            return;
        }

        int tileWidth = AtlasState.getTileWidth();
        int viewX = (this.width - MAP_VIEW_SIZE) / 2;
        int viewY = 30;

        context.fill(viewX - 2, viewY - 2, viewX + MAP_VIEW_SIZE + 2, viewY + MAP_VIEW_SIZE + 2, 0xC0000000);

        // Clip the map tiles to the panel: in 1.21.11 every DrawContext element captures the
        // scissor rect at submit time, so without an explicit scissor the tiles would be drawn
        // over the whole screen (and the HUD borders). Same pattern vanilla uses for maps.
        context.enableScissor(viewX, viewY, viewX + MAP_VIEW_SIZE, viewY + MAP_VIEW_SIZE);

        Matrix3x2fStack matrices = context.getMatrices();
        matrices.pushMatrix();
        // View is centered on (centerX, centerZ); scale so one map tile (tileWidth blocks)
        // renders as MAP_VIEW_SIZE * zoom pixels.
        float pxPerBlock = (float) ((double) MAP_VIEW_SIZE / ((double) tileWidth * zoom));
        matrices.translate(viewX + MAP_VIEW_SIZE / 2.0F, viewY + MAP_VIEW_SIZE / 2.0F);
        matrices.scale(pxPerBlock, pxPerBlock);

        int tileX = Math.floorDiv((int) centerX, tileWidth);
        int tileZ = Math.floorDiv((int) centerZ, tileWidth);
        int radius = (int) Math.ceil(MAP_VIEW_SIZE / (double) tileWidth * zoom) + 1;

        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int mapId = findMapIdForTile(tileX + dx, tileZ + dz, tileWidth);
                if (mapId < 0) {
                    continue;
                }
                double tileCenterWorldX = (tileX + dx) * tileWidth + tileWidth / 2.0;
                double tileCenterWorldZ = (tileZ + dz) * tileWidth + tileWidth / 2.0;
                float tileScreenX = (float) (tileCenterWorldX - centerX);
                float tileScreenZ = (float) (tileCenterWorldZ - centerZ);

                MapState state = AtlasState.getMapState(mapId);
                if (state == null) {
                    continue;
                }
                matrices.pushMatrix();
                matrices.translate(tileScreenX - tileWidth / 2.0F, tileScreenZ - tileWidth / 2.0F);
                matrices.scale((float) tileWidth / 128.0F, (float) tileWidth / 128.0F);
                MapRenderer renderer = mc.getMapRenderer();
                MapRenderState renderState = OVERVIEW_RENDER_STATES.computeIfAbsent(mapId, k -> new MapRenderState());
                renderer.update(new MapIdComponent(mapId), state, renderState);
                context.drawMap(renderState);
                matrices.popMatrix();
            }
        }

        matrices.popMatrix();
        context.disableScissor();

        // Player marker + coords.
        float playerPxX = viewX + MAP_VIEW_SIZE / 2.0F;
        float playerPxY = viewY + MAP_VIEW_SIZE / 2.0F;
        // Player arrow: server-owned texture, rotated to the player's yaw (north-up map).
        // theta = PI + yaw — same convention as AtlasMinimap (verified on all four cardinals).
        float arrowAngle = (float) Math.PI + (float) Math.toRadians(mc.player.getYaw());
        float arrowScale = AtlasMinimap.ARROW_SCREEN_PX / 32.0F;
        matrices.pushMatrix();
        matrices.translate(playerPxX, playerPxY);
        matrices.rotate(arrowAngle);
        matrices.scale(arrowScale, arrowScale);
        matrices.translate(-16.0F, -16.0F);
        context.drawTexture(RenderPipelines.GUI_TEXTURED, AtlasMinimap.ARROW_TEXTURE, 0, 0, 0, 0, 32, 32, 32, 32, 0xFFFFFFFF);
        matrices.popMatrix();
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("X " + (int) centerX + " Z " + (int) centerZ),
                this.width / 2, viewY + MAP_VIEW_SIZE + 4, 0xFFFFFFFF);

        if (settingsOpen) {
            renderSettings(context);
        }
    }

    /** Small settings panel with the minimap size slider. */
    private void renderSettings(DrawContext context) {
        int panelW = 220;
        int panelH = 60;
        int panelX = this.width / 2 - panelW / 2;
        int panelY = this.height / 2 - panelH / 2;
        context.fill(panelX - 2, panelY - 2, panelX + panelW + 2, panelY + panelH + 2, 0xE0000000);

        int sliderX = panelX + 10;
        int sliderY = panelY + 22;
        int sliderW = panelW - 20;
        int sliderH = 20;
        SliderWidget slider = new SliderWidget(sliderX, sliderY, sliderW, sliderH,
                Text.literal("Tamaño del minimapa: " + AtlasMinimap.getSize() + " px"), 0.0) {
            @Override
            protected void updateMessage() {
                int v = (int) (AtlasMinimap.MINIMAP_SIZE_MIN + this.value * (AtlasMinimap.MINIMAP_SIZE_MAX - AtlasMinimap.MINIMAP_SIZE_MIN));
                this.setMessage(Text.literal("Tamaño del minimapa: " + v + " px"));
            }

            @Override
            protected void applyValue() {
                int v = (int) (AtlasMinimap.MINIMAP_SIZE_MIN + this.value * (AtlasMinimap.MINIMAP_SIZE_MAX - AtlasMinimap.MINIMAP_SIZE_MIN));
                AtlasMinimap.setSize(v);
            }
        };
        slider.setDimensionsAndPosition(sliderW, sliderH, sliderX, sliderY);
        // The panel is opened/closed by the button; the slider lives only while open.
        this.addDrawableChild(slider);
        // Remove it after drawing this frame so repeated render() calls don't pile up widgets.
        this.remove(slider);
    }


    @Override
    public boolean shouldPause() {
        return false;
    }

    private static int findMapIdForTile(int tileX, int tileZ, int tileWidth) {
        int[] ids = AtlasState.getMapIds();
        for (int id : ids) {
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
