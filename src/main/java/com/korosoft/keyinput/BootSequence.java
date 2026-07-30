package com.korosoft.keyinput;

import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix3x2fStack;

/**
 * The whole boot experience, as a small state machine driven one frame at a time from the loading
 * splash ({@code SplashOverlayMixin}). It runs in this order, like a real game's front-end:
 *
 * <ol>
 *   <li><b>Two disclaimer pages</b> — black background, white text, each fading in and holding, the
 *       legal/attribution notice split across them. A left click skips to the next page.</li>
 *   <li><b>Branded loading</b> — dark background, the KoroSoft title and a real progress bar,
 *       shown until the resource reload finishes, then cross-faded away to reveal the menu.</li>
 * </ol>
 *
 * <p>The disclaimer always plays in full, no matter how fast resources loaded, because the splash
 * mixin keeps the overlay up until this reports finished. The click is delivered by
 * {@code MouseMixin} while {@link #isActive()} is true — the overlay itself receives no input.
 *
 * <p>All timing uses {@link Util#getMeasuringTimeMs()}; state is static because there is exactly one
 * boot per process.
 */
public final class BootSequence {

    private BootSequence() {
    }

    private static final int PHASE_UNINIT = -1;
    private static final int PHASE_PAGE_1 = 0;
    private static final int PHASE_PAGE_2 = 1;
    private static final int PHASE_LOADING = 2;
    private static final int PHASE_DONE = 3;

    private static final long FADE_IN_MS = 700L;
    private static final long MIN_VISIBLE_MS = 2200L; // floor: a page cannot be left before this, ever
    private static final long PAGE_HOLD_MS = 6000L;   // auto-advance if the player never clicks
    private static final long FADE_OUT_MS = 500L;
    private static final long LOADING_MIN_MS = 700L;  // keep the logo up at least this long
    private static final long LOADING_FADE_MS = 600L;

    private static final int WHITE = 0xFFFFFF;
    private static final int HINT_WHITE = 0xC8C8C8;
    private static final int LOADING_BG = 0x0B0B12;
    private static final int TITLE_GOLD = 0xE8C170;
    private static final int BAR_TRACK = 0x33405E;
    private static final int BAR_FILL = 0xE8C170;

    private static volatile int phase = PHASE_UNINIT;
    private static long phaseStart;
    private static long fadeOutStart = -1L;
    private static volatile boolean clickPending = false;
    private static volatile boolean active = false;
    private static float smoothedProgress = 0.0F;

    /** True while the boot overlay owns the screen; {@code MouseMixin} routes clicks here only then. */
    public static boolean isActive() {
        return active;
    }

    /** Recorded by {@code MouseMixin} on a left click; consumed by the current disclaimer page. */
    public static void requestAdvance() {
        clickPending = true;
    }

    private static boolean consumeClick() {
        if (clickPending) {
            clickPending = false;
            return true;
        }
        return false;
    }

    /**
     * Draws one frame of the boot sequence. Returns true once everything is done and the splash
     * overlay should dismiss itself.
     */
    public static boolean render(DrawContext ctx, MinecraftClient client, boolean reloadComplete, float reloadProgress) {
        long now = Util.getMeasuringTimeMs();
        if (phase == PHASE_UNINIT) {
            phase = PHASE_PAGE_1;
            phaseStart = now;
            active = true;
        }

        int w = ctx.getScaledWindowWidth();
        int h = ctx.getScaledWindowHeight();

        if (phase == PHASE_PAGE_1 || phase == PHASE_PAGE_2) {
            renderDisclaimerPage(ctx, client.textRenderer, w, h, now,
                    phase == PHASE_PAGE_1 ? DisclaimerText.P1_TITLE : DisclaimerText.P2_TITLE,
                    phase == PHASE_PAGE_1 ? DisclaimerText.P1_BODY : DisclaimerText.P2_BODY);
            return false;
        }

        boolean finished = renderLoading(ctx, client.textRenderer, w, h, now, reloadComplete, reloadProgress);
        if (finished) {
            phase = PHASE_DONE;
            active = false;
            return true;
        }
        return false;
    }

    private static void advancePhase(long now) {
        phase++;
        phaseStart = now;
        fadeOutStart = -1L;
    }

