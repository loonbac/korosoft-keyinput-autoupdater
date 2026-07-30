package com.korosoft.keyinput;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Custom payload sent server -> client on the "keyinput:parry" channel.
 * Sent only when a parry actually lands (the damage was cancelled), never on the key press.
 *
 * <p>The server owns the whole look of the flash, so it can be retuned with a script reload
 * instead of a mod rebuild — which would otherwise force every player to re-download the jar.
 * {@code holdPercent} and {@code peakPercent} are 0-100 so the wire format stays all-int.
 */
public record ParryFlashPayload(int flashMillis, int holdPercent, int peakPercent, int attackerEntityId)
        implements CustomPayload {

    public static final CustomPayload.Id<ParryFlashPayload> ID =
            new CustomPayload.Id<>(Identifier.of("keyinput", "parry"));

    /** Used when an older server sends a shorter payload than this client knows how to read. */
    private static final int DEFAULT_FLASH_MILLIS = 320;
    private static final int DEFAULT_HOLD_PERCENT = 45;
    private static final int DEFAULT_PEAK_PERCENT = 72;
    // No fourth int from an older server means "no silhouette" — the flash still plays.
    private static final int DEFAULT_ATTACKER_ENTITY_ID = -1;

    // Written by hand as fixed 4-byte big-endian ints to match the Skript side, which uses
    // DataOutputStream.writeInt(). PacketCodecs.INTEGER is a VarInt and would decode those
    // bytes as garbage.
    //
    // The decoder is deliberately LENIENT, because Minecraft kicks the player outright on a
    // custom payload it cannot decode cleanly: a field the server does not send yet falls back
    // to its default, and any trailing bytes from a newer server are drained instead of left
    // over (unread bytes are a DecoderException, which reads to the player as "Conexión perdida").
    public static final PacketCodec<RegistryByteBuf, ParryFlashPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeInt(payload.flashMillis());
                buf.writeInt(payload.holdPercent());
                buf.writeInt(payload.peakPercent());
                buf.writeInt(payload.attackerEntityId());
            },
            buf -> {
                int flashMillis = buf.readableBytes() >= Integer.BYTES ? buf.readInt() : DEFAULT_FLASH_MILLIS;
                int holdPercent = buf.readableBytes() >= Integer.BYTES ? buf.readInt() : DEFAULT_HOLD_PERCENT;
                int peakPercent = buf.readableBytes() >= Integer.BYTES ? buf.readInt() : DEFAULT_PEAK_PERCENT;
                int attackerEntityId = buf.readableBytes() >= Integer.BYTES ? buf.readInt() : DEFAULT_ATTACKER_ENTITY_ID;
                buf.skipBytes(buf.readableBytes());
                return new ParryFlashPayload(flashMillis, holdPercent, peakPercent, attackerEntityId);
            }
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
