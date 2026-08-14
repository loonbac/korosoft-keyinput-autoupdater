package com.korosoft.keyinput.mixin;

import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LivingEntityRenderer.class)
public interface LivingEntityRendererAccessor {

    /** Exposes {@code getModel()} so the pose can be read after the render has animated it. */
    @Invoker("getModel")
    EntityModel<?> keyinput$invokeGetModel();
}