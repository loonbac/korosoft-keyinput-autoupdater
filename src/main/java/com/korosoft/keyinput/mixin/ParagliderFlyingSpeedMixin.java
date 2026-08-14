package com.korosoft.keyinput.mixin;

import com.korosoft.keyinput.ParagliderState;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the gliding player's horizontal momentum: overrides the air speed while the server
 * confirmed the local glide. This is the Yarn 1.21.11 analog of the original mod's
 * {@code Player.getFlyingSpeed()} override: {@code PlayerEntity.getOffGroundSpeed()} returns the
 * sprinting air speed {@code 0.025999999f} (or {@code 0.02f} when not sprinting) and feeds
 * {@code LivingEntity.getMovementSpeed(float)} -> {@code updateVelocity} in the airborne travel
 * path. While gliding it returns {@code 0.026f * speed} with the server-provided multiplier, so the
 * sprinting flag forced by the physics mixin no longer matters and the server's config rules.
 */
@Mixin(PlayerEntity.class)
public class ParagliderFlyingSpeedMixin {

    /** The original's sprinting air speed constant. */
    private static final float DEFAULT_SPRINTING_AIR_SPEED = 0.026F;

    @Inject(method = "getOffGroundSpeed()F", at = @At("HEAD"), cancellable = true)
    private void keyinput$paragliderAirSpeed(CallbackInfoReturnable<Float> cir) {
        if (ParagliderState.localParagliding()) {
            cir.setReturnValue(DEFAULT_SPRINTING_AIR_SPEED * ParagliderState.speed());
        }
    }
}
