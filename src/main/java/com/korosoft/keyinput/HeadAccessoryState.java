package com.korosoft.keyinput;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side registry of "which player wears which head accessory, anchored and posed how", fed by
 * {@link HeadAccessoryPayload} from the server and read by {@link HeadAccessoryRenderLayer} on the render
 * thread.
 *
 * <p>Thread-safe via {@link ConcurrentHashMap}. Everything is server-driven: the variant CMD, anchor (X, Y, Z),
 * scale, rotation (yaw, pitch, roll) and vertical flip.
 */
public final class HeadAccessoryState {

    public record WornHeadAccessory(int cmd, float headX, float headY, float headZ, float scale,
                                    float yawDeg, float pitchDeg, float rollDeg, boolean flip) {
    }

    private static final Map<UUID, WornHeadAccessory> WORN = new ConcurrentHashMap<>();

    private HeadAccessoryState() {
    }

    /**
     * Records or clears a player's head accessory. {@code cmd <= 0} removes the entry.
     */
    public static void put(UUID playerUuid, int cmd, float headX, float headY, float headZ, float scale,
                           float yawDeg, float pitchDeg, float rollDeg, int flip) {
        if (cmd <= HeadAccessoryPayload.NONE) {
            WORN.remove(playerUuid);
            return;
        }
        WORN.put(playerUuid, new WornHeadAccessory(cmd, headX, headY, headZ, scale, yawDeg, pitchDeg, rollDeg, flip != 0));
    }

    /** The worn head accessory spec for a player, or {@code null} if they are not wearing one. */
    public static WornHeadAccessory getWorn(UUID playerUuid) {
        if (playerUuid == null) {
            return null;
        }
        return WORN.get(playerUuid);
    }

    /** Drops everything — called on disconnect, so a stale registry never follows a rejoin. */
    public static void clear() {
        WORN.clear();
    }
}
