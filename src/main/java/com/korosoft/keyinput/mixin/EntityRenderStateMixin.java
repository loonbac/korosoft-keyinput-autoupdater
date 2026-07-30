package com.korosoft.keyinput.mixin;

import com.korosoft.keyinput.ForceBlackState;
import net.minecraft.client.render.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Adds a boolean to every {@code EntityRenderState} so the parry silhouette can mark the single
 * state instance it is about to draw as "pure black" (see {@link ForceBlackState}). The flag must
 * live on the state and not in a static field — see that interface's doc.
 *
 * <p>Mixed onto the base {@code EntityRenderState}, not {@code LivingEntityRenderState}, so the cast
 * in the color hook is always valid regardless of the concrete render-state subclass the entity
 * happens to use.
 */
@Mixin(EntityRenderState.class)
public class EntityRenderStateMixin implements ForceBlackState {

    @Unique
    private boolean keyinput$forceBlack;

    @Override
    public boolean keyinput$isForceBlack() {
        return keyinput$forceBlack;
    }

    @Override
    public void keyinput$setForceBlack(boolean forceBlack) {
        keyinput$forceBlack = forceBlack;
    }
}
