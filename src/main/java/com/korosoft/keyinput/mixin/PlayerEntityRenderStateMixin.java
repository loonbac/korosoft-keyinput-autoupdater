package com.korosoft.keyinput.mixin;

import com.korosoft.keyinput.BackpackRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.UUID;

/**
 * Adds the worn player's UUID to {@code PlayerEntityRenderState} so the backpack render layer can
 * look up which model (if any) that player is wearing. Same pattern as
 * {@code EntityRenderStateMixin}: the renderer reuses one state instance per entity, so the field
 * is overwritten every frame in {@code updateRenderState} (see {@code PlayerBackpackFeatureMixin})
 * and there is no staleness window.
 */
@Mixin(PlayerEntityRenderState.class)
public class PlayerEntityRenderStateMixin implements BackpackRenderState {

    @Unique
    private UUID keyinput$playerUuid;

    @Override
    public UUID keyinput$getPlayerUuid() {
        return keyinput$playerUuid;
    }

    @Override
    public void keyinput$setPlayerUuid(UUID playerUuid) {
        keyinput$playerUuid = playerUuid;
    }
}
