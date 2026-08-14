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
 * Draw-time backstop for the gliding arm pose targeting EMF's own part render path.
 *
 * <p>EMF's {@code EMFModelPart} (superclass of the player-arm parts it draws) does NOT render
 * through vanilla {@code ModelPart.render} — it uses its own submit pipeline
 * ({@code renderLikeVanilla} / {@code renderLikeETF} / {@code renderBoxes} /
 * {@code renderBoxesNoChildren} / {@code renderWithTextureOverride}), so the vanilla
 * {@link ParagliderPartPoseMixin} never fires for those parts. These HEAD injections force the
 * gliding rotation on the registered arm parts right before any of EMF's draw paths transforms the
 * part. The mixin targets EMF by class name (optional dependency: when EMF is absent the target is
 * silently skipped).
 */
@Mixin(targets = "traben.entity_model_features.models.parts.EMFModelPart")
public class ParagliderEmfPartMixin {

    @Inject(method = "renderLikeVanilla", at = @At("HEAD"))
    private void keyinput$glidingArmLikeVanilla(MatrixStack matrices, VertexConsumer vertexConsumer,
                                                int light, int overlay, int color, CallbackInfo ci) {
        ParagliderPose.forceIfGlidingArm((ModelPart) (Object) this);
    }

    @Inject(method = "renderLikeETF", at = @At("HEAD"))
    private void keyinput$glidingArmLikeEtf(MatrixStack matrices, VertexConsumer vertexConsumer,
                                            int light, int overlay, int color, CallbackInfo ci) {
        ParagliderPose.forceIfGlidingArm((ModelPart) (Object) this);
    }

    @Inject(method = "renderWithTextureOverride", at = @At("HEAD"))
    private void keyinput$glidingArmWithOverride(MatrixStack matrices, VertexConsumer vertexConsumer,
                                                 int light, int overlay, int color, CallbackInfo ci) {
        ParagliderPose.forceIfGlidingArm((ModelPart) (Object) this);
    }

    @Inject(method = "renderBoxes", at = @At("HEAD"))
    private void keyinput$glidingArmBoxes(MatrixStack matrices, VertexConsumer vertexConsumer,
                                          CallbackInfo ci) {
        ParagliderPose.forceIfGlidingArm((ModelPart) (Object) this);
    }

    @Inject(method = "renderBoxesNoChildren", at = @At("HEAD"))
    private void keyinput$glidingArmBoxesNoChildren(MatrixStack matrices, VertexConsumer vertexConsumer,
                                                    float scale, CallbackInfo ci) {
        ParagliderPose.forceIfGlidingArm((ModelPart) (Object) this);
    }
}
