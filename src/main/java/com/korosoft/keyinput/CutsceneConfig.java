package com.korosoft.keyinput;

import java.util.Locale;

/**
 * Holds every ORBIT/ASCEND cinematic tunable pushed by the server on "keyinput:cutconfig" (see
 * {@link CutsceneConfigPayload}). Mirrors {@link HudConfig}'s lifecycle: apply-on-receipt from the
 * netty thread, {@link #reset()} on disconnect so the next server never inherits this one's
 * tuning, and every default equal to what the mod has always hardcoded, so a server that sends
 * nothing looks byte-identical to before this config existed.
 *
 * <p>{@link #apply(String)} parses a "key=value;key=value;..." spec. Unknown keys are silently
 * ignored and a value that fails to parse keeps whatever this key is CURRENTLY holding (not the
 * hardcoded default) — both rules exist for the same reason: they make the wire format
 * append-only forever. An old jar talking to a new server (extra unknown keys) and a new jar
 * talking to an old server (missing keys, defaults stand) both have to degrade gracefully instead
 * of breaking, and a single typo'd key in an otherwise-valid spec must never undo a value a
 * previous, valid apply() already set.
 */
public final class CutsceneConfig {

    // ---- orbit (CutsceneCamera) ----

    // Far enough to frame the player against their surroundings, close enough that a lobby's
    // walls rarely get between camera and player.
    private static final float DEFAULT_ORBIT_DISTANCE = 5.5F;

    // A revolution and a half: enough that the motion is unmistakably an orbit rather than a
    // drift, slow enough over ~5s that it never reads as a spin.
    private static final float DEFAULT_ORBIT_DEGREES = 540.0F;

    // Looking slightly down at the player. Pitch is what lifts the camera: moveBy() walks
    // backwards along the view axis, so a downward pitch pulls out and up in one move.
    private static final float DEFAULT_ORBIT_PITCH = 22.0F;

    // The pull-out finishes here and the last stretch orbits at a settled distance while the
    // fade takes over. Pulling out for the whole clip would still be receding at the cut.
    private static final float DEFAULT_ORBIT_PULLOUT = 0.6F;

    // How far the camera has to clear the player before the model is drawn. The eased pull-out
    // starts slow, so the camera spends its first second or so still inside the head: drawing
    // the model there would fill the screen with the inner face of the skin's hat layer (which
    // renders no-cull, unlike the base layer) — a mess that varies per skin. Comfortably outside
    // the model's ~0.35 half-extent, so nothing pops through.
    private static final float DEFAULT_ORBIT_VISIBLE = 0.8F;

    // ---- ascend (AscendCamera) ----

    // The pull-back finishes here; the rest of the clip holds at ascend.back while the fade
    // takes over. Short on purpose — this shot is about watching the player leave straight up,
    // not about touring the scenery.
    private static final float DEFAULT_ASCEND_PULLOUT = 0.18F;

    private static final float DEFAULT_ASCEND_BACK = 5.0F;

    // Not a look angle — moveBy() walks backwards along the view axis, so this pitch exists only
    // to give the pull-back an upward component as well as a backward one. It is overwritten by
    // the look-at aim once the camera is in position (see CameraMixin).
    private static final float DEFAULT_ASCEND_SETUPPITCH = 6.0F;

    // Where the camera actually points once settled, in degrees — negative is up. Chosen
    // directly rather than derived from aiming at some point on the player, because this angle
    // IS the framing decision and nothing else should get a vote in it.
    //
    // Slightly up, not level and definitely not down. The camera ends around head height, so any
    // aim at the player's body tips it into the ground: the earlier version aimed near the feet
    // and sat ~17 degrees down, which put the player at the top of the frame and threw away the
    // shot — they cleared the edge almost at once and the rest of it watched grass. Tilting a
    // little above the horizon puts them low in frame with sky above, which is the room they need
    // to rise through. This is the knob to turn if the framing is still wrong.
    private static final float DEFAULT_ASCEND_LOOKPITCH = -5.0F;

