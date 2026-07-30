package com.korosoft.keyinput.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.session.Session;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets {@code AuthSession}/{@code MainMenuScreen} replace {@code MinecraftClient.session} with a
 * copy that carries the KoroAuth-canonical username, right before connecting, so the client's own
 * login handshake (and any client-side display of "your name" before the proxy rewrites the
 * profile) is consistent with the account the player just authenticated as.
 *
 * <p>This is belt-and-suspenders, not the security boundary: the proxy's {@code LoginGatekeeper}
 * (KoroAuth plugin) always overrides the {@code GameProfile} username + UUID from the validated
 * token server-side (design doc "Model A"), regardless of what the client claims here.
 *
 * <p>{@code session} is a private final field on vanilla {@code MinecraftClient} with no public
 * setter, hence the accessor mixin rather than a public API call.
 */
@Mixin(MinecraftClient.class)
public interface MinecraftClientSessionAccessor {

    @Accessor("session")
    @Mutable
    void keyinput$setSession(Session session);
}
