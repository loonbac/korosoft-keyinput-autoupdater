package com.korosoft.keyinput;

import com.korosoft.keyinput.auth.AuthOutcome;
import com.korosoft.keyinput.auth.AuthSession;
import com.korosoft.keyinput.auth.KoroAuthClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/**
 * The KoroAuth login/register card, styled after a HoYoverse/Genshin-style modal: a centered
 * light/frosted rounded card painted OVER the existing launcher background (the mod's Genshin-style
 * splash art, see {@link MenuBackground}) — the background is never replaced, only overlaid.
 *
 * <p>This card is <b>mandatory and unskippable</b>: it is opened automatically by
 * {@link MainMenuScreen} whenever no session is active, has no close button, and ignores Esc. The
 * player can only leave it by logging in or registering. On success the token is stored (and
 * persisted) in {@link AuthSession} and the card simply closes back to {@link MainMenuScreen} — it
 * does <b>not</b> connect automatically; the player starts the game from the menu when they choose.
 *
 * <p>The username is <b>not editable</b>: it is fixed to the launcher account name and shown as a
 * locked chip. The player only types a password (and, when registering, a confirmation).
 *
 * <p>Entrance is animated (scale-up + slide + veil fade-in); see {@link #render}.
 *
 * <p>Every player-facing string lives in {@link AuthText} (neutral Spanish, "tú" form, no voseo).
 */
public class LoginScreen extends Screen {

    // --- Card metrics ---
    private static final int CARD_W = 300;
    private static final int PAD_X = 24;
    private static final int PAD_TOP = 20;
    private static final int PAD_BOTTOM = 18;
    private static final int FIELD_W = CARD_W - PAD_X * 2;
    private static final int FIELD_H = 20;
    private static final int GAP_SM = 8;
    private static final int GAP_MED = 16;
    private static final int BUTTON_H = 24;
    private static final int LOGO_H = 40;

    /** Minimum breathing room (logical px) kept between the card and the viewport edge at any GUI scale. */
    private static final int VIEWPORT_MARGIN = 14;

    // --- Entrance / exit animation ---
    private static final long ANIM_MS = 280L;
    private static final long CLOSE_MS = 240L;

    // --- Colors (ARGB — always 0xFF______ for opaque, per this project's 1.21.11 DrawContext rule) ---
    private static final int CARD_FILL = 0xE8FBFAF6;
    private static final int CARD_BORDER = 0x33000000;
    private static final int CARD_ACCENT = 0xFFE8C170;
    private static final int TITLE_COLOR = 0xFF20222C;
    private static final int LINK_COLOR = 0xFFA9781B;
    private static final int LINK_HOVER_COLOR = 0xFFC79A3A;
    private static final int ERROR_COLOR = 0xFFB3261E;
    private static final int INFO_COLOR = 0xFF43485A;
    private static final int CHIP_FILL = 0x14202230;
    private static final int CHIP_NAME_COLOR = 0xFF20222C;
    private static final int CHIP_CAPTION_COLOR = 0xFF7A7E8C;
    private static final int LOCK_COLOR = 0xFF8A6D2B;

    private static final Identifier LOGO_TEXTURE = Identifier.of("keyinput", "textures/gui/korosoft_logo.png");
    private static final int LOGO_TEX_W = 1559;
    private static final int LOGO_TEX_H = 239;

    private final MainMenuScreen parent;

    private boolean registerMode = false;
    private boolean submitting = false;
    private String statusMessage;
    private int statusColor = ERROR_COLOR;

    /** The launcher account name — fixed, not editable. Shown in the locked identity chip. */
    private String playerName = "";

    private TextFieldWidget passwordField;
    private TextFieldWidget confirmPasswordField;
    private ButtonWidget primaryButton;

    // Card geometry + link hit-boxes, recomputed in init().
    private int cardX;
    private int cardY;
    private int cardH;