    // Same rationale as orbit.visible: below this the camera is still inside the player's head
    // and drawing the model would fill the screen with the inner face of the skin's no-cull hat
    // layer.
    private static final float DEFAULT_ASCEND_VISIBLE = 0.8F;

    // A full 180-degree spin to face the (stationary) camera — see
    // AscendCamera#getSelfTurnDegrees for why this turns the model rather than the camera.
    private static final float DEFAULT_ASCEND_TURN = 180.0F;

    // ---- fade (Cutscene#getAlpha) ----

    // ASCEND keeps the screen clear while the player is still visibly rising and only starts
    // whitening for the last stretch — whitening the whole way would wash out the one thing this
    // variant exists to show. Chosen so there is still a comfortable margin of fade left to reach
    // full white by the time the fade duration (== the music length) runs out.
    private static final float DEFAULT_ASCEND_WHITESTART = 0.72F;

    // Progress at which the ASCEND fade is ALREADY fully white, leaving the rest of the clock as
    // a hold. Everything between this and 1.0 is dead white on purpose — see Cutscene#getAlpha().
    private static final float DEFAULT_ASCEND_WHITEFULL = 0.88F;

    // ---- beam (AscendBeam) ----

    private static final float DEFAULT_BEAM_RADIUS = 1.6F;
    private static final float DEFAULT_BEAM_HEIGHT = 30.0F;

    // Geometry, not just a tunable number: AscendBeam divides 2*PI by this to get the angular
    // step between wall quads, which is why it is floored well above zero (see MIN_SIDES below)
    // rather than only above it.
    private static final float DEFAULT_BEAM_SIDES = 24.0F;

    // Soft glow, not a solid wall — tuned low enough that the no-cull overlap at grazing angles
    // (the far wall showing through the near one) reads as volume rather than as a visible seam.
    private static final float DEFAULT_BEAM_ALPHA = 0.22F;
    private static final float DEFAULT_BEAM_POOL_ALPHA = 0.45F;

    // Near-white with a faint lavender cast — chosen to blend into the white curtain fade
    // (ascend.whitestart) that takes over for the last stretch of this same cinematic, rather
    // than reading as a distinct color the curtain then has to overwrite.
    private static final float DEFAULT_BEAM_RED = 0.85F;
    private static final float DEFAULT_BEAM_GREEN = 0.82F;
    private static final float DEFAULT_BEAM_BLUE = 1.0F;

    private static final float DEFAULT_BEAM_POOL_RADIUS = 2.4F;

    // The column falls out of the sky instead of just fading in: below this fraction only the
    // shrinking segment from the descending bottom edge up to the top is drawn.
    private static final float DEFAULT_BEAM_FALL = 0.18F;

    // ---- clamp bounds ----
    //
    // A server could send garbage (a Skript typo, a bad substitution). Every field below is
    // clamped on apply() the same way Cutscene's own MIN/MAX_FADE_MILLIS and HudConfig's
    // MIN/MAX_SLIDE_MILLIS already guard their inputs, so a bad number can only ever push a
    // cinematic to a weird-looking edge, never crash or hang the client.

    // Shared by every plain "how far" tunable: orbit/ascend distance and visibility thresholds.
    private static final float MIN_DISTANCE = 0.0F;
    private static final float MAX_DISTANCE = 64.0F;

    // Shared by every camera pitch: outside +-90 degrees a pitch starts looking backwards through
    // itself, which is never a sane cinematic angle.
    private static final float MIN_PITCH = -90.0F;
    private static final float MAX_PITCH = 90.0F;

    // Shared by the two yaw-offset totals (orbit.degrees, ascend.turn). These are multipliers on
    // an easing curve, not divisors, so the only risk is an absurd spin rather than a crash — the
    // bound just keeps a fat-fingered value from spinning the camera or model for 50 turns.
    private static final float MIN_TURN_DEGREES = -7200.0F;
    private static final float MAX_TURN_DEGREES = 7200.0F;

    // Shared by every "progress / this" divisor: orbit.pullout, ascend.pullout, beam.fall. Zero
    // (or anything a float rounds down to) would divide by zero and NaN the whole curve, so the
    // floor sits well clear of it; 1.0 is the natural ceiling since progress itself never exceeds 1.
    private static final float MIN_FRACTION = 0.01F;
    private static final float MAX_FRACTION = 1.0F;

