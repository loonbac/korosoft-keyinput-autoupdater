package com.korosoft.keyinput;

import com.korosoft.keyinput.auth.AuthSession;
import com.korosoft.keyinput.auth.KoroAuthClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.session.Session;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

/**
 * The launcher menu that replaces the vanilla title screen. It is not a Minecraft main menu with a
 * server list — this client only ever connects to one place — so the whole screen is a single
 * "start" surface: a Genshin-style layout (big centered call-to-action, corner controls, version
 * strip) painted over the KoroSoft splash art, with its own font and no borrowed assets.
 *
 * <p>Clicking anywhere that is not a corner button connects straight to the server. The corners
 * carry only what the player still needs from a main menu: quit-to-desktop and the options screen.
 */
public class MainMenuScreen extends Screen {

    // The Minecraft server the launcher connects to. The auth backend lives on its own
    // subdomain (see KoroAuthClient.AUTH_HOST) with a real CA certificate, so this is
    // intentionally NOT the same host as the auth endpoint.
    private static final String SERVER_ADDRESS = "mc.korosoft.site";
    private static final String SERVER_NAME = "KoroSoft SMP";
    private static final String MC_VERSION = "1.21.11";

    private static final int START_COLOR = 0xFFF3EFE6;
    private static final int HINT_COLOR = 0xFFCBD3E0;
    private static final int VERSION_COLOR = 0xFFAEB8C8;
    private static final int WELCOME_COLOR = 0xFFF3EFE6;
    private static final long WELCOME_MS = 4500L;

    // Corner icon buttons (text-less, square, pixel-art; see IconButtonWidget): quit sits
    // bottom-left below the version strip; options and logout are stacked vertically in the
    // bottom-right corner, options on top and logout directly below it.
    private static final Identifier ICON_LOGOUT = Identifier.of("keyinput", "textures/gui/icon_logout.png");
    private static final Identifier ICON_OPTIONS = Identifier.of("keyinput", "textures/gui/icon_options.png");
    private static final Identifier ICON_QUIT = Identifier.of("keyinput", "textures/gui/icon_quit.png");
    private static final int ICON_SIZE = 22;
    private static final int ICON_GAP = 4;
    private static final int ICON_MARGIN = 8;

    /** Latches on the first click so a double-click cannot fire two connect attempts. */
    private boolean connecting = false;

    /** Welcome-back greeting: {@code >= 0} while showing, the timestamp it started at. */
    private long welcomeStartMs = -1L;
    private String welcomeName;

    public MainMenuScreen() {
        super(Text.literal(SERVER_NAME));
    }

    @Override
    protected void init() {
        // Unlatch the connect guard whenever the menu is (re)shown — e.g. after being kicked by a
        // proxy restart or any disconnect, control returns to this same screen instance with
        // `connecting` still true, which would otherwise silently swallow every click to reconnect
        // and force a full game restart.
        this.connecting = false;

        // Start the menu theme, except during the boot sequence — there the boot overlay starts it
        // the instant it steps aside, so it lines up with the menu becoming visible rather than with
        // the menu being set up underneath the still-showing disclaimer. On every later visit (e.g.
        // returning from the server) the boot sequence is done, so this is what starts it.
        if (!BootSequence.isActive()) {
            MenuMusic.ensurePlaying();
        }

        // Bottom-right corner icon row, built right-to-left so quit/options keep the same position
        // whether or not logout is present: quit (rightmost), options, logout (leftmost, only when
        // a session is active — otherwise the login card is already mandatory and logout is moot).
        // Bottom-left, aligned under the version strip (drawn at x=12 in render()): quit to desktop.
        int quitY = this.height - ICON_SIZE - ICON_MARGIN;
        this.addDrawableChild(new IconButtonWidget(12, quitY, ICON_SIZE, ICON_QUIT,
                Text.literal(DisclaimerText.MENU_QUIT), this.client::scheduleStop));

        // Bottom-right, stacked vertically: options in the upper slot, logout in the lower slot.
        // Options keeps its slot whether or not logout is present; logout only exists while a
        // session is active (otherwise the login card is already mandatory and logout is moot).
        int rightX = this.width - ICON_MARGIN - ICON_SIZE;
        int logoutY = this.height - ICON_SIZE - ICON_MARGIN;
        int optionsY = logoutY - ICON_SIZE - ICON_GAP;

        this.addDrawableChild(new IconButtonWidget(rightX, optionsY, ICON_SIZE, ICON_OPTIONS,
                Text.literal(DisclaimerText.MENU_OPTIONS),
                () -> this.client.setScreen(new OptionsScreen(this, this.client.options))));

        if (AuthSession.isLoggedIn()) {
            this.addDrawableChild(new IconButtonWidget(rightX, logoutY, ICON_SIZE, ICON_LOGOUT,
                    Text.literal(DisclaimerText.MENU_LOGOUT), () -> {
                        AuthSession.clear();
                        this.client.setScreen(new LoginScreen(this));
                    }));
        }

        if (AuthSession.isLoggedIn()) {
            // Returning player, auto-logged-in from the saved session: greet them once.
            if (AuthSession.consumeWelcome()) {
                this.welcomeName = this.client.getSession().getUsername();
                this.welcomeStartMs = Util.getMeasuringTimeMs();
            }
        } else {
            // First launch / no saved session: the login card is mandatory and must appear before
            // the player can do anything (no "Iniciar" click needed, and it has no close button).
            // Deferred to avoid re-entrant setScreen while this screen is still being set up.
            this.client.execute(() -> {
                if (!AuthSession.isLoggedIn() && this.client.currentScreen == this) {
                    this.client.setScreen(new LoginScreen(this));
                }
            });
        }
    }