    /**
     * Fit-to-viewport down-scale applied on top of the entrance animation's scale, so the card
     * (fixed at {@link #CARD_W} x content-height logical px) never overflows a small logical
     * viewport at high GUI scale. Never exceeds 1.0 — only ever shrinks. Recomputed in init();
     * mouse input is mapped through the inverse of this factor, see {@link #unmapX} / {@link #unmapY}.
     */
    private float uiScale = 1.0F;
    private int usernameChipY;
    private int[] toggleLinkBounds = new int[4];
    private int[] forgotLinkBounds = new int[4];
    private int toggleLinkY;
    private int forgotLinkY;

    // Entrance animation state.
    private long openTimeMs;
    private float animEased;

    // Exit animation state — the card animates OUT (mirror of the entrance) before actually
    // switching screens, so a successful login never snaps away instantly.
    private boolean closing = false;
    private long closeStartMs;
    private Runnable afterClose;

    public LoginScreen(MainMenuScreen parent) {
        super(Text.literal(registerTitleFor(false)));
        this.parent = parent;
    }

    private static String registerTitleFor(boolean registerMode) {
        return registerMode ? AuthText.REGISTER_TITLE : AuthText.LOGIN_TITLE;
    }

    @Override
    protected void init() {
        this.openTimeMs = Util.getMeasuringTimeMs();

        String sessionName = this.client.getSession().getUsername();
        this.playerName = (sessionName == null || sessionName.isBlank()) ? "Player" : sessionName;

        this.cardX = (this.width - CARD_W) / 2;

        int y = PAD_TOP;
        y += LOGO_H + GAP_MED;
        y += this.textRenderer.fontHeight + GAP_MED;

        this.usernameChipY = y;
        y += FIELD_H + GAP_SM;
        int passwordY = y;
        y += FIELD_H;

        int confirmY = -1;
        if (this.registerMode) {
            y += GAP_SM;
            confirmY = y;
            y += FIELD_H;
        }
        y += GAP_SM;

        this.forgotLinkY = y;
        y += this.textRenderer.fontHeight + GAP_SM;

        // Reserve a fixed two-line status area regardless of whether a message is showing right
        // now, so the button/toggle-link below never jump around as errors appear/disappear.
        y += this.textRenderer.fontHeight * 2 + GAP_SM;

        int buttonY = y;
        y += BUTTON_H + GAP_SM;

        this.toggleLinkY = y;
        y += this.textRenderer.fontHeight;
        y += PAD_BOTTOM;

        this.cardH = y;
        this.cardY = (this.height - this.cardH) / 2;

        // Fit-to-viewport: shrink (never grow) so the card plus a margin always fits the logical
        // viewport. The card center stays put (cardX/cardY are unchanged), and render() scales
        // around that same center, so it stays symmetrically centered at any factor.
        float maxW = this.width - VIEWPORT_MARGIN * 2;
        float maxH = this.height - VIEWPORT_MARGIN * 2;
        this.uiScale = Math.min(1.0F, Math.min(maxW / CARD_W, maxH / this.cardH));

        int fieldX = this.cardX + PAD_X;

        this.passwordField = new TextFieldWidget(this.textRenderer, fieldX, this.cardY + passwordY, FIELD_W, FIELD_H,
                Text.literal(AuthText.PASSWORD_PLACEHOLDER));
        this.passwordField.setPlaceholder(Text.literal(AuthText.PASSWORD_PLACEHOLDER));
        this.passwordField.setMaxLength(128);
        maskAsPassword(this.passwordField);
        this.addDrawableChild(this.passwordField);

        if (this.registerMode) {
            this.confirmPasswordField = new TextFieldWidget(this.textRenderer, fieldX, this.cardY + confirmY,
                    FIELD_W, FIELD_H, Text.literal(AuthText.CONFIRM_PASSWORD_PLACEHOLDER));
            this.confirmPasswordField.setPlaceholder(Text.literal(AuthText.CONFIRM_PASSWORD_PLACEHOLDER));
            this.confirmPasswordField.setMaxLength(128);
            maskAsPassword(this.confirmPasswordField);
            this.addDrawableChild(this.confirmPasswordField);
        } else {
            this.confirmPasswordField = null;
        }

        String buttonLabel = this.registerMode ? AuthText.REGISTER_BUTTON : AuthText.LOGIN_BUTTON;
        this.primaryButton = this.addDrawableChild(ButtonWidget.builder(Text.literal(buttonLabel), b -> submit())
                .position(fieldX, this.cardY + buttonY)
                .size(FIELD_W, BUTTON_H)
                .build());
        this.primaryButton.active = !this.submitting;

        // No close button on purpose: authentication is mandatory before the player can play.

        int forgotWidth = this.textRenderer.getWidth(AuthText.FORGOT_PASSWORD_LINK);
        int forgotX = this.cardX + CARD_W - PAD_X - forgotWidth;
        this.forgotLinkBounds = new int[]{forgotX, this.cardY + this.forgotLinkY,
                forgotX + forgotWidth, this.cardY + this.forgotLinkY + this.textRenderer.fontHeight};

        String toggleText = this.registerMode ? AuthText.LOGIN_LINK : AuthText.REGISTER_LINK;
        int toggleWidth = this.textRenderer.getWidth(toggleText);
        int toggleX = this.cardX + (CARD_W - toggleWidth) / 2;
        this.toggleLinkBounds = new int[]{toggleX, this.cardY + this.toggleLinkY,
                toggleX + toggleWidth, this.cardY + this.toggleLinkY + this.textRenderer.fontHeight};

        this.setInitialFocus(this.passwordField);
    }