    // ascend.whitestart / ascend.whitefull individually, before the pairwise invariant below is
    // enforced.
    private static final float MIN_WHITE = 0.0F;
    private static final float MAX_WHITE = 1.0F;

    private static final float MIN_BEAM_DIMENSION = 0.0F;
    private static final float MAX_BEAM_RADIUS = 128.0F;
    private static final float MAX_BEAM_HEIGHT = 512.0F;

    // Floored at 3 (the minimum for a closed polygon), capped so a typo cannot ask for a
    // many-thousand-quad cylinder every frame.
    private static final float MIN_BEAM_SIDES = 3.0F;
    private static final float MAX_BEAM_SIDES = 128.0F;

    private static final float MIN_COLOR = 0.0F;
    private static final float MAX_COLOR = 1.0F;
    private static final float MIN_ALPHA = 0.0F;
    private static final float MAX_ALPHA = 1.0F;

    // written on the netty thread, read on the render thread — same convention as HudConfig
    private static volatile float orbitDistance = DEFAULT_ORBIT_DISTANCE;
    private static volatile float orbitDegrees = DEFAULT_ORBIT_DEGREES;
    private static volatile float orbitPitch = DEFAULT_ORBIT_PITCH;
    private static volatile float orbitPullout = DEFAULT_ORBIT_PULLOUT;
    private static volatile float orbitVisible = DEFAULT_ORBIT_VISIBLE;

    private static volatile float ascendPullout = DEFAULT_ASCEND_PULLOUT;
    private static volatile float ascendBack = DEFAULT_ASCEND_BACK;
    private static volatile float ascendSetupPitch = DEFAULT_ASCEND_SETUPPITCH;
    private static volatile float ascendLookPitch = DEFAULT_ASCEND_LOOKPITCH;
    private static volatile float ascendVisible = DEFAULT_ASCEND_VISIBLE;
    private static volatile float ascendTurn = DEFAULT_ASCEND_TURN;

    private static volatile float ascendWhiteStart = DEFAULT_ASCEND_WHITESTART;
    private static volatile float ascendWhiteFull = DEFAULT_ASCEND_WHITEFULL;

    private static volatile float beamRadius = DEFAULT_BEAM_RADIUS;
    private static volatile float beamHeight = DEFAULT_BEAM_HEIGHT;
    private static volatile float beamSides = DEFAULT_BEAM_SIDES;
    private static volatile float beamAlpha = DEFAULT_BEAM_ALPHA;
    private static volatile float beamRed = DEFAULT_BEAM_RED;
    private static volatile float beamGreen = DEFAULT_BEAM_GREEN;
    private static volatile float beamBlue = DEFAULT_BEAM_BLUE;
    private static volatile float beamPoolRadius = DEFAULT_BEAM_POOL_RADIUS;
    private static volatile float beamPoolAlpha = DEFAULT_BEAM_POOL_ALPHA;
    private static volatile float beamFall = DEFAULT_BEAM_FALL;

    private CutsceneConfig() {
    }

