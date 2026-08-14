package com.korosoft.keyinput.mixin;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.item.model.BasicItemModel;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.Function;

/**
 * Exposes the two atlas render-layer getters on {@link BasicItemModel} so the backpack layer can
 * force the layer whose atlas matches the model's actual sprite atlas. A mismatch (model baked
 * with the items atlas, layer binding the blocks atlas — or vice versa) renders the quads as
 * solid white.
 */
@Mixin(BasicItemModel.class)
public interface BasicItemModelAccessor {

    @Accessor("ITEMS_ATLAS_RENDER_LAYER_GETTER")
    static Function<ItemStack, RenderLayer> keyinput$getItemsAtlasGetter() {
        throw new AssertionError();
    }

    @Accessor("BLOCKS_ATLAS_RENDER_LAYER_GETTER")
    static Function<ItemStack, RenderLayer> keyinput$getBlocksAtlasGetter() {
        throw new AssertionError();
    }
}
