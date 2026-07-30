package com.korosoft.keyinput.mixin;

import net.minecraft.client.gui.screen.ingame.RecipeBookScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Removes the recipe-book toggle button from every recipe screen (survival inventory, crafting
 * table, furnace, smoker, blast furnace) — all of which extend {@link RecipeBookScreen}.
 *
 * <p>The button is added client-side in {@code RecipeBookScreen#addRecipeBook()}, and there is no
 * clientbound packet that lets the server hide it, so this can only be done on the client. Cancelling
 * the whole method is safe: {@code init()} calls {@code recipeBook.initialize(...)} BEFORE
 * {@code addRecipeBook()}, so the widget's geometry is already set up (no NPE in {@code render()} or
 * {@code handledScreenTick()}); this only skips adding the toggle button and registering the widget
 * as a selectable child. With no button to toggle it, {@code recipeBook.isOpen()} stays false
 * forever, so the book never draws and never intercepts clicks. No recipe-book keybind exists, so
 * removing the button removes every route to it — hidden AND unusable in one hook.
 */
@Mixin(RecipeBookScreen.class)
public abstract class RecipeBookScreenMixin {

    @Inject(method = "addRecipeBook", at = @At("HEAD"), cancellable = true)
    private void keyinput$removeRecipeBookButton(CallbackInfo ci) {
        ci.cancel();
    }
}
