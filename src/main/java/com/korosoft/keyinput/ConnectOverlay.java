package com.korosoft.keyinput;

import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Overlay;
import net.minecraft.resource.ResourceReload;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

/**
 * The overlay shown from the moment the player clicks "Haz clic para comenzar" until the world is
 * ready to take over. It sits on top of whatever vanilla screens the connect sequence installs
 * underneath it (login handshake, {@code DownloadingTerrainScreen}, and — critically — the
 * {@code SplashOverlay} that vanilla would otherwise show for the post-connect resource reload), so
 * the player only ever sees KoroSoft branding, never the red Mojang splash.
 *
 * <p>Mirrors {@link KoroBootOverlay}'s trick of driving a salvaged {@link ResourceReload} to
 * completion itself: {@code MinecraftClientMixin} intercepts the reload's {@code SplashOverlay} and
 * hands it to {@link ConnectSequence} instead of installing it, and this overlay finishes it here
 * once {@link ConnectSequence#reload()} reports complete.
 *
 * <p><b>Failure handling is vanilla passthrough, on purpose:</b> if the connection fails, times out,
 * or the player gets kicked, vanilla puts {@link DisconnectedScreen} up as
 * {@code MinecraftClient.currentScreen}. This overlay checks for that at the very top of every
 * frame and steps aside the instant it appears — no retry, no custom error UI, no cancel button.
 * {@code DisconnectedScreen} already gets the KoroSoft splash background via
 * {@code MenuBackgroundMixin}, so the handoff looks intentional rather than like a crash.
 */
public class ConnectOverlay extends Overlay {

    private static final int BACKGROUND_DIM = 130;
    private static final int TITLE_GOLD = 0xE8C170;
    private static final int HINT_WHITE = 0xC8C8C8;
    private static final int BAR_TRACK = 0x33405E;
    private static final int BAR_FILL = 0xE8C170;

    /** Minimum time the ENTERING phase text stays up before handing off — purely to avoid a
     * single-frame flash if {@code client.world} happens to already be non-null the instant we get
     * here. This is a FLOOR, not the dismissal trigger: {@code client.world} only becomes non-null in
     * the PLAY phase (GameJoinS2CPacket), which is seconds after the resource reload completes (the
     * configuration phase) — the KoroAuth PLAY-phase handshake (8s deadline) runs in between. Tearing
     * down on this timer alone would expose vanilla's ConnectScreen for that whole gap, so
     * {@link #maybeFinishEntering} requires {@code client.world != null} regardless of how long this
     * floor takes to elapse. If the connection instead hangs or gets kicked, the DisconnectedScreen
     * passthrough at the top of {@link #render} is the escape hatch — there is no infinite-freeze
     * risk from waiting here. */
    private static final long MIN_ENTERING_MS = 400L;

    private static final long SPINNER_PERIOD_MS = 1600L;
    private static final int SPINNER_DOTS = 8;
    private static final long INDETERMINATE_PERIOD_MS = 1400L;

    private final MinecraftClient client;

    /** Guards the reload completion (throwException + exceptionHandler) so it runs exactly once. */
    private boolean reloadFinished = false;
    private long enteringSinceMs = -1L;
    private float smoothedProgress = 0.0F;

