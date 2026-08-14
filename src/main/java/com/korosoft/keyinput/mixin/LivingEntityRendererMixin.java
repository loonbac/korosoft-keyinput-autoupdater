package com.korosoft.keyinput.mixin;

import com.korosoft.keyinput.AscendCamera;
import com.korosoft.keyinput.Cutscene;
import com.korosoft.keyinput.ForceBlackState;
import com.korosoft.keyinput.SelfNameTag;
import com.korosoft.keyinput.TorsoMatrixCache;
import com.korosoft.keyinput.TorsoPoseCache;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the local player see their own nametag in third person, the way everyone else already
 * sees it. Vanilla hides it in the last line of {@code hasLabel}:
 *
 * <pre>return MinecraftClient.isHudEnabled()
 *     &amp;&amp; livingEntity != minecraftClient.getCameraEntity()
 *     &amp;&amp; bl
 *     &amp;&amp; !livingEntity.hasPassengers();</pre>
 *
 * <p>The self reaches that line unconditionally — the team-visibility switch above it sits behind
 * {@code if (livingEntity != clientPlayerEntity)} and is skipped — so the single term that hides
 * the tag is the camera-entity identity check. Only that term is relaxed, which leaves the F1
 * check, the invisibility check and the passenger check doing their jobs.
 *
 * <p><b>Why null instead of capturing the entity:</b> this runs for every living entity's
 * hasLabel, not just the player's, so it needs to be scoped. Nulling the camera entity only when
 * the camera is attached to the local player is exactly equivalent to testing the entity itself:
 * the term is {@code livingEntity != cameraEntity}, and the only entity for which it is false when
 * the camera holds the local player IS the local player. Every other entity that reaches this line
 * already compares unequal and is unaffected. Entities on a scoreboard team never reach it at all.
 *
 * <p>This restores the whole tag, not just the name. {@code EntityRenderer#updateRenderState}
 * writes {@code displayName} and {@code nameLabelPos} together behind this same hasLabel result:
 * the name comes from {@code PlayerEntity#getDisplayName()} — i.e. {@code Team.decorateName(...)},
 * the TAB plugin's prefix/suffix — and the belowname killtag line was already being written to the
 * render state (gated only on distance, never on hasLabel), but was being dropped downstream
 * because a null {@code nameLabelPos} makes the label command a no-op. Giving hasLabel a true here
 * supplies that position and both lines render.
 *
 * <p>Unrelated second concern living in the same mixin because it targets the same class: the
 * ASCEND cinematic (see {@link Cutscene.Kind#ASCEND}) turns the local player's rendered model to
 * face the anchored camera. See {@link #keyinput$turnSelfForAscend} below.
 */
@Mixin(LivingEntityRenderer.class)
public class LivingEntityRendererMixin {

    // the real method; the (Entity, double) overload alongside it is the generic bridge
    private static final String HAS_LABEL_METHOD = "hasLabel(Lnet/minecraft/entity/LivingEntity;D)Z";
    private static final String GET_CAMERA_ENTITY_TARGET =
            "Lnet/minecraft/client/MinecraftClient;getCameraEntity()Lnet/minecraft/entity/Entity;";
    private static final String IS_INVISIBLE_TO_TARGET =
            "Lnet/minecraft/entity/LivingEntity;isInvisibleTo(Lnet/minecraft/entity/player/PlayerEntity;)Z";

    // the real method; erasure of updateRenderState(T, S, float), the generic (Entity,
    // EntityRenderState, float) bridge sits alongside it same as HAS_LABEL_METHOD above
    private static final String UPDATE_RENDER_STATE_METHOD =
            "updateRenderState(Lnet/minecraft/entity/LivingEntity;"
                    + "Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;F)V";

    // TEMPORARY DIAGNOSTIC LOGGING — see keyinput$showLabelForVisibleCustomName below.
    // Remove once the ModelEngine nametag investigation is closed.
    private static final Logger LOGGER = LoggerFactory.getLogger("korosoft-core");
    @Unique
    private static final java.util.Set<Integer> keyinput$LOGGED_IDS = new java.util.HashSet<>();
    @Unique
    private static final java.util.Set<Integer> keyinput$RS_LOGGED_IDS = new java.util.HashSet<>();

    @ModifyExpressionValue(method = HAS_LABEL_METHOD, at = @At(value = "INVOKE", target = GET_CAMERA_ENTITY_TARGET))
    private Entity keyinput$showOwnNameTag(Entity cameraEntity) {
        return SelfNameTag.shouldShow(cameraEntity) ? null : cameraEntity;
    }

    /**
     * Restores the nametag for entities that are engine-invisible but still have an explicitly
     * visible custom name — the ModelEngine case: it hides the base mob with
     * {@code setInvisible(true)} and renders its own model on top, but vanilla's hasLabel bails out
     * on the very next check ({@code !livingEntity.isInvisibleTo(clientPlayer)}) before
     * {@code CustomNameVisible} is ever consulted, so the label never draws.
     *
     * <p>Scoped to {@code isCustomNameVisible()}, not {@code hasCustomName()}: the latter would also
     * un-hide potion-invisible mobs and nameless armor stands that merely happen to carry a name,
     * which is not the intent here.
     */
    @ModifyExpressionValue(method = HAS_LABEL_METHOD, at = @At(value = "INVOKE", target = IS_INVISIBLE_TO_TARGET))
    private boolean keyinput$showLabelForVisibleCustomName(boolean original, LivingEntity livingEntity, double distance) {
        if (original && keyinput$LOGGED_IDS.add(livingEntity.getId())) {
            LOGGER.info("[KORO-NAMETAG] id={} type={} invisibleTo(original)={} customNameVisible={} hasCustomName={} name={}",
                    livingEntity.getId(),
                    livingEntity.getType().toString(),
                    original,
                    livingEntity.isCustomNameVisible(),
                    livingEntity.hasCustomName(),
                    livingEntity.getCustomName() != null ? livingEntity.getCustomName().getString() : "null");
        }
        if (original && livingEntity.isCustomNameVisible()) {
            return false;
        }
        return original;
    }

    /**
     * Turns the local player's rendered model to face the ASCEND camera, which by design stays
     * anchored behind them (see {@code CameraMixin#keyinput$applyAscend}) instead of moving to see
     * their face. Render-state only, never the entity: {@code focusedEntity.getYaw(tickProgress)}
     * feeds straight back into that camera every frame ({@code CameraMixin}), and the player's real
     * yaw also syncs to the server, so writing it here would corrupt the shot it is meant to fix and
     * fight the network every tick. TAIL, not an earlier injection point, so this runs after vanilla
     * has already written {@code bodyYaw} for this frame and simply adds the turn on top rather than
     * racing it.
     *
     * <p>{@code PlayerEntityRenderer#updateRenderState} calls {@code super.updateRenderState(...)}
     * as its first act before doing any of its own player-specific work, so injecting here at the
     * {@link LivingEntityRenderer} level already covers the player render path — no separate mixin
     * on {@code PlayerEntityRenderer} is needed.
     *
     * <p>Scoped to the client's own player and the exact cinematic that motivates it — never any
     * other entity, and never the ORBIT variant, which already frames the player correctly and has
     * no use for this.
     */
    /**
     * Records which entity is about to be rendered so the torso matrix capture in
     * {@code ModelPartTorsoMixin} can attribute each player's matrix to the right entity (correct
     * even with many players wearing backpacks). Runs on the super (LivingEntityRenderer) path,
     * which {@code PlayerEntityRenderer#updateRenderState} calls first, so player states are
     * covered too.
     */
    @Inject(method = UPDATE_RENDER_STATE_METHOD, at = @At("TAIL"))
    private void keyinput$trackCurrentEntity(LivingEntity entity, LivingEntityRenderState state, float tickProgress, CallbackInfo ci) {
        // Captura Torso DESACTIVADA (2026-08-09): TorsoPoseCache.bind() retenia el
        // PlayerEntityRenderState de CADA frame de cada jugador (leak de ~450k estados en
        // sesiones largas, heap del cliente al 99%). El world pass (BackpackWorldRenderer)
        // esta deshabilitado y BackpackRenderLayer aplica body.applyTransform directo, asi
        // que estos caches no los lee nadie activo. Reactivar solo junto al world pass.
    }

    /**
     * Registers the player's body part so the torso matrix can be captured during the real render.
     *
     * <p>Runs at the tail of {@code render(...)} — by then the model parts carry the pose that was
     * actually drawn. The body part is registered so that {@code ModelPartTorsoMixin} captures the
     * final body-to-world matrix (EMF animations included) the next time this player renders; the
     * backpack world pass reads that matrix directly instead of replicating the transform chain by
     * hand, which is why the previous angle-based capture stayed rigid.
     *
     * <p>The model object is the renderer's own instance (one per renderer, shared across players
     * but mutated one player at a time on the Render thread), so tracking the body part here and
     * capturing during the next render of the same frame is safe.
     */
    @Inject(method = "render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;"
            + "Lnet/minecraft/client/util/math/MatrixStack;"
            + "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;"
            + "Lnet/minecraft/client/render/state/CameraRenderState;)V", at = @At("HEAD"))
    private void keyinput$trackTorsoBodyPart(LivingEntityRenderState state,
                                             MatrixStack matrices,
                                             net.minecraft.client.render.command.OrderedRenderCommandQueue queue,
                                             net.minecraft.client.render.state.CameraRenderState cameraState,
                                             CallbackInfo ci) {
        if (!(state instanceof PlayerEntityRenderState)) {
            return;
        }
        if (!(((LivingEntityRendererAccessor) (Object) this).keyinput$invokeGetModel()
                instanceof PlayerEntityModel playerModel)) {
            return;
        }
        // TorsoMatrixCache.setTrackedBodyPart(playerModel.body);  // desactivado: leak (ver arriba)
    }

    @Inject(method = UPDATE_RENDER_STATE_METHOD, at = @At("TAIL"))
    private void keyinput$turnSelfForAscend(LivingEntity entity, LivingEntityRenderState state, float tickProgress, CallbackInfo ci) {
        if (!Cutscene.isActive() || Cutscene.getKind() != Cutscene.Kind.ASCEND) {
            return;
        }
        if (entity != MinecraftClient.getInstance().player) {
            return;
        }

        // Head is left alone on purpose: relativeHeadYaw is measured off bodyYaw, so leaving it at
        // zero while the body turns brings the head around with it and the player turns as one
        // piece. Offsetting only the body would leave the head still pointed at its old relative
        // angle — effectively snapped back over the shoulder at 180 degrees, a pose vanilla's own
        // head-turn clamp would never produce on its own.
        state.bodyYaw += AscendCamera.getSelfTurnDegrees(Cutscene.getCinematicProgress());
    }

    /**
     * Forces the parried mob's whole model to render pure black for the parry silhouette (see
     * {@code ParrySilhouette}). {@code getMixColor} is the per-renderer tint hook — it returns
     * {@code -1} (no tint) by vanilla default, and its result becomes the single {@code color}
     * argument that reaches every model vertex ({@code ModelPart#renderCuboid}). Returning an
     * opaque-black ARGB collapses the whole body to black in one place instead of touching vertices.
     *
     * <p>Gated on the flag carried by this render state, not a static boolean, because GUI entity
     * rendering is deferred: this hook fires when the batched GUI flush finally draws the entity,
     * and the only reliable "is this the silhouette?" signal at that moment is the state itself.
     * See {@link ForceBlackState}.
     *
     * <p>Only the base model is affected — feature layers (armor, held items, glowing eyes) submit
     * their own models and do not run through {@code getMixColor}. For a sub-second flash that reads
     * as a black silhouette regardless; the dominant body is what sells it.
     */
    @Inject(method = "getMixColor", at = @At("RETURN"), cancellable = true)
    private void keyinput$forceBlackMixColor(LivingEntityRenderState state, CallbackInfoReturnable<Integer> cir) {
        if (((ForceBlackState) state).keyinput$isForceBlack()) {
            cir.setReturnValue(0xFF000000);
        }
    }

    /**
     * Clears the pure-black flag every time the render state is refreshed. The renderer reuses one
     * render-state instance for both the world pass and the GUI silhouette draw, so without this the
     * flag set for the silhouette would linger and paint the mob black in the actual world on the
     * next frame. {@code updateRenderState} runs once per frame for every rendered entity — the
     * world pass therefore always clears it before the entity draws normally, and the silhouette
     * code sets it again by hand only after its own {@code getAndUpdateRenderState} call.
     */
    @Inject(method = UPDATE_RENDER_STATE_METHOD, at = @At("TAIL"))
    private void keyinput$clearForceBlack(LivingEntity entity, LivingEntityRenderState state, float tickProgress, CallbackInfo ci) {
        // TEMPORARY DIAGNOSTIC — logs every rendered living entity that is named or invisible, once
        // per id, regardless of whether hasLabel is ever evaluated. Catches the ModelEngine base mob
        // even if label culling skips it. Remove with the other KORO-NAMETAG logging.
        if ((entity.hasCustomName() || entity.isInvisible()) && keyinput$RS_LOGGED_IDS.add(entity.getId())) {
            LOGGER.info("[KORO-RS] id={} type={} invisible={} customNameVisible={} hasCustomName={} name={}",
                    entity.getId(),
                    entity.getType().toString(),
                    entity.isInvisible(),
                    entity.isCustomNameVisible(),
                    entity.hasCustomName(),
                    entity.getCustomName() != null ? entity.getCustomName().getString() : "null");
        }
        ((ForceBlackState) state).keyinput$setForceBlack(false);
    }
}
