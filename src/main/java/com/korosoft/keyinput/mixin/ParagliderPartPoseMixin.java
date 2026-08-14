package com.korosoft.keyinput.mixin;

import com.korosoft.keyinput.ParagliderPose;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draw-time backstop for the gliding arm pose on the VANILLA {@code ModelPart.render} path: forces
 * the gliding rotation on the registered arm parts right before {@code ModelPart.render} applies
 * their transform.
 *
 * <p>WHY: EMF renders the player's arms with its own part instances and re-applies the server
 * resource pack's custom animations when the model parts are drawn (deferred submit flush), AFTER
 * our {@code setAngles} / renderer injections. Intercepting the draw path is the last possible
 * point — the rotation is set immediately before the part transform is computed, so nothing can
 * override it afterwards. EMF's own draw methods are covered by {@link ParagliderEmfPartMixin}.
 */
@Mixin(ModelPart.class)
public class ParagliderPartPoseMixin {

    private static final String RENDER_4 =
            "render(Lnet/minecraft/client/util/math/MatrixStack;"
                    + "Lnet/minecraft/client/render/VertexConsumer;II)V";

    private static final String RENDER_5 =
            "render(Lnet/minecraft/client/util/math/MatrixStack;"
                    + "Lnet/minecraft/client/render/VertexConsumer;III)V";

    @Inject(method = RENDER_4, at = @At("HEAD"))
    private void keyinput$glidingArmPose4(MatrixStack matrices, VertexConsumer vertexConsumer,
                                          int light, int overlay, CallbackInfo ci) {
        ParagliderPose.forceIfGlidingArm((ModelPart) (Object) this);
    }

    @Inject(method = RENDER_5, at = @At("HEAD"))
    private void keyinput$glidingArmPose5(MatrixStack matrices, VertexConsumer vertexConsumer,
                                          int light, int overlay, int color, CallbackInfo ci) {
        ParagliderPose.forceIfGlidingArm((ModelPart) (Object) this);
    }
}
