package com.korosoft.keyinput;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;

/**
 * Whether the local player should see their own floating nametag. Vanilla never shows it: the
 * last line of {@code LivingEntityRenderer#hasLabel} ends in
 * {@code ... && livingEntity != minecraftClient.getCameraEntity() && ...}, and for the local
 * player those two are the same entity. {@code LivingEntityRendererMixin} relaxes exactly that
 * one term through this gate.
 *
 * <p>Everything here is derived per frame from state someone else owns — the perspective option
 * and {@link Cutscene} — so there is nothing to leak or reset.
 */
public final class SelfNameTag {

    private SelfNameTag() {
    }

    /**
     * @param cameraEntity whatever {@code MinecraftClient#getCameraEntity()} just returned inside
     *                     {@code hasLabel}
     * @return true when that call should be treated as "not the local player", i.e. when the own
     *         tag is wanted
     */
    public static boolean shouldShow(Entity cameraEntity) {
        // the transfer cinematic is meant to be clean — no HUD, and no tag floating over the
        // player being orbited. Derived, never a flag of our own, like every other cinematic gate.
        if (Cutscene.isActive()) {
            return false;
        }

        MinecraftClient client = MinecraftClient.getInstance();

        // Scoped to the local player on purpose. When the camera is attached to something else
        // (spectating a mob), vanilla's suppression of *that* entity's tag must stay untouched:
        // returning false here hands hasLabel back its real camera entity, unmodified.
        if (cameraEntity == null || cameraEntity != client.player) {
            return false;
        }

        // false for both THIRD_PERSON_BACK and THIRD_PERSON_FRONT, true only for FIRST_PERSON —
        // where there is no model to hang a tag on anyway. This is the F5 state the player sees,
        // not the camera's own third-person flag, which the cinematic forces behind their back.
        return !client.options.getPerspective().isFirstPerson();
    }
}
