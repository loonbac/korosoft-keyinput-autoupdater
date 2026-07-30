package com.korosoft.keyinput.mixin;

import com.korosoft.keyinput.MainMenuScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Swaps the vanilla title screen for the KoroSoft launcher. Every time the game would show the
 * main menu (with no world loaded) this redirects to {@link MainMenuScreen}. The boot disclaimer is
 * no longer triggered here — it now runs during the loading splash ({@code SplashOverlayMixin} →
 * {@code BootSequence}) — so this is just the menu swap.
 *
 * <p>Guarded on {@code world == null} so it only fires for the actual main-menu title screen, never
 * for a title screen constructed as another screen's parent while in a world. {@code MainMenuScreen}
 * is not a {@code TitleScreen}, so this never recurses.
 */
@Mixin(TitleScreen.class)
public class TitleScreenMixin {

    @Inject(method = "init", at = @At("HEAD"), cancellable = true)
    private void keyinput$redirectToLauncher(CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null) {
            return;
        }
        client.setScreen(new MainMenuScreen());
        ci.cancel();
    }
}
