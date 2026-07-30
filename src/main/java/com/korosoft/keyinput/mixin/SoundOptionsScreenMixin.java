package com.korosoft.keyinput.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import java.util.Arrays;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.option.SoundOptionsScreen;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.sound.SoundCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Removes the music volume slider from the sound options screen. {@code getVolumeOptions} builds the
 * per-category slider list (every category except MASTER); this strips the MUSIC entry out of it, so
 * the player is never offered a control for music they cannot use — {@link GameOptionsMixin} keeps
 * it muted regardless.
 *
 * <p>The MUSIC slider is identified by reference: {@code getSoundVolumeOption(MUSIC)} returns the
 * same cached instance that populates the array, so an identity filter removes exactly that entry.
 */
@Mixin(SoundOptionsScreen.class)
public class SoundOptionsScreenMixin {

    @ModifyReturnValue(method = "getVolumeOptions", at = @At("RETURN"))
    private SimpleOption<?>[] keyinput$hideMusicSlider(SimpleOption<?>[] original) {
        SimpleOption<Double> music = MinecraftClient.getInstance().options.getSoundVolumeOption(SoundCategory.MUSIC);
        return Arrays.stream(original).filter(option -> option != music).toArray(SimpleOption[]::new);
    }
}
