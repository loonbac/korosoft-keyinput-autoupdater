package com.korosoft.keyinput;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Custom payload sent client -> server on the "keyinput:cutready" channel.
 * Carries no data: the mod sends it once, the moment the transfer curtain reaches full white,
 * telling the server it is now safe to send the BungeeCord "Connect" that switches backends.
 */
public record CutreadyPayload() implements CustomPayload {

    public static final CutreadyPayload INSTANCE = new CutreadyPayload();

    public static final CustomPayload.Id<CutreadyPayload> ID =
            new CustomPayload.Id<>(Identifier.of("keyinput", "cutready"));

    // Nothing to encode, so PacketCodec.unit() writes zero bytes on the wire. Fine to decode
    // with a VarInt-style codec like KeyPayload's, unlike the S2C payloads: Skript's incoming
    // listener just gets handed the raw (empty) byte array, it does not run one of the mod's
    // codecs to read it.
    public static final PacketCodec<RegistryByteBuf, CutreadyPayload> CODEC = PacketCodec.unit(INSTANCE);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
