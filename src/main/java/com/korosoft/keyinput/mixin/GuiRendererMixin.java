package com.korosoft.keyinput.mixin;

import com.korosoft.keyinput.AccessoryPortrait;
import com.korosoft.keyinput.EmfGuiGate;
import com.korosoft.keyinput.ScreenLayout;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.screen.Screen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hosts {@link EmfGuiGate} at the exact spot in the frame where it must run.
 *
 * <p>{@code GuiRenderer.render} is the retained-GUI flush: EMF's {@code Mixin_GuiEntityTester}
 * sets {@code EMFAnimationEntityContext.setIsInGui = true} at its HEAD and false at its TAIL, and
 * the call to {@code prepare()} inside it is what renders every queued GUI entity to texture —
 * evaluating EMF animations (and thus {@code is_in_gui}) along the way. Injecting AT the
 * {@code prepare()} call site is therefore guaranteed to run after EMF's HEAD write and before any
 * animation is evaluated, with no dependence on mixin priority ordering at HEAD.
 *
 * <p>Timing of {@code MENU_ACTIVE}: screens render (and {@code HandledScreenMixin} refreshes the
 * flag) earlier in the same {@code GameRenderer.render} pass than this flush, so the value read
 * here is this frame's truth. The extra {@code instanceof HandledScreen} check clears staleness
 * for the frames where no container screen is open at all (nothing refreshes {@code MENU_ACTIVE}
 * then).
 *
 * <p>This targets a VANILLA class on purpose: it always applies (and fails loudly at launch if
 * Mojang reshapes the method), unlike a {@code @Pseudo} mixin on EMF's own classes which is
 * skipped silently. The EMF side of the gate stays soft via reflection in {@link EmfGuiGate}.
 */
@Mixin(GuiRenderer.class)
public class GuiRendererMixin {

    @Inject(
            method = "render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/render/GuiRenderer;prepare()V"
            )
    )
    private void keyinput$gateEmfInGui(CallbackInfo ci) {
        // Resolve LIVE from the current screen, not the MENU_ACTIVE flag. The flag is refreshed only
        // while a HandledScreen renders and stays stale-true once our menu has been opened, so the
        // vanilla inventory (also a HandledScreen) would keep the pose. Asking "is the screen open
        // right now our accessories layout?" by title has no state to go stale.
        Screen screen = MinecraftClient.getInstance().currentScreen;
        ScreenLayout layout = screen == null ? null : ScreenLayout.forTitle(screen.getTitle().getString());
        boolean menuActive = layout != null && layout.showPortrait();
        // Keep the flag honest too, so the second-layer isInGui() mixin sees the same live truth.
        AccessoryPortrait.MENU_ACTIVE = menuActive;
        EmfGuiGate.gate(menuActive);
    }
}
