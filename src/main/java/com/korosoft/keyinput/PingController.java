package com.korosoft.keyinput;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.UUID;

/**
 * The ping "trigger": polls the ping key, raycasts where the camera is looking, and sends a
 * {@link PingPayload} to the server. Ported from Ping-Wheel's PingController, adapted to the
 * 1.21.11 Yarn API (the Mouse mixin fires the key edge; the raycast runs here each frame).
 */
public final class PingController {

    private static boolean pingQueued = false;
    private static long lastPingTime = 0;
    private static int pingSequence = 0;

    /** Cooldown between pings, in ticks (matches Ping-Wheel's ~1s correction period). */
    private static final long COOLDOWN_TICKS = 20;

    /** Maximum raycast distance in blocks. */
    private static final double RAYCAST_DISTANCE = 256.0;

    private PingController() {
    }

    /** Called from the Mouse mixin when the ping button is pressed. */
    public static void queuePingAction() {
        pingQueued = true;
    }

    /** Called from the render loop so the raycast happens with the current camera. */
    public static void pollPingAction(float tickDelta) {
        if (!pingQueued) {
            return;
        }
        pingQueued = false;

        MinecraftClient mc = MinecraftClient.getInstance();
        Entity cameraEntity = mc.getCameraEntity();
        if (cameraEntity == null || mc.world == null || mc.player == null) {
            return;
        }

        long worldTime = mc.world.getTime();
        if (worldTime - lastPingTime > COOLDOWN_TICKS) {
            pingSequence++;
        }
        lastPingTime = worldTime;

        Vec3d direction = cameraEntity.getRotationVec(tickDelta);
        Vec3d start = cameraEntity.getCameraPosVec(tickDelta);
        Vec3d end = start.add(direction.multiply(RAYCAST_DISTANCE));

        HitResult hit = mc.world.raycast(new RaycastContext(
                start, end,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                cameraEntity));

        UUID entityId = null;
        if (hit.getType() == HitResult.Type.ENTITY && hit instanceof EntityHitResult entityHit) {
            entityId = entityHit.getEntity().getUuid();
        }

        int dimension = mc.world.getRegistryKey().getValue().hashCode();
        ClientPlayNetworking.send(new PingPayload(
                hit.getPos().x, hit.getPos().y, hit.getPos().z,
                entityId, pingSequence, dimension));
    }
}
