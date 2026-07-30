package com.korosoft.keyinput.mixin;

import com.korosoft.keyinput.NameTagConfig;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lets the server scale and toggle the background of the belowname label — the killtag line the
 * TAB plugin writes under player nametags (see {@code LivingEntityRendererMixin}'s class doc for
 * how that line reaches the render state at all) — independently of the main nametag sitting right
 * above it.
 *
 * <p>{@code PlayerEntityRenderer#renderLabelIfPresent} calls
 * {@code OrderedRenderCommandQueue#submitLabel} twice in a row: once for {@code playerName} (the
 * belowname line, ordinal 0 below) and once for {@code displayName} (the main nametag, ordinal 1),
 * both funnelled through the exact same {@code LabelCommandRenderer$Commands#add} — see that
 * mixin's class doc for where the scale is actually applied. Neither call carries any flag saying
 * which label it is, so this mixin raises one around ONLY the first (belowname) call, scoped to
 * {@link NameTagConfig#startRenderingBelowName()}/{@link NameTagConfig#stopRenderingBelowName()}.
 *
 * <p>Targets the overload typed with {@code PlayerEntityRenderState} explicitly (full descriptor
 * below), never the generic {@code EntityRenderState} bridge that sits alongside it — the bridge
 * just delegates to this one, so injecting into it too would double the wrap.
 *
 * <p>The flag is a plain (non-volatile) static boolean on {@link NameTagConfig}: this whole
 * sequence — raise, call through, lower — happens on the render thread, back to back, within a
 * single frame, and {@code add()} never re-enters this call, so there is nothing to race.
 *
 * <p>The lower happens in a {@code finally}, not just after the call: an exception thrown out of
 * {@code submitLabel} (or anything it calls into) must never leave the flag stuck raised, which
 * would go on to scale the main nametag as well, silently, until the next belowname call happened
 * to clear it again.
 */
@Mixin(PlayerEntityRenderer.class)
public class PlayerEntityRendererMixin {

    // the real method; the (EntityRenderState, ...) overload alongside it is the generic bridge
    private static final String RENDER_LABEL_METHOD =
            "renderLabelIfPresent(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;"
                    + "Lnet/minecraft/client/util/math/MatrixStack;"
                    + "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;"
                    + "Lnet/minecraft/client/render/state/CameraRenderState;)V";

    private static final String SUBMIT_LABEL_TARGET =
            "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;submitLabel("
                    + "Lnet/minecraft/client/util/math/MatrixStack;"
                    + "Lnet/minecraft/util/math/Vec3d;"
                    + "I"
                    + "Lnet/minecraft/text/Text;"
                    + "Z"
                    + "I"
                    + "D"
                    + "Lnet/minecraft/client/render/state/CameraRenderState;)V";

    @WrapOperation(method = RENDER_LABEL_METHOD, at = @At(value = "INVOKE", target = SUBMIT_LABEL_TARGET, ordinal = 0))
    private void keyinput$scaleBelowNameSubmit(OrderedRenderCommandQueue queue, MatrixStack matrices, Vec3d pos,
                                                int verticalOffset, Text text, boolean seeThrough, int light,
                                                double squaredDistanceToCamera, CameraRenderState cameraRenderState,
                                                Operation<Void> original) {
        // Raised unconditionally, even at vanilla-equivalent tuning (scale 1.0, background on):
        // the flag also gates the label background now (see LabelCommandRendererCommandsMixin), so
        // skipping it here on the "nothing to do" case would also skip the background check there,
        // and the belowname label would silently inherit whatever the main nametag ends up with.
        NameTagConfig.startRenderingBelowName();
        try {
            original.call(queue, matrices, pos, verticalOffset, text, seeThrough, light, squaredDistanceToCamera,
                    cameraRenderState);
        } finally {
            NameTagConfig.stopRenderingBelowName();
        }
    }
}
