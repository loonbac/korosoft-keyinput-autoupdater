package com.korosoft.keyinput.mixin;

import com.korosoft.keyinput.BootSequence;
import com.korosoft.keyinput.Cutscene;
import com.korosoft.keyinput.PingController;
import com.korosoft.keyinput.ScrollPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.input.MouseInput;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The other half of the cinematic's input lock: mouse look. Freezing the player's yaw is not only
 * about taking control away — the camera orbit is expressed as an offset from that yaw, so a view
 * that could still move would drag the orbit around with it.
 *
 * <p>Cancels Mouse#updateMouse, the single place the accumulated cursor delta is turned into
 * {@code player.changeLookDirection(...)}, so nothing else the mouse does (screens, clicks,
 * scrolling) is touched. Safe to cancel outright because Mouse#tick zeroes cursorDeltaX/cursorDeltaY
 * unconditionally after this call, not inside it: the delta accumulated while locked is discarded
 * rather than banked, so releasing the lock cannot snap the view around.
 *
 * <p>Derived from {@link Cutscene#isActive()} per frame, no state of its own — a lock that could
 * survive its owner would leave the player unable to look around until they relogged.
 */
@Mixin(Mouse.class)
public class MouseMixin {

    @Inject(method = "updateMouse(D)V", at = @At("HEAD"), cancellable = true)
    private void keyinput$lockLookDuringCutscene(double timeDelta, CallbackInfo ci) {
        if (Cutscene.isActive()) {
            ci.cancel();
        }
    }

    /**
     * Feeds the boot sequence its click. The loading splash is an {@code Overlay}, which receives no
     * input of its own, so the "click to advance the disclaimer" gesture is caught here at the raw
     * GLFW callback — the only place a click exists before there is any screen to hand it to. Only a
     * left-button press while the boot sequence is on screen is consumed as an advance.
     */
    @Inject(method = "onMouseButton(JLnet/minecraft/client/input/MouseInput;I)V", at = @At("HEAD"))
    private void keyinput$bootAdvance(long window, MouseInput input, int action, CallbackInfo ci) {
        if (BootSequence.isActive() && action == GLFW.GLFW_PRESS && input.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            BootSequence.requestAdvance();
        }
        // Ping wheel: middle mouse button queues a ping. Only in-game (no screen open), only on
        // press (not release), and not while the player is typing in chat.
        if (action == GLFW.GLFW_PRESS && input.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null && mc.currentScreen == null) {
                PingController.queuePingAction();
            }
        }
    }

    /**
     * Forwards mouse-wheel scrolls to the server on the "korosoft-core:scroll" channel, as
     * discrete notches (+1 up / -1 down). Only sent while a screen is open (the server uses it
     * for in-GUI cycling, e.g. the backpack furnace upgrade mode); with no screen up there is
     * nothing to cycle, so the packet is skipped to keep the channel quiet.
     */
    @Inject(method = "onMouseScroll(JDD)V", at = @At("HEAD"))
    private void keyinput$sendScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (MinecraftClient.getInstance().currentScreen == null) {
            return;
        }
        int delta = vertical > 0.0 ? 1 : (vertical < 0.0 ? -1 : 0);
        if (delta != 0) {
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new ScrollPayload(delta));
        }
    }
}
