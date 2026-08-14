package com.korosoft.keyinput;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/**
 * Plays the Ping-Wheel ping sound at the marker's position. Ships inside the mod
 * (assets/keyinput/sounds.json + assets/keyinput/sounds/ping.ogg) so no server resource pack is
 * needed — exactly like CutsceneSound's cues.
 *
 * <p>Positional with LINEAR attenuation (the ping sound comes from the marked spot, fading with
 * distance), on MASTER so it is never muted by the server's music shutdown (GameOptionsMixin
 * zeroes only the MUSIC category).
 */
public final class PingSound {

    public static final Identifier SOUND_ID = Identifier.of("keyinput", "ping");

    private PingSound() {
    }

    /** Plays a directional ping at the given world position (attenuates with distance). */
    public static void play(double x, double y, double z) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) {
            return;
        }
        SoundEvent event = SoundEvent.of(SOUND_ID);
        SoundInstance sound = new PositionedSoundInstance(
                event.id(),
                SoundCategory.MASTER,
                1.0F,
                1.0F,
                SoundInstance.createRandom(),
                false,
                0,
                SoundInstance.AttenuationType.LINEAR,
                x, y, z,
                false);
        mc.getSoundManager().play(sound);
    }
}
