package com.korosoft.keyinput;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Custom payload sent server -> client on the "korosoft-core:head" channel. Carries the FULL
 * render spec for a player's worn head accessory (e.g. portable radio, hats) — variant CMD, anchor,
 * scale, rotation (yaw, pitch, roll) and vertical flip — so it renders client-side on that player's
 * head (zero entity lag, glued to the animated head model — see {@link HeadAccessoryRenderLayer}).
 */
public record HeadAccessoryPayload(UUID playerUuid, int cmd, float headX, float headY, float headZ, float scale,
                                   float yawDeg, float pitchDeg, float rollDeg, int flip) implements CustomPayload {

    public static final CustomPayload.Id<HeadAccessoryPayload> ID =
            new CustomPayload.Id<>(Identifier.of("korosoft-core", "head"));

    public static final int NONE = 0;

    public static final float DEFAULT_HEAD_X = 0.0F;
    public static final float DEFAULT_HEAD_Y = 0.60F;
    public static final float DEFAULT_HEAD_Z = 0.0F;
    public static final float DEFAULT_SCALE = 0.70F;
    public static final float DEFAULT_YAW_DEG = 0.0F;
    public static final float DEFAULT_PITCH_DEG = 0.0F;
    public static final float DEFAULT_ROLL_DEG = 0.0F;
    public static final int DEFAULT_FLIP = 1;

    public static final PacketCodec<RegistryByteBuf, HeadAccessoryPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeInt(1);
                writeUtf(buf, payload.playerUuid().toString());
                buf.writeInt(payload.cmd());
                buf.writeFloat(payload.headX());
                buf.writeFloat(payload.headY());
                buf.writeFloat(payload.headZ());
                buf.writeFloat(payload.scale());
                buf.writeFloat(payload.yawDeg());
                buf.writeFloat(payload.pitchDeg());
                buf.writeFloat(payload.rollDeg());
                buf.writeInt(payload.flip());
            },
            buf -> {
                readInt(buf, 1);
                String uuid = readUtf(buf, "");
                int cmd = readInt(buf, NONE);
                float headX = readFloat(buf, DEFAULT_HEAD_X);
                float headY = readFloat(buf, DEFAULT_HEAD_Y);
                float headZ = readFloat(buf, DEFAULT_HEAD_Z);
                float scale = readFloat(buf, DEFAULT_SCALE);
                float yawDeg = readFloat(buf, DEFAULT_YAW_DEG);
                float pitchDeg = readFloat(buf, DEFAULT_PITCH_DEG);
                float rollDeg = readFloat(buf, DEFAULT_ROLL_DEG);
                int flip = readInt(buf, DEFAULT_FLIP);
                buf.skipBytes(buf.readableBytes());

                UUID playerUuid = null;
                if (!uuid.isEmpty()) {
                    try {
                        playerUuid = UUID.fromString(uuid);
                    } catch (IllegalArgumentException ignored) {
                        playerUuid = null;
                    }
                }
                if (playerUuid == null) {
                    return new HeadAccessoryPayload(UUID.randomUUID(), NONE, DEFAULT_HEAD_X, DEFAULT_HEAD_Y,
                            DEFAULT_HEAD_Z, DEFAULT_SCALE, DEFAULT_YAW_DEG, DEFAULT_PITCH_DEG, DEFAULT_ROLL_DEG, DEFAULT_FLIP);
                }
                return new HeadAccessoryPayload(playerUuid, cmd, headX, headY, headZ, scale, yawDeg, pitchDeg, rollDeg, flip);
            }
    );

    private static void writeUtf(RegistryByteBuf buf, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        buf.writeShort(bytes.length);
        buf.writeBytes(bytes);
    }

    private static String readUtf(RegistryByteBuf buf, String fallback) {
        if (buf.readableBytes() < Short.BYTES) {
            return fallback;
        }
        int len = buf.readUnsignedShort();
        if (buf.readableBytes() < len) {
            buf.skipBytes(buf.readableBytes());
            return fallback;
        }
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int readInt(RegistryByteBuf buf, int fallback) {
        return buf.readableBytes() >= Integer.BYTES ? buf.readInt() : fallback;
    }

    private static float readFloat(RegistryByteBuf buf, float fallback) {
        return buf.readableBytes() >= Float.BYTES ? buf.readFloat() : fallback;
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