    public ConnectOverlay(MinecraftClient client) {
        this.client = client;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float deltaTicks) {
        // Escape hatch, checked first every frame: on any connect failure/timeout/kick, vanilla has
        // already put DisconnectedScreen up underneath us. Step aside immediately instead of risking
        // a stuck overlay — there is no world/reload event coming after a disconnect.
        if (this.client.currentScreen instanceof DisconnectedScreen) {
            ConnectSequence.reset();
            this.client.setOverlay(null);
            return;
        }

        int w = ctx.getScaledWindowWidth();
        int h = ctx.getScaledWindowHeight();
        MenuBackground.drawCover(ctx, w, h);
        MenuBackground.dim(ctx, w, h, BACKGROUND_DIM);

        ConnectSequence.Phase phase = ConnectSequence.phase();
        long now = Util.getMeasuringTimeMs();

        if (phase == ConnectSequence.Phase.RELOADING) {
            if (advanceReload(now)) {
                // A genuine pack-corruption error occurred: ConnectSequence was reset and vanilla's
                // synchronous recovery reload already installed its own SplashOverlay in our place
                // (see advanceReload's Javadoc). Stop drawing immediately — this overlay has already
                // been superseded for this frame.
                return;
            }
            // advanceReload may have flipped ConnectSequence into ENTERING; re-read for this frame's
            // bar/text so they do not lag a frame behind the phase they just entered.
            phase = ConnectSequence.phase();
        }

        if (phase == ConnectSequence.Phase.ENTERING && maybeFinishEntering(now)) {
            return;
        }

        drawSpinner(ctx, w, h, now);
        drawProgressBar(ctx, w, h, phase, now);
        drawPhaseText(ctx, w, h, phase);
    }

    /**
     * Drives the salvaged {@link ResourceReload} the same way {@link KoroBootOverlay} drives the
     * boot one: once it reports complete, run its exception and (on success) its completion callback
     * exactly as vanilla would have, which wires up the world/menu screen underneath us.
     *
     * <p>On failure, {@code exceptionHandler.accept(Optional.of(t))} is vanilla's own
     * {@code onResourceReloadFailure} handler; verified via javap against the yarn-mapped client jar
     * that for a {@code force=false} reload (which this always is — only the boot reload forces) it
     * SYNCHRONOUSLY calls {@code reloadResources(true, ctx)} to recover onto vanilla-only packs. That
     * recovery reload hits the exact same {@code setOverlay(new SplashOverlay(...))} call site our
     * mixin redirects. {@link ConnectSequence#reset()} is called BEFORE {@code accept(...)} so that
     * when the recovery reload's {@code setOverlay} fires mid-{@code accept()},
     * {@code ConnectSequence.isActive()} is already false and {@code MinecraftClientMixin} lets it
     * through untouched — the player sees vanilla's red splash and failure toast for the recovery,
     * which is correct here: the mandatory pack is genuinely broken.
     *
     * @return true if a reload failure was handled (caller must stop rendering this frame — vanilla
     *         has taken over the overlay slot), false otherwise.
     */
    private boolean advanceReload(long now) {
        ResourceReload reload = ConnectSequence.reload();
        if (reload == null) {
            return false;
        }
        ConnectSequence.setProgress(reload.getProgress());
        if (this.reloadFinished || !reload.isComplete()) {
            return false;
        }
        Consumer<Optional<Throwable>> handler = ConnectSequence.exceptionHandler();
        if (handler == null) {
            // Defensive: RELOADING should never be entered without onReloadStart() also having set
            // this, but guard against a future code path that reaches this state some other way.
            return false;
        }
        this.reloadFinished = true;
        try {
            reload.throwException();
            handler.accept(Optional.empty());
        } catch (Throwable throwable) {
            ConnectSequence.reset();
            handler.accept(Optional.of(throwable));
            return true;
        }
        ConnectSequence.markEntering();
        this.enteringSinceMs = now;
        return false;
    }

    /** Returns true once this call dismissed the overlay (caller must stop drawing and return). */
    private boolean maybeFinishEntering(long now) {
        if (this.enteringSinceMs < 0L) {
            this.enteringSinceMs = now;
        }
        // world != null is the real signal (PLAY phase reached, GameJoinS2CPacket handled); the
        // minimum hold is only a floor against a single-frame flash, never a substitute for it — see
        // MIN_ENTERING_MS's Javadoc for why waiting here is safe (DisconnectedScreen passthrough
        // covers the hang/kick case).
        boolean worldReady = this.client.world != null;
        boolean minHoldElapsed = now - this.enteringSinceMs >= MIN_ENTERING_MS;
        if (!worldReady || !minHoldElapsed) {
            return false;
        }
        this.client.setOverlay(null);
        ConnectSequence.reset();
        return true;
    }

