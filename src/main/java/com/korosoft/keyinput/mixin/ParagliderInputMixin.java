package com.korosoft.keyinput.mixin;

import com.korosoft.keyinput.ParagliderPayload;
import com.korosoft.keyinput.ParagliderState;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Right-click toggle for the paraglider. The original mod cancels the toggle inside the item's
 * {@code use()} server-side; here the SERVER owns the item logic, so the client cancels the vanilla
 * use instead and just sends the toggle request on the wire.
 *
 * <p>Only fires while {@link ParagliderState#canUse()} — the server has confirmed this player is
 * holding the paraglider — so nothing else that lives on right-click is touched. Cancelling with
 * {@code ActionResult.FAIL} makes {@code MinecraftClient.doItemUse} stop (a FAIL after
 * {@code interactBlock} returns from the method; a FAIL after {@code interactItem} skips the
 * equip-progress reset and the hand loop moves on — the OFF_HAND guard mirrors the original item's
 * {@code hand == OFF_HAND -> PASS} so a full off-hand item use is never swallowed by the toggle).
 */
@Mixin(ClientPlayerInteractionManager.class)
public class ParagliderInputMixin {

    private static final String INTERACT_ITEM =
            "interactItem(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/util/Hand;)Lnet/minecraft/util/ActionResult;";

    private static final String INTERACT_BLOCK =
            "interactBlock(Lnet/minecraft/client/network/ClientPlayerEntity;Lnet/minecraft/util/Hand;Lnet/minecraft/util/hit/BlockHitResult;)Lnet/minecraft/util/ActionResult;";

    @Inject(method = INTERACT_ITEM, at = @At("HEAD"), cancellable = true)
    private void keyinput$paragliderToggleItem(PlayerEntity player, Hand hand,
                                               CallbackInfoReturnable<ActionResult> cir) {
        if (!ParagliderState.canUse()) {
            return;
        }
        if (hand == Hand.MAIN_HAND) {
            ParagliderState.setAutoDeployDisabled(true);
            ParagliderState.clearAutoDeployRequest();
            ClientPlayNetworking.send(new ParagliderPayload(!ParagliderState.localParagliding()));
        }
        // swallow the main-hand use; a non-empty off-hand is also skipped so the toggle click does
        // not double-fire on the hand loop (same net effect as the original's CONSUME)
        cir.setReturnValue(ActionResult.FAIL);
    }

    @Inject(method = INTERACT_BLOCK, at = @At("HEAD"), cancellable = true)
    private void keyinput$paragliderToggleBlock(ClientPlayerEntity player, Hand hand,
                                                BlockHitResult hitResult,
                                                CallbackInfoReturnable<ActionResult> cir) {
        if (!ParagliderState.canUse()) {
            return;
        }
        if (hand == Hand.MAIN_HAND) {
            ParagliderState.setAutoDeployDisabled(true);
            ParagliderState.clearAutoDeployRequest();
            ClientPlayNetworking.send(new ParagliderPayload(!ParagliderState.localParagliding()));
        }
        cir.setReturnValue(ActionResult.FAIL);
    }
}
