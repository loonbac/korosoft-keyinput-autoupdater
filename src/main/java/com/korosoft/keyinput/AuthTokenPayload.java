package com.korosoft.keyinput;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;

/**
 * Custom payload sent client -> server on {@code korosoft:auth}, once per JOIN, carrying the raw
 * KoroAuth session token so the proxy can authenticate the player in the play phase.
 *
 * <p>This replaces the original login-phase challenge/response (see {@code KeyInputClient}'s
 * login responder and {@code PlayAuthGatekeeper} on the plugin side): the proxy build in use does
 * not deliver login-phase plugin messages to the Fabric client, so the token is presented in the
 * play phase instead — the same reliable mechanism {@link HelloPayload} already uses.
 *
 * <p>Wire format: the token's raw UTF-8 bytes, no length prefix — the proxy reads the whole
 * payload as a UTF-8 string ({@code new String(event.getData(), UTF_8)}).
 */
public record AuthTokenPayload(String token) implements CustomPayload {

    public static final CustomPayload.Id<AuthTokenPayload> ID =
            new CustomPayload.Id<>(Identifier.of("korosoft", "auth"));

    public static final PacketCodec<RegistryByteBuf, AuthTokenPayload> CODEC = PacketCodec.of(
            (payload, buf) -> buf.writeBytes(payload.token().getBytes(StandardCharsets.UTF_8)),
            buf -> {
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                return new AuthTokenPayload(new String(bytes, StandardCharsets.UTF_8));
            }
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
