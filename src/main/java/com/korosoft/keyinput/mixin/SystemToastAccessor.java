package com.korosoft.keyinput.mixin;

import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@link SystemToast}'s private {@code title} field so {@code ToastManagerMixin} can tell
 * apart the download-progress toast (whose type is a throwaway {@code new SystemToast.Type()}
 * instance built inside {@code ServerResourcePackLoader}, so it cannot be matched by constant)
 * from every other system toast, by inspecting its translation key instead.
 */
@Mixin(SystemToast.class)
public interface SystemToastAccessor {

    @Accessor("title")
    Text keyinput$getTitle();
}
