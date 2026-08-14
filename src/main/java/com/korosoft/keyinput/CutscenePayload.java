package com.korosoft.keyinput;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Custom payload sent server -> client on the "keyinput:cutscene" channel.
 * Drives the full-screen transfer curtain (see {@link Cutscene}): {@link #COMMAND_START} begins
 * the fade-in to full white, {@link #COMMAND_END} clears it to lift — the destination backend
 * confirming the player arrived, which the mod combines with a world/player of its own before it
 * actually cuts. END is a clearance, not an order. {@link #COMMAND_RUSH} asks the fade to close
 * early without restarting it (see {@link Cutscene#rush}).
 *
 * <p>{@code kind} was appended after command/fadeMillis were already shipped, and appended is the
 * operative word: an OLD jar only reads the first two ints and drains whatever is left as trailing
 * bytes, so it still plays the ORBIT cinematic end-to-end on a payload carrying a third field it
 * has never heard of. Nothing about the wire format changed for it — this is graceful degradation
 * by construction, not a kick.
 */
public record CutscenePayload(int command, int fadeMillis, int kind) implements CustomPayload {

    public static final CustomPayload.Id<CutscenePayload> ID =
            new CustomPayload.Id<>(Identifier.of("korosoft-core", "cutscene"));

    public static final int COMMAND_START = 1;
    public static final int COMMAND_END = 2;
    public static final int COMMAND_RUSH = 3;

    public static final int KIND_ORBIT = 0;
    public static final int KIND_ASCEND = 1;

    /**
     * Used when an older server sends a shorter payload than this client knows how to read.
     * Defaults to END rather than START: a malformed/truncated payload must never leave the
     * curtain stuck up, so the fail-open direction is "clear it to lift", not "raise it". A
     * clearance is enough — anywhere a curtain could be stuck, there is already a world.
     */
    private static final int DEFAULT_COMMAND = COMMAND_END;
    private static final int DEFAULT_FADE_MILLIS = 600;
    private static final int DEFAULT_KIND = KIND_ORBIT;

    // Written by hand as fixed 4-byte big-endian ints to match the Skript side, which uses
    // DataOutputStream.writeInt(). Note this is NOT about endianness: PacketCodecs.INTEGER is
    // byteBuf.readInt()/writeInt(), i.e. byte-identical to this (the VarInt codec is the separate
    // PacketCodecs.VAR_INT). The real reason to hand-roll is the decoder below.
    //
    // The decoder is deliberately LENIENT, because Minecraft kicks the player outright on a
    // custom payload it cannot decode cleanly: a field the server does not send yet falls back
    // to its default, and any trailing bytes from a newer server are drained instead of left
    // over (unread bytes are a DecoderException, which reads to the player as "Conexión perdida").
    public static final PacketCodec<RegistryByteBuf, CutscenePayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeInt(payload.command());
                buf.writeInt(payload.fadeMillis());
                buf.writeInt(payload.kind());
            },
            buf -> {
                int command = buf.readableBytes() >= Integer.BYTES ? buf.readInt() : DEFAULT_COMMAND;
                int fadeMillis = buf.readableBytes() >= Integer.BYTES ? buf.readInt() : DEFAULT_FADE_MILLIS;
                int kind = buf.readableBytes() >= Integer.BYTES ? buf.readInt() : DEFAULT_KIND;
                buf.skipBytes(buf.readableBytes());
                return new CutscenePayload(command, fadeMillis, kind);
            }
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
