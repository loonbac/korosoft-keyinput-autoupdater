package com.korosoft.keyinput.mixin;

import com.korosoft.keyinput.MenuBackground;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the vanilla menu background — the blurred world (or the dirt/panorama when there is no
 * world) — with the KoroSoft splash art, so every menu screen matches the launcher instead of
 * showing the game behind it. This is the background the options screen and pause menu draw via
 * {@code Screen#renderBackground}.
 *
 * <p>Only applied on the main-menu side — when there is no world loaded. Inside the server the pause
 * and options screens keep the vanilla blurred-world background, so pressing Escape mid-game shows
 * the game, not the launcher art. Container screens ({@link HandledScreen}) are always left alone
 * (chests, the anvil, the mod's own custom GUI panels), and screens that draw their own background
 * (the launcher menu) never reach this injection.
 */
@Mixin(Screen.class)
public class MenuBackgroundMixin {

    @Inject(method = "renderBackground(Lnet/minecraft/client/gui/DrawContext;IIF)V",
            at = @At("HEAD"), cancellable = true)
    private void keyinput$customMenuBackground(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        // In a world, keep the vanilla look — the player wants to see the game behind the pause menu.
        if (MinecraftClient.getInstance().world != null) {
            return;
        }
        Screen self = (Screen) (Object) this;
        if (self instanceof HandledScreen) {
            return;
        }
        MenuBackground.drawCover(ctx, self.width, self.height);
        MenuBackground.dim(ctx, self.width, self.height, 110);
        ci.cancel();
    }
}
