package com.korosoft.keyinput;

/**
 * Snapshot of the torso pose of a rendered player model, taken at the end of the renderer's
 * real {@code PlayerEntityModel.setAngles} call — i.e. exactly what is being drawn that frame
 * (vanilla rotations plus whatever EMF re-applied from its animation system).
 *
 * <p>All values are in degrees (pitch/yaw/roll) or 1/16-block model units (origins). The world
 * pass uses this to keep the backpack glued to the animated torso without re-running the render
 * pipeline itself.
 */
public record TorsoPose(
        float bodyPitch, float bodyYaw, float bodyRoll,
        float bodyOriginX, float bodyOriginY, float bodyOriginZ,
        float rootPitch, float rootYaw, float rootRoll,
        float rootOriginX, float rootOriginY, float rootOriginZ) {

    /** Pose with no animation applied — every transform reduces to the identity chain. */
    public static final TorsoPose IDENTITY = new TorsoPose(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
}
