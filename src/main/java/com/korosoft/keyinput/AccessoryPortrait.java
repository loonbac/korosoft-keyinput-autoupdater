package com.korosoft.keyinput;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.ArmedEntityRenderState;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws the local player as a posed portrait — the centerpiece of the accessories screen, with the
 * five ring slots arranged around it (see {@code accesorios.sk}). Positioned and drawn on top of the
 * panel and its slots, per {@code HandledScreenMixin}'s TAIL hook into {@code render}.
 *
 * <p>Much simpler than {@link ParrySilhouette}, which is nonetheless its working reference for this
 * whole pipeline: there is no world entity to track, no distance-based sizing, and no behind-camera
 * rejection — the player is always available and always on screen while this GUI is open. What
 * carries over unchanged is the deferred-render mechanics: grab the render state via
 * {@code getAndUpdateRenderState}, mutate it, and queue it with {@code DrawContext.addEntity}.
 *
 * <p>The arm/head POSE is NOT set here. The pack ships a custom EMF (Fresh Animations) player model
 * that owns the player's rig and re-poses it every frame from its own animations, so anything this
 * mod writes to the vanilla model is overwritten. The accessories-menu pose therefore lives in the
 * pack instead ({@code emf/cem/player.jem}), gated on EMF's {@code is_in_gui} variable — which this
 * very {@code addEntity} render is what turns on. This class only positions, sizes and faces the
 * portrait; the pack does the posing.
 */
public final class AccessoryPortrait {

    // ---- Look calibration. STARTING GUESSES — eyeball-tune these in game. ----

    // How far the scissor box around addEntity extends past `size` on each side. Too small clips
    // the model at the edges (mainly the outstretched arms); too large costs nothing since the box
    // is otherwise empty. Widen this first if the portrait looks cut off.
    private static final float RECT_MARGIN = 1.4F;

    // Face the viewer. 180 is dead-on front in the deferred GUI pipeline (same value
    // ParrySilhouette uses to turn the mob toward the camera); 0 renders the BACK. A few degrees
    // off 180 give a subtle three-quarter portrait instead of a flat mugshot.
    private static final float BODY_YAW = 180.0F;

    // Fully lit, matching ParrySilhouette's GUI-snapshot value, to avoid the portrait picking up
    // an odd lighting tint from wherever the game happened to last compute ambient light.
    private static final int FULL_BRIGHT = 15728880;

    /**
     * True only while our accessories screen is the one being rendered. Consumed by
     * {@link EmfGuiGate} (driven from {@code GuiRendererMixin}) to confine EMF's {@code is_in_gui}
     * animation variable — and with it the pack's {@code player.jem} menu pose — to this screen,
     * so the pose does NOT show in the vanilla inventory, which is also {@code is_in_gui}.
     * {@link mixin.HandledScreenMixin} sets this every frame from the resolved layout. Volatile
     * because it is read during the GUI flush, outside the screen-render call stack that writes it.
     */
    public static volatile boolean MENU_ACTIVE = false;

    private AccessoryPortrait() {
    }

    /**
     * Queues the portrait for this frame at the given screen position. {@code centerX}/{@code
     * centerY} are absolute screen coordinates (panel origin already folded in by the caller);
     * {@code size} is the render size in GUI px, passed straight through to
     * {@code DrawContext.addEntity} as its `size` argument.
     */
    public static void render(DrawContext context, int centerX, int centerY, int size) {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerEntity player = mc.player;
        if (player == null) {
            return;
        }

        // Mirrors ParrySilhouette's setup: grab the renderer, pull a render state snapshot, mutate
        // it, then queue it. GUI entity rendering is deferred; the pack's EMF model does the posing.
        EntityRenderManager dispatcher = mc.getEntityRenderDispatcher();
        EntityRenderer<? super PlayerEntity, ?> renderer = dispatcher.getRenderer(player);
        EntityRenderState state = renderer.getAndUpdateRenderState(player, 1.0F);
        state.light = FULL_BRIGHT;
        state.outlineColor = 0;
        state.shadowPieces.clear();

        // No floating username over the portrait. The deferred GUI snapshot otherwise draws the
        // player's name label; with the model flipped upright (rotateZ below) it lands UNDER the
        // figure as ghost text. Nulling the label position suppresses it for this snapshot only —
        // the world render rebuilds it next frame from getAndUpdateRenderState.
        state.nameLabelPos = null;
        state.displayName = null;

        if (state instanceof LivingEntityRenderState livingState) {
            livingState.bodyYaw = BODY_YAW;
            livingState.relativeHeadYaw = 0.0F;
            livingState.pitch = 0.0F;
            // Undo the baked base scale so `size` alone controls how big it renders, same as
            // ParrySilhouette and vanilla's own InventoryScreen.drawEntity.
            livingState.width = livingState.width / livingState.baseScale;
            livingState.height = livingState.height / livingState.baseScale;
            livingState.baseScale = 1.0F;
            // The portrait is a clean model shot — whatever the player happens to be holding must
            // NOT appear in their hand here. The held-item feature renderer draws from these
            // ItemRenderStates; clearing them (isEmpty -> true) makes both hands, the head slot and
            // the spyglass render empty for this snapshot only. The world render rebuilds them next
            // frame from getAndUpdateRenderState, so nothing leaks back out.
            livingState.headItemRenderState.clear();
        }
        if (state instanceof ArmedEntityRenderState armed) {
            armed.rightHandItemState.clear();
            armed.leftHandItemState.clear();
            armed.rightHandItem = ItemStack.EMPTY;
            armed.leftHandItem = ItemStack.EMPTY;
        }
        if (state instanceof PlayerEntityRenderState playerState) {
            playerState.spyglassState.clear();
        }

        Vector3f offset = new Vector3f(0.0F, state.height / 2.0F, 0.0F);
        // GUI space has Y flipped relative to the model's own space; rotating 180 degrees around Z
        // is what makes the deferred snapshot come out upright instead of drawn upside down.
        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI);
        Quaternionf cameraAngle = new Quaternionf();

        int half = Math.round(size * RECT_MARGIN);
        int x1 = centerX - half;
        int y1 = centerY - half;
        int x2 = centerX + half;
        int y2 = centerY + half;

        context.addEntity(state, size, offset, rotation, cameraAngle, x1, y1, x2, y2);
    }
}
