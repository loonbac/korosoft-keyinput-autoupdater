package com.korosoft.keyinput;

import net.minecraft.util.math.MathHelper;

/**
 * Shape of the ASCEND transfer cinematic's camera move: the player is lifted into the sky while
 * the camera stays anchored to the ground and watches, then pulls back a short distance so the
 * departure reads against the surroundings instead of a fixed close-up.
 *
 * <p>Unlike {@link CutsceneCamera} (which is pure math with no state of its own), this variant
 * needs one static anchor point: the ground camera has to sit where the player's eye WAS at the
 * start of the cinematic, not where it currently is, or the camera would rise with them and the
 * whole point of the shot — watching them leave — would be lost. That anchor is the only state
 * here, and it is cleared by {@link Cutscene#resetToIdle()} the same way every other cinematic
 * flag is, so a leftover anchor can never pop the next cinematic's first frame.
 *
 * <p>The anchor is captured LAZILY, on the first cinematic frame, from inside {@code CameraMixin},
 * using the exact same smoothed focus point vanilla's own {@code Camera#update} computes —
 * <em>not</em> from {@link Cutscene#start} via some eye-position getter. Capturing it any earlier
 * would miss the smoothed {@code cameraY} term {@code CameraMixin} already has to reproduce for the
 * orbit variant, and would pop the camera a few centimetres on frame 1. This is captured-at-start
 * data, not a gate: nothing here or in {@code Cutscene} ever asks "is the cinematic running" by
 * checking {@link #isCaptured()} — that question only ever has one answer, {@link Cutscene#isActive()}.
 */
public final class AscendCamera {

    private static volatile boolean captured;
    private static volatile double eyeX;
    private static volatile double eyeY;
    private static volatile double eyeZ;
    private static volatile double baseY;
    private static volatile float anchorYaw;

    private AscendCamera() {
    }

    /**
     * Records the ground anchor. Idempotent by design, same as {@link Cutscene#start}: the first
     * cinematic frame captures it, and every frame after that is a no-op, because re-capturing
     * later would drag the "ground" position along with the rising player.
     *
     * <p>{@code baseY} is the entity-Y term {@code CameraMixin} already computes on its way to the
     * smoothed eye height ({@code eyeY}) — threaded through here rather than re-derived so
     * {@link com.korosoft.keyinput.AscendBeam}'s ground anchor can never drift a frame out of sync
     * with the eye anchor above it.
     */
    public static void capture(double eyeX, double eyeY, double eyeZ, double baseY, float yaw) {
        if (captured) {
            return;
        }
        AscendCamera.eyeX = eyeX;
        AscendCamera.eyeY = eyeY;
        AscendCamera.eyeZ = eyeZ;
        AscendCamera.baseY = baseY;
        AscendCamera.anchorYaw = yaw;
        captured = true;
    }

    public static boolean isCaptured() {
        return captured;
    }

    /** Cleared by {@link Cutscene#resetToIdle()}; see the class doc for why nothing else may clear it. */
    public static void reset() {
        captured = false;
    }

    public static double getEyeX() {
        return eyeX;
    }

    public static double getEyeY() {
        return eyeY;
    }

    public static double getEyeZ() {
        return eyeZ;
    }

    /** The anchor's ground (feet) Y — where {@link com.korosoft.keyinput.AscendBeam} bases the column. */
    public static double getBaseY() {
        return baseY;
    }

    public static float getAnchorYaw() {
        return anchorYaw;
    }

    /** 0..1 blend from the anchored setup pose into the pulled-back watching pose. */
    public static float pullOutWeight(float progress) {
        return HudAnimator.easeInOutCubic(Math.min(1.0F, progress / CutsceneConfig.getAscendPullout()));
    }

    /**
     * How far around the player's render model has turned to face the (stationary) camera, in
     * degrees. Lives here rather than in the mixin that applies it because it is the other half of
     * the same framing decision as {@link #pullOutWeight}: the camera stays anchored behind the
     * player by design (see the class doc), so the only way to get their face into a shot that
     * watches them leave is to turn the model itself, not the camera. Locked to
     * {@link #pullOutWeight} on purpose — one shot, one curve — so the turn can never drift a frame
     * out of step with the pull-back that motivates it.
     *
     * <p>Sine easing, not the cubic the pull-back uses: this is a full 180-degree spin rather than a
     * short positional nudge, and a cubic's near-linear middle would read as the model whipping
     * around at a visibly different rate than the camera settling into place.
     */
    public static float getSelfTurnDegrees(float progress) {
        return CutsceneConfig.getAscendTurn() * HudAnimator.easeInOutSine(pullOutWeight(progress));
    }

    /** Setup pitch only — {@code moveBy}'s axis, overwritten by the look-at aim once positioned. */
    public static float getSetupPitch(float basePitch, float progress) {
        return MathHelper.lerp(pullOutWeight(progress), basePitch, CutsceneConfig.getAscendSetupPitch());
    }

    public static float getBackDistance(float progress) {
        return CutsceneConfig.getAscendBack() * pullOutWeight(progress);
    }

    /** The settled aim, blended in from the player's own pitch over the pull-back. */
    public static float getLookPitch() {
        return CutsceneConfig.getAscendLookPitch();
    }

    /**
     * Whether the camera has cleared the player's head and the model may be drawn. Derived from the
     * pull-back alone, exactly like {@link CutsceneCamera#isThirdPersonAt}, because the pull-back IS
     * how far the camera has travelled from the eye — it is zero at progress 0 by construction, so
     * frame 0 is guaranteed first person and the model can never pop in while the camera is still
     * inside the head.
     *
     * <p>Explicitly NOT the camera's distance to anything it is aiming at. An earlier version gated
     * on the distance to an aim point down near the player's feet, which the camera already sits a
     * metre above on frame 0 while still buried in their head — so it reported third person
     * immediately and drew the inside of the skin's no-cull hat layer. That aim point is gone now
     * (see {@link #getLookPitch()}), but the lesson outlives it: the question is how far the camera
     * has MOVED, never how far away something it looks at happens to be.
     */
    public static boolean isThirdPersonAt(float progress) {
        return getBackDistance(progress) >= CutsceneConfig.getAscendVisible();
    }
}
