package com.korosoft.keyinput.mixin;

import com.korosoft.keyinput.HudAnimator;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Slides the tablist down from above the top edge while the player list key is held.
 * Whether this runs at all is decided by InGameHudMixin's visibility gate.
 */
@Mixin(PlayerListHud.class)
public class PlayerListHudMixin {

    @WrapMethod(method = "render")
    private void keyinput$slidePlayerList(DrawContext context, int scaledWindowWidth, Scoreboard scoreboard,
                                          ScoreboardObjective objective, Operation<Void> original) {
        float eased = HudAnimator.easeOutCubic(HudAnimator.getTablistProgress());
        Matrix3x2fStack matrices = context.getMatrices();
        matrices.pushMatrix();
        // -Y lifts it above the top of the screen, so it enters from above
        matrices.translate(0.0F, -(1.0F - eased) * HudAnimator.TABLIST_SLIDE_DISTANCE);
        try {
            original.call(context, scaledWindowWidth, scoreboard, objective);
        } finally {
            matrices.popMatrix();
        }
    }
}
