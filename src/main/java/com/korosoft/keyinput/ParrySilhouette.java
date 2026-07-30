package com.korosoft.keyinput;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws the parried mob as a pure-black, frozen silhouette on top of the white parry flash (see
 * {@link ParryFlash}). Called from the HUD's TAIL, after the flash has painted the screen, so the
 * silhouette queues on top of it.
 *
 * <p>"Frozen in place": the mob is drawn at the screen position its FROZEN world point (captured at
 * parry time by {@code KeyInputClient}) projects to this frame, sized by how far that point is from
 * the camera. Turning the view moves the silhouette across the screen exactly as the real spot
 * would move — it hangs in the world where the enemy was, not glued to the HUD.
 *
 * <p>Two things this pipeline forces, both verified against the 1.21.11 deferred renderer:
 * <ul>
 *   <li><b>Pure black</b> comes from tagging the render state ({@link ForceBlackState}) so
 *       {@code LivingEntityRenderer#getMixColor} returns opaque black when the deferred GUI batch
 *       finally draws it. There is no colour argument on {@code DrawContext.addEntity}.</li>
 *   <li><b>Behind-camera rejection</b> is a dot-product test, NOT the projected z: this version's
 *       {@code GameRenderer.project} throws away the clip-space w, so a point behind the camera does
 *       not come back with any clean out-of-range flag — its projected coordinates are simply
 *       garbage. The dot test is the only reliable guard.</li>
 * </ul>
 */
public final class ParrySilhouette {

    // Below this flash opacity the silhouette is dropped, so it vanishes with the bright part of the
    // flash instead of lingering as a black cut-out once the white has faded off the world.
    private static final float VISIBILITY_THRESHOLD = 0.10F;

    // A point whose camera-relative direction barely faces forward projects unstably; require a small
    // positive dot so grazing/behind points are skipped rather than drawn at a garbage position.
    private static final double FRONT_DOT_MIN = 0.10;

    // ---- Look calibration. These are the knobs to eyeball in game if the silhouette reads wrong. ----
    // Maps the mob's real on-screen pixel height to the GUI entity render scale. If the silhouette is
    // too big or too small for how far the enemy was, this is the one number to change.
    private static final float SIZE_PER_PIXEL = 0.5F;
    private static final float MIN_SIZE = 4.0F;
    private static final float MAX_SIZE = 512.0F;
    // The viewport/scissor box addEntity renders inside. Kept generous around the projected centre so
    // the model is never clipped; empty margin costs nothing.
    private static final float MIN_RECT = 24.0F;
    private static final float RECT_MARGIN = 1.25F;

    // Fully lit: the black tint wins regardless, but matching vanilla's GUI value avoids odd
    // lighting states on the render snapshot.
    private static final int FULL_BRIGHT = 15728880;

    private ParrySilhouette() {
    }

    /** Queues the silhouette for this frame. No-op when there is no parried entity to draw. */
    public static void render(DrawContext context) {
        if (ParryFlash.getEntityId() == ParryFlash.NO_ENTITY) {
            return;
        }
        // getAlpha() also returns 0 once the flash is over, so this covers "flash finished" too.
        if (ParryFlash.getAlpha() <= VISIBILITY_THRESHOLD) {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;
        if (world == null || mc.player == null) {
            return;
        }

        Entity entity = world.getEntityById(ParryFlash.getEntityId());
        if (!(entity instanceof LivingEntity living) || entity == mc.player) {
            // dead, unloaded, or somehow the player: no model to draw, flash carries on alone
            return;
        }

        Camera camera = mc.gameRenderer.getCamera();
        Vec3d camPos = camera.getCameraPos();
        Vec3d center = new Vec3d(ParryFlash.getFrozenX(), ParryFlash.getFrozenY(), ParryFlash.getFrozenZ());

        // Behind-camera guard (see class doc for why project's output cannot answer this).
        Vec3d toCenter = center.subtract(camPos);
        double yawRad = Math.toRadians(camera.getYaw());
        double pitchRad = Math.toRadians(camera.getPitch());
        Vec3d look = new Vec3d(
                -Math.sin(yawRad) * Math.cos(pitchRad),
                -Math.sin(pitchRad),
                Math.cos(yawRad) * Math.cos(pitchRad));
        if (toCenter.dotProduct(look) <= FRONT_DOT_MIN) {
            return;
        }

        int screenWidth = context.getScaledWindowWidth();
        int screenHeight = context.getScaledWindowHeight();

        // project() returns normalized device coords (x,y in [-1,1], y UP), perspective divide done.
        Vec3d ndcCenter = mc.gameRenderer.project(center);
        float screenX = (float) ((ndcCenter.x * 0.5 + 0.5) * screenWidth);
        float screenY = (float) ((1.0 - (ndcCenter.y * 0.5 + 0.5)) * screenHeight);

        // Real on-screen height: project the frozen point's feet and head and measure the gap. This
        // is what makes the silhouette shrink with distance the way the actual mob did.
        double height = living.getBoundingBox().getLengthY();
        Vec3d ndcFeet = mc.gameRenderer.project(new Vec3d(center.x, center.y - height / 2.0, center.z));
        Vec3d ndcHead = mc.gameRenderer.project(new Vec3d(center.x, center.y + height / 2.0, center.z));
        float pixelHeight = (float) Math.abs(ndcHead.y - ndcFeet.y) * 0.5F * screenHeight;

        float size = MathHelper.clamp(pixelHeight * SIZE_PER_PIXEL, MIN_SIZE, MAX_SIZE);
        float half = Math.max(pixelHeight, MIN_RECT) * RECT_MARGIN;
        int x1 = Math.round(screenX - half);
        int y1 = Math.round(screenY - half);
        int x2 = Math.round(screenX + half);
        int y2 = Math.round(screenY + half);

        // Mirror InventoryScreen.drawEntity's setup so the deferred GUI renderer draws a clean,
        // upright, front-facing snapshot — but grab the render state ourselves so we can tag it black.
        EntityRenderManager dispatcher = mc.getEntityRenderDispatcher();
        EntityRenderer<? super LivingEntity, ?> renderer = dispatcher.getRenderer(living);
        EntityRenderState state = renderer.getAndUpdateRenderState(living, 1.0F);
        state.light = FULL_BRIGHT;
        state.outlineColor = 0;
        state.shadowPieces.clear();
        // Set AFTER getAndUpdateRenderState — that call clears the flag (see LivingEntityRendererMixin).
        ((ForceBlackState) state).keyinput$setForceBlack(true);

        if (state instanceof LivingEntityRenderState livingState) {
            // Face the viewer. The attacker was facing the player to land its hit, so a front-on
            // silhouette reads as "the enemy that just struck", not an arbitrary pose.
            livingState.bodyYaw = 180.0F;
            livingState.relativeHeadYaw = 0.0F;
            livingState.pitch = 0.0F;
            // Undo the baked base scale so `size` alone controls how big it renders, same as vanilla.
            livingState.width = livingState.width / livingState.baseScale;
            livingState.height = livingState.height / livingState.baseScale;
            livingState.baseScale = 1.0F;
        }

        Vector3f offset = new Vector3f(0.0F, state.height / 2.0F, 0.0F);
        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI);
        Quaternionf cameraAngle = new Quaternionf();
        context.addEntity(state, size, offset, rotation, cameraAngle, x1, y1, x2, y2);
    }
}
