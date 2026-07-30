package com.korosoft.keyinput.mixin;

import net.minecraft.client.input.Input;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Input.movementVector is {@code protected} in Yarn 1.21.11 and has no setter, so the cinematic's
 * input lock cannot zero it from outside the package. Input.playerInput next to it is public and
 * needs no accessor.
 */
@Mixin(Input.class)
public interface InputAccessor {

    @Accessor("movementVector")
    void keyinput$setMovementVector(Vec2f movementVector);
}
