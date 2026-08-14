package com.korosoft.keyinput;

import net.minecraft.client.model.ModelPart;
import net.minecraft.entity.Entity;
import org.joml.Matrix4f;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Holds the per-player torso matrix captured during the real entity render.
 *
 * <p>The render pipeline is single-threaded on the Render thread. {@code updateRenderState} runs
 * per entity before its model renders, so it records which entity is about to be drawn; when that
 * player's body part is transformed ({@code ModelPart.applyTransform}), the exact body-to-world
 * matrix is captured and attributed to that entity. The backpack world pass then reads the matrix
 * of the specific player it is drawing — correct even with many players wearing backpacks.
 *
 * <p>This replaces the old angle-based {@link TorsoPoseCache}: replicating the torso transform by
 * hand (origin/16 translates + X/Y/Z rotations) could never match the real render chain exactly
 * (pivot translate order, quaternion composition, EMF's own pivots), which is why the backpack
 * stayed rigid. Using the ACTUAL drawn matrix makes the backpack behave like a chestplate: it
 * inherits every rotation, translation and lean of the animated torso.
 */
public final class TorsoMatrixCache {

    private static ModelPart trackedBodyPart;
    private static Entity currentEntity;
    private static final Map<Entity, Matrix4f> MATRICES_BY_ENTITY = new IdentityHashMap<>();

    private TorsoMatrixCache() {
    }

    /** Called from {@code LivingEntityRenderer.updateRenderState} — the entity being rendered. */
    public static void setCurrentEntity(Entity entity) {
        currentEntity = entity;
    }

    /** Marks the body part to capture while the current entity's model renders. */
    public static void setTrackedBodyPart(ModelPart bodyPart) {
        trackedBodyPart = bodyPart;
    }

    /** The body part currently being tracked, or null. */
    public static ModelPart getTrackedBodyPart() {
        return trackedBodyPart;
    }

    /** Called from the tail of {@code ModelPart.applyTransform} for the tracked part. */
    public static void captureForCurrentEntity(Matrix4f matrix) {
        if (currentEntity != null) {
            MATRICES_BY_ENTITY.put(currentEntity, new Matrix4f(matrix));
        }
    }

    /** The torso matrix captured for this entity, or null if it was not rendered this frame. */
    public static Matrix4f getMatrixFor(Entity entity) {
        Matrix4f matrix = MATRICES_BY_ENTITY.get(entity);
        return matrix == null ? null : new Matrix4f(matrix);
    }

    /** Drops matrices for entities that left the world (disconnected, despawned, dimension change). */
    public static void cleanup(net.minecraft.client.world.ClientWorld world) {
        MATRICES_BY_ENTITY.entrySet().removeIf(e -> {
            Entity entity = e.getKey();
            return entity.isRemoved() || entity.getEntityWorld() != world;
        });
    }
}