    /** Renders the field's content as a run of {@code *} the same length as the real text. */
    private static void maskAsPassword(TextFieldWidget field) {
        field.addFormatter((text, firstCharacterIndex) ->
                OrderedText.styledForwardsVisitedString("*".repeat(text.length()), Style.EMPTY));
    }

    /** Ease-out with a subtle overshoot ("pop") — modern feel, kept mild so it still reads as MC. */
    private static float easeOutBack(float t) {
        float c1 = 1.70158F;
        float c3 = c1 + 1.0F;
        float u = t - 1.0F;
        return 1.0F + c3 * u * u * u + c1 * u * u;
    }

    /** Starts the exit animation; {@code after} runs once the card has finished animating out. */
    private void beginClose(Runnable after) {
        if (this.closing) {
            return;
        }
        this.closing = true;
        this.closeStartMs = Util.getMeasuringTimeMs();
        this.afterClose = after;
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Intentionally empty: the background is drawn (unscaled) at the top of render() so the
        // card's entrance transform never scales the full-screen art with it.
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        float scale;
        float slide;
        int veil;
        if (this.closing) {
            // Exit: mirror of the entrance — the card shrinks and slides down while the veil fades
            // back out, then we switch screens once the animation completes.
            float tc = Math.clamp((Util.getMeasuringTimeMs() - this.closeStartMs) / (float) CLOSE_MS, 0.0F, 1.0F);
            float e = HudAnimator.easeInQuad(tc);
            this.animEased = 1.0F;                     // keep the accent bar fully drawn on the way out
            scale = 1.0F - 0.12F * e;                 // 1.0 -> 0.88
            slide = e * 14.0F;                        // slide down and away
            veil = Math.round(90.0F * (1.0F - e));    // fade the dim back out
            if (tc >= 1.0F && this.afterClose != null) {
                Runnable done = this.afterClose;
                this.afterClose = null;
                this.client.execute(done);            // defer the setScreen out of render()
            }
        } else {
            float t = Math.clamp((Util.getMeasuringTimeMs() - this.openTimeMs) / (float) ANIM_MS, 0.0F, 1.0F);
            this.animEased = HudAnimator.easeOutCubic(t);
            scale = 0.90F + 0.10F * easeOutBack(t);
            slide = (1.0F - this.animEased) * 12.0F;
            veil = Math.round(90.0F * HudAnimator.easeOutCubic(Math.clamp(t * 1.25F, 0.0F, 1.0F)));
        }

        // Unscaled launcher background + a veil that fades in with the card.
        MenuBackground.drawCover(ctx, this.width, this.height);
        MenuBackground.dim(ctx, this.width, this.height, veil);

        float cx = this.cardX + CARD_W / 2.0F;
        float cy = this.cardY + this.cardH / 2.0F;
        Matrix3x2fStack matrices = ctx.getMatrices();
        matrices.pushMatrix();
        matrices.translate(cx, cy + slide);
        matrices.scale(scale * this.uiScale, scale * this.uiScale);
        matrices.translate(-cx, -cy);

        drawCard(ctx);
        drawLogoAndTitle(ctx);
        drawIdentityChip(ctx);

        // ClickableWidget.render() computes hover state (and thus tooltips) directly from the
        // mouseX/mouseY ints handed to it here — NOT from a mouseMoved() callback — so those ints
        // must be mapped into the card's unscaled space the same way click events are, or hover
        // and tooltips would desync from the rendered (uiScale-shrunk) widgets.
        int mx = (int) unmapX(mouseX);
        int my = (int) unmapY(mouseY);

        // Widgets (password fields, primary button) — renderBackground() is empty, so this only
        // draws the children, and they are transformed together with the card above.
        super.render(ctx, mx, my, delta);

        drawLink(ctx, AuthText.FORGOT_PASSWORD_LINK, this.forgotLinkBounds, mx, my);
        drawLink(ctx, this.registerMode ? AuthText.LOGIN_LINK : AuthText.REGISTER_LINK, this.toggleLinkBounds, mx, my);
        drawStatus(ctx);

        matrices.popMatrix();
    }

