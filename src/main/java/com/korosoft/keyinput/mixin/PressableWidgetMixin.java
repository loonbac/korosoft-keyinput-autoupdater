package com.korosoft.keyinput.mixin;

import com.korosoft.keyinput.CustomButtonRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.PressableWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reskins every button in the game to the KoroSoft look. {@code drawButton} is the shared method
 * that paints the vanilla stone-textured background for all button-like widgets — plain buttons,
 * the "Done" buttons, the cycling toggles on the options screen — so cancelling it here and drawing
 * our own panel instead reskins them all at once. Only the background is replaced; each widget still
 * draws its own label afterwards, so nothing about text or layout changes.
 */
@Mixin(PressableWidget.class)
public class PressableWidgetMixin {

    @Inject(method = "drawButton(Lnet/minecraft/client/gui/DrawContext;)V", at = @At("HEAD"), cancellable = true)
    private void keyinput$customButton(DrawContext ctx, CallbackInfo ci) {
        ClickableWidget self = (ClickableWidget) (Object) this;
        CustomButtonRenderer.draw(ctx, self.getX(), self.getY(), self.getWidth(), self.getHeight(),
                self.active, self.isSelected());
        ci.cancel();
    }
}
