package com.korosoft.keyinput;

import java.util.UUID;

/**
 * Carried by {@code PlayerEntityRenderState} (mixed in by
 * {@code mixin.PlayerEntityRenderStateMixin}) so the backpack layer knows WHICH player it is
 * drawing for. The 1.21.11 render-state pipeline hands feature layers a state, never the entity —
 * the UUID is the only stable key into {@link BackpackState}.
 */
public interface BackpackRenderState {

    UUID keyinput$getPlayerUuid();

    void keyinput$setPlayerUuid(UUID playerUuid);
}
