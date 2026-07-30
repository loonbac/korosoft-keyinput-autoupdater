package com.korosoft.keyinput.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.option.GameOptions;
import net.minecraft.sound.SoundCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Forces the vanilla background music to silence. {@code getSoundVolume(MUSIC)} is what the music
 * tracker checks before starting a track, so reporting 0 for the MUSIC category keeps it off
 * regardless of the stored option value — which, paired with {@link SoundOptionsScreenMixin} hiding
 * the slider, is what "off by default and not adjustable" means here.
 *
 * <p>Only the MUSIC category is touched. MASTER (and everything multiplied by it) is returned
 * untouched, so the cutscene cues — which play on MASTER — are unaffected.
 */
@Mixin(GameOptions.class)
public class GameOptionsMixin {

    @ModifyReturnValue(method = "getSoundVolume", at = @At("RETURN"))
    private float keyinput$muteMusic(float original, SoundCategory category) {
        return category == SoundCategory.MUSIC ? 0.0F : original;
    }
}
