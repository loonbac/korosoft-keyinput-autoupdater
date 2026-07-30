package com.korosoft.keyinput;

import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Overlay;
import net.minecraft.resource.ResourceReload;

/**
 * The KoroSoft boot overlay, installed in place of the vanilla {@code SplashOverlay} (see
 * {@code MinecraftClientMixin}). It wraps the same resource reload and completion callback the
 * vanilla splash would have used, and draws {@link BootSequence} — the two disclaimer pages, then
 * the branded loading screen — instead of the Mojang logo.
 *
 * <p><b>Why a separate class instead of a mixin on SplashOverlay:</b> this modpack ships performance
 * mods (ModernFix, Sodium, ImmediatelyFast) that mixin the vanilla {@code SplashOverlay} and can
 * dismiss it the instant the reload finishes, stripping its fade. Hijacking that class meant the
 * disclaimer only lasted as long as the load did — a fraction of a second on a warm cache. This
 * overlay is our own type, so none of those mixins touch it, and dismissal happens only when
 * {@link BootSequence} says it is done. The disclaimer timing is therefore fully ours.
 *
 * <p>The reload is still driven to completion here: once {@code reload.isComplete()}, the captured
 * exception handler is invoked exactly as vanilla would (it runs {@code onFinishedLoading}, which
 * sets up the title/launcher screen underneath), so the loading fade reveals a ready menu.
 */
public class KoroBootOverlay extends Overlay {

    private final MinecraftClient client;
    private final ResourceReload reload;
    private final Consumer<Optional<Throwable>> exceptionHandler;
    private boolean reloadFinished = false;

    public KoroBootOverlay(MinecraftClient client, ResourceReload reload, Consumer<Optional<Throwable>> exceptionHandler) {
        this.client = client;
        this.reload = reload;
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        // Finish the reload once it completes — this runs onFinishedLoading, which sets the launcher
        // menu as the current screen underneath us, ready for the loading fade to reveal.
        if (!this.reloadFinished && this.reload.isComplete()) {
            this.reloadFinished = true;
            try {
                this.reload.throwException();
                this.exceptionHandler.accept(Optional.empty());
            } catch (Throwable throwable) {
                this.exceptionHandler.accept(Optional.of(throwable));
            }
        }

        if (BootSequence.render(context, this.client, this.reloadFinished, this.reload.getProgress())) {
            // Boot is done: start the menu theme exactly as the menu becomes visible, then step aside.
            MenuMusic.ensurePlaying();
            this.client.setOverlay(null);
        }
    }

    @Override
    public boolean pausesGame() {
        return true;
    }
}
