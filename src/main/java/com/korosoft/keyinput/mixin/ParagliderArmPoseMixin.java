package com.korosoft.keyinput.mixin;

import com.korosoft.keyinput.ParagliderPose;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the gliding arm pose at the TAIL of {@code PlayerEntityModel.setAngles} with a HIGH
 * priority so it runs AFTER the EMF reapply
 * ({@code Mixin_PlayerModel_ReApplyPoseToExtraCallsMadeByMods}, default priority 1000) which would
 * otherwise restore the custom-model pose over ours.
 *
 * <p>This injection point covers BOTH the full player model (third person + remote players) and the
 * first-person arm model (the {@code HeldItemRenderer} arm model is a {@code PlayerEntityModel}
 * animated through the same {@code setAngles} with the local player's render state) — so the arms
 * stay up even in first person.
 *
 * <p>The draw-time enforcement happens in {@link ParagliderPartPoseMixin} /
 * {@link ParagliderEmfPartMixin}; this mixin only registers/unregisters the arm parts and applies
 * the pose to the model for held-item positioning.
 */
@Mixin(value = PlayerEntityModel.class, priority = 3000)
public class ParagliderArmPoseMixin {

    private static final String SET_ANGLES_METHOD =
            "setAngles(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;)V";

    @Inject(method = SET_ANGLES_METHOD, at = @At("TAIL"))
    private void keyinput$paraglidingArmPose(PlayerEntityRenderState state, CallbackInfo ci) {
        PlayerEntityModel model = (PlayerEntityModel) (Object) this;
        if (!ParagliderPose.applyIfGliding(model, state)) {
            ParagliderPose.unregisterArms(model);
        }
    }
}
