package com.korosoft.keyinput.mixin;

import com.korosoft.keyinput.Cutscene;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Half of the cinematic's input lock: movement. Wipes what KeyboardInput#tick just read off the
 * keyboard, so the player cannot walk out of their own cutscene.
 *
 * <p>Targets KeyboardInput rather than its Input superclass because KeyboardInput#tick overrides
 * Input#tick outright and never calls super — an injection on the base method would simply never
 * run for the real client input.
 *
 * <p>Both fields have to go: ClientPlayerEntity reads movement from {@code getMovementInput()}
 * (movementVector) but jump/sneak/sprint from {@code playerInput}. Zeroing at TAIL rather than
 * cancelling the whole tick keeps vanilla's own bookkeeping intact and leaves exactly one thing
 * changed. The lock is derived from {@link Cutscene#isActive()} every tick and owns no state, so
 * it cannot outlive the cinematic.
 */
@Mixin(KeyboardInput.class)
public class KeyboardInputMixin {

    @Inject(method = "tick()V", at = @At("TAIL"))
    private void keyinput$lockMovementDuringCutscene(CallbackInfo ci) {
        if (!Cutscene.isActive()) {
            return;
        }

        ((Input) (Object) this).playerInput = PlayerInput.DEFAULT;
        ((InputAccessor) this).keyinput$setMovementVector(Vec2f.ZERO);
    }
}
