package com.korosoft.keyinput;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3x2fStack;

import java.util.List;

/**
 * Draws the ping markers over the HUD: a diamond icon at the projected screen position plus a
 * distance label, and an edge arrow when the marker is off-screen. Ported from Ping-Wheel's
 * OverlayRenderer/PingLocationRenderer/DirectionIndicatorRenderer, using
 * {@code GameRenderer.project} (NDC) like {@link ParrySilhouette} already does in this mod.
 *
 * <p>The draw happens from {@code GameRenderer#render} right before the GUI flush (the same hook
 * the transfer curtain uses) so the pings sit on top of everything, including the tablist.
 */
public final class PingRenderer {

    public static final Identifier PING_TEXTURE = Identifier.of("keyinput", "textures/ping/ping.png");
    public static final Identifier ARROW_TEXTURE = Identifier.of("keyinput", "textures/ping/arrow.png");

    private static final int WHITE = -1;

    private PingRenderer() {
    }

    /** Called from the render loop with the deferred DrawContext. */
    public static void render(DrawContext context, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) {
            return;
        }
        // Hide the markers while any screen (inventory, chat, menu) is open — the ping
        // overlay belongs to the world view, exactly like Ping-Wheel's own HUD overlay.
        if (mc.currentScreen != null) {
            return;
        }

        List<PingState> pings = PingManager.snapshot();
        if (pings.isEmpty()) {
            return;
        }

        int screenW = context.getScaledWindowWidth();
        int screenH = context.getScaledWindowHeight();

        for (PingState ping : pings) {
            drawPing(context, mc, ping, screenW, screenH);
        }
    }

    private static void drawPing(DrawContext context, MinecraftClient mc, PingState ping,
                                 int screenW, int screenH) {
        GameRenderer gameRenderer = mc.gameRenderer;
        Vec3d worldPos = new Vec3d(ping.getX(), ping.getY(), ping.getZ());
        Vec3d ndc = gameRenderer.project(worldPos);

        // NDC: x,y in [-1,1], y up. Behind-camera rejection via the dot product (like
        // ParrySilhouette) because project() gives garbage for behind points.
        Vec3d camPos = gameRenderer.getCamera().getCameraPos();
        Vec3d toPing = worldPos.subtract(camPos);
        double yawRad = Math.toRadians(gameRenderer.getCamera().getYaw());
        double pitchRad = Math.toRadians(gameRenderer.getCamera().getPitch());
        Vec3d look = new Vec3d(
                -Math.sin(yawRad) * Math.cos(pitchRad),
                -Math.sin(pitchRad),
                Math.cos(yawRad) * Math.cos(pitchRad));
        boolean behind = toPing.dotProduct(look) <= 0.02;

        float screenX = (float) ((ndc.x * 0.5 + 0.5) * screenW);
        float screenY = (float) ((1.0 - (ndc.y * 0.5 + 0.5)) * screenH);

        double distance = camPos.distanceTo(worldPos);

        // Scale like Ping-Wheel: grows closer, shrinks far away.
        float scale = (float) Math.max(1.0, 2.0 / Math.pow(Math.max(distance, 1.0), 0.3)) * 0.5F;

        Matrix3x2fStack matrices = context.getMatrices();
        if (!behind && screenX >= -20 && screenX <= screenW + 20 && screenY >= -20 && screenY <= screenH + 20) {
            // On-screen marker: diamond icon + distance label below it.
            matrices.pushMatrix();
            matrices.translate(screenX, screenY);
            matrices.scale(scale, scale);
            drawDiamond(context, 12);
            matrices.popMatrix();

            String label = formatDistance(distance);
            TextRenderer font = mc.textRenderer;
            int labelW = font.getWidth(label);
            context.drawTextWithShadow(font, label,
                    Math.round(screenX) - labelW / 2, Math.round(screenY) + 10, WHITE);
        } else {
            // Off-screen: arrow at the screen edge pointing toward the marker.
            drawEdgeArrow(context, matrices, screenX, screenY, screenW, screenH, behind);
        }
    }

    private static void drawDiamond(DrawContext context, int size) {
        Matrix3x2fStack matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.rotate((float) (Math.PI / 4.0));
        matrices.translate(-size / 2.0F, -size / 2.0F);
        context.fill(0, 0, size, size, WHITE);
        matrices.popMatrix();
    }

    private static void drawEdgeArrow(DrawContext context, Matrix3x2fStack matrices,
                                      float screenX, float screenY, int screenW, int screenH, boolean behind) {
        float centerX = screenW / 2.0F;
        float centerY = screenH / 2.0F;
        float dx = screenX - centerX;
        float dy = screenY - centerY;
        if (behind) {
            dx = -dx;
            dy = -dy;
        }
        float angle = (float) Math.atan2(dy, dx);

        // Intersect the direction with the screen rect (20px margin from the edges).
        float edgeX = centerX;
        float edgeY = centerY;
        float halfW = screenW / 2.0F - 20.0F;
        float halfH = screenH / 2.0F - 20.0F;
        if (dx != 0 || dy != 0) {
            float t = Float.MAX_VALUE;
            if (Math.abs(dx) > 0.001F) {
                t = Math.min(t, Math.abs(halfW / dx));
            }
            if (Math.abs(dy) > 0.001F) {
                t = Math.min(t, Math.abs(halfH / dy));
            }
            edgeX = centerX + dx * t;
            edgeY = centerY + dy * t;
        }

        matrices.pushMatrix();
        matrices.translate(edgeX, edgeY);
        matrices.rotate(angle);
        // Arrow texture is 64x64 with the arrow pointing right; draw at 12px.
        context.drawTexture(RenderPipelines.GUI_TEXTURED, ARROW_TEXTURE,
                -6, -6, 0.0F, 0.0F, 12, 12, 64, 64);
        matrices.popMatrix();
    }

    private static String formatDistance(double distance) {
        if (distance >= 100.0) {
            return String.format("%.0fm", distance);
        }
        return String.format("%.1fm", distance);
    }
}
