package com.korosoft.keyinput.mixin;

import com.korosoft.keyinput.ConnectSequence;
import com.korosoft.keyinput.KoroBootOverlay;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Overlay;
import net.minecraft.client.gui.screen.SplashOverlay;
import net.minecraft.client.option.GameOptions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Swaps the vanilla boot splash for {@link KoroBootOverlay} at the one spot the client installs it
 * (the {@code setOverlay(new SplashOverlay(...))} call in the constructor). The vanilla instance is
 * still built, but instead of showing it, its reload and completion callback are salvaged (via
 * {@link SplashOverlayAccessor}) and handed to our overlay, which is what actually goes on screen.
 *
 * <p>This is the robust replacement for hijacking {@code SplashOverlay#render}: the boot visuals now
 * live on a type no other mod mixes into, so performance mods that shorten or dismiss the vanilla
 * splash cannot cut the disclaimer short.
 *
 * <p>The second redirect below does the same salvage trick for the <em>other</em> place vanilla
 * shows a {@code SplashOverlay}: the resource reload {@code MinecraftClient.reloadResources()}
 * triggers after connecting to a server (to load the mandatory pack). When a KoroSoft connect flow
 * is in progress ({@link ConnectSequence#isActive()}), that splash is suppressed entirely — the
 * reload and its completion callback are handed to {@code com.korosoft.keyinput.ConnectOverlay}
 * (already on screen from {@code MainMenuScreen.startGame()}) instead, so no red splash ever flashes
 * between login and the world loading.
 */
@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

    @Shadow
    @Final
    public GameOptions options;

    /**
     * Skips the accessibility/narrator onboarding screen that vanilla shows on first launch.
     *
     * <p>{@code createInitScreens} queues an {@link net.minecraft.client.gui.screen.AccessibilityOnboardingScreen}
     * whenever {@code options.onboardAccessibility} is set — the default for a fresh install. Forcing
     * the flag off at HEAD, before that check runs, drops the screen from the startup list so the
     * client goes straight to our boot flow. {@code MinecraftClient} calls {@code options.write()}
     * right after init, so this also persists the choice to {@code options.txt} — the popup never
     * comes back on later launches either. (The {@code SharedConstants.FORCE_ONBOARDING_SCREEN} dev
     * flag is false in a released client, so this covers every real player.)
     */
    @Inject(method = "createInitScreens", at = @At("HEAD"))
    private void keyinput$skipAccessibilityOnboarding(CallbackInfoReturnable<Boolean> cir) {
        this.options.onboardAccessibility = false;
    }

    @Redirect(
            method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/MinecraftClient;setOverlay(Lnet/minecraft/client/gui/screen/Overlay;)V"))
    private void keyinput$replaceBootOverlay(MinecraftClient self, Overlay overlay) {
        if (overlay instanceof SplashOverlay) {
            SplashOverlayAccessor salvage = (SplashOverlayAccessor) overlay;
            self.setOverlay(new KoroBootOverlay(self, salvage.getReload(), salvage.getExceptionHandler()));
        } else {
            self.setOverlay(overlay);
        }
    }

    @Redirect(
            method = "reloadResources(ZLnet/minecraft/client/MinecraftClient$LoadingContext;)Ljava/util/concurrent/CompletableFuture;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/MinecraftClient;setOverlay(Lnet/minecraft/client/gui/screen/Overlay;)V"))
    private void keyinput$suppressConnectReloadSplash(MinecraftClient self, Overlay overlay) {
        if (ConnectSequence.isActive() && overlay instanceof SplashOverlay splash) {
            SplashOverlayAccessor salvage = (SplashOverlayAccessor) splash;
            ConnectSequence.onReloadStart(salvage.getReload(), salvage.getExceptionHandler());
            // Do NOT install the red SplashOverlay: ConnectOverlay stays on top and drains this
            // ResourceReload's progress and runs its completion itself (see ConnectOverlay.advanceReload).
            return;
        }
        self.setOverlay(overlay); // boot-independent reloads (F3+T, options) keep vanilla behavior
    }
}
