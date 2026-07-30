package com.korosoft.keyinput.mixin;

import com.korosoft.keyinput.ScreenLayout;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.client.gui.screen.ingame.ForgingScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.ScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The anvil half of the custom-screen engine.
 *
 * Two things vanilla does that a custom screen has to undo:
 *
 * 1. ForgingScreen.init() runs {@code super.init()} and only THEN {@code setup()}, which is
 *    where nameField is created. Positioning the field from HandledScreen.init would run
 *    against a null field, so it happens here instead.
 *
 * 2. AnvilScreen.onSlotUpdate does {@code nameField.setEditable(!stack.isEmpty())} — the
 *    rename box is dead unless slot 0 holds an item. With a dead box, every keystroke falls
 *    through to HandledScreen.keyPressed, where the inventory key (E by default) CLOSES the
 *    screen. That is why typing used to shut the menu. The server now parks a throwaway item
 *    in slot 0 to wake the box up, and we blank the name it would otherwise inherit from it.
 */
@Mixin(AnvilScreen.class)
public abstract class AnvilScreenMixin extends ForgingScreen<AnvilScreenHandler> {

    @Shadow
    private TextFieldWidget nameField;

    protected AnvilScreenMixin() {
        super(null, null, null, null);
    }

    @Unique
    private ScreenLayout keyinput$layout() {
        return ScreenLayout.forTitle(this.getTitle().getString());
    }

    /**
     * AnvilScreen OVERRIDES drawForeground, so cancelling it on HandledScreen never runs for
     * an anvil — which is why the vanilla title and the "Cost: 1" level label kept showing
     * through the panel.
     */
    @Inject(method = "drawForeground", at = @At("HEAD"), cancellable = true)
    private void keyinput$hideAnvilLabels(DrawContext context, int mouseX, int mouseY,
                                          CallbackInfo ci) {
        ScreenLayout l = keyinput$layout();
        if (l != null && l.hideLabels()) {
            ci.cancel();
        }
    }

    /** setup() is where nameField exists and x/y are already resolved. */
    @Inject(method = "setup", at = @At("TAIL"))
    private void keyinput$placeField(CallbackInfo ci) {
        ScreenLayout l = keyinput$layout();
        if (l == null || !l.hasField() || this.nameField == null) {
            return;
        }
        this.nameField.setX(this.x + l.anchorX() + l.fieldX());
        this.nameField.setY(this.y + l.anchorY() + l.fieldY());
        this.nameField.setWidth(l.fieldW());
        this.nameField.setHeight(l.fieldH());
        this.nameField.setDrawsBackground(false);   // the panel art already draws the field
    }

    /**
     * Vanilla fills the box with the name of whatever sits in slot 0. Since slot 0 only holds
     * a throwaway item that exists to keep the box alive, the player must not see its name.
     */
    @Inject(method = "onSlotUpdate", at = @At("TAIL"))
    private void keyinput$blankField(ScreenHandler handler, int slotId, ItemStack stack,
                                     CallbackInfo ci) {
        if (slotId != 0 || this.nameField == null) {
            return;
        }
        ScreenLayout l = keyinput$layout();
        if (l == null || !l.hasField()) {
            return;
        }
        this.nameField.setText("");
        this.nameField.setEditable(true);
        this.setFocused(this.nameField);
    }
}
