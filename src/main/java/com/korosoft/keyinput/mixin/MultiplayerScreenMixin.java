package com.korosoft.keyinput.mixin;

import com.korosoft.keyinput.MainMenuScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sends the multiplayer server list straight to the KoroSoft launcher. This client only ever
 * connects to one place, so the vanilla server-list screen is never wanted — the case that reaches
 * it is leaving the server (its "back" target is this list). Redirecting it here drops the player
 * back on the launcher menu instead.
 *
 * <p><b>Why deferred instead of cancelling init:</b> cancelling {@code init} leaves the screen with
 * null fields, and it still gets ticked once before the swap — {@code MultiplayerScreen#tick} then
 * NPEs on its (uninitialised) server-list pinger. So init is allowed to run fully, and the swap is
 * scheduled via {@code client.execute(...)} to run on the next tick, outside the re-entrant
 * setScreen/init call chain. The screen is fully valid for the one frame it may exist, and
 * {@link MainMenuScreen} is not a {@code MultiplayerScreen}, so this never recurses.
 */
@Mixin(MultiplayerScreen.class)
public class MultiplayerScreenMixin {

    @Inject(method = "init", at = @At("TAIL"))
    private void keyinput$redirectToLauncher(CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.currentScreen instanceof MultiplayerScreen) {
                client.setScreen(new MainMenuScreen());
            }
        });
    }
}
