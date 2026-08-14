package com.korosoft.keyinput.mixin;

import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the protected {@code LivingEntityRenderer#addFeature} through an interface so the
 * backpack layer can be appended from {@code PlayerBackpackFeatureMixin} (which targets the
 * subclass — a direct {@code @Shadow} there would fail, because shadows must resolve to members
 * declared in the target class itself, and {@code addFeature} lives in the superclass).
 *
 * <p>The generated invoker lands on {@code LivingEntityRenderer}, so {@code PlayerEntityRenderer}
 * inherits it, and the {@code (LivingEntityRendererInvoker) (Object) this} cast in the constructor
 * injection reaches it at runtime.
 */
@Mixin(LivingEntityRenderer.class)
public interface LivingEntityRendererInvoker {

    @Invoker("addFeature")
    boolean keyinput$invokeAddFeature(FeatureRenderer<?, ?> featureRenderer);
}
