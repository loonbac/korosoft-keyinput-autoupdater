package com.korosoft.keyinput;

import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side registry of "which player wears which backpack, anchored and posed how", fed by
 * {@link BackpackPayload} from the server and read by {@link BackpackRenderLayer} on the render
 * thread.
 *
 * <p>The payload handler runs on the network thread; the render layer runs on the render thread.
 * A plain map would race — a player equipping while another thread renders could hand the layer a
 * partially updated entry. {@link ConcurrentHashMap} makes every {@code put} atomic and every
 * {@code get} read a consistent snapshot, which is all this registry needs.
 *
 * <p>EVERYTHING is server-driven: the variant (paper {@code custom_model_data} the server pack
 * maps to the worn model), the anchor (backY/backZ), the extra scale and the yaw. Nothing here is
 * a tuning constant — adjusting the backpack is a Skript edit, never a mod rebuild.
 */
public final class BackpackState {

    /** Immutable per-player render spec — safe to publish through the concurrent map. */
    public record WornBackpack(int cmd, float backY, float backZ, float scale, float yawDeg, float originY,
                               boolean flip) {
    }

    private static final Map<UUID, WornBackpack> WORN = new ConcurrentHashMap<>();

    private BackpackState() {
    }

    /**
     * Records or clears a player's backpack. {@code cmd <= 0} removes the entry.
     */
    public static void put(UUID playerUuid, int cmd, float backY, float backZ, float scale, float yawDeg,
                           float originY, int flip) {
        if (cmd <= BackpackPayload.NONE) {
            WORN.remove(playerUuid);
            return;
        }
        WORN.put(playerUuid, new WornBackpack(cmd, backY, backZ, scale, yawDeg, originY, flip != 0));
    }

    /** The worn backpack spec for a player, or {@code null} if they are not wearing one. */
    public static WornBackpack getWorn(UUID playerUuid) {
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