    /**
     * Parses "key=value;key=value;..." and applies every recognised key. See the class doc for
     * why unknown keys are ignored and unparsable values keep their current value instead of
     * reverting to the default.
     */
    public static void apply(String spec) {
        if (spec == null || spec.isEmpty()) {
            return;
        }

        // ascend.whitestart/whitefull are validated as a PAIR, not independently: Cutscene#getAlpha
        // divides by (whitefull - whitestart), so committing one half without checking the other
        // could leave them equal or inverted and NaN the ASCEND fade. Candidates start at the
        // CURRENT value so a spec that only touches one half of the pair is still checked against
        // a coherent baseline, and the "seen" flags track whether this spec actually mentioned
        // each key so an unrelated apply() call never re-commits values nobody touched.
        float whiteStartCandidate = ascendWhiteStart;
        float whiteFullCandidate = ascendWhiteFull;
        boolean whiteStartSeen = false;
        boolean whiteFullSeen = false;

        for (String rawPair : spec.split(";")) {
            int eq = rawPair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = rawPair.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String rawValue = rawPair.substring(eq + 1).trim();

            float value;
            try {
                value = Float.parseFloat(rawValue);
            } catch (NumberFormatException e) {
                // A typo'd number must never undo an earlier, valid apply() — skip this key and
                // keep going rather than falling back to the default or aborting the whole spec.
                continue;
            }

            // Float.parseFloat("NaN") succeeds — it does not throw — and Math.clamp(NaN, min, max)
            // returns NaN unchanged for any bounds, so without this check a spec like
            // "orbit.pullout=NaN" would sail past both parsing and clamping straight into a
            // volatile field, poisoning downstream per-frame division for the rest of the session.
            // Treat NaN exactly like an unparseable value: skip this key and keep whatever it is
            // currently holding. (ascend.whitestart/whitefull are safe either way — skipping a NaN
            // half here is equivalent to that key never being sent, which the pairwise invariant
            // check below already handles.)
            if (Float.isNaN(value)) {
                continue;
            }

            switch (key) {
                case "orbit.distance" -> orbitDistance = clamp(value, MIN_DISTANCE, MAX_DISTANCE);
                case "orbit.degrees" -> orbitDegrees = clamp(value, MIN_TURN_DEGREES, MAX_TURN_DEGREES);
                case "orbit.pitch" -> orbitPitch = clamp(value, MIN_PITCH, MAX_PITCH);
                case "orbit.pullout" -> orbitPullout = clamp(value, MIN_FRACTION, MAX_FRACTION);
                case "orbit.visible" -> orbitVisible = clamp(value, MIN_DISTANCE, MAX_DISTANCE);

                case "ascend.pullout" -> ascendPullout = clamp(value, MIN_FRACTION, MAX_FRACTION);
                case "ascend.back" -> ascendBack = clamp(value, MIN_DISTANCE, MAX_DISTANCE);
                case "ascend.setuppitch" -> ascendSetupPitch = clamp(value, MIN_PITCH, MAX_PITCH);
                case "ascend.lookpitch" -> ascendLookPitch = clamp(value, MIN_PITCH, MAX_PITCH);
                case "ascend.visible" -> ascendVisible = clamp(value, MIN_DISTANCE, MAX_DISTANCE);
                case "ascend.turn" -> ascendTurn = clamp(value, MIN_TURN_DEGREES, MAX_TURN_DEGREES);

                case "ascend.whitestart" -> {
                    whiteStartCandidate = clamp(value, MIN_WHITE, MAX_WHITE);
                    whiteStartSeen = true;
                }
                case "ascend.whitefull" -> {
                    whiteFullCandidate = clamp(value, MIN_WHITE, MAX_WHITE);
                    whiteFullSeen = true;
                }

                case "beam.radius" -> beamRadius = clamp(value, MIN_BEAM_DIMENSION, MAX_BEAM_RADIUS);
                case "beam.height" -> beamHeight = clamp(value, MIN_BEAM_DIMENSION, MAX_BEAM_HEIGHT);
                case "beam.sides" -> beamSides = clamp(value, MIN_BEAM_SIDES, MAX_BEAM_SIDES);
                case "beam.alpha" -> beamAlpha = clamp(value, MIN_ALPHA, MAX_ALPHA);
                case "beam.red" -> beamRed = clamp(value, MIN_COLOR, MAX_COLOR);
                case "beam.green" -> beamGreen = clamp(value, MIN_COLOR, MAX_COLOR);
                case "beam.blue" -> beamBlue = clamp(value, MIN_COLOR, MAX_COLOR);
                case "beam.pool.radius" -> beamPoolRadius = clamp(value, MIN_BEAM_DIMENSION, MAX_BEAM_RADIUS);
                case "beam.pool.alpha" -> beamPoolAlpha = clamp(value, MIN_ALPHA, MAX_ALPHA);
                case "beam.fall" -> beamFall = clamp(value, MIN_FRACTION, MAX_FRACTION);

                default -> {
                    // Unknown key: ignored, never thrown. This is what keeps the wire format
                    // append-only forever — an old jar has to be able to receive a spec with keys
                    // it has never heard of and just skip them.
                }
            }
        }

        // Commit the pair only if it stays strictly ordered after clamping; see the loop-setup
        // comment above for why a rejected pair keeps BOTH halves at their pre-apply value rather
        // than committing just the one that looked fine in isolation.
        if ((whiteStartSeen || whiteFullSeen) && whiteStartCandidate < whiteFullCandidate) {
            ascendWhiteStart = whiteStartCandidate;
            ascendWhiteFull = whiteFullCandidate;
        }
    }

