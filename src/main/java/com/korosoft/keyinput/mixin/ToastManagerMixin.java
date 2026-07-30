package com.korosoft.keyinput.mixin;

import net.minecraft.client.toast.SystemToast;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.text.TextContent;
import net.minecraft.text.TranslatableTextContent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Silently swallows two vanilla system toasts that are pure noise on this server:
 *
 * <ul>
 *   <li><b>"Unverified chat messages"</b> ({@code SystemToast.Type.UNSECURE_SERVER_WARNING}) —
 *       always shown here because the proxy relays unsigned chat; the warning is expected and only
 *       alarms players.</li>
 *   <li><b>"Downloading resource pack x/y"</b> ({@code download.pack.title}) — the mod force-accepts
 *       the mandatory server pack on every connect and backend switch (see
 *       {@code ServerResourcePackLoaderMixin}), so this progress toast fires constantly and adds
 *       nothing the player can act on.</li>
 * </ul>
 *
 * <p>Both funnel through the single {@link ToastManager#add(Toast)} choke point. The unsecure
 * warning carries a stable enum-like constant and is matched by identity. The download toast's type
 * is a throwaway {@code new SystemToast.Type()} built inside {@code ServerResourcePackLoader}, so it
 * cannot be matched by constant — it is identified by its translation key via
 * {@link SystemToastAccessor} instead. Cancelling {@code add} keeps the toast out of the visible
 * queue; because the download listener re-issues {@code add} for every progress update (its
 * {@code getToast} lookup never finds a live entry), each subsequent update is dropped the same way.
 */
@Mixin(ToastManager.class)
public class ToastManagerMixin {

    private static final String DOWNLOAD_PACK_TITLE_KEY = "download.pack.title";

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void keyinput$suppressNoiseToasts(Toast toast, CallbackInfo ci) {
        if (!(toast instanceof SystemToast systemToast)) {
            return;
        }

        if (systemToast.getType() == SystemToast.Type.UNSECURE_SERVER_WARNING) {
            ci.cancel();
            return;
        }

        TextContent content = ((SystemToastAccessor) systemToast).keyinput$getTitle().getContent();
        if (content instanceof TranslatableTextContent translatable
                && DOWNLOAD_PACK_TITLE_KEY.equals(translatable.getKey())) {
            ci.cancel();
        }
    }
}
