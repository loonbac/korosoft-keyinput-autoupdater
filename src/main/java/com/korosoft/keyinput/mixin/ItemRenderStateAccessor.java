package com.korosoft.keyinput.mixin;

import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.render.model.json.Transformation;
import net.minecraft.client.render.RenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the per-layer render internals the backpack layer needs: the baked {@link RenderLayer}
 * (tells which atlas the model was stitched into), the stored {@link Transformation}, and a way
 * to swap the layer to the atlas that actually holds the model's sprites (fixes the solid-white
 * render caused by an atlas mismatch). Fields are package-private on
 * {@code ItemRenderState.LayerRenderState}.
 */
@Mixin(ItemRenderState.LayerRenderState.class)
public interface ItemRenderStateAccessor {

    @Accessor("renderLayer")
    RenderLayer keyinput$getRenderLayer();

    @Accessor("transform")
    Transformation keyinput$getTransform();

    @Invoker("setRenderLayer")
    void keyinput$setRenderLayer(RenderLayer layer);

    @Accessor("tints")
    int[] keyinput$getTints();
}