    /** Called on disconnect: the next server must not inherit this one's cinematic tuning. */
    public static void reset() {
        orbitDistance = DEFAULT_ORBIT_DISTANCE;
        orbitDegrees = DEFAULT_ORBIT_DEGREES;
        orbitPitch = DEFAULT_ORBIT_PITCH;
        orbitPullout = DEFAULT_ORBIT_PULLOUT;
        orbitVisible = DEFAULT_ORBIT_VISIBLE;

        ascendPullout = DEFAULT_ASCEND_PULLOUT;
        ascendBack = DEFAULT_ASCEND_BACK;
        ascendSetupPitch = DEFAULT_ASCEND_SETUPPITCH;
        ascendLookPitch = DEFAULT_ASCEND_LOOKPITCH;
        ascendVisible = DEFAULT_ASCEND_VISIBLE;
        ascendTurn = DEFAULT_ASCEND_TURN;

        ascendWhiteStart = DEFAULT_ASCEND_WHITESTART;
        ascendWhiteFull = DEFAULT_ASCEND_WHITEFULL;

        beamRadius = DEFAULT_BEAM_RADIUS;
        beamHeight = DEFAULT_BEAM_HEIGHT;
        beamSides = DEFAULT_BEAM_SIDES;
        beamAlpha = DEFAULT_BEAM_ALPHA;
        beamRed = DEFAULT_BEAM_RED;
        beamGreen = DEFAULT_BEAM_GREEN;
        beamBlue = DEFAULT_BEAM_BLUE;
        beamPoolRadius = DEFAULT_BEAM_POOL_RADIUS;
        beamPoolAlpha = DEFAULT_BEAM_POOL_ALPHA;
        beamFall = DEFAULT_BEAM_FALL;
    }

    private static float clamp(float value, float min, float max) {
        return Math.clamp(value, min, max);
    }

    public static float getOrbitDistance() {
        return orbitDistance;
    }

    public static float getOrbitDegrees() {
        return orbitDegrees;
    }

    public static float getOrbitPitch() {
        return orbitPitch;
    }

    public static float getOrbitPullout() {
        return orbitPullout;
    }

    public static float getOrbitVisible() {
        return orbitVisible;
    }

    public static float getAscendPullout() {
        return ascendPullout;
    }

    public static float getAscendBack() {
        return ascendBack;
    }

    public static float getAscendSetupPitch() {
        return ascendSetupPitch;
    }

    public static float getAscendLookPitch() {
        return ascendLookPitch;
    }

    public static float getAscendVisible() {
        return ascendVisible;
    }

    public static float getAscendTurn() {
        return ascendTurn;
    }

    public static float getAscendWhiteStart() {
        return ascendWhiteStart;
    }

    public static float getAscendWhiteFull() {
        return ascendWhiteFull;
    }

    public static float getBeamRadius() {
        return beamRadius;
    }

    public static float getBeamHeight() {
        return beamHeight;
    }

    /** Rounded to int at the call site (AscendBeam) — kept as float here so every tunable in this
     * class shares one type and one parsing path. */
    public static float getBeamSides() {
        return beamSides;
    }

    public static float getBeamAlpha() {
        return beamAlpha;
    }

    public static float getBeamRed() {
        return beamRed;
    }

    public static float getBeamGreen() {
        return beamGreen;
    }

    public static float getBeamBlue() {
        return beamBlue;
    }

    public static float getBeamPoolRadius() {
        return beamPoolRadius;
    }

    public static float getBeamPoolAlpha() {
        return beamPoolAlpha;
    }

    public static float getBeamFall() {
        return beamFall;
    }
}
