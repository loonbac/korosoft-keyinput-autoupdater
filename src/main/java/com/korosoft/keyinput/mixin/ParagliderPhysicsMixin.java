package com.korosoft.keyinput.mixin;

import com.korosoft.keyinput.ParagliderPayload;
import com.korosoft.keyinput.ParagliderState;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The paraglider physics, ported from the original mod's {@code ClientPlayerMovement} but gated
 * strictly on the server's confirmation: nothing here applies unless
 * {@link ParagliderState#localParagliding()} — which is set ONLY by the S2C broadcast for this
 * player's UUID.
 *
 * <p>Injected at the TAIL of {@code ClientPlayerEntity.tickMovement()}, i.e. right after the whole
 * vanilla movement chain (including {@code travel}) ran and right before
 * {@code ClientPlayerEntity.tick()} sends the position packets — so the clamped velocity is what
 * the server sees.
 *
 * <ul>
 *   <li>Fall tracking for auto-deploy: accumulated {@code prevY - y} while falling, reset when
 *       rising or on the ground (mirrors the original).</li>
 *   <li>Auto-deploy: when the server config allows it and the manual-disable flag is clear, falling
 *       >= 1.45 blocks sends the C2S request (the server confirms via the S2C echo).</li>
 *   <li>While the server confirmed gliding: no fall damage ({@code fallDistance = 0}), vertical
 *       velocity clamped to {@code max(m.y, -0.05)} with horizontal untouched, and sprinting forced
 *       on (the original's sprint = gliding animation and air-speed wiring).</li>
 *   <li>Landing while gliding sends the C2S stop request (auto-stop on ground).</li>
 * </ul>
 */
@Mixin(ClientPlayerEntity.class)
public class ParagliderPhysicsMixin {

    private static final double MIN_GLIDE_FALL_SPEED = -0.05D;

    /** Auto-deploy triggers after this accumulated fall, like the original mod. */
    private static final double AUTO_DEPLOY_FALL_DISTANCE = 1.45D;

    @Inject(method = "tickMovement()V", at = @At("TAIL"))
    private void keyinput$paragliderPhysics(CallbackInfo ci) {
        ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;

        boolean onGround = player.isOnGround();
        double y = player.getY();
        double prevY = ParagliderState.getPrevY();
        if (!onGround && y <= prevY) {
            ParagliderState.addAccumulatedFallDistance(prevY - y);
        } else {
            ParagliderState.resetAccumulatedFallDistance();
        }
        ParagliderState.setPrevY(y);

        // landing re-arms auto-deploy (the original resets its auto flag on ground)
        if (onGround) {
            ParagliderState.setAutoDeployDisabled(false);
            ParagliderState.clearAutoDeployRequest();
        }

        // auto-deploy: the server config says yes, no manual disable happened, not already gliding,
        // airborne and the fall has accumulated enough. The client only ASKS — the server confirms.
        // One request per fall: if the server denies it (cooldown etc.) the request is not re-sent
        // until the S2C echo, a landing or a manual toggle clears it.
        if (ParagliderState.canUse() && ParagliderState.autoDeploy()
                && !ParagliderState.autoDeployDisabled() && !ParagliderState.localParagliding()
                && !onGround
                && ParagliderState.getAccumulatedFallDistance() >= AUTO_DEPLOY_FALL_DISTANCE
                && !ParagliderState.isAutoDeployRequested()) {
            ParagliderState.markAutoDeployRequested();
            ClientPlayNetworking.send(new ParagliderPayload(true));
        }

        if (ParagliderState.localParagliding()) {
            if (onGround) {
                // auto stop on landing — same request channel as the manual toggle
                ClientPlayNetworking.send(new ParagliderPayload(false));
            } else {
                Vec3d velocity = player.getVelocity();
                if (velocity.y < MIN_GLIDE_FALL_SPEED) {
                    player.setVelocity(velocity.x, MIN_GLIDE_FALL_SPEED, velocity.z);
                }
                player.fallDistance = 0.0F;
                player.setSprinting(true);
            }
        }
    }
}
