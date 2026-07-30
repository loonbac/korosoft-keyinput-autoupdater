package com.korosoft.keyinput.mixin;

import com.korosoft.keyinput.AscendCamera;
import com.korosoft.keyinput.Cutscene;
import com.korosoft.keyinput.CutsceneCamera;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the transfer cinematic's camera move for the length of the transfer (see
 * {@link CutsceneCamera}/{@link AscendCamera} for the shape of each variant, {@link Cutscene} for
 * the clock and which variant is active).
 *
 * <p>Runs at TAIL and overwrites whatever vanilla decided, rather than editing it in flight: the
 * player could be in any perspective when the cinematic starts, and the move has to look the same
 * either way. The focus point is recomputed exactly the way {@code update} computes it for first
 * person — including the smoothed {@code cameraY} eye height, which is why those two private
 * fields are shadowed. Deriving it any other way (e.g. {@code getCameraPosVec}) would miss that
 * smoothing and pop by a few centimetres on the first frame, which is precisely the jump cut the
 * "start from the player's current view" requirement exists to avoid.
 *
 * <p>Position is applied through vanilla's own third-person idiom — face an angle, sit on a point,
 * then {@code moveBy} backwards along the view axis — so {@code clipToSpace} keeps the camera from
 * ending up inside a wall (which would render as an opaque black frame while the curtain is still
 * mostly transparent).
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    private static final String UPDATE_METHOD =
            "update(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;ZZF)V";

    @Shadow
    private boolean thirdPerson;

    @Shadow
    private float cameraY;

    @Shadow
    private float lastCameraY;

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    protected abstract void setPos(double x, double y, double z);

    @Shadow
    protected abstract void moveBy(float surge, float heave, float sway);

    @Shadow
    private float clipToSpace(float distance) {
        throw new AssertionError("shadow");
    }

    @Inject(method = UPDATE_METHOD, at = @At("TAIL"))
    private void keyinput$applyCutsceneCamera(World area, Entity focusedEntity, boolean thirdPerson,
                                              boolean inverseView, float tickProgress, CallbackInfo ci) {
        if (!Cutscene.isActive()) {
            return;
        }

        if (Cutscene.getKind() == Cutscene.Kind.ASCEND) {
            keyinput$applyAscend(focusedEntity, tickProgress);
        } else {
            keyinput$applyOrbit(focusedEntity, tickProgress);
        }
    }

    private void keyinput$applyOrbit(Entity focusedEntity, float tickProgress) {
        float progress = Cutscene.getCinematicProgress();

        // WorldRenderer skips the focused entity unless camera.isThirdPerson() — which reads this
        // field and nothing else — so without this the orbit circles an invisible player. Written
        // here rather than through client.options.setPerspective(...) on purpose: the option is
        // persisted user state and would strand the player in third person if any exit path
        // missed it. update() already stored the vanilla value near its head, and nothing reads
        // it until renderWorld later in the same frame, so overwriting it at TAIL sticks for
        // exactly this frame and is re-derived from scratch on the next one.
        this.thirdPerson = CutsceneCamera.isThirdPersonAt(progress);

        double focusX = MathHelper.lerp((double) tickProgress, focusedEntity.lastX, focusedEntity.getX());
        double focusY = MathHelper.lerp((double) tickProgress, focusedEntity.lastY, focusedEntity.getY())
                + MathHelper.lerp(tickProgress, this.lastCameraY, this.cameraY);
        double focusZ = MathHelper.lerp((double) tickProgress, focusedEntity.lastZ, focusedEntity.getZ());

        // the player's yaw is frozen by the input lock for the whole cinematic, so this anchors
        // the orbit to the view they were left holding
        float yaw = focusedEntity.getYaw(tickProgress) + CutsceneCamera.getOrbitYawOffset(progress);
        float pitch = CutsceneCamera.getPitch(focusedEntity.getPitch(tickProgress), progress);

        this.setRotation(yaw, pitch);
        this.setPos(focusX, focusY, focusZ);

        float distance = CutsceneCamera.getDistance(progress);
        if (distance > 0.0F) {
            this.moveBy(-this.clipToSpace(distance), 0.0F, 0.0F);
        }
    }

    /**
     * Ground-anchored watching shot: the camera stays where the player's eye WAS at the start of
     * the cinematic while the player rises away from it, then pulls back a short distance and aims
     * up to keep them framed. See {@link AscendCamera}'s class doc for why the anchor is captured
     * here (lazily, from the same smoothed focus point as the orbit branch) rather than from
     * {@code Cutscene.start}.
     */
    private void keyinput$applyAscend(Entity focusedEntity, float tickProgress) {
        float progress = Cutscene.getCinematicProgress();

        // The feet term before cameraY's smoothing offset is added — captured below as the beam's
        // ground anchor, so AscendBeam never has to re-derive it independently of the eye anchor.
        double entityY = MathHelper.lerp((double) tickProgress, focusedEntity.lastY, focusedEntity.getY());
        double focusX = MathHelper.lerp((double) tickProgress, focusedEntity.lastX, focusedEntity.getX());
        double focusY = entityY + MathHelper.lerp(tickProgress, this.lastCameraY, this.cameraY);
        double focusZ = MathHelper.lerp((double) tickProgress, focusedEntity.lastZ, focusedEntity.getZ());

        if (!AscendCamera.isCaptured()) {
            AscendCamera.capture(focusX, focusY, focusZ, entityY, focusedEntity.getYaw(tickProgress));
        }

        float w = AscendCamera.pullOutWeight(progress);

        // Rotation here only exists to give moveBy() its axis (backward + up out of the setup
        // pitch); it is fully overwritten below once the camera is actually in position.
        this.setRotation(AscendCamera.getAnchorYaw(), AscendCamera.getSetupPitch(focusedEntity.getPitch(tickProgress), progress));

        // The ANCHOR eye, not the live one: the camera must stay on the ground while the player
        // leaves, which is the entire point of this shot.
        this.setPos(AscendCamera.getEyeX(), AscendCamera.getEyeY(), AscendCamera.getEyeZ());

        float dist = AscendCamera.getBackDistance(progress);
        if (dist > 0.0F) {
            this.moveBy(-this.clipToSpace(dist), 0.0F, 0.0F);
        }

        // The final aim, and it needs no look-at math at all: moveBy above walked the camera
        // straight backwards along the anchor yaw, so the anchor yaw IS the direction back at the
        // player's column, exactly, for the whole shot. clipToSpace can shorten that walk but never
        // bends it. This used to solve an atan2 towards an aim point and got the same answer the
        // long way round, with a degenerate case at frame 0 (camera directly above the target,
        // direction undefined) that only existed because of the detour.
        //
        // Pitch is a CHOSEN angle rather than a consequence of aiming at a point. That is the whole
        // improvement: "point the camera a little higher" is now this one number, instead of solving
        // backwards for which aim height happens to produce the angle you wanted. Aiming at a point
        // near the feet from a camera that sits above head height had it tipped ~17 degrees into the
        // ground — the player left the top of the frame almost immediately and the shot spent itself
        // staring at grass.
        float yaw = AscendCamera.getAnchorYaw();
        float pitch = MathHelper.lerp(w, focusedEntity.getPitch(tickProgress), AscendCamera.getLookPitch());
        this.setRotation(yaw, pitch);

        // Same rationale as the orbit branch: WorldRenderer skips the focused entity unless this is
        // true. Gated on the pull-back, not on the distance to the anchored target — see
        // AscendCamera#isThirdPersonAt for why that distance is the wrong question.
        this.thirdPerson = AscendCamera.isThirdPersonAt(progress);
    }
}
