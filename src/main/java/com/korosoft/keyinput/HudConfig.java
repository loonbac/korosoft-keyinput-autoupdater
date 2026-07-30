package com.korosoft.keyinput;

/**
 * Holds the HUD animation settings pushed by the server on "keyinput:hudcfg".
 * Defaults are kept sane so the mod still behaves correctly on a server that
 * never sends a config (or on a vanilla one).
 */
public final class HudConfig {

    private static final int DEFAULT_SIDEBAR_Y_OFFSET = 0;
    private static final int DEFAULT_SLIDE_MILLIS = 180;

    // a server could send garbage; clamping keeps the animation from dividing by
    // zero (instant snap) or crawling for minutes
    private static final int MIN_SLIDE_MILLIS = 1;
    private static final int MAX_SLIDE_MILLIS = 5000;

    // written on the netty thread, read on the render thread
    private static volatile int sidebarYOffset = DEFAULT_SIDEBAR_Y_OFFSET;
    private static volatile int slideMillis = DEFAULT_SLIDE_MILLIS;

    private HudConfig() {
    }

    public static void apply(int sidebarYOffset, int slideMillis) {
        HudConfig.sidebarYOffset = sidebarYOffset;
        HudConfig.slideMillis = Math.clamp(slideMillis, MIN_SLIDE_MILLIS, MAX_SLIDE_MILLIS);
    }

    /** Called on disconnect: the next server must not inherit this one's settings. */
    public static void reset() {
        sidebarYOffset = DEFAULT_SIDEBAR_Y_OFFSET;
        slideMillis = DEFAULT_SLIDE_MILLIS;
    }

    /** Vertical shift of the scoreboard sidebar in GUI pixels; negative moves it up. */
    public static int getSidebarYOffset() {
        return sidebarYOffset;
    }

    public static int getSlideMillis() {
        return slideMillis;
    }
}
