package com.korosoft.keyinput;

import net.minecraft.client.MinecraftClient;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side mirror of the SERVER-owned paraglider state. The server decides everything (whether
 * the player may glide, the glide state of every player, the auto-deploy config, the speed
 * multiplier); this class only stores what the server said and lets the input/physics/render mixins
 * read it.
 *
 * <p>{@link #localParagliding} is set ONLY from the S2C broadcast for this client's own UUID — the
 * server confirmation is the truth, never the client's own request. The physics applies exclusively
 * while the server confirmed the local glide.
 */
public final class ParagliderState {

    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    /** Who is gliding right now, keyed by player UUID, fed by {@link ParagliderStatePayload}. */
    private static final Map<UUID, Boolean> PARAGLIDING = new HashMap<>();

    /** Server said this client may deploy (it is holding the paraglider). */
    private static boolean canUse;

    /** Server config: auto-deploy when falling a meaningful distance. */
    private static boolean autoDeploy;

    /** Server config: horizontal speed multiplier while gliding. */
    private static float speed = 1.0F;

    /** My glide state — ONLY the S2C echo for my UUID (server confirmation is the truth). */
    private static boolean localParagliding;

    /** Manual use disables auto-deploy until the player lands, like the original mod. */
    private static boolean autoDeployDisabled;

    /** Fall tracking for auto-deploy, mirroring the original's ClientPlayerMovement. */
    private static double prevY = -1024.0D;

    private static double accumulatedFallDistance;

    /**
     * One pending auto-deploy request per fall: re-sending every tick while a server denies the
     * request (cooldown, etc.) would spam the channel. Cleared by the S2C echo, by landing and by
     * a manual toggle. Pure packet hygiene — it never decides whether the glide is allowed.
     */
    private static boolean autoDeployRequested;

    private ParagliderState() {
    }

    public static void put(ParagliderStatePayload payload) {
        PARAGLIDING.put(payload.player(), payload.paragliding());
        UUID me = myUuid();
        if (!payload.player().equals(ZERO_UUID) && payload.player().equals(me)) {
            localParagliding = payload.paragliding();
            autoDeployRequested = false;
        }
    }

    public static void put(ParagliderCanUsePayload payload) {
        canUse = payload.canUse();
        autoDeploy = payload.autoDeploy();
        speed = payload.speed();
    }

    public static boolean isGliding(UUID uuid) {
        return uuid != null && Boolean.TRUE.equals(PARAGLIDING.get(uuid));
    }

    /** Diagnostic: how many players the server has reported gliding state for. */
    public static int debugGlidingCount() {
        return PARAGLIDING.size();
    }

    /** UUID of the local player, or the zero UUID before any world is joined. */
    public static UUID myUuid() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null ? mc.player.getUuid() : ZERO_UUID;
    }

    public static boolean canUse() {
        return canUse;
    }

    public static boolean autoDeploy() {
        return autoDeploy;
    }

    public static boolean localParagliding() {
        return localParagliding;
    }

    public static boolean autoDeployDisabled() {
        return autoDeployDisabled;
    }

    public static void setAutoDeployDisabled(boolean autoDeployDisabled) {
        ParagliderState.autoDeployDisabled = autoDeployDisabled;
    }

    public static boolean isAutoDeployRequested() {
        return autoDeployRequested;
    }

    public static void markAutoDeployRequested() {
        autoDeployRequested = true;
    }

    public static void clearAutoDeployRequest() {
        autoDeployRequested = false;
    }

    public static float speed() {
        return speed;
    }

    public static double getPrevY() {
        return prevY;
    }

    public static void setPrevY(double prevY) {
        ParagliderState.prevY = prevY;
    }

    public static double getAccumulatedFallDistance() {
        return accumulatedFallDistance;
    }

    public static void resetAccumulatedFallDistance() {
        accumulatedFallDistance = 0.0D;
    }

    public static void addAccumulatedFallDistance(double amount) {
        accumulatedFallDistance += amount;
    }

    /** Called on disconnect: the next server may send nothing at all, so never inherit this session. */
    public static void reset() {
        PARAGLIDING.clear();
        canUse = false;
        autoDeploy = false;
        speed = 1.0F;
        localParagliding = false;
        autoDeployDisabled = false;
        autoDeployRequested = false;
        prevY = -1024.0D;
        accumulatedFallDistance = 0.0D;
            ParagliderPose.clearGlidingArms();
    }
}
