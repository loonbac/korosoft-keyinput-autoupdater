package com.korosoft.keyinput.mixin;

import net.minecraft.client.render.item.ItemRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@code ItemRenderState#getFirstLayer} (private) for the backpack diagnostics.
 */
@Mixin(ItemRenderState.class)
public interface ItemRenderStateInvoker {

    @Invoker("getFirstLayer")
    ItemRenderState.LayerRenderState keyinput$invokeGetFirstLayer();
}