    private void drawCard(DrawContext ctx) {
        int x2 = this.cardX + CARD_W;
        int y2 = this.cardY + this.cardH;
        ctx.fill(this.cardX, this.cardY, x2, y2, CARD_FILL);

        // Top accent bar "wipes" out from the center as the card settles.
        int half = Math.round((CARD_W / 2.0F) * this.animEased);
        int mid = this.cardX + CARD_W / 2;
        ctx.fill(mid - half, this.cardY, mid + half, this.cardY + 3, CARD_ACCENT);

        ctx.fill(this.cardX, this.cardY, this.cardX + 1, y2, CARD_BORDER);
        ctx.fill(x2 - 1, this.cardY, x2, y2, CARD_BORDER);
        ctx.fill(this.cardX, y2 - 1, x2, y2, CARD_BORDER);
    }

    private void drawLogoAndTitle(DrawContext ctx) {
        int logoDrawW = FIELD_W;
        int logoDrawH = LOGO_H;
        int logoX = this.cardX + PAD_X;
        int logoY = this.cardY + PAD_TOP;
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, LOGO_TEXTURE, logoX, logoY, 0.0F, 0.0F,
                logoDrawW, logoDrawH, LOGO_TEX_W, LOGO_TEX_H, LOGO_TEX_W, LOGO_TEX_H);

