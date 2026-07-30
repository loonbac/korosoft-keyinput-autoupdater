package com.korosoft.keyinput;

import net.minecraft.client.MinecraftClient;

/**
 * Per-frame slide animation state for the scoreboard sidebar and the tablist.
 * Driven by real elapsed time (not ticks) so the motion stays smooth regardless
 * of TPS, and advanced once per frame from InGameHud#render.
 *
 * <p>progress 0 = fully hidden (off-screen), 1 = fully shown.
 */
public final class HudAnimator {

    /** Below this the element is treated as fully hidden and is not drawn at all. */
    public static final float EPSILON = 0.001F;

    // How far each element travels, in GUI pixels. The sidebar hugs the right edge and
    // the tablist the top edge, so these only need to exceed their on-screen extent;
    // both are comfortably wider/taller than any realistic vanilla-sized HUD element.
    public static final float SIDEBAR_SLIDE_DISTANCE = 200.0F;
    public static final float TABLIST_SLIDE_DISTANCE = 200.0F;

    // An unfocused or stuttering window can hand us a multi-second delta; capping it
    // keeps the element from teleporting past its target in a single frame.
    private static final float MAX_FRAME_MILLIS = 100.0F;

    private static float sidebarProgress;
    private static float tablistProgress;
    private static long lastFrameNanos;

    private HudAnimator() {
    }

    /** Advances both animations. Must be called exactly once per rendered frame. */
    public static void update() {
        long now = System.nanoTime();
        long previous = lastFrameNanos;
        lastFrameNanos = now;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            sidebarProgress = 0.0F;
            tablistProgress = 0.0F;
            return;
        }

        // first frame after a reset has no baseline to measure against
        float deltaMillis = previous == 0L ? 0.0F : (now - previous) / 1_000_000.0F;
        deltaMillis = Math.clamp(deltaMillis, 0.0F, MAX_FRAME_MILLIS);

        // playerListKey, not a hardcoded GLFW code, so a rebound key still works
        float target = client.options.playerListKey.isPressed() ? 1.0F : 0.0F;
        float step = deltaMillis / HudConfig.getSlideMillis();

        sidebarProgress = approach(sidebarProgress, target, step);
        tablistProgress = approach(tablistProgress, target, step);
    }

    /** Called on disconnect so the HUD does not slide in from a stale position on the next join. */
    public static void reset() {
        sidebarProgress = 0.0F;
        tablistProgress = 0.0F;
        lastFrameNanos = 0L;
    }

    public static float getSidebarProgress() {
        return sidebarProgress;
    }

    public static float getTablistProgress() {
        return tablistProgress;
    }

    /** Ease-out cubic: fast entry, soft landing. */
    public static float easeOutCubic(float progress) {
        float inverse = 1.0F - progress;
        return 1.0F - inverse * inverse * inverse;
    }

    /** Ease-in-out cubic: still at both ends, quickest through the middle. */
    public static float easeInOutCubic(float progress) {
        if (progress < 0.5F) {
            return 4.0F * progress * progress * progress;
        }
        float inverse = -2.0F * progress + 2.0F;
        return 1.0F - inverse * inverse * inverse / 2.0F;
    }

    /** Ease-in-out sine: same still ends as the cubic, but without its rush through the middle. */
    public static float easeInOutSine(float progress) {
        return (float) (1.0 - Math.cos(Math.PI * progress)) / 2.0F;
    }

    /** Ease-in quadratic: barely moves at first, then accelerates all the way into the far end. */
    public static float easeInQuad(float progress) {
        return progress * progress;
    }

    private static float approach(float current, float target, float step) {
        return current < target
                ? Math.min(target, current + step)
                : Math.max(target, current - step);
    }
}
