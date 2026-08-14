package com.korosoft.keyinput;

import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.Entity;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Bridges the renderer's real entity pass to the backpack world pass.
 *
 * <p>During the normal world render the {@code LivingEntityRenderer} updates a render state per
 * entity ({@code updateRenderState}) and then animates the shared model via
 * {@code PlayerEntityModel.setAngles(state)}. The pose is captured at the tail of that setAngles
 * call — the exact pose that gets drawn (EMF animations included) — and associated to the entity
 * through the state that {@code updateRenderState} already bound.
 *
 * <p>The world pass only reads {@link #get(Entity)}; it never re-runs the render pipeline, which
 * is why this works where the previous state-reapply approach failed (the EMF biped pose is only
 * populated inside the real entity pass, and never for the local player in first person).
 *
 * <p>Identity-keyed maps: render states and entities are reused by the engine, identity (not
 * equals) is the correct semantics for both.
 */
public final class TorsoPoseCache {

    private static final Map<EntityRenderState, Entity> STATE_TO_ENTITY = new IdentityHashMap<>();
    private static final Map<Entity, TorsoPose> POSE_BY_ENTITY = new IdentityHashMap<>();

    private TorsoPoseCache() {
    }

    /** Called from {@code LivingEntityRenderer.updateRenderState} for every rendered living entity. */
    public static void bind(EntityRenderState state, Entity entity) {
        if (entity instanceof net.minecraft.entity.player.PlayerEntity) {
            STATE_TO_ENTITY.put(state, entity);
        }
    }

    /** Called from the tail of {@code PlayerEntityModel.setAngles} with the drawn pose. */
    public static void capture(EntityRenderState state, TorsoPose pose) {
        Entity entity = STATE_TO_ENTITY.get(state);
        if (entity != null) {
            POSE_BY_ENTITY.put(entity, pose);
        }
    }

    /** Latest pose drawn for this entity, or the identity pose if it was never rendered. */
    public static TorsoPose get(Entity entity) {
        TorsoPose pose = POSE_BY_ENTITY.get(entity);
        return pose != null ? pose : TorsoPose.IDENTITY;
    }

    /**
     * Latest pose drawn for the entity bound to this render state (feature-layer path: the
     * layer only has the state, never the entity). Falls back to the identity pose when the
     * state was never bound (should not happen for players being rendered).
     */
    public static TorsoPose getPoseForState(EntityRenderState state) {
        Entity entity = STATE_TO_ENTITY.get(state);
        return entity != null ? get(entity) : TorsoPose.IDENTITY;
    }

    /** Drops poses for entities that left the world (disconnected, despawned, dimension change). */
    public static void cleanup(net.minecraft.client.world.ClientWorld world) {
        Iterator<Map.Entry<Entity, TorsoPose>> it = POSE_BY_ENTITY.entrySet().iterator();
        while (it.hasNext()) {
            Entity entity = it.next().getKey();
            if (entity.isRemoved() || entity.getEntityWorld() != world) {
                it.remove();
            }
        }
    }
}
