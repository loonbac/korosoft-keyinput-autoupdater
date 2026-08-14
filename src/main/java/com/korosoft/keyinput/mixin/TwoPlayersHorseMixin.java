package com.korosoft.keyinput.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Doble montura: reposiciona al segundo pasajero del caballo hacia atrás y abajo.
 *
 * <p>Port del mod "Two Players One Horse" (Beethoven92, GPLv3) a Korosoft-Core.
 * El server (Skript, doble_montura.sk) es el dueño de la lógica: hace el
 * {@code addPassenger()} del 2º jugador. Este mixin SOLO arregla el render:
 * el cliente vanilla coloca a todos los pasajeros en el mismo punto de la silla
 * (el segundo flota/superpuesto); aquí le damos su propio punto de montaje.
 *
 * <p>Lógica idéntica al original (nombres Yarn 1.21.11 verificados con javap):
 * <ul>
 *   <li>Índice 0 (conductor): offset {@code (+0.2, 0, -0.2)} — apenas adelante.</li>
 *   <li>Índice 1 (2º pasajero): offset {@code (-0.6, -0.3*height, -0.7*height)} — atrás y abajo.</li>
 * </ul>
 * El offset se rota con el yaw del caballo (igual que hace el vanilla). Se usa
 * {@code super.getPassengerAttachmentPos} (el de {@code AnimalEntity}, la silla) para
 * no recurrir infinitamente con el inject a HEAD.
 */
@Mixin(AbstractHorseEntity.class)
public abstract class TwoPlayersHorseMixin extends AbstractHorseEntity {

    /** El {@code standAnimO} de Mojang — campo Yarn verificado por bytecode. */
    @Shadow
    private float lastAngryAnimationProgress;

    protected TwoPlayersHorseMixin(EntityType<? extends AbstractHorseEntity> entityType, World world) {
        super(entityType, world);
    }

    @Inject(method = "getPassengerAttachmentPos", at = @At("HEAD"), cancellable = true)
    private void keyinput$secondPassengerAttachment(Entity passenger, EntityDimensions dimensions,
                                                    float tickDelta, CallbackInfoReturnable<Vec3d> cir) {
        List<Entity> passengers = getPassengerList();
        int passengerIndex = Math.max(passengers.indexOf(passenger), 0);

        double horizontalOffset;
        double verticalOffset;
        if (passengers.size() > 1) {
            horizontalOffset = passengerIndex == 0 ? 0.2 : -0.6;
            verticalOffset = passengerIndex == 0 ? 0.0 : -0.3 * lastAngryAnimationProgress;
        } else {
            horizontalOffset = 0.0;
            verticalOffset = 0.0;
        }

        // super = AnimalEntity.getPassengerAttachmentPos (el punto de montaje base de la silla)
        Vec3d base = super.getPassengerAttachmentPos(passenger, dimensions, tickDelta);
        Vec3d offset = new Vec3d(0.0,
                0.15 * lastAngryAnimationProgress * tickDelta + verticalOffset,
                -0.7 * lastAngryAnimationProgress * tickDelta + horizontalOffset);
        // el offset se expresa en el espacio del caballo, no del mundo
        Vec3d rotated = offset.rotateY(-(float) Math.toRadians(getYaw()));
        cir.setReturnValue(base.add(rotated));
    }
}
