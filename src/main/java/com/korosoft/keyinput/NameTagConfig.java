package com.korosoft.keyinput;

import java.util.Locale;

/**
 * Holds the belowname label scale pushed by the server on "keyinput:namecfg". Mirrors
 * {@link HudConfig}/{@link CutsceneConfig}'s lifecycle: apply-on-receipt from the netty thread,
 * {@link #reset()} on disconnect so the next server never inherits this one's tuning, and a
 * default equal to vanilla's own scale so a server that never sends anything looks byte-identical
 * to before this config existed.
 *
 * <p>"Belowname" is the killtag line the TAB plugin writes under player nametags — see
 * {@code LivingEntityRendererMixin}'s class doc for how that line reaches the render state in the
 * first place. This class touches the belowname line's SCALE and its label BACKGROUND (the dark
 * box drawn behind the text); there is no shadow control here, and none is planned — that was
 * already ruled out as not client-controllable.
 *
 * <p>{@link #apply(String)} parses a "key=value" spec, following {@link CutsceneConfig}'s rules
 * verbatim: an unknown key is ignored and an unparsable or NaN value keeps whatever this field is
 * CURRENTLY holding (not the hardcoded default), so the wire format stays append-only forever.
 *
 * <p>{@link #renderingBelowName} is a second piece of state entirely, not part of the wire
 * format: it is the flag the render-thread mixins raise for the duration of a single
 * {@code submitLabel} call so the shared {@code LabelCommandRenderer$Commands#add} path can tell
 * which of the two labels (belowname vs. the main nametag) it is currently building. See
 * {@code PlayerEntityRendererMixin}'s class doc for why a plain boolean is safe here (the render
 * thread never re-enters this call).
 */
public final class NameTagConfig {

    /** Vanilla's own label scale — sending nothing must look exactly like this mod not existing. */
    private static final float DEFAULT_BELOW_NAME_SCALE = 1.0F;

    // A server could send garbage (a Skript typo, a bad substitution). Clamped the same way every
    // other config in this mod clamps its inputs, so a bad number can only ever push the label to
    // a weird-looking edge (tiny or huge), never zero/negative/NaN it into invisibility or a crash.
    private static final float MIN_BELOW_NAME_SCALE = 0.25F;
    private static final float MAX_BELOW_NAME_SCALE = 4.0F;

    // written on the netty thread, read on the render thread — same convention as HudConfig
    private static volatile float belowNameScale = DEFAULT_BELOW_NAME_SCALE;

    // true = vanilla behaviour (background drawn). Same netty-write / render-thread-read pattern
    // as belowNameScale above.
    private static volatile boolean belowNameBackground = true;

    /**
     * Raised by {@code PlayerEntityRendererMixin} for the exact duration of the belowname
     * {@code submitLabel} call, lowered in a {@code finally} so an exception mid-call can never
     * leave it stuck (which would go on to scale the main nametag too, forever, until the next
     * belowname call happened to clear it). Plain (non-volatile) boolean: both the raise/lower and
     * the read in {@code LabelCommandRenderer$Commands$$Mixin#add} happen on the same render
     * thread, back to back within a single frame, with no other thread ever touching it.
     */
    private static boolean renderingBelowName = false;

    private NameTagConfig() {
    }

    /**
     * Parses "key=value;key=value;..." and applies every recognised key. See the class doc for
     * why an unknown key is ignored and an unparsable/NaN value keeps its current value instead of
     * reverting to the default.
     */
    public static void apply(String spec) {
        if (spec == null || spec.isEmpty()) {
            return;
        }

        for (String rawPair : spec.split(";")) {
            int eq = rawPair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = rawPair.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String rawValue = rawPair.substring(eq + 1).trim();

            if (key.equals("belowscale")) {
                float value;
                try {
                    value = Float.parseFloat(rawValue);
                } catch (NumberFormatException e) {
                    // A typo'd number must never undo an earlier, valid apply() — skip it and
                    // keep whatever this key is currently holding.
                    continue;
                }

                // Float.parseFloat("NaN") succeeds without throwing, and Math.clamp(NaN, min, max)
                // returns NaN unchanged for any bounds — without this check a spec like
                // "belowscale=NaN" would sail past both parsing and clamping straight into the
                // volatile field, poisoning every belowname label drawn afterwards.
                if (Float.isNaN(value)) {
                    continue;
                }

                belowNameScale = clamp(value, MIN_BELOW_NAME_SCALE, MAX_BELOW_NAME_SCALE);
            } else if (key.equals("belowbg")) {
                // Same tolerant rule as belowscale: an unparsable value must never undo an
                // earlier, valid apply() — skip it and keep whatever this field currently holds.
                if (rawValue.equalsIgnoreCase("true")) {
                    belowNameBackground = true;
                } else if (rawValue.equalsIgnoreCase("false")) {
                    belowNameBackground = false;
                }
            }
            // Unknown key: ignored, never thrown — keeps the wire format append-only forever.
        }
    }

    /** Called on disconnect: the next server must not inherit this one's belowname tuning. */
    public static void reset() {
        belowNameScale = DEFAULT_BELOW_NAME_SCALE;
        belowNameBackground = true;
    }

    private static float clamp(float value, float min, float max) {
        return Math.clamp(value, min, max);
    }

    /** Read on the render thread by {@code PlayerEntityRendererMixin}. */
    public static float getBelowNameScale() {
        return belowNameScale;
    }

    /** Read on the render thread by {@code LabelCommandRendererCommandsMixin}. */
    public static boolean isBelowNameBackground() {
        return belowNameBackground;
    }

    /**
     * Raised by {@code PlayerEntityRendererMixin} right before it calls through to the belowname
     * {@code submitLabel} invocation. Raised unconditionally, even when scale is 1.0 and
     * background is on (the vanilla-equivalent tuning) — see that mixin's class doc for why: the
     * flag now also gates the label background, so it must be raised every single time to let
     * {@code LabelCommandRendererCommandsMixin} tell the belowname label apart from the main one.
     */
    public static void startRenderingBelowName() {
        renderingBelowName = true;
    }

    /**
     * Lowered from a {@code finally} block by {@code PlayerEntityRendererMixin}, unconditionally —
     * cheaper than guarding it and just as correct, since clearing an already-false flag is a
     * no-op.
     */
    public static void stopRenderingBelowName() {
        renderingBelowName = false;
    }

    /** Read by the {@code LabelCommandRenderer$Commands} mixin to decide whether the label it is
     * currently building is the belowname line this config's scale applies to. */
    public static boolean isRenderingBelowName() {
        return renderingBelowName;
    }
}
