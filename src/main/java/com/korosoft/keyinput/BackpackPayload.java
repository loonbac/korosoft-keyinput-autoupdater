package com.korosoft.keyinput;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Custom payload sent server -> client on the "korosoft-core:backpack" channel. Carries the FULL
 * render spec for a player's worn backpack — variant, anchor, scale, yaw — so the backpack
 * renders client-side on that player's back (zero server-entity lag — see
 * {@link BackpackRenderLayer}) and NOTHING about its placement is hardcoded in the mod.
 *
 * <p>The client resolves the model through the full {@code ItemModelManager} path with a real
 * paper+CMD stack, exactly like the server's own item displays — that is what makes the textures
 * and the model's {@code fixed} display transform apply.
 *
 * <p>Wire format v6, matching what the Skript side writes with DataOutputStream:
 *
 * <pre>
 *   int   version
 *   UTF   playerUuid   the worn player's UUID
 *   int   cmd          paper custom_model_data of the worn variant (0 = not worn)
 *   float backY        anchor height above the feet, blocks
 *   float backZ        anchor distance behind the body center, blocks
 *   float scale        extra scale multiplier (on top of the model's fixed transform)
 *   float yawDeg       rotation around the Y axis, degrees (model front faces the viewer at 0)
 *   float originY      entity render origin offset above the feet (measured ~2.0)
 *   int   flip         vertical flip of the model (1 = model is drawn upside-down in the
 *                      layer space and must be flipped back; 0 = normal). Server-driven:
 *                      the feature-layer space has +Y pointing down, item models are Y-up.
 * </pre>
 *
 * <p>The decoder is deliberately LENIENT, exactly like {@link ScreenLayoutPayload}: a garbled or
 * truncated payload must never kick the player, so every field falls back to a safe default and
 * trailing bytes are drained. Older senders decode with {@code cmd=0} — "not worn" — the safe
 * fail-closed direction.
 */
public record BackpackPayload(UUID playerUuid, int cmd, float backY, float backZ, float scale, float yawDeg,
                              float originY, int flip) implements CustomPayload {

    public static final CustomPayload.Id<BackpackPayload> ID =
            new CustomPayload.Id<>(Identifier.of("korosoft-core", "backpack"));

    /** Sent by the server when the backpack comes off (or the player leaves). */
    public static final int NONE = 0;

    /** Fallbacks matching the pre-payload server constants. */
    public static final float DEFAULT_BACK_Y = 1.05F;
    public static final float DEFAULT_BACK_Z = 0.25F;
    public static final float DEFAULT_SCALE = 1.0F;
    public static final float DEFAULT_YAW_DEG = 180.0F;
    /** Entity render origin sits ~2.0 blocks above the feet (measured from the layer matrices). */
    public static final float DEFAULT_ORIGIN_Y = 2.0F;
    /** Vertical flip: 1 = flip the model upside-down to compensate the layer space; 0 = normal. */
    public static final int DEFAULT_FLIP = 1;

    public static final PacketCodec<RegistryByteBuf, BackpackPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeInt(6);
                writeUtf(buf, payload.playerUuid().toString());
                buf.writeInt(payload.cmd());
                buf.writeFloat(payload.backY());
                buf.writeFloat(payload.backZ());
                buf.writeFloat(payload.scale());
                buf.writeFloat(payload.yawDeg());
                buf.writeFloat(payload.originY());
                buf.writeInt(payload.flip());
            },
            buf -> {
                readInt(buf, 1); // version, reserved
                String uuid = readUtf(buf, "");
                int cmd = readInt(buf, NONE);
                float backY = readFloat(buf, DEFAULT_BACK_Y);
                float backZ = readFloat(buf, DEFAULT_BACK_Z);
                float scale = readFloat(buf, DEFAULT_SCALE);
                float yawDeg = readFloat(buf, DEFAULT_YAW_DEG);
                float originY = readFloat(buf, DEFAULT_ORIGIN_Y);
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
                    return new BackpackPayload(UUID.randomUUID(), NONE, DEFAULT_BACK_Y, DEFAULT_BACK_Z,
                            DEFAULT_SCALE, DEFAULT_YAW_DEG, DEFAULT_ORIGIN_Y, DEFAULT_FLIP);
                }
                return new BackpackPayload(playerUuid, cmd, backY, backZ, scale, yawDeg, originY, flip);
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
        if (len < 0 || buf.readableBytes() < len) {
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
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
