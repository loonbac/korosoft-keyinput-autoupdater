package com.korosoft.keyinput.mixin;

import com.korosoft.keyinput.NameTagConfig;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Applies {@link NameTagConfig#getBelowNameScale()} and {@link NameTagConfig#isBelowNameBackground()}
 * to the label currently being built, the moment the flag {@code PlayerEntityRendererMixin} raises
 * around the belowname {@code submitLabel} call says this IS that label.
 *
 * <p>Targets {@code LabelCommandRenderer$Commands#add}, the single method both the belowname line
 * and the main nametag funnel through — {@code LabelCommand} itself carries no discriminator for
 * which one it is, which is exactly why the flag exists at all (see
 * {@code PlayerEntityRendererMixin}'s class doc).
 *
 * <p>{@code add()}'s only {@code MatrixStack.scale(FFF)} call sits right after the billboard
 * rotation ({@code matrices.multiply(cameraState.orientation)}) and right before the matrix
 * snapshot used to build the {@code LabelCommand} — i.e. after the position translate, so
 * touching it here can never shift the label's anchor point the way pre-scaling the caller's
 * {@code MatrixStack} before {@code submitLabel} would (that translate happens AFTER this method
 * is entered, so a pre-applied scale would also scale that translation vector). There is also no
 * rotation/shear beyond the billboard orientation left after this point, so multiplying all three
 * axes uniformly is safe.
 *
 * <p>The Y argument is {@code -0.025f} (negative — it flips the label right-side-up in this
 * billboarded space). Multiplying by a positive scale factor preserves that sign, which is exactly
 * what is wanted: a bigger scale should make the label bigger, never flip it.
 */
@Mixin(targets = "net.minecraft.client.render.command.LabelCommandRenderer$Commands")
public class LabelCommandRendererCommandsMixin {

    private static final String ADD_METHOD =
            "add(Lnet/minecraft/client/util/math/MatrixStack;"
                    + "Lnet/minecraft/util/math/Vec3d;"
                    + "I"
                    + "Lnet/minecraft/text/Text;"
                    + "Z"
                    + "I"
                    + "D"
                    + "Lnet/minecraft/client/render/state/CameraRenderState;)V";

    private static final String SCALE_TARGET = "Lnet/minecraft/client/util/math/MatrixStack;scale(FFF)V";

    private static final String BACKGROUND_OPACITY_TARGET =
            "Lnet/minecraft/client/option/GameOptions;getTextBackgroundOpacity(F)F";

    @ModifyArgs(method = ADD_METHOD, at = @At(value = "INVOKE", target = SCALE_TARGET))
    private void keyinput$scaleBelowNameLabel(Args args) {
        // Nothing to do for the main nametag: the flag is only raised around the belowname call.
        if (!NameTagConfig.isRenderingBelowName()) {
            return;
        }

        float scale = NameTagConfig.getBelowNameScale();
        args.set(0, args.<Float>get(0) * scale);
        args.set(1, args.<Float>get(1) * scale);
        args.set(2, args.<Float>get(2) * scale);
    }

    /**
     * {@code add()} calls {@code GameOptions#getTextBackgroundOpacity} exactly once, and the
     * result (scaled to 0-255, shifted into the alpha byte) is stored in a local that feeds every
     * downstream branch that builds the {@code LabelCommand}'s background color — see this mixin's
     * class doc. Forcing that single call site to 0 for the belowname label therefore zeroes the
     * background's alpha everywhere it is used, without needing to touch each branch separately.
     */
    @ModifyExpressionValue(method = ADD_METHOD, at = @At(value = "INVOKE", target = BACKGROUND_OPACITY_TARGET))
    private float keyinput$hideBelowNameBackground(float originalOpacity) {
        if (NameTagConfig.isRenderingBelowName() && !NameTagConfig.isBelowNameBackground()) {
            return 0.0F;
        }
        return originalOpacity;
    }
}
