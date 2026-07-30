package com.korosoft.keyinput;

import net.minecraft.util.math.MathHelper;

/**
 * Shape of the transfer cinematic's camera move: pure math over {@link Cutscene}'s progress, with
 * no state of its own. {@code CameraMixin} feeds it the progress and applies the result.
 *
 * <p>The whole move is expressed as offsets from the player's <em>live</em> view, and every curve
 * starts at zero, so at progress 0 the camera is bit-for-bit the vanilla first-person camera and
 * the pull-out is continuous instead of a jump cut. The player's yaw is frozen for the duration
 * anyway (see {@code MouseMixin}), which is what makes the orbit read as circling around the view
 * they were left holding.
 */
public final class CutsceneCamera {

    private CutsceneCamera() {
    }

    /**
     * Whether the camera has cleared the player and the model should be drawn, i.e. whether the
     * cinematic looks like third person yet. Derived from progress alone, like everything here.
     *
     * <p>Note this is deliberately <em>not</em> the same gate as the held item's: the hand has to
     * go the instant the camera detaches from the eye, or it would visibly slide away with it,
     * whereas the model must wait until the camera is outside it. The gap between the two is what
     * reads as the camera lifting out of the player's head.
     */
    public static boolean isThirdPersonAt(float progress) {
        return getDistance(progress) >= CutsceneConfig.getOrbitVisible();
    }

    /** How far behind the player the camera sits. 0 at progress 0, i.e. exactly first person. */
    public static float getDistance(float progress) {
        return CutsceneConfig.getOrbitDistance() * HudAnimator.easeInOutCubic(pullOutProgress(progress));
    }

    /** Camera pitch, leaving the player's own pitch and settling on {@link CutsceneConfig#getOrbitPitch()}. */
    public static float getPitch(float basePitch, float progress) {
        return MathHelper.lerp(HudAnimator.easeInOutCubic(pullOutProgress(progress)), basePitch, CutsceneConfig.getOrbitPitch());
    }

    /**
     * Degrees to add to the player's yaw. Eased in and out so the orbit has no angular velocity
     * at either end — it must not snap into motion out of a static first-person view, and it must
     * not still be swinging when the cut lands.
     */
    public static float getOrbitYawOffset(float progress) {
        return CutsceneConfig.getOrbitDegrees() * HudAnimator.easeInOutSine(progress);
    }

    private static float pullOutProgress(float progress) {
        return Math.min(1.0F, progress / CutsceneConfig.getOrbitPullout());
    }
}
