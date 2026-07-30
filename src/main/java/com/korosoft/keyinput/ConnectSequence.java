package com.korosoft.keyinput;

import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.resource.ResourceReload;

/**
 * State machine for the custom "connecting" flow, driven from three places: {@link MainMenuScreen}
 * (starts it on click), {@code ServerResourcePackLoaderMixin} (flags the pack download), and
 * {@code MinecraftClientMixin} (salvages the resource reload that would otherwise show the vanilla
 * red splash). {@link ConnectOverlay} reads it every frame to decide what to paint.
 *
 * <p>State is static because there is exactly one connect attempt in flight at a time — like
 * {@link BootSequence}, but for the post-menu connect instead of the process boot. Fields are
 * volatile because {@code ConnectScreen.connect} spawns a background network thread that can reach
 * {@link #markDownloading()} while the client thread is rendering {@link ConnectOverlay}.
 */
public final class ConnectSequence {

    private ConnectSequence() {
    }

    /** Stages of the custom connect flow, in the order they are expected to occur. */
    public enum Phase {
        IDLE,
        CONNECTING,
        DOWNLOADING,
        RELOADING,
        ENTERING,
        DONE
    }

    private static volatile Phase phase = Phase.IDLE;
    private static volatile float progress = 0.0F;
    private static volatile ResourceReload reload;
    private static volatile Consumer<Optional<Throwable>> exceptionHandler;

    /** Called by {@code MainMenuScreen.startGame()} just before {@code ConnectScreen.connect}, so the
     * background connector thread never reaches {@link #markDownloading()} while this is still IDLE. */
    public static void start() {
        reload = null;
        exceptionHandler = null;
        progress = 0.0F;
        phase = Phase.CONNECTING;
    }

    /**
     * Called by {@code ServerResourcePackLoaderMixin} the instant the server's pack download is
     * queued. Guarded so a stray/late call cannot regress a phase that already moved further along
     * (e.g. back from RELOADING to DOWNLOADING).
     */
    public static void markDownloading() {
        if (phase == Phase.CONNECTING) {
            phase = Phase.DOWNLOADING;
        }
    }

    /**
     * Called by {@code MinecraftClientMixin} when it intercepts the {@code SplashOverlay} that
     * vanilla would have shown for the post-connect resource reload. Hands the reload and its
     * completion callback to {@link ConnectOverlay} so it can drive them to completion itself.
     */
    public static void onReloadStart(ResourceReload newReload, Consumer<Optional<Throwable>> newExceptionHandler) {
        reload = newReload;
        exceptionHandler = newExceptionHandler;
        phase = Phase.RELOADING;
    }

    /** True while a custom connect flow owns the overlay — from {@link #start()} until {@link #reset()}. */
    public static boolean isActive() {
        Phase current = phase;
        return current != Phase.IDLE && current != Phase.DONE;
    }

    public static Phase phase() {
        return phase;
    }

    /** Called by {@link ConnectOverlay} once the intercepted reload completes successfully, moving the
     * flow to its final visible phase until {@code client.world} takes over. */
    public static void markEntering() {
        phase = Phase.ENTERING;
    }

    public static ResourceReload reload() {
        return reload;
    }

    public static Consumer<Optional<Throwable>> exceptionHandler() {
        return exceptionHandler;
    }

    public static float progress() {
        return progress;
    }

    public static void setProgress(float newProgress) {
        progress = newProgress;
    }

    /** Called by {@link ConnectOverlay} once the flow finishes (success hand-off or failure passthrough). */
    public static void reset() {
        phase = Phase.IDLE;
        reload = null;
        exceptionHandler = null;
        progress = 0.0F;
    }
}
