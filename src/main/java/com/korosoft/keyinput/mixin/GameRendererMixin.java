package com.korosoft.keyinput.mixin;

import com.korosoft.keyinput.Cutscene;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paints the full-screen transfer curtain (see {@link Cutscene}) from GameRenderer#render rather
 * than InGameHud#render, because InGameHud#render is skipped entirely whenever there is no world —
 * exactly the window a Velocity backend switch opens up. It is also why the curtain survives the
 * cinematic's HUD suppression (see {@code InGameHudMixin}): it was never part of the HUD to begin
 * with, and cancelling InGameHud#render cannot reach it.
 *
 * <p><b>Why not a literal bytecode TAIL:</b> in this Yarn 1.21.11 pipeline, GameRenderer#render
 * builds its {@link DrawContext} against a buffered {@code GuiRenderState} that only actually
 * reaches the screen via the {@code GuiRenderer#render(GpuBufferSlice)} call near the end of the
 * method — the whole HUD/screen/toast/overlay pass is deferred, not immediate-mode. A callback
 * injected at the method's true TAIL runs AFTER that flush already submitted the frame and
 * BEFORE the next frame's {@code guiState.clear()}: any {@code DrawContext} draws added there
 * are queued into a buffer nobody ever reads again — dead, invisible code. Injecting immediately
 * before that flush call is the actual "last thing painted, on top of everything queued this
 * frame" point for this pipeline, and the flush itself is unconditional (not gated behind
 * {@code world != null} or a screen being open), so slice 1's requirement — the curtain covering
 * the whole no-world transfer window — still holds.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    private static final String RENDER_METHOD = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V";
    private static final String RENDER_HAND_METHOD = "renderHand(FZLorg/joml/Matrix4f;)V";
    private static final String GUI_FLUSH_TARGET =
            "Lnet/minecraft/client/gui/render/GuiRenderer;render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V";
    private static final String IS_FIRST_PERSON_TARGET =
            "Lnet/minecraft/client/option/Perspective;isFirstPerson()Z";

    /**
     * The single per-frame entry point for the curtain: advances the fade/hold/failsafe state
     * machine and paints it.
     */
    @Inject(method = RENDER_METHOD, at = @At(value = "INVOKE", target = GUI_FLUSH_TARGET))
    private void keyinput$renderCutscene(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci,
                                         @Local DrawContext drawContext) {
        Cutscene.tick();

        float alpha = Cutscene.getAlpha();
        if (alpha > Cutscene.EPSILON) {
            // ARGB: alpha in the high byte, pure white below it — same convention as ParryFlash.
            // A 6-digit literal here would leave alpha at 0 and draw nothing.
            int argb = (Math.round(alpha * 255.0F) << 24) | 0x00FFFFFF;
            drawContext.fill(0, 0, drawContext.getScaledWindowWidth(), drawContext.getScaledWindowHeight(), argb);
        }
    }

    /**
     * Drops the first-person held item for the cinematic. {@code CameraMixin} detaches the camera
     * from the player's eye, but renderHand keys off {@code options.getPerspective()} rather than
     * off the camera, so the hand would otherwise stay welded to the lens and slide out of the
     * player's head with it.
     *
     * <p>Only the perspective term of renderHand's condition is forced: cancelling the method
     * outright would also skip the entityRenderDispatcher and vertex-consumer flushes it opens
     * with, which have nothing to do with the hand. Gated on {@code isActive()} rather than on the
     * camera's own third-person gate on purpose — the hand has to go the instant the camera
     * detaches, which is earlier (see {@code CutsceneCamera#isThirdPersonAt}).
     */
    @ModifyExpressionValue(method = RENDER_HAND_METHOD, at = @At(value = "INVOKE", target = IS_FIRST_PERSON_TARGET))
    private boolean keyinput$hideHeldItemDuringCutscene(boolean firstPerson) {
        return firstPerson && !Cutscene.isActive();
    }
}