    private void drawWelcome(DrawContext ctx) {
        if (this.welcomeStartMs < 0) {
            return;
        }
        long elapsed = Util.getMeasuringTimeMs() - this.welcomeStartMs;
        if (elapsed > WELCOME_MS) {
            this.welcomeStartMs = -1L;
            return;
        }
        float alpha;
        if (elapsed < 400L) {
            alpha = elapsed / 400.0F;                       // fade in
        } else if (elapsed > WELCOME_MS - 900L) {
            alpha = (WELCOME_MS - elapsed) / 900.0F;        // fade out
        } else {
            alpha = 1.0F;                                   // hold
        }
        alpha = Math.clamp(alpha, 0.0F, 1.0F);
        int color = ((int) (alpha * 255) << 24) | (WELCOME_COLOR & 0x00FFFFFF);
        String msg = String.format(AuthText.WELCOME, this.welcomeName == null ? "" : this.welcomeName);

        Matrix3x2fStack matrices = ctx.getMatrices();
        matrices.pushMatrix();
        matrices.translate(this.width / 2.0F, this.height * 0.16F);
        matrices.scale(1.6F, 1.6F);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(msg), 0, 0, color);
        matrices.popMatrix();
    }

    /**
     * Package-visible so {@link LoginScreen} can call back into it after a successful
     * {@code /login} or {@code /register} — the gate below re-checks {@link AuthSession} and, now
     * that a token exists, falls through to the actual connect instead of reopening the card.
     */
    void startGame() {
        if (this.connecting) {
            return;
        }
        if (!AuthSession.isLoggedIn()) {
            // Not authenticated yet (mandatory per the KoroAuth design — a vanilla/unauthenticated
            // client can't answer the proxy's korosoft:auth login challenge and gets kicked): show
            // the login card instead of connecting straight away.
            this.client.setScreen(new LoginScreen(this));
            return;
        }

        this.connecting = true;
        MenuMusic.stop();
        // Connect with the launcher's own account name (case preserved). The proxy's
        // PlayAuthGatekeeper validates the session token in the play phase and only requires that
        // the connecting name canonicalizes to the token's account — no client-side session
        // rewrite needed, and the original casing (e.g. "LoonBac21") is kept in game.
        ServerAddress address = ServerAddress.parse(SERVER_ADDRESS);
        ServerInfo info = new ServerInfo(SERVER_NAME, SERVER_ADDRESS, ServerInfo.ServerType.OTHER);
        // Accept the server's (mandatory) resource pack automatically — no prompt. The prompt is
        // driven by this policy being PROMPT; ENABLED makes onResourcePackSend auto-download instead.
        info.setResourcePackPolicy(ServerInfo.ResourcePackPolicy.ENABLED);

        // Arm the overlay BEFORE connecting: ConnectScreen.connect spawns a background network thread
        // that can reach the server and start downloading the pack (ServerResourcePackLoaderMixin's
        // markDownloading()) almost immediately. If ConnectSequence.start() ran after connect(), that
        // background thread could call markDownloading() first, and start() would then stomp it back
        // to CONNECTING, losing the DOWNLOADING signal to a race.
        ConnectSequence.start();
        this.client.setOverlay(new ConnectOverlay(this.client));
        // Through login and the resource-pack reload the player only sees KoroSoft branding, never
        // vanilla's ConnectScreen or the red SplashOverlay the reload would otherwise trigger (see
        // ConnectOverlay/MinecraftClientMixin).
        ConnectScreen.connect(this, this.client, address, info, false, null);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        // Let the corner buttons consume their clicks first; anything else is "start".
        if (super.mouseClicked(click, doubled)) {
            return true;
        }
        if (click.button() == 0) {
            startGame();
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        // Debug shortcut: Shift+L opens the vanilla singleplayer world list.
        if (input.key() == GLFW.GLFW_KEY_L && (input.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0) {
            this.client.setScreen(new SelectWorldScreen(this));
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        MenuBackground.drawCover(ctx, this.width, this.height);
        // A whisper of a veil so the light call-to-action text stays readable over bright sky.
        MenuBackground.dim(ctx, this.width, this.height, 45);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);

        // Big centered "INICIAR JUEGO", scaled up around the screen's upper third.
        Matrix3x2fStack matrices = ctx.getMatrices();
        matrices.pushMatrix();
        matrices.translate(this.width / 2.0F, this.height * 0.42F);
        matrices.scale(2.2F, 2.2F);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(DisclaimerText.MENU_START), 0, 0, START_COLOR);
        matrices.popMatrix();

        // Pulsing "Haz clic para comenzar" just below it.
        float pulse = (float) ((Math.sin(Util.getMeasuringTimeMs() / 500.0) + 1.0) / 2.0);
        int hintAlpha = 0x66 + (int) (pulse * 0x99);
        int hint = (hintAlpha << 24) | (HINT_COLOR & 0x00FFFFFF);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(DisclaimerText.MENU_HINT),
                this.width / 2, (int) (this.height * 0.42F) + 22, hint);

        // Version strip, bottom-left (the icon row now lives in the bottom-right corner).
        ctx.drawTextWithShadow(this.textRenderer,
                Text.literal("v" + modVersion() + " · MC " + MC_VERSION + " · " + SERVER_NAME),
                12, this.height - 46, VERSION_COLOR);

        drawWelcome(ctx);
    }

    private static String modVersion() {
        return FabricLoader.getInstance()
                .getModContainer("korosoft-core")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("?");
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
