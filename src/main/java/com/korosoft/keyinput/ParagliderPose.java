package com.korosoft.keyinput;

import com.korosoft.keyinput.BackpackRenderState;
import com.korosoft.keyinput.ParagliderState;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Shared gliding arm pose logic. Server-owned state: this mixin only draws what the S2C broadcast
 * says ({@link ParagliderState#isGliding(UUID)}). Both arms are fixed extended forward — no
 * bobbing, no leg changes (deliberately minimal, per project decision).
 *
 * <p>EMF (Entity Model Features) renders the player's arms with its own part instances and
 * re-applies the server resource pack's custom animations when the model parts are actually drawn
 * (deferred submit flush) — so posing the model in {@code setAngles} is not enough. The definitive
 * fix is {@link #forceIfGlidingArm(ModelPart)}: the arm parts of every server-confirmed gliding
 * player are registered in {@link #GLIDING_ARMS} and every draw path (vanilla
 * {@code ModelPart.render} and EMF's own {@code renderLikeVanilla}/{@code renderBoxes}... see
 * {@code ParagliderPartPoseMixin} / {@code ParagliderEmfPartMixin}) forces the gliding rotation
 * right before the part is transformed.
 */
public final class ParagliderPose {

    /** The original mod's arm rotation for the gliding pose. */
    public static final float ARM_ROTATION = 3.3831854F;

    /** Arm parts that must render in the gliding pose. Render thread only. */
    private static final Set<ModelPart> GLIDING_ARMS = new HashSet<>();

    private ParagliderPose() {
    }

    /** Returns true (and applies the pose) if the player is gliding or wearing a head accessory. */
    public static boolean applyIfGliding(PlayerEntityModel model, PlayerEntityRenderState state) {
        UUID uuid = ((BackpackRenderState) state).keyinput$getPlayerUuid();
        if (uuid == null) {
            return false;
        }
        boolean gliding = ParagliderState.isGliding(uuid);
        HeadAccessoryState.WornHeadAccessory head = HeadAccessoryState.getWorn(uuid);
        boolean wearingHead = (head != null && head.cmd() > 0);

        if (!gliding && !wearingHead) {
            return false;
        }
        apply(model);
        GLIDING_ARMS.add(model.rightArm);
        GLIDING_ARMS.add(model.leftArm);
        return true;
    }

    /** Removes this model's arms from the gliding set (player stopped gliding). */
    public static void unregisterArms(PlayerEntityModel model) {
        GLIDING_ARMS.remove(model.rightArm);
        GLIDING_ARMS.remove(model.leftArm);
    }

    public static boolean isRegisteredGlidingArm(ModelPart part) {
        return GLIDING_ARMS.contains(part);
    }

    /**
     * Called from the draw-path mixins (HEAD): if this part is one of the registered gliding arms,
     * force the gliding rotation right before the part transform is applied. Wins over any
     * EMF/pack animation because it is applied at draw time.
     */
    public static void forceIfGlidingArm(ModelPart part) {
        if (GLIDING_ARMS.contains(part)) {
            part.pitch = ARM_ROTATION;
            part.yaw = 0.0F;
            part.roll = 0.0F;
        }
    }

    /** Clears all registered gliding arms (disconnect / state reset). */
    public static void clearGlidingArms() {
        GLIDING_ARMS.clear();
    }

    /** Fixed gliding pose: both arms extended forward, yaw/roll zeroed. */
    public static void apply(PlayerEntityModel model) {
        ModelPart rightArm = model.rightArm;
        rightArm.pitch = ARM_ROTATION;
        rightArm.yaw = 0.0F;
        rightArm.roll = 0.0F;
        ModelPart leftArm = model.leftArm;
        leftArm.pitch = ARM_ROTATION;
        leftArm.yaw = 0.0F;
        leftArm.roll = 0.0F;
    }
}
