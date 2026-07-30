package com.korosoft.keyinput;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/**
 * The looping launcher-menu theme ({@code intro.mp3} → {@code mus_intro.ogg}), started once the boot
 * sequence hands control to {@link MainMenuScreen} and stopped when the player leaves for the
 * server. Built on the same client-side path as {@link CutsceneSound}: a non-positional instance
 * resolved through sounds.json, no static registry entry needed.
 *
 * <p>Played on {@link SoundCategory#MASTER} on purpose — the MUSIC category is force-muted for this
 * client ({@code GameOptionsMixin}), so a MUSIC-category track would never be heard. MASTER keeps it
 * audible while still respecting the player's overall volume.
 */
public final class MenuMusic {

    public static final Identifier INTRO = Identifier.of("keyinput", "mus_intro");

    private static volatile SoundInstance playing;

    private MenuMusic() {
    }

    /**
     * Starts the theme if it is not already playing. Idempotent so returning to the menu from a
     * submenu (options, etc.) does not restart the track — the menu screen re-inits, but the music
     * keeps going.
     */
    public static void ensurePlaying() {
        MinecraftClient client = MinecraftClient.getInstance();
        SoundInstance current = playing;
        if (current != null && client.getSoundManager().isPlaying(current)) {
            return;
        }

        SoundEvent event = SoundEvent.of(INTRO);
        SoundInstance sound = new PositionedSoundInstance(
                event.id(),
                SoundCategory.MASTER,
                1.0F,
                1.0F,
                SoundInstance.createRandom(),
                true,   // repeat: loop the theme for as long as the menu is up
                0,
                SoundInstance.AttenuationType.NONE,
                0.0,
                0.0,
                0.0,
                true);
        playing = sound;
        client.getSoundManager().play(sound);
    }

    /** Stops the theme. Called when the player connects to the server or quits the menu. */
    public static void stop() {
        SoundInstance sound = playing;
        playing = null;
        if (sound != null) {
            MinecraftClient.getInstance().getSoundManager().stop(sound);
        }
    }
}
