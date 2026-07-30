package com.korosoft.keyinput;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * The volumetric light column drawn under the rising player during the ASCEND cinematic (see
 * {@link Cutscene}, {@link AscendCamera}). Pure math and a render call, same as {@link
 * CutsceneCamera}: everything here is recomputed from {@link Cutscene#getCinematicProgress()} and
 * {@link AscendCamera}'s anchor every frame, and there is deliberately no field on this class —
 * a cached "has it landed yet" flag could fall out of sync with the clock it is supposed to be
 * derived from, the same failure mode {@link Cutscene}'s own class doc warns about.
 *
 * <p>Registered against {@code WorldRenderEvents.BEFORE_TRANSLUCENT} rather than an "after
 * translucent" hook: this Fabric API build (16.2.10, matching the 1.21.9+ deferred-rendering
 * rewrite) no longer has one — {@code AFTER_TRANSLUCENT} and {@code LAST} are gone. Firing right
 * as vanilla's own translucent pass starts means this geometry lands in the same batched
 * {@code VertexConsumerProvider.Immediate} flush as water/glass/etc., so it still depth-tests
 * against opaque terrain that was drawn earlier in the frame.
 */
public final class AscendBeam {

    // Lifted off the ground a hair so the disc does not z-fight with whatever block the anchor
    // was standing on.
    private static final float POOL_Y_OFFSET = 0.02F;

    // How much additional progress after landing it takes the pool to reach full alpha — short
    // enough that it does not lag noticeably behind the column touching down.
    private static final float POOL_FADE_IN_FRACTION = 0.1F;

    private AscendBeam() {
    }

    /**
     * Draws the column and its ground pool for the current frame. No-op outside the ASCEND
     * cinematic, same gate {@code CameraMixin} uses for the rest of this variant's effects.
     */
    public static void render(WorldRenderContext context) {
        if (!Cutscene.isActive() || Cutscene.getKind() != Cutscene.Kind.ASCEND || !AscendCamera.isCaptured()) {
            return;
        }

        // Pre-fading by the curtain here, once, means nothing below has to know the curtain
        // exists: every alpha computed from this point on is already "how much of the beam should
        // still be visible", so the beam simply has nothing left to draw by the time the curtain
        // goes solid white instead of needing its own teardown path.
        float curtainFade = 1.0F - Cutscene.getAlpha();
        if (curtainFade <= Cutscene.EPSILON) {
            return;
        }

        MatrixStack matrices = context.matrices();
        if (matrices == null) {
            return;
        }

        float progress = Cutscene.getCinematicProgress();
        float baseY = (float) AscendCamera.getBaseY();
        float topY = baseY + CutsceneConfig.getBeamHeight();

        float fallFraction = CutsceneConfig.getBeamFall();
        float bottomY;
        if (progress < fallFraction) {
            float fallT = HudAnimator.easeOutCubic(progress / fallFraction);
            bottomY = MathHelper.lerp(fallT, topY, baseY);
        } else {
            bottomY = baseY;
        }

        Vec3d camPos = context.worldState().cameraRenderState.pos;

        matrices.push();
        // WorldRenderEvents hands the world-space coordinates it deals in relative to the camera,
        // not as absolute world coordinates — bake that offset into the matrix stack once here so
        // every vertex below can be written in plain world-space X/Y/Z.
        matrices.translate(AscendCamera.getEyeX() - camPos.x, -camPos.y, AscendCamera.getEyeZ() - camPos.z);
        MatrixStack.Entry entry = matrices.peek();

        VertexConsumer wall = context.consumers().getBuffer(RenderLayers.debugQuads());
        renderWall(entry, wall, bottomY, topY, CutsceneConfig.getBeamAlpha() * curtainFade);

        if (progress >= fallFraction) {
            float poolT = Math.min(1.0F, (progress - fallFraction) / POOL_FADE_IN_FRACTION);
            float poolAlpha = CutsceneConfig.getBeamPoolAlpha() * HudAnimator.easeOutCubic(poolT) * curtainFade;
            if (poolAlpha > Cutscene.EPSILON) {
                VertexConsumer pool = context.consumers().getBuffer(RenderLayers.debugTriangleFan());
                renderPool(entry, pool, baseY + POOL_Y_OFFSET, poolAlpha);
            }
        }

        matrices.pop();
    }

    /**
     * The cylinder's side surface as a ring of quads. Drawn through {@code debugQuads()} — the
     * only public, untextured, translucent, no-cull position+color layer this Yarn mapping
     * exposes — so the far wall is visible through the near one at grazing angles by design: that
     * overlap is what reads as a dense volume instead of a thin, papery tube.
     */
    private static void renderWall(MatrixStack.Entry entry, VertexConsumer consumer, float bottomY, float topY, float alpha) {
        float radius = CutsceneConfig.getBeamRadius();
        int sides = Math.round(CutsceneConfig.getBeamSides());
        float colorR = CutsceneConfig.getBeamRed();
        float colorG = CutsceneConfig.getBeamGreen();
        float colorB = CutsceneConfig.getBeamBlue();

        float step = (float) (Math.PI * 2.0 / sides);
        for (int i = 0; i < sides; i++) {
            float a0 = step * i;
            float a1 = step * (i + 1);
            float x0 = radius * MathHelper.cos(a0);
            float z0 = radius * MathHelper.sin(a0);
            float x1 = radius * MathHelper.cos(a1);
            float z1 = radius * MathHelper.sin(a1);

            consumer.vertex(entry, x0, bottomY, z0).color(colorR, colorG, colorB, alpha);
            consumer.vertex(entry, x0, topY, z0).color(colorR, colorG, colorB, alpha);
            consumer.vertex(entry, x1, topY, z1).color(colorR, colorG, colorB, alpha);
            consumer.vertex(entry, x1, bottomY, z1).color(colorR, colorG, colorB, alpha);
        }
    }

    /**
     * The ground pool as a triangle fan: a full-alpha center vertex surrounded by a ring of
     * zero-alpha rim vertices, so the interpolated fragment alpha fades smoothly from bright
     * center to nothing at the edge instead of ending in a hard-edged disc.
     */
    private static void renderPool(MatrixStack.Entry entry, VertexConsumer consumer, float y, float centerAlpha) {
        float poolRadius = CutsceneConfig.getBeamPoolRadius();
        int sides = Math.round(CutsceneConfig.getBeamSides());
        float colorR = CutsceneConfig.getBeamRed();
        float colorG = CutsceneConfig.getBeamGreen();
        float colorB = CutsceneConfig.getBeamBlue();

        consumer.vertex(entry, 0.0F, y, 0.0F).color(colorR, colorG, colorB, centerAlpha);

        float step = (float) (Math.PI * 2.0 / sides);
        for (int i = 0; i <= sides; i++) {
            // <= sides, not < sides: the fan has to close on itself, which needs the first rim
            // point repeated as the last vertex rather than relying on the draw mode to wrap.
            float a = step * i;
            float x = poolRadius * MathHelper.cos(a);
            float z = poolRadius * MathHelper.sin(a);
            consumer.vertex(entry, x, y, z).color(colorR, colorG, colorB, 0.0F);
        }
    }
}
