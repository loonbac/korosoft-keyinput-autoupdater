package com.korosoft.keyinput.mixin;

import com.korosoft.keyinput.AccessoryPortrait;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * SECOND layer of the {@code is_in_gui} gate: ANDs the return value of EMF's {@code isInGui()}
 * with {@link AccessoryPortrait#MENU_ACTIVE}.
 *
 * <p>The PRIMARY gate lives in {@code GuiRendererMixin} + {@link com.korosoft.keyinput.EmfGuiGate}:
 * it forces the backing FIELD ({@code EMFAnimationEntityContext.setIsInGui}) to false before the
 * GUI entity-texture pass whenever our accessories screen is not the one rendering. That gate was
 * added because this mixin alone was observed to have NO effect in game — a {@link Pseudo} mixin
 * that does not apply is skipped silently, so it cannot be trusted as the only line of defense.
 * This one is kept as belt-and-suspenders: if it DOES apply it is idempotent with the field gate
 * (same AND), and it additionally covers any hypothetical EMF code path that raises the flag
 * outside {@code GuiRenderer.render}.
 *
 * <p>{@link Pseudo} + a string target make this a soft dependency: if EMF is absent or renames the
 * method the mixin is simply skipped, never a crash. {@code remap = false} because these are EMF's
 * own names, not Minecraft mappings.
 */
@Pseudo
@Mixin(targets = "traben.entity_model_features.models.animation.EMFAnimationEntityContext", remap = false)
public class EmfAnimationContextMixin {

    @ModifyReturnValue(method = "isInGui", at = @At("RETURN"), remap = false)
    private static boolean keyinput$gateInGuiToKoroMenu(boolean original) {
        return original && AccessoryPortrait.MENU_ACTIVE;
    }
}
