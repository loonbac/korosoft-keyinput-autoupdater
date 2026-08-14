package com.korosoft.keyinput;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Custom payload sent client -> server on the "keyinput:keys" channel.
 * Carries the GLFW key code of the key that was just pressed.
 */
public record KeyPayload(int keyCode) implements CustomPayload {

    public static final CustomPayload.Id<KeyPayload> ID =
            new CustomPayload.Id<>(Identifier.of("korosoft-core", "keys"));

    public static final PacketCodec<RegistryByteBuf, KeyPayload> CODEC =
            PacketCodec.tuple(PacketCodecs.INTEGER, KeyPayload::keyCode, KeyPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
