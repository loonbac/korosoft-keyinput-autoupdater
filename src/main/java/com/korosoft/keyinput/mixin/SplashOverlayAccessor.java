package com.korosoft.keyinput.mixin;

import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.gui.screen.SplashOverlay;
import net.minecraft.resource.ResourceReload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the two things the vanilla {@code SplashOverlay} was built with — the resource reload and
 * the load-completion callback — so {@code MinecraftClientMixin} can hand them to
 * {@link com.korosoft.keyinput.KoroBootOverlay} when it swaps the overlay out. The vanilla splash
 * instance is constructed and then discarded; only these two references are salvaged from it.
 */
@Mixin(SplashOverlay.class)
public interface SplashOverlayAccessor {

    @Accessor("reload")
    ResourceReload getReload();

    @Accessor("exceptionHandler")
    Consumer<Optional<Throwable>> getExceptionHandler();
}
