package com.korosoft.keyinput.mixin;

import com.korosoft.keyinput.ConnectSequence;
import java.net.URL;
import java.util.UUID;
import net.minecraft.client.resource.server.ServerResourcePackLoader;
import net.minecraft.client.resource.server.ServerResourcePackManager;
import net.minecraft.network.ClientConnection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Always accepts the server's resource pack, silently, no matter the client's per-server policy.
 * The server pack is mandatory for this modpack (it carries every custom item, block, font and
 * sound), so the vanilla "accept / decline" prompt is only a way to end up with a broken-looking
 * game. {@code init} runs when the connection hands the loader its acceptance policy; forcing
 * {@code acceptAll()} at its tail overrides whatever was decided — including a previously declined
 * or disabled setting — so every pack that follows is accepted and downloaded automatically.
 *
 * <p>The second inject flags {@link ConnectSequence} as soon as the pack download is queued, which
 * is the earliest real signal that login finished and the server started pushing the pack — the cue
 * {@code ConnectOverlay} uses to move its phase text from "Conectando..." to "Descargando...".
 */
@Mixin(ServerResourcePackLoader.class)
public class ServerResourcePackLoaderMixin {

    @Shadow @Final private ServerResourcePackManager manager;

    @Inject(method = "init", at = @At("TAIL"))
    private void keyinput$alwaysAccept(ClientConnection connection, ServerResourcePackManager.AcceptanceStatus status, CallbackInfo ci) {
        this.manager.acceptAll();
    }

    @Inject(method = "addResourcePack(Ljava/util/UUID;Ljava/net/URL;Ljava/lang/String;)V", at = @At("HEAD"))
    private void keyinput$markDownloading(UUID id, URL url, String hash, CallbackInfo ci) {
        ConnectSequence.markDownloading();
    }
}
