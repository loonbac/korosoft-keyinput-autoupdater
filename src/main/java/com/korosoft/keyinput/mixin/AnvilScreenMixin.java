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

    @Unique
    private String keyinput$currentTitle = null;
    @Unique
    private int keyinput$tickerTick = 0;
    @Unique
    private int keyinput$lastOffset = -1;

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
        // The layout resolves HERE (after retitle), which can be later than the slot-0
        // update that vanilla already processed. Blank the field now so the throwaway
        // paper's name ("Papel" or a marker) never shows in the box. If the slot-0 item
        // is a "♪ " now-playing label, show it read-only instead.
        if (this.handler != null && this.handler.getSlot(0) != null) {
            ItemStack stack = this.handler.getSlot(0).getStack();
            if (!stack.isEmpty()) {
                String name = stack.getName().getString();
                if (name.startsWith("♪ Error:")) {
                    this.keyinput$currentTitle = null;
                    this.keyinput$lastOffset = -1;
                    this.nameField.setText(name.substring(9));
                    this.nameField.setEditable(true);
                    this.nameField.setCursorToStart(false);
                } else if (name.startsWith("♪ ")) {
                    this.keyinput$currentTitle = name.substring(2);
                    this.keyinput$tickerTick = 0;
                    this.keyinput$lastOffset = 0;
                    this.nameField.setText(this.keyinput$currentTitle);
                    this.nameField.setEditable(false);
                    this.nameField.setCursorToStart(false);
                } else {
                    this.keyinput$currentTitle = null;
                    this.keyinput$lastOffset = -1;
                    this.nameField.setText("");
                    this.nameField.setEditable(true);
                }
                this.setFocused(this.nameField);
            }
        }
    }

    /**
     * Vanilla fills the box with the name of whatever sits in slot 0. Since slot 0 only holds
     * a throwaway item that exists to keep the box alive, the player must not see its name.
     *
     * EXCEPTION: a slot-0 item whose display name starts with the music marker "♪ " is a
     * radio display label:
     *   - "♪ <título>"        → now playing: shown read-only (cannot edit) with marquee ticker if long
     *   - "♪ Error: <msg>"    → playback error: shown editable, so a click lets the player
     *                           clear it and type a new URL (the server clears the error
     *                           when the text changes).
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
        String name = stack.getName().getString();
        if (name.startsWith("♪ Error:")) {
            this.keyinput$currentTitle = null;
            this.keyinput$lastOffset = -1;
            this.nameField.setText(name.substring(9));
            this.nameField.setEditable(true);
            this.nameField.setCursorToStart(false);
            this.setFocused(this.nameField);
            return;
        }
        if (name.startsWith("♪ ")) {
            String newTitle = name.substring(2);
            if (!newTitle.equals(this.keyinput$currentTitle)) {
                this.keyinput$currentTitle = newTitle;
                this.keyinput$tickerTick = 0;
                this.keyinput$lastOffset = 0;
                this.nameField.setText(newTitle);
                this.nameField.setEditable(false);
                this.nameField.setCursorToStart(false);
            }
            this.setFocused(this.nameField);
            return;
        }
        this.keyinput$currentTitle = null;
        this.keyinput$lastOffset = -1;
        this.nameField.setText("");
        this.nameField.setEditable(true);
        this.setFocused(this.nameField);
    }

    /**
     * Smooth marquee / ping-pong ticker for long song titles on custom screens.
     * When the title exceeds the display width, pauses at the start, scrolls right,
     * pauses at the end, and scrolls back to the left in a continuous loop.
     */
    @Inject(method = "handledScreenTick", at = @At("TAIL"))
    private void keyinput$tickMarquee(CallbackInfo ci) {
        if (this.nameField == null || this.keyinput$currentTitle == null) {
            return;
        }
        ScreenLayout l = keyinput$layout();
        if (l == null || !l.hasField()) {
            return;
        }
        int innerW = this.nameField.getInnerWidth();
        int totalW = this.getTextRenderer().getWidth(this.keyinput$currentTitle);
        if (totalW <= innerW) {
            return;
        }

        int maxOffset = 0;
        for (int i = 0; i < this.keyinput$currentTitle.length(); i++) {
            if (this.getTextRenderer().getWidth(this.keyinput$currentTitle.substring(i)) > innerW) {
                maxOffset = i + 1;
            } else {
                break;
            }
        }
        if (maxOffset <= 0) {
            return;
        }

        this.keyinput$tickerTick++;
        int pauseTicks = 40;     // 2.0s pause at each end
        int stepTicks = 4;       // 200ms per character step
        int travelTicks = maxOffset * stepTicks;
        int cycleTicks = pauseTicks + travelTicks + pauseTicks + travelTicks;
        if (cycleTicks <= 0) {
            return;
        }

        int t = this.keyinput$tickerTick % cycleTicks;
        int offset;
        if (t < pauseTicks) {
            offset = 0;
        } else if (t < pauseTicks + travelTicks) {
            offset = (t - pauseTicks) / stepTicks;
        } else if (t < pauseTicks + travelTicks + pauseTicks) {
            offset = maxOffset;
        } else {
            int elapsed = t - (pauseTicks + travelTicks + pauseTicks);
            offset = maxOffset - (elapsed / stepTicks);
        }

        offset = Math.max(0, Math.min(offset, maxOffset));
        if (offset != this.keyinput$lastOffset) {
            this.keyinput$lastOffset = offset;
            this.nameField.setText(this.keyinput$currentTitle.substring(offset));
            this.nameField.setEditable(false);
            this.nameField.setCursorToStart(false);
        }
    }
}
