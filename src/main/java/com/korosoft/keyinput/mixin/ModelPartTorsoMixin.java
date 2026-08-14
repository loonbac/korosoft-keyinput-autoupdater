package com.korosoft.keyinput.mixin;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the FINAL model-to-world matrix of the player's torso while the real entity pass
 * renders it, so the backpack can be drawn with the exact same transform the chest plate would
 * get (EMF animations, crouch lean, body turns — everything).
 *
 * <p>{@code applyTransform} is the method every {@code ModelPart} runs to apply its pivot
 * translate + rotations + scale. When the part being transformed IS the body part of the player
 * currently being rendered, the matrix on the stack at the TAIL of {@code applyTransform} is the
 * complete body frame: camera-relative world transform * root chain * body pivot. The world pass
 * then draws the backpack inside that frame, which makes it behave like a chestplate glued to the
 * animated torso instead of a rigid object positioned with a manually replicated transform chain.
 */
@Mixin(ModelPart.class)
public abstract class ModelPartTorsoMixin {

    @Inject(method = "applyTransform(Lnet/minecraft/client/util/math/MatrixStack;)V", at = @At("TAIL"))
    private void keyinput$captureTorsoMatrix(MatrixStack matrices, CallbackInfo ci) {
        // Captura DESACTIVADA (2026-08-09): ver LivingEntityRendererMixin — este cache
        // (TorsoMatrixCache.MATRICES_BY_ENTITY) se llenaba por frame y su unico cleanup
        // vive en BackpackWorldRenderer (deshabilitado). Nadie activo lee estas matrices,
        // asi que la captura solo retenia entidades + matrices sin liberar.
    }
}
