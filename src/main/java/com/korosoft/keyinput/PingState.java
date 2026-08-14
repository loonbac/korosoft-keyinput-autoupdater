package com.korosoft.keyinput;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;

import java.util.UUID;

/**
 * One visible ping marker, kept client-side. Holds the world-space position (following a target
 * entity if one was pinged), the author, the sequence and the dimension. Lifespan is tracked by
 * the tick age.
 */
public final class PingState {

    /** How long a ping stays visible, in ticks (7s, same as Ping-Wheel's default). */
    public static final int LIFETIME_TICKS = 140;

    private double x;
    private double y;
    private double z;
    private final UUID author;
    private final UUID entityId;
    private final int sequence;
    private final int dimension;
    private long bornAtMillis;

    public PingState(double x, double y, double z, UUID author, UUID entityId, int sequence, int dimension) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.author = author;
        this.entityId = entityId;
        this.sequence = sequence;
        this.dimension = dimension;
        this.bornAtMillis = System.currentTimeMillis();
    }

    /** Returns true when the ping expired (lifetime in real time, not frames) and should be removed. */
    public boolean isExpired() {
        return System.currentTimeMillis() - bornAtMillis > LIFETIME_TICKS * 50L;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    /** Re-anchors the marker to a moving target entity, if the world still has it. */
    public void followEntity() {
        if (entityId == null) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) {
            return;
        }
        Entity entity = null;
        for (Entity e : mc.world.getEntities()) {
            if (e.getUuid().equals(entityId)) {
                entity = e;
                break;
            }
        }
        if (entity != null && !entity.isRemoved()) {
            var pos = entity.getLerpedPos(1.0F);
            this.x = pos.x;
            this.y = pos.y + entity.getBoundingBox().getLengthY();
            this.z = pos.z;
        }
    }

    public UUID getAuthor() {
        return author;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public int getSequence() {
        return sequence;
    }

    public int getDimension() {
        return dimension;
    }
}
