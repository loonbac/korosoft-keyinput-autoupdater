package com.korosoft.keyinput.mixin;

import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Slot.x and Slot.y are {@code public final int} in Yarn 1.21.11, so a custom screen cannot
 * simply move a slot. Widening them here is what lets the server place slots anywhere on the
 * panel instead of on the vanilla 18px grid.
 */
@Mixin(Slot.class)
public interface SlotAccessor {

    @Mutable
    @Accessor("x")
    void keyinput$setX(int x);

    @Mutable
    @Accessor("y")
    void keyinput$setY(int y);
}
