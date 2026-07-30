package com.korosoft.keyinput.mixin;

import com.korosoft.keyinput.KeyPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks the keyboard callback and forwards key edges to the server.
 * 1.21.11 signature: onKey(long window, int action, KeyInput input).
 *
 * <p>PRESS is sent as the positive GLFW code (unchanged behaviour: parry, accessory keys,
 * etc.). RELEASE is sent as the NEGATED code (e.g. Z press = 90, Z release = -90), so the
 * server can track a key being HELD across its press/release edges — needed for "hold Z to
 * bleed out faster" in remainlight.sk. REPEAT (action 2) is ignored; the server runs its own
 * drain timer between the press and release edges, so it needs only the two edges.
 *
 * <p>The wire stays a single fixed 4-byte int (KeyPayload), so this is fully backward
 * compatible: an old jar that only ever sends positive presses simply never triggers the
 * release-only handlers, and every existing positive-code keybind is untouched. A server that
 * does not care about a given release just ignores the negative code, exactly as it already
 * ignores unhandled positive codes.
 */
@Mixin(Keyboard.class)
public class KeyboardMixin {

    @Inject(method = "onKey", at = @At("HEAD"))
    private void keyinput$onKey(long window, int action, KeyInput input, CallbackInfo ci) {
        // GLFW actions: 0 = RELEASE, 1 = PRESS, 2 = REPEAT.
        if (action != 0 && action != 1) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            return;
        }
        // The no-screen gate applies to PRESSES only, so chat/menu typing never fires a
        // keybind. RELEASE is always forwarded (even if a screen opened mid-hold) so a held
        // key can never get stuck "down" on the server just because the player opened a GUI
        // before letting go.
        if (action == 1 && mc.currentScreen != null) {
            return;
        }
        if (ClientPlayNetworking.canSend(KeyPayload.ID)) {
            int code = action == 1 ? input.key() : -input.key();
            ClientPlayNetworking.send(new KeyPayload(code));
        }
    }
}
