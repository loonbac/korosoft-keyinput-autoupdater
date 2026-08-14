package com.korosoft.keyinput.mixin;

import com.korosoft.keyinput.TorsoPose;
import com.korosoft.keyinput.TorsoPoseCache;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the torso pose exactly as it is being drawn in the real entity pass.
 *
 * <p>TAIL of {@code setAngles(PlayerEntityRenderState)}: by then the model carries the vanilla
 * pose plus whatever EMF re-applied from its animation system (its injected re-apply runs in this
 * same method), so the snapshot is precisely what the renderer is about to draw this frame. The
 * state was already bound to its entity by {@link TorsoPoseCache#bind} during
 * {@code updateRenderState}, which is what lets us attribute the shared model's pose to the right
 * player.
 *
 * <p>GUI renders (inventory screen) go through the same setAngles with their own state and would
 * overwrite the entry with a pose from the still world behind the screen — harmless: the world is
 * frozen while a GUI is open and the next real entity pass re-captures as soon as it closes.
 */
@Mixin(PlayerEntityModel.class)
public class PlayerEntityModelMixin {

    private static final String SET_ANGLES =
            "setAngles(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;)V";

    @Inject(method = SET_ANGLES, at = @At("TAIL"))
    private void keyinput$captureTorsoPose(PlayerEntityRenderState state, CallbackInfo ci) {
        PlayerEntityModel self = (PlayerEntityModel) (Object) this;
        ModelPart body = self.body;
        ModelPart root = self.getRootPart();
        TorsoPoseCache.capture(state, new TorsoPose(
                body.pitch, body.yaw, body.roll,
                body.originX, body.originY, body.originZ,
                root.pitch, root.yaw, root.roll,
                root.originX, root.originY, root.originZ));
    }
}
