package com.korosoft.keyinput.mixin;

import com.korosoft.keyinput.ParagliderPose;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Registers/unregisters the gliding arm parts for the full third-person/remote render: at the
 * INVOKE of {@code EntityModel.setAngles} with {@code shift = AFTER} inside
 * {@code LivingEntityRenderer.render} every setAngles pass (vanilla + EMF reapply + our
 * high-priority tail) has finished, and the point still runs before the feature layers and before
 * the deferred {@code submitModel} queue draws the model.
 *
 * <p>HIGH priority (3000, like {@link ParagliderArmPoseMixin}): EMF's {@code falseAnimation} also
 * injects at this exact point (default priority 1000) and its {@code triggerManualAnimation}
 * ({@code animation.run()} + {@code checkArmOverrides}) re-applies the server resource pack's
 * custom player animation OVER the model — with a higher priority this mixin runs AFTER it.
 */
@Mixin(value = LivingEntityRenderer.class, priority = 3000)
public class ParagliderRenderPoseMixin {

    private static final String RENDER_METHOD =
            "render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;"
                    + "Lnet/minecraft/client/util/math/MatrixStack;"
                    + "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;"
                    + "Lnet/minecraft/client/render/state/CameraRenderState;)V";

    private static final String SET_ANGLES_TARGET =
            "Lnet/minecraft/client/render/entity/model/EntityModel;setAngles(Ljava/lang/Object;)V";

    @Inject(method = RENDER_METHOD, at = @At(value = "INVOKE", target = SET_ANGLES_TARGET, shift = At.Shift.AFTER))
    private void keyinput$paraglidingRenderPose(LivingEntityRenderState state,
                                                MatrixStack matrices,
                                                OrderedRenderCommandQueue queue,
                                                CameraRenderState cameraState,
                                                CallbackInfo ci) {
        if (!(state instanceof PlayerEntityRenderState playerState)) {
            return;
        }
        if (!(((LivingEntityRendererAccessor) (Object) this).keyinput$invokeGetModel()
                instanceof PlayerEntityModel playerModel)) {
            return;
        }
        if (!ParagliderPose.applyIfGliding(playerModel, playerState)) {
            ParagliderPose.unregisterArms(playerModel);
        }
    }
}
