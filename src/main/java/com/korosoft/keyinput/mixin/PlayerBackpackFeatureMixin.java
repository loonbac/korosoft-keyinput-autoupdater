package com.korosoft.keyinput.mixin;

import com.korosoft.keyinput.BackpackRenderLayer;
import com.korosoft.keyinput.BackpackRenderState;
import com.korosoft.keyinput.HeadAccessoryRenderLayer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.PlayerLikeEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wires the backpack render layer into the player renderer and stamps each player's UUID onto
 * its render state every frame.
 *
 * <p>Two injections, one class:
 * <ul>
 *   <li>{@code <init>} TAIL — after the vanilla layers are registered, appends the backpack
 *       layer so it draws on top of the body. {@code addFeature} is protected on
 *       {@code LivingEntityRenderer} and the renderer itself implements
 *       {@code FeatureRendererContext<PlayerEntityRenderState, PlayerEntityModel>}, so
 *       {@code this} is both the receiver and the layer's context.</li>
 *   <li>{@code updateRenderState} TAIL — the state instance is reused frame to frame, so the
 *       UUID is (re)written here, before any render reads it. Targets the {@code PlayerLikeEntity}
 *       overload explicitly, exactly like the existing label mixin's descriptor discipline: the
 *       {@code LivingEntity}/{@code Entity} bridges alongside it just delegate to this one.</li>
 * </ul>
 */
@Mixin(PlayerEntityRenderer.class)
public class PlayerBackpackFeatureMixin {

    private static final String UPDATE_RENDER_STATE_METHOD =
            "updateRenderState(Lnet/minecraft/entity/PlayerLikeEntity;"
                    + "Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;F)V";

    @Inject(method = "<init>", at = @At("TAIL"))
    private void keyinput$addBackpackLayer(EntityRendererFactory.Context context, boolean bl, CallbackInfo ci) {
        // addFeature is protected on LivingEntityRenderer and cannot be @Shadow'ed from a mixin
        // targeting the subclass — use the @Invoker accessor instead. The renderer itself IS the
        // FeatureRendererContext the layer needs (LivingEntityRenderer implements it).
        ((LivingEntityRendererInvoker) (Object) this)
                .keyinput$invokeAddFeature(new BackpackRenderLayer((PlayerEntityRenderer<?>) (Object) this));
        ((LivingEntityRendererInvoker) (Object) this)
                .keyinput$invokeAddFeature(new HeadAccessoryRenderLayer((PlayerEntityRenderer<?>) (Object) this));
    }

    @Inject(method = UPDATE_RENDER_STATE_METHOD, at = @At("TAIL"))
    private void keyinput$capturePlayerUuid(PlayerLikeEntity entity, PlayerEntityRenderState state, float tickProgress, CallbackInfo ci) {
        ((BackpackRenderState) state).keyinput$setPlayerUuid(entity.getUuid());
    }
}