    private void drawSpinner(DrawContext ctx, int w, int h, long now) {
        int cx = w / 2;
        int cy = (int) (h * 0.42F);
        int radius = 18;
        int dotSize = 4;
        double rotation = (now % SPINNER_PERIOD_MS) / (double) SPINNER_PERIOD_MS * Math.PI * 2.0;
        for (int i = 0; i < SPINNER_DOTS; i++) {
            double angle = rotation + (Math.PI * 2.0 * i / SPINNER_DOTS);
            int dx = cx + (int) (Math.cos(angle) * radius);
            int dy = cy + (int) (Math.sin(angle) * radius);
            // The head of the spin (i == 0) reads brightest; trailing dots fade out.
            float fadeAlpha = 0.2F + 0.8F * (1.0F - ((float) i / SPINNER_DOTS));
            ctx.fill(dx - dotSize / 2, dy - dotSize / 2, dx + dotSize / 2, dy + dotSize / 2, argb(TITLE_GOLD, fadeAlpha));
        }
    }

    private void drawProgressBar(DrawContext ctx, int w, int h, ConnectSequence.Phase phase, long now) {
        int barW = Math.min(w - 160, 420);
        int barX = (w - barW) / 2;
        int barY = barY(h);
        ctx.fill(barX - 1, barY - 1, barX + barW + 1, barY + 5, argb(BAR_TRACK, 1.0F));

        if (phase == ConnectSequence.Phase.RELOADING || phase == ConnectSequence.Phase.ENTERING) {
            // Real progress is known (the resource reload reports it): smooth it the same way
            // BootSequence does, so a burst of instantly-cached resources does not snap the bar.
            this.smoothedProgress = MathHelper.clamp(this.smoothedProgress * 0.90F + ConnectSequence.progress() * 0.10F, 0.0F, 1.0F);
            ctx.fill(barX, barY, barX + (int) (barW * this.smoothedProgress), barY + 4, argb(BAR_FILL, 1.0F));
        } else {
            // CONNECTING / DOWNLOADING have no numeric progress to show (login handshake, pack
            // download size is unknown up front): an indeterminate highlight sweeps the track so the
            // bar still reads as "working" instead of stalled.
            float t = (now % INDETERMINATE_PERIOD_MS) / (float) INDETERMINATE_PERIOD_MS;
            int highlightW = Math.max(barW / 4, 40);
            int travel = barW + highlightW;
            int highlightX = barX - highlightW + (int) (t * travel);
            int drawX = Math.max(barX, highlightX);
            int drawEnd = Math.min(barX + barW, highlightX + highlightW);
            if (drawEnd > drawX) {
                ctx.fill(drawX, barY, drawEnd, barY + 4, argb(BAR_FILL, 1.0F));
            }
        }
    }

    private void drawPhaseText(DrawContext ctx, int w, int h, ConnectSequence.Phase phase) {
        String text = switch (phase) {
            case DOWNLOADING -> DisclaimerText.CONNECT_DOWNLOADING;
            case RELOADING -> DisclaimerText.CONNECT_RELOADING;
            case ENTERING -> DisclaimerText.CONNECT_ENTERING;
            default -> DisclaimerText.CONNECT_CONNECTING;
        };
        ctx.drawCenteredTextWithShadow(this.client.textRenderer, Text.literal(text), w / 2, barY(h) + 14, argb(HINT_WHITE, 1.0F));
    }

    private static int barY(int h) {
        return (int) (h * 0.62F);
    }

    private static int argb(int rgb, float vis) {
        int a = MathHelper.clamp((int) (vis * 255.0F), 0, 255);
        return (a << 24) | (rgb & 0x00FFFFFF);
    }

    @Override
    public boolean pausesGame() {
        return true;
    }
}
