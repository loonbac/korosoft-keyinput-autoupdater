package com.korosoft.keyinput;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Custom payload sent client -> server on the "korosoft-core:scroll" channel.
 * Carries the vertical scroll delta (GLFW wheel) in "notches": +1 = scroll up,
 * -1 = scroll down. Sent whenever the mouse wheel moves while a screen that
 * cares about it is open (the server decides what the scroll means).
 */
public record ScrollPayload(int delta) implements CustomPayload {

    public static final CustomPayload.Id<ScrollPayload> ID =
            new CustomPayload.Id<>(Identifier.of("korosoft-core", "scroll"));

    public static final PacketCodec<RegistryByteBuf, ScrollPayload> CODEC =
            PacketCodec.tuple(PacketCodecs.INTEGER, ScrollPayload::delta, ScrollPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
