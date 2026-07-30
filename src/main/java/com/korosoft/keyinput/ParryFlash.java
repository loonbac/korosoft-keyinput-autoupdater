package com.korosoft.keyinput;

/**
 * State of the white screen flash played when a parry lands.
 * Driven by real elapsed time (not ticks) so the fade stays smooth regardless of TPS,
 * and sampled once per frame from InGameHud#render.
 *
 * <p>Every knob (duration, hold, peak opacity) comes from the server on each flash, so the
 * look can be retuned with a script reload instead of a mod rebuild — a rebuild would force
 * every player to re-download the jar.
 */
public final class ParryFlash {

    /** Below this the flash is treated as finished and is not drawn at all. */
    public static final float EPSILON = 0.004F;

    // Used only until the server sends its first flash: a fully opaque white screen would be
    // blinding, so the default reads as a hard flash while the world stays visible through it.
    private static final float DEFAULT_PEAK_ALPHA = 0.72F;
    private static final float DEFAULT_HOLD_FRACTION = 0.45F;

    // The server owns these values, but it could send garbage. Clamping keeps a bad number from
    // freezing the screen white forever (a bad duration still ends the flash — see getAlpha).
    // Hold is allowed all the way to 1.0: that means "full peak for the whole duration, then cut to
    // nothing" — a hard on/off flash with no fade at all, which is a look the server can ask for.
    // getAlpha guards the fade division so hold == 1.0 never divides by zero.
    private static final int MIN_FLASH_MILLIS = 1;
    private static final int MAX_FLASH_MILLIS = 5000;
    private static final float MAX_HOLD_FRACTION = 1.0F;

    // Sentinel for "the server did not tell us which entity was parried" (older server, or the
    // attacker was not resolvable client-side): the flash still plays, just with no silhouette.
    public static final int NO_ENTITY = -1;

    // written on the netty thread (via client.execute), read on the render thread
    private static volatile boolean active;
    private static volatile long startNanos;
    private static volatile long durationNanos;
    private static volatile float holdFraction = DEFAULT_HOLD_FRACTION;
    private static volatile float peakAlpha = DEFAULT_PEAK_ALPHA;

    // The parried entity's network id plus the world-space point (bounding-box centre) it occupied
    // at the instant of the parry. The position is FROZEN here on purpose — the silhouette is meant
    // to hang where the enemy was, so the mob (and the player's aim) can move on without dragging it.
    // See ParrySilhouette for how these are projected back to the screen each frame.
    private static volatile int entityId = NO_ENTITY;
    private static volatile double frozenX;
    private static volatile double frozenY;
    private static volatile double frozenZ;

    private ParryFlash() {
    }

    /**
     * Starts (or restarts, if a parry lands mid-fade) the flash.
     * Hold and peak arrive as 0-100 percentages so the wire format stays all-int.
     *
     * @param parriedEntityId network id of the parried entity, or {@link #NO_ENTITY} for no silhouette
     * @param cx,cy,cz world-space bounding-box centre of that entity, frozen at parry time
     */
    public static void start(int flashMillis, int holdPercent, int peakPercent,
                             int parriedEntityId, double cx, double cy, double cz) {
        durationNanos = Math.clamp(flashMillis, MIN_FLASH_MILLIS, MAX_FLASH_MILLIS) * 1_000_000L;
        holdFraction = Math.clamp(holdPercent / 100.0F, 0.0F, MAX_HOLD_FRACTION);
        peakAlpha = Math.clamp(peakPercent / 100.0F, 0.0F, 1.0F);
        entityId = parriedEntityId;
        frozenX = cx;
        frozenY = cy;
        frozenZ = cz;
        startNanos = System.nanoTime();
        active = true;
    }

    /** Called on disconnect: the next server must not inherit a flash still fading out. */
    public static void reset() {
        active = false;
        startNanos = 0L;
        durationNanos = 0L;
        holdFraction = DEFAULT_HOLD_FRACTION;
        peakAlpha = DEFAULT_PEAK_ALPHA;
        entityId = NO_ENTITY;
        frozenX = 0.0;
        frozenY = 0.0;
        frozenZ = 0.0;
    }

    /** Network id of the parried entity for this flash, or {@link #NO_ENTITY}. */
    public static int getEntityId() {
        return entityId;
    }

    public static double getFrozenX() {
        return frozenX;
    }

    public static double getFrozenY() {
        return frozenY;
    }

    public static double getFrozenZ() {
        return frozenZ;
    }

    /** Current overlay opacity, 0 when the flash is over. Safe to call every frame. */
    public static float getAlpha() {
        if (!active) {
            return 0.0F;
        }

        long elapsed = System.nanoTime() - startNanos;
        long duration = durationNanos;
        if (elapsed >= duration || duration <= 0L) {
            // guard: a nonsensical duration must end the flash, never hang it
            active = false;
            return 0.0F;
        }

        // read the volatile knobs once, so a flash arriving mid-frame cannot change the curve
        // halfway through this calculation
        float hold = holdFraction;
        float peak = peakAlpha;

        // clamped because nanoTime is monotonic per call site, not across threads
        float progress = Math.clamp((float) elapsed / (float) duration, 0.0F, 1.0F);
        if (progress <= hold) {
            return peak;
        }

        // hold == 1.0 is the hard-cut case: full peak the whole way, then the duration guard above
        // ends it instantly. Nothing to fade, and this also keeps the division below off zero.
        if (hold >= 1.0F) {
            return peak;
        }

        // remap the remaining time to 0..1 and ease out from the peak
        float fade = (progress - hold) / (1.0F - hold);
        return peak * (1.0F - HudAnimator.easeOutCubic(fade));
    }
}