    private static void renderDisclaimerPage(DrawContext ctx, TextRenderer font, int w, int h, long now,
                                             String heading, List<String> body) {
        // Opaque black: nothing behind the disclaimer ever shows through.
        ctx.fill(0, 0, w, h, 0xFF000000);

        float alpha;
        if (fadeOutStart > 0L) {
            float ft = (float) (now - fadeOutStart) / FADE_OUT_MS;
            if (ft >= 1.0F) {
                advancePhase(now);
                return;
            }
            alpha = 1.0F - ft;
        } else {
            long t = now - phaseStart;
            alpha = t < FADE_IN_MS ? (float) t / FADE_IN_MS : 1.0F;
            // A page holds for a guaranteed minimum before anything — click or timer — can leave it,
            // so the disclaimer always gets its time on screen regardless of how fast the game loaded.
            boolean clicked = consumeClick();
            if (t >= MIN_VISIBLE_MS && (clicked || t >= PAGE_HOLD_MS)) {
                fadeOutStart = now;
            }
        }

        Matrix3x2fStack matrices = ctx.getMatrices();
        final int margin = 16;

        // Heading: shrink to fit the viewport width so it never overflows at high GUI scale (where
        // the logical viewport is small). Capped at the original 1.8x on roomy screens.
        int headingW = Math.max(1, font.getWidth(heading));
        float titleScale = Math.min(1.8F, (w - margin * 2) / (float) headingW);
        int titleTopY = Math.round(h * 0.15F);
        matrices.pushMatrix();
        matrices.translate(w / 2.0F, titleTopY);
        matrices.scale(titleScale, titleScale);
        ctx.drawCenteredTextWithShadow(font, Text.literal(heading), 0, 0, argb(WHITE, alpha));
        matrices.popMatrix();

        // Skip hint anchored to the bottom margin; the body band below stops short of it so the two
        // never collide (they used to overlap on small viewports).
        float pulse = (float) ((Math.sin(now / 500.0) + 1.0) / 2.0);
        float hintAlpha = alpha * (0.45F + pulse * 0.45F);
        int hintTopY = h - margin - font.fontHeight;
        ctx.drawCenteredTextWithShadow(font, Text.literal(DisclaimerText.DISCLAIMER_HINT),
                w / 2, hintTopY, argb(HINT_WHITE, hintAlpha));

        // Body: measure its full wrapped height, then uniformly shrink (never enlarge) and vertically
        // center it inside the band between the heading and the hint, so it always fits.
        int maxWidth = Math.min(w - margin * 2, 520);
        int lineH = font.fontHeight + 3;
        int paraGap = 7;
        int totalLines = 0;
        for (String paragraph : body) {
            totalLines += font.wrapLines(Text.literal(paragraph), maxWidth).size();
        }
        int bodyHeight = Math.max(1, totalLines * lineH + Math.max(0, body.size() - 1) * paraGap);

        int bandTop = titleTopY + Math.round(font.fontHeight * titleScale) + 12;
        int bandBottom = hintTopY - 8;
        int bandH = Math.max(1, bandBottom - bandTop);
        float bodyScale = Math.min(1.0F, bandH / (float) bodyHeight);
        float effBodyH = bodyHeight * bodyScale;
        float startY = bandTop + (bandH - effBodyH) / 2.0F;

        matrices.pushMatrix();
        matrices.translate(w / 2.0F, startY);
        matrices.scale(bodyScale, bodyScale);
        matrices.translate(-w / 2.0F, -startY);
        float yy = startY;
        for (String paragraph : body) {
            for (OrderedText line : font.wrapLines(Text.literal(paragraph), maxWidth)) {
                ctx.drawCenteredTextWithShadow(font, line, w / 2, Math.round(yy), argb(WHITE, alpha));
                yy += lineH;
            }
            yy += paraGap;
        }
        matrices.popMatrix();
    }

    private static boolean renderLoading(DrawContext ctx, TextRenderer font, int w, int h, long now,
                                         boolean reloadComplete, float reloadProgress) {
        long t = now - phaseStart;

        // Once resources are ready (and the logo has had its minimum moment), fade the whole thing
        // out — as the dark veil drops, the already-rendered launcher menu shows through underneath.
        if (reloadComplete && t >= LOADING_MIN_MS && fadeOutStart < 0L) {
            fadeOutStart = now;
        }
        float veil;
        if (fadeOutStart > 0L) {
            float ft = (float) (now - fadeOutStart) / LOADING_FADE_MS;
            if (ft >= 1.0F) {
                return true;
            }
            veil = 1.0F - ft;
        } else {
            veil = 1.0F;
        }

        ctx.fill(0, 0, w, h, argb(LOADING_BG, veil));

        Matrix3x2fStack matrices = ctx.getMatrices();
        matrices.pushMatrix();
        float loadTitleScale = Math.min(2.0F, (w - 32) / (float) Math.max(1, font.getWidth(DisclaimerText.LOADING_TITLE)));
        matrices.translate(w / 2.0F, h * 0.42F);
        matrices.scale(loadTitleScale, loadTitleScale);
        ctx.drawCenteredTextWithShadow(font, Text.literal(DisclaimerText.LOADING_TITLE), 0, 0, argb(TITLE_GOLD, veil));
        matrices.popMatrix();

        smoothedProgress = MathHelper.clamp(smoothedProgress * 0.90F + reloadProgress * 0.10F, 0.0F, 1.0F);
        int barW = Math.min(w - 160, 420);
        int barX = (w - barW) / 2;
        int barY = (int) (h * 0.58F);
        ctx.fill(barX - 1, barY - 1, barX + barW + 1, barY + 5, argb(BAR_TRACK, veil));
        ctx.fill(barX, barY, barX + (int) (barW * smoothedProgress), barY + 4, argb(BAR_FILL, veil));
        ctx.drawCenteredTextWithShadow(font, Text.literal(DisclaimerText.LOADING_SUBTITLE),
                w / 2, barY - 16, argb(HINT_WHITE, veil));
        return false;
    }

    private static int argb(int rgb, float vis) {
        int a = MathHelper.clamp((int) (vis * 255.0F), 0, 255);
        return (a << 24) | (rgb & 0x00FFFFFF);
    }
}
