package com.korosoft.keyinput;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Static state machine for the full-screen white curtain painted during a lobby -> survival
 * server transfer. Never tied to {@code ClientLevel}: a Velocity backend switch does not fire
 * {@code DISCONNECT} and leaves the world in an undefined state for a window with no world at
 * all, so this has to survive on plain static fields the same way {@link ParryFlash} does.
 *
 * <p>States: IDLE -&gt; FADING (alpha ramps 0..1 over a server-supplied duration) -&gt; HELD
 * (full white, indefinite) -&gt; back to IDLE. Lifting is always a hard cut (full white to
 * nothing in one frame) by product decision — there is no fade-out state.
 *
 * <p>The lift needs both sides to agree: the server's {@link #end()} clearance (the player really
 * arrived on the destination backend, with their state restored) AND a world and player existing
 * on this side (the new world is actually there to be shown). See {@link #liftIfCleared()}.
 *
 * <p>Sampled once per frame from {@code GameRenderer#render} (see {@code GameRendererMixin}),
 * not {@code InGameHud#render}: the latter is skipped entirely whenever there is no world,
 * which is exactly the window a backend switch opens up.
 *
 * <p>This is also the only place that knows the cinematic is running. The music cue, the input
 * lock and the camera orbit all derive from {@link #isActive()} / {@link #getCinematicProgress()}
 * per frame rather than tracking their own copy, so {@link #resetToIdle()} — which every exit
 * path already funnels through — releases all of them at once. A second flag that could fall out
 * of sync with this one would be a way to strand a player with no controls; there is deliberately
 * no such flag.
 *
 * <p>A player must never be trapped behind the curtain. Two independent failsafes lift it if
 * the server's "end" signal never arrives: once JOIN has fired for the new backend and the
 * world/player are both ready, a short settle grace lifts it; and, regardless of that, a hard
 * ceiling measured from entering HELD lifts it no matter what. Both log a warning when they
 * fire, because that only happens when something upstream (a lost payload, a Skript reload
 * wiping a listener, ...) failed to send "end" — a diagnostic signal worth keeping.
 */
public final class Cutscene {

    private static final Logger LOGGER = LoggerFactory.getLogger("korosoft-core");

    /** Below this the curtain is treated as fully transparent and is not drawn at all. */
    public static final float EPSILON = 0.001F;

    private enum State { IDLE, FADING, HELD }

    /**
     * Which cinematic variant is playing. ORBIT is the original lobby -&gt; survival move
     * ({@link CutsceneCamera}); ASCEND is survival -&gt; lobby, the player lifted into the sky while
     * the camera stays grounded ({@link AscendCamera}). Everything that branches on this reads it
     * from {@link #getKind()} per frame, same rule as {@link #isActive()} — no derived system keeps
     * its own copy.
     */
    public enum Kind { ORBIT, ASCEND }

    /**
     * Maps the wire's raw {@code kind} int to a {@link Kind}, failing toward ORBIT for anything it
     * does not recognise — including an old client's own {@code DEFAULT_KIND} and any future value
     * this jar predates. ORBIT is the known-good original; a garbled kind should never risk playing
     * a camera move nobody tested rather than just falling back to the one that has always worked.
     */
    public static Kind kindFromWire(int wire) {
        return wire == CutscenePayload.KIND_ASCEND ? Kind.ASCEND : Kind.ORBIT;
    }

    // The server owns the fade duration, but it could send garbage. Clamping keeps a bad
    // number from dividing by zero or freezing the fade — same defensive pattern as
    // ParryFlash's MIN/MAX_FLASH_MILLIS.
    private static final int MIN_FADE_MILLIS = 1;
    private static final int MAX_FADE_MILLIS = 10_000;

    // Short grace after JOIN + world + player are all ready before the settle failsafe is
    // allowed to lift the curtain: the very first frame after JOIN has neither chunks nor
    // entities loaded yet, so lifting instantly would just trade the curtain for the terrain
    // pop-in it exists to hide.
    private static final long SETTLE_GRACE_NANOS = 500_000_000L;

    // Absolute ceiling from entering HELD, independent of everything else: no matter what
    // went wrong upstream, the player is never stuck behind a white screen for longer than
    // this. Non-negotiable per the slice 1 spec.
    private static final long HARD_CEILING_NANOS = 15_000_000_000L;

    // written on the client thread only (payload handlers wrap in client.execute(), and the
    // render-thread reader is also the client thread), but kept volatile to match the same
    // defensive convention ParryFlash uses for its cross-callback state
    private static volatile State state = State.IDLE;
    private static volatile long fadeStartNanos;
    private static volatile long fadeDurationNanos;
    private static volatile long heldStartNanos;

    // set by onJoin() once the new backend's JOIN event fires while the curtain is up; reset
    // to false whenever the curtain is not active, so a join from BEFORE the transfer started
    // (or the lobby's own initial join) can never satisfy the settle failsafe
    private static volatile boolean joinedSinceStart;

    // 0L means "not ready yet"; set the first frame the settle failsafe's three conditions are
    // all true at once, so SETTLE_GRACE_NANOS measures from readiness, not from HELD itself
    private static volatile long readySinceNanos;

    // The server's half of the lift condition: the destination backend has confirmed the player
    // arrived with their state restored. Not a lift on its own — see liftIfCleared().
    private static volatile boolean serverCleared;

    // Which cinematic variant is running. Only meaningful while state != IDLE; resetToIdle()
    // snaps it back to the known-good default so a stray read between cinematics never sees a
    // stale ASCEND from the one before.
    private static volatile Kind kind = Kind.ORBIT;

    // rush() lets the server close the curtain early once it detects the player stopped rising
    // (hit a ceiling) without restarting the fade or letting the alpha jump backwards. See rush()
    // and getAlpha() for how these four combine.
    private static volatile boolean rushing;
    private static volatile long rushStartNanos;
    private static volatile long rushDurationNanos;
    private static volatile float rushStartAlpha;

    private Cutscene() {
    }

    /**
     * Starts the fade-in. Ignored if the curtain is already FADING or HELD, so a duplicate or
     * late "start" (e.g. a Skript reload re-sending it) can never yank the alpha backwards
     * mid-fade and flicker.
     */
    public static void start(int fadeMillis, Kind newKind) {
        if (state != State.IDLE) {
            return;
        }
        fadeDurationNanos = Math.clamp(fadeMillis, MIN_FADE_MILLIS, MAX_FADE_MILLIS) * 1_000_000L;
        fadeStartNanos = System.nanoTime();
        joinedSinceStart = false;
        serverCleared = false;
        kind = newKind;
        rushing = false;
        rushStartNanos = 0L;
        rushDurationNanos = 0L;
        rushStartAlpha = 0.0F;
        state = State.FADING;

        // last, so the cue can never outlive a start that was rejected above
        CutsceneSound.play(newKind == Kind.ASCEND ? CutsceneSound.INTRO_NOISE : CutsceneSound.CYMBAL);
    }

    /** Which cinematic variant is currently running (meaningless while {@link #isActive()} is false). */
    public static Kind getKind() {
        return kind;
    }

    /**
     * The server detected the player stopped rising (hit a ceiling) and wants the curtain to
     * close early to hide it. No-op unless the fade is actually in progress, and no-op if a rush
     * is already underway — same idempotency rule as {@link #start}, so a duplicate or late rush
     * can never restart the countdown or yank the alpha backwards.
     *
     * <p>Captures the CURRENT alpha before touching anything else so the accelerated fade picks up
     * exactly where the normal one left off; starting from anywhere else would be a visible jump.
     */
    public static void rush(int millis) {
        if (state != State.FADING || rushing) {
            return;
        }
        rushStartAlpha = getAlpha();
        rushStartNanos = System.nanoTime();
        rushDurationNanos = Math.clamp(millis, MIN_FADE_MILLIS, MAX_FADE_MILLIS) * 1_000_000L;
        rushing = true;
    }

    /**
     * The server's clearance to lift: the destination backend confirms the player arrived with
     * their state restored. Deliberately <em>not</em> a lift on its own — the curtain drops only
     * once {@link #liftIfCleared()} also finds a world and a player on this side.
     *
     * <p>Neither side can make this call alone. Only the server knows the player really landed on
     * the destination backend; only the client knows the new world actually exists yet, which is
     * why a fixed server-side delay is not a substitute — it would not adapt to a slow machine and
     * could lift onto a half-built world. Requiring both is strictly better than either.
     */
    public static void end() {
        if (state == State.IDLE) {
            // nothing is up: do not latch a clearance that would lift the NEXT cinematic instantly
            return;
        }
        serverCleared = true;
        liftIfCleared();
    }

    /** Called on disconnect: the next server must not inherit a curtain still up. */
    public static void reset() {
        resetToIdle();
    }

    /**
     * Advances the state machine. Must be called exactly once per rendered frame, from
     * {@code GameRenderer#render} so it keeps running through the no-world window too.
     */
    public static void tick() {
        State current = state;
        if (current == State.IDLE) {
            return;
        }

        // catch-up for the uncommon case: END landed before the new world existed, so the lift
        // could not happen in the payload handler. The common case never reaches here — it already
        // lifted between frames, which is what keeps the cut atomic.
        if (liftIfCleared()) {
            return;
        }

        long now = System.nanoTime();

        if (current == State.FADING) {
            if (rushing) {
                if (rushProgress(now) >= 1.0F) {
                    enterHeld(now);
                }
                return;
            }
            if (now - fadeStartNanos >= fadeDurationNanos) {
                enterHeld(now);
            }
            return;
        }

        runFailsafes(now);
    }

    /**
     * Drops the curtain iff both halves of the lift condition hold: the server cleared it, and
     * there is a world and a player to actually show. Always a hard cut — full white to nothing in
     * one frame, no fade-out, by product decision.
     *
     * @return true if it lifted
     */
    private static boolean liftIfCleared() {
        if (!serverCleared) {
            return false;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) {
            return false;
        }

        resetToIdle();
        return true;
    }

    /** Notifies the state machine that JOIN fired. No-op while the curtain is not up. */
    public static void onJoin() {
        if (state != State.IDLE) {
            joinedSinceStart = true;
        }
    }

    /**
     * Whether the cinematic is running at all. The single source of truth every derived effect
     * (input lock, camera orbit) queries per frame instead of tracking its own flag: a leaked
     * input lock strands the player until they relog, and state that is only ever derived cannot
     * leak — {@link #resetToIdle()} releases everything at once, through every exit path.
     */
    public static boolean isActive() {
        return state != State.IDLE;
    }

    /**
     * How far the cinematic has run, 0..1. Everything the cinematic drives — the fade, the music
     * cue, the camera orbit — hangs off this one wall clock, so they cannot drift apart: the fade
     * duration the server sends IS the length of the music, and the transfer fires when it ends.
     */
    public static float getCinematicProgress() {
        State current = state;
        if (current == State.IDLE) {
            return 0.0F;
        }
        if (current == State.HELD) {
            return 1.0F;
        }

        long duration = fadeDurationNanos;
        if (duration <= 0L) {
            return 1.0F;
        }
        long elapsed = System.nanoTime() - fadeStartNanos;
        return Math.clamp((float) elapsed / (float) duration, 0.0F, 1.0F);
    }

    /** Current overlay opacity, 0 when the curtain is not showing. Safe to call every frame. */
    public static float getAlpha() {
        if (rushing) {
            // Ignores the per-kind curve entirely: once the server calls for a rush, the shape of
            // the normal fade no longer matters, only getting to full white smoothly from wherever
            // the alpha already was. Starting anywhere else would jump.
            float t = rushProgress(System.nanoTime());
            return MathHelper.lerp(HudAnimator.easeInOutSine(t), rushStartAlpha, 1.0F);
        }

        float progress = getCinematicProgress();
        if (kind == Kind.ASCEND) {
            // Stays fully clear while the beam visibly lifts the player — whitening from frame one
            // would wash out the one thing this variant exists to show — then takes the screen in
            // one short move and HOLDS full white for the rest of the clock.
            //
            // The hold is the point, and it is why full white and the end of the clock are two
            // different instants here rather than one. Reaching full white is what sends cutready,
            // which is what makes the server transfer, so a curve that only touches 1.0 at the very
            // last frame gives no white at all before the world is torn down — the cut lands on the
            // same frame the screen finishes whitening. Arriving early and sitting there is what
            // makes it read as "everything went white, and then you were somewhere else".
            float whiteStart = CutsceneConfig.getAscendWhiteStart();
            float whiteFull = CutsceneConfig.getAscendWhiteFull();
            if (progress <= whiteStart) {
                return 0.0F;
            }
            float t = (progress - whiteStart) / (whiteFull - whiteStart);
            return HudAnimator.easeInOutSine(Math.clamp(t, 0.0F, 1.0F));
        }

        // Eased in, not a straight ramp: over the ~5s the music runs, a linear ramp is already
        // reading as solid white around two thirds through and then has nothing left to do.
        // Accelerating into the end keeps the orbit legible while the screen visibly whitens the
        // whole way, and lands on full white exactly when the cue does.
        return HudAnimator.easeInQuad(progress);
    }

    /** 0..1 progress through the rush window. Only meaningful while {@link #rushing} is true. */
    private static float rushProgress(long now) {
        long duration = rushDurationNanos;
        if (duration <= 0L) {
            return 1.0F;
        }
        long elapsed = now - rushStartNanos;
        return Math.clamp((float) elapsed / (float) duration, 0.0F, 1.0F);
    }

    private static void enterHeld(long now) {
        state = State.HELD;
        heldStartNanos = now;
        readySinceNanos = 0L;

        // tell the server we are fully white and it may transfer us now
        if (ClientPlayNetworking.canSend(CutreadyPayload.ID)) {
            ClientPlayNetworking.send(CutreadyPayload.INSTANCE);
        }
    }

    private static void runFailsafes(long now) {
        if (now - heldStartNanos >= HARD_CEILING_NANOS) {
            LOGGER.warn("[Cutscene] failsafe fired: hard ceiling ({} ms) reached in HELD with no "
                    + "'end' signal from the server; lifting the curtain on our own", HARD_CEILING_NANOS / 1_000_000L);
            resetToIdle();
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        boolean transferSettled = joinedSinceStart && client.world != null && client.player != null;
        if (!transferSettled) {
            readySinceNanos = 0L;
            return;
        }

        if (readySinceNanos == 0L) {
            readySinceNanos = now;
            return;
        }

        if (now - readySinceNanos >= SETTLE_GRACE_NANOS) {
            LOGGER.warn("[Cutscene] failsafe fired: JOIN + world/player settled with no 'end' "
                    + "signal from the server; lifting the curtain on our own");
            resetToIdle();
        }
    }

    /**
     * The single choke point every exit path funnels through — happy path, both failsafes, and
     * disconnect alike. Clears every derived system in one place (sound, camera anchor, rush) so
     * none of them can carry state into the next cinematic, which could otherwise strand the
     * player (a leaked input lock) or pop the next cinematic's first frame (a stale camera anchor).
     */
    private static void resetToIdle() {
        state = State.IDLE;

        // In the happy path the ogg ended on its own a frame ago and this does nothing. It only
        // bites when the cinematic is torn down early (a failsafe, a disconnect), where the cue
        // would otherwise keep playing over a lobby nobody is leaving any more.
        CutsceneSound.stop();
        AscendCamera.reset();
        fadeStartNanos = 0L;
        fadeDurationNanos = 0L;
        heldStartNanos = 0L;
        readySinceNanos = 0L;
        joinedSinceStart = false;
        serverCleared = false;
        kind = Kind.ORBIT;
        rushing = false;
        rushStartNanos = 0L;
        rushDurationNanos = 0L;
        rushStartAlpha = 0.0F;
    }
}
