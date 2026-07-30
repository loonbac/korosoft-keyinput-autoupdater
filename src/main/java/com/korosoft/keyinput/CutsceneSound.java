package com.korosoft.keyinput;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/**
 * The music cues for the transfer cinematic — one per {@link Cutscene.Kind}. Ships inside the mod
 * (assets/keyinput/sounds.json + assets/keyinput/sounds/*.ogg) rather than in the Nexo resource
 * pack: the mod is mandatory anyway, and touching the pack would force every player to
 * re-download it.
 *
 * <p><b>Why the SoundEvent is constructed directly instead of registered:</b> nothing on the
 * playback path needs {@code Registries.SOUND_EVENT}. A {@link SoundInstance} resolves its samples
 * through {@code SoundManager#get(Identifier)}, i.e. purely through sounds.json — the static
 * registry is only consulted to warn about unknown ids and to serialise sounds the *server* sends.
 * Registering a client-only entry into a static registry that a Paper/Skript server never syncs
 * buys nothing here and puts a client-only id into a structure the vanilla protocol assigns raw
 * network ids from, so it is left alone.
 *
 * <p>Deliberately non-positional (relative, no attenuation, MASTER): this is a music cue, not a
 * world sound. That is also what keeps the stereo ogg playing as stereo — OpenAL only pans mono
 * buffers, and here "flat" is the intent, not a bug.
 */
public final class CutsceneSound {

    public static final Identifier CYMBAL = Identifier.of("keyinput", "mus_cymbal");
    public static final Identifier INTRO_NOISE = Identifier.of("keyinput", "mus_intronoise");

    // Kept only so the cue can be cut short if the cinematic is torn down early (a failsafe
    // firing, a disconnect). In the happy path the ogg has already ended by then. This is a
    // handle, never a source of truth: Cutscene alone decides whether the cinematic is running.
    private static volatile SoundInstance playing;

    private CutsceneSound() {
    }

    /** Starts the cue for {@code id}. Called from {@link Cutscene#start}, which is already idempotent. */
    public static void play(Identifier id) {
        stop();

        SoundEvent event = SoundEvent.of(id);
        SoundInstance sound = new PositionedSoundInstance(
                event.id(),
                SoundCategory.MASTER,
                1.0F,
                1.0F,
                SoundInstance.createRandom(),
                false,
                0,
                SoundInstance.AttenuationType.NONE,
                0.0,
                0.0,
                0.0,
                true);
        playing = sound;
        MinecraftClient.getInstance().getSoundManager().play(sound);
    }

    /** Cuts the cue. A no-op once the ogg has finished on its own, which is the normal case. */
    public static void stop() {
        SoundInstance sound = playing;
        playing = null;
        if (sound != null) {
            MinecraftClient.getInstance().getSoundManager().stop(sound);
        }
    }
}