        String title = this.registerMode ? AuthText.REGISTER_TITLE : AuthText.LOGIN_TITLE;
        int titleY = this.cardY + PAD_TOP + LOGO_H + GAP_MED;
        ctx.drawCenteredTextWithShadow(this.textRenderer, title, this.cardX + CARD_W / 2, titleY, TITLE_COLOR);
    }

    /** The locked, non-editable identity chip showing the fixed launcher account name. */
    private void drawIdentityChip(DrawContext ctx) {
        int x = this.cardX + PAD_X;
        int y = this.cardY + this.usernameChipY;
        int x2 = x + FIELD_W;
        int y2 = y + FIELD_H;

        // Caption above the chip.
        ctx.drawText(this.textRenderer, AuthText.ACCOUNT_CAPTION, x,
                y - this.textRenderer.fontHeight - 2, CHIP_CAPTION_COLOR, false);

        // Chip body + border (muted, read-only look).
        ctx.fill(x, y, x2, y2, CHIP_FILL);
        ctx.fill(x, y, x2, y + 1, CARD_BORDER);
        ctx.fill(x, y2 - 1, x2, y2, CARD_BORDER);
        ctx.fill(x, y, x + 1, y2, CARD_BORDER);
        ctx.fill(x2 - 1, y, x2, y2, CARD_BORDER);

        // Account name, vertically centered.
        int textY = y + (FIELD_H - this.textRenderer.fontHeight) / 2;
        ctx.drawText(this.textRenderer, this.playerName, x + 6, textY, CHIP_NAME_COLOR, false);

        // Small lock glyph on the right edge, so "not editable" reads at a glance.
        drawLockIcon(ctx, x2 - 15, y + (FIELD_H - 9) / 2);
    }

    /** A tiny padlock drawn from fills (no texture needed). */
    private void drawLockIcon(DrawContext ctx, int x, int y) {
        ctx.fill(x, y + 3, x + 8, y + 9, LOCK_COLOR);      // body
        ctx.fill(x + 1, y, x + 7, y + 1, LOCK_COLOR);      // shackle top
        ctx.fill(x + 1, y, x + 2, y + 4, LOCK_COLOR);      // shackle left
        ctx.fill(x + 6, y, x + 7, y + 4, LOCK_COLOR);      // shackle right
        ctx.fill(x + 3, y + 5, x + 5, y + 7, CARD_FILL);   // keyhole
    }

    private void drawLink(DrawContext ctx, String text, int[] bounds, int mouseX, int mouseY) {
        boolean hovered = isInside(bounds, mouseX, mouseY);
        int color = hovered ? LINK_HOVER_COLOR : LINK_COLOR;
        ctx.drawText(this.textRenderer, text, bounds[0], bounds[1], color, false);
    }

    private void drawStatus(DrawContext ctx) {
        if (this.statusMessage == null) {
            return;
        }
        int maxWidth = FIELD_W;
        int x = this.cardX + PAD_X;
        // Status area starts right after the forgot-password link line.
        int y = this.forgotLinkBounds[3] + GAP_SM;
        for (OrderedText line : this.textRenderer.wrapLines(Text.literal(this.statusMessage), maxWidth)) {
            ctx.drawText(this.textRenderer, line, x, y, this.statusColor, false);
            y += this.textRenderer.fontHeight + 2;
        }
    }

    private static boolean isInside(int[] bounds, int x, int y) {
        return x >= bounds[0] && x <= bounds[2] && y >= bounds[1] && y <= bounds[3];
    }

    /**
     * Inverse-maps a screen-space coordinate into the card's unscaled logical space, about the
     * card's center — the same pivot {@link #render} scales around. Widgets and this screen's own
     * link hit-boxes are all laid out in unscaled coordinates, but the card is drawn shrunk by
     * {@link #uiScale} at high GUI scale, so every incoming mouse coordinate must be unmapped
     * through this before any hit test (including the ones inside vanilla widgets via {@code super}).
     */
    private double unmapX(double x) {
        float cx = this.cardX + CARD_W / 2.0F;
        return cx + (x - cx) / this.uiScale;
    }

    private double unmapY(double y) {
        float cy = this.cardY + this.cardH / 2.0F;
        return cy + (y - cy) / this.uiScale;
    }

    private Click unmap(Click click) {
        return new Click(unmapX(click.x()), unmapY(click.y()), click.buttonInfo());
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (this.closing) {
            return true;   // swallow input while the card is animating out
        }
        Click mapped = unmap(click);
        if (super.mouseClicked(mapped, doubled)) {
            return true;
        }
        if (mapped.button() != 0) {
            return false;
        }
        int mx = (int) mapped.x();
        int my = (int) mapped.y();
        if (isInside(this.forgotLinkBounds, mx, my)) {
            this.statusMessage = AuthText.FORGOT_PASSWORD_MESSAGE;
            this.statusColor = INFO_COLOR;
            return true;
        }
        if (isInside(this.toggleLinkBounds, mx, my)) {
            this.registerMode = !this.registerMode;
            this.statusMessage = null;
            this.clearAndInit();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(Click click) {
        return super.mouseReleased(unmap(click));
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        // offsetX/offsetY are a screen-space delta; the transform between screen and card space is
        // a pure scale (no rotation), so dividing the delta by the same factor as the position keeps
        // drag distances (e.g. TextFieldWidget's drag-to-select) consistent in unscaled card space.
        return super.mouseDragged(unmap(click), offsetX / this.uiScale, offsetY / this.uiScale);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if ((input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) && !this.submitting) {
            submit();
            return true;
        }
        return super.keyPressed(input);
    }

    private void submit() {
        if (this.submitting || this.closing) {
            return;
        }

        String username = this.playerName.trim();
        String password = this.passwordField.getText();
        if (password.isEmpty()) {
            this.statusMessage = AuthText.ERROR_BAD_REQUEST;
            this.statusColor = ERROR_COLOR;
            return;
        }
        if (this.registerMode && !password.equals(this.confirmPasswordField.getText())) {
            this.statusMessage = AuthText.ERROR_PASSWORDS_DONT_MATCH;
            this.statusColor = ERROR_COLOR;
            return;
        }

        this.submitting = true;
        this.primaryButton.active = false;
        this.primaryButton.setMessage(Text.literal(AuthText.SUBMITTING));
        this.statusMessage = null;

        // The canonical form KoroAuth stores accounts under (Usernames.canonicalize in the
        // plugin: trim + lowercase). Only used client-side to label the session; the plugin does
        // its own canonicalization independently and is the actual source of truth.
        String canonicalUsername = username.toLowerCase(Locale.ROOT);

        // TextFieldWidget only exposes its content as a String (no char[]-native input widget
        // exists in vanilla) — this local is unavoidable, but it is not retained anywhere: it is
        // read once into a char[] copy immediately below, and KoroAuthClient takes ownership of
        // that copy and zeroes it once the request body is built. Never logged.
        char[] passwordChars = password.toCharArray();

        var future = this.registerMode
                ? KoroAuthClient.register(username, passwordChars)
                : KoroAuthClient.login(username, passwordChars);

        future.thenAccept(outcome -> this.client.execute(() -> handleOutcome(canonicalUsername, outcome)));
    }

    private void handleOutcome(String canonicalUsername, AuthOutcome outcome) {
        this.submitting = false;
        if (this.primaryButton != null) {
            this.primaryButton.active = true;
            this.primaryButton.setMessage(Text.literal(this.registerMode ? AuthText.REGISTER_BUTTON : AuthText.LOGIN_BUTTON));
        }

        if (outcome.isSuccess()) {
            AuthSession.set(canonicalUsername, outcome.token());
            // Animate the card OUT, then return to the launcher menu — do NOT connect automatically.
            // The player connects when they choose to (the menu's "Iniciar" is now unlocked).
            beginClose(() -> this.client.setScreen(this.parent));
            return;
        }

        this.statusMessage = switch (outcome.kind()) {
            case INVALID_CREDENTIALS -> AuthText.ERROR_INVALID_CREDENTIALS;
            case NOT_WHITELISTED -> AuthText.ERROR_NOT_WHITELISTED;
            case ALREADY_REGISTERED -> AuthText.ERROR_ALREADY_REGISTERED;
            case INVALID_USERNAME -> AuthText.ERROR_INVALID_USERNAME;
            case RATE_LIMITED -> AuthText.ERROR_RATE_LIMITED;
            case BAD_REQUEST, METHOD_NOT_ALLOWED -> AuthText.ERROR_BAD_REQUEST;
            case SERVER_ERROR -> AuthText.ERROR_SERVER_ERROR;
            case NETWORK_ERROR -> AuthText.ERROR_NETWORK;
            case SUCCESS -> null; // unreachable: handled by the isSuccess() branch above
        };
        this.statusColor = ERROR_COLOR;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // Mandatory login: Esc must not dismiss the card.
        return false;
    }
}
