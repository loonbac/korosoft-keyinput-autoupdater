package com.korosoft.keyinput.mixin;

import com.korosoft.keyinput.Cutscene;
import com.korosoft.keyinput.HudAnimator;
import com.korosoft.keyinput.HudBarsState;
import com.korosoft.keyinput.HudConfig;
import com.korosoft.keyinput.ParryFlash;
import com.korosoft.keyinput.ParrySilhouette;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.scoreboard.ScoreboardObjective;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Slides the scoreboard sidebar in from the right edge while the player list key is held.
 * The sidebar is hidden (not drawn at all) when the animation is fully retracted.
 * Also paints the parry flash over the finished HUD, and takes the whole HUD away for the
 * duration of the transfer cinematic.
 */
@Mixin(InGameHud.class)
public class InGameHudMixin {

    /**
     * The one HEAD entry point of the HUD. The cinematic's HUD blackout is folded in here rather
     * than added as a second HEAD injector on purpose: Mixin gives no ordering guarantee between
     * two injectors sharing a target point, so a separate canceller could just as easily run
     * before this one and skip the animation tick — the same ordering trap slice 1 hit.
     *
     * <p>The animation is advanced <em>before</em> the cancel, not after: {@link HudAnimator} is
     * documented as advancing exactly once per rendered frame and measures a real-time delta off
     * its own last-frame stamp. Cancelling first would leave that stamp seconds stale and spend a
     * clamped catch-up step on the frame the HUD returns. Advancing first keeps the contract and
     * means the HUD is already in the right position the moment it comes back.
     *
     * <p>Cancelling here takes the whole vanilla HUD — hotbar, hearts, crosshair, chat, boss bars,
     * scoreboard — which is the point. It cannot reach the transfer curtain: that is painted from
     * GameRenderer#render (see {@code GameRendererMixin}), which is exactly why it lives there.
     */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void keyinput$advanceHudAnimation(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        HudAnimator.update();

        // derived per frame from the one piece of cinematic state, never a flag of our own, so
        // the HUD cannot stay hidden past Cutscene.resetToIdle()
        if (Cutscene.isActive()) {
            ci.cancel();
        }
    }

    /**
     * Full-screen white flash for a landed parry. Injected at TAIL so it covers the whole HUD
     * (hearts, hotbar, chat) instead of being painted over by it.
     */
        @Inject(method = "render", at = @At("TAIL"))
    private void keyinput$renderParryFlash(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        // Fallback HUD bars for AMD: painted over the finished HUD, before the parry flash so a
        // landed parry still covers them like everything else (see HudBarsState).
        HudBarsState.render(context);

        float alpha = ParryFlash.getAlpha();
        if (alpha <= ParryFlash.EPSILON) {
            return;
        }
        // ARGB: alpha in the high byte, pure white below it
        int argb = (Math.round(alpha * 255.0F) << 24) | 0x00FFFFFF;
        context.fill(0, 0, context.getScaledWindowWidth(), context.getScaledWindowHeight(), argb);

        // The parried mob, pure black, frozen where it was — queued after the white fill so the
        // deferred GUI batch draws it on top of the flash. No-op when the server sent no entity.
        ParrySilhouette.render(context);
    }

    /**
     * Wraps the inner sidebar draw (the overload that actually paints it) rather than the
     * outer one, because the outer opens a new root layer first. Wrapping instead of
     * injecting keeps pushMatrix/popMatrix balanced even if the original throws.
     */
    @WrapMethod(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V")
    private void keyinput$slideScoreboardSidebar(DrawContext context, ScoreboardObjective objective, Operation<Void> original) {
        float progress = HudAnimator.getSidebarProgress();
        if (progress <= HudAnimator.EPSILON) {
            // fully slid out: skip the draw entirely, so the sidebar is hidden by default
            return;
        }

        float eased = HudAnimator.easeOutCubic(progress);
        Matrix3x2fStack matrices = context.getMatrices();
        matrices.pushMatrix();
        // +X pushes it off toward the right edge; Y is the server-configured offset
        matrices.translate((1.0F - eased) * HudAnimator.SIDEBAR_SLIDE_DISTANCE, HudConfig.getSidebarYOffset());
        try {
            original.call(context, objective);
        } finally {
            matrices.popMatrix();
        }
    }

    /**
     * Vanilla drops the tablist the instant the key is released, which would eat the whole
     * slide-out. Keeping this gate true while the animation is still running lets it play,
     * and it flips back to vanilla behaviour once the tablist is fully retracted.
     */
    @ModifyExpressionValue(
            method = "renderPlayerList(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/option/KeyBinding;isPressed()Z")
    )
    private boolean keyinput$keepPlayerListVisibleWhileSliding(boolean pressed) {
        return pressed || HudAnimator.getTablistProgress() > HudAnimator.EPSILON;
    }
}
