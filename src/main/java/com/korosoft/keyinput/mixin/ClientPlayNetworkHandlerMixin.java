package com.korosoft.keyinput.mixin;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.MapUpdateS2CPacket;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TEMPORAL — debug del atlas negro. Loguea cada paquete de mapa que llega al cliente para
 * verificar si el server manda el patch de colores y si el cliente lo procesa.
 */
@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {

    @Inject(method = "onMapUpdate", at = @At("HEAD"))
    private void keyinput$debugMapUpdate(MapUpdateS2CPacket packet, CallbackInfo ci) {
        int mapId = packet.mapId().id();
        var dataOpt = packet.updateData();
        int nz = -1;
        int len = -1;
        if (dataOpt.isPresent()) {
            var data = dataOpt.get();
            var colors = data.colors();
            len = colors == null ? -2 : colors.length;
            nz = 0;
            if (colors != null) {
                for (int k = 0; k < Math.min(colors.length, 500); k++) {
                    if (colors[k] != 0) {
                        nz++;
                    }
                }
            }
        }
        LoggerFactory.getLogger("korosoft-core/atlas")
                .info("[ATLAS] onMapUpdate mapId={} scale={} updateDataPresent={} colorsLen={} nz500={}",
                        mapId, packet.scale(), dataOpt.isPresent(), len, nz);
    }
}
