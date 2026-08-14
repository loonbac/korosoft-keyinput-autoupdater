package com.korosoft.keyinput;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Custom payload sent server -&gt; client on the "korosoft-core:hudbars" channel. Carries the four
 * HUD bars (health, food, stamina, mana) that the server already renders through MythicHUD, so the
 * mod can re-draw them with vanilla quads on GPUs where MythicHUD's shader-based glyphs never
 * appear (AMD/integrated). Every value AND every pixel position is server-driven, replicating the
 * server's MythicHUD layout (positions, sizes, colors and icons) — this mod only knows how to
 * paint a rectangle, never what a bar means or where it goes.
 *
 * Wire format v2, matching what the Skript side writes with DataOutputStream:
 *
 *   int    version     always 4
 *   int    debug       preview mode: 0 off | 1..4 show ONLY that bar (force render on any GPU) | 5 all
 *   four fixed blocks, in this order: HEALTH, FOOD, STAMINA, MANA:
 *     int    align     0 = absolute x | 1 = centered (x = offset from screen center) | 2 = right
 *     int    x, y      position in scaled GUI pixels (see align)
 *     int    w, h      bar size (MythicHUD outline is 88x9)
 *     int    iconId    0 = none | 1 = heart | 2 = mana | 3 = stamina | 4 = food
 *     int    iconOx, iconOy   icon offset relative to the bar CENTER (scaled px)
 *     int    iconTint  1 = tint icon with fillColor | 0 = native texture color (no tint)
 *     int    fillTex    0 bar_fill | 1 hunger | 2 poison | 3 wither | 4 burning | 5 freezing | 6 absorption
 *     int    outlineTex 0 bar_outline | 1 bar_outline_absorption
 *     UTF    fillColor  ARGB hex color (mmohud.yml bar_fill color), e.g. "FFFF0606"
 *     UTF    outlineColor ARGB hex outline color, e.g. "FF990000"
 *     float  value     current amount
 *     float  max       maximum amount (the bar renders value/max of its width)
 *
 * The decoder is deliberately LENIENT, same rule as every other payload in this mod: Minecraft
 * kicks the player outright on a custom payload it cannot decode cleanly, so a short/garbled
 * payload falls back to zeros and any trailing bytes from a newer sender are drained.
 */
public record HudBarsPayload(
        int debug, Bar health, Bar food, Bar stamina, Bar mana
) implements CustomPayload {

    /** One bar spec: placement, look and current values. */
    public record Bar(
            int align, int x, int y, int w, int h,
            int iconId, int iconOx, int iconOy, int iconTint,
            int fillTex, int outlineTex,
            int fillColor, int outlineColor,
            float value, float max
    ) {
        /** Draw width proportional to value/max, clamped to the inner bar width. */
        public int fillWidth() {
            if (max <= 0.0F || w <= 2) {
                return 0;
            }
            float ratio = Math.max(0.0F, Math.min(1.0F, value / max));
            return Math.round((w - 2) * ratio);
        }
    }

    public static final CustomPayload.Id<HudBarsPayload> ID =
            new CustomPayload.Id<>(Identifier.of("korosoft-core", "hudbars"));

    public static final PacketCodec<RegistryByteBuf, HudBarsPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeInt(2); // version
                buf.writeInt(payload.debug());
                writeBlock(buf, payload.health());
                writeBlock(buf, payload.food());
                writeBlock(buf, payload.stamina());
                writeBlock(buf, payload.mana());
            },
            buf -> {
                readInt(buf); // version, reserved
                int debug = readInt(buf);

                Bar health = readBlock(buf);
                Bar food = readBlock(buf);
                Bar stamina = readBlock(buf);
                Bar mana = readBlock(buf);

                buf.skipBytes(buf.readableBytes());

                return new HudBarsPayload(debug, health, food, stamina, mana);
            }
    );

    private static void writeBlock(RegistryByteBuf buf, Bar b) {
        buf.writeInt(b.align());
        buf.writeInt(b.x());
        buf.writeInt(b.y());
        buf.writeInt(b.w());
        buf.writeInt(b.h());
        buf.writeInt(b.iconId());
        buf.writeInt(b.iconOx());
        buf.writeInt(b.iconOy());
        buf.writeInt(b.iconTint());
        buf.writeInt(b.fillTex());
        buf.writeInt(b.outlineTex());
        buf.writeString(Integer.toHexString(b.fillColor()));
        buf.writeString(Integer.toHexString(b.outlineColor()));
        buf.writeFloat(b.value());
        buf.writeFloat(b.max());
    }

    private static Bar readBlock(RegistryByteBuf buf) {
        int align = readInt(buf), x = readInt(buf), y = readInt(buf), w = readInt(buf), h = readInt(buf);
        int iconId = readInt(buf), iconOx = readInt(buf), iconOy = readInt(buf);
        int iconTint = readInt(buf);
        int fillTex = readInt(buf), outlineTex = readInt(buf);
        int fillColor = parseHex(readString(buf));
        int outlineColor = parseHex(readString(buf));
        float value = readFloat(buf), max = readFloat(buf);
        return new Bar(align, x, y, w, h, iconId, iconOx, iconOy, iconTint, fillTex, outlineTex, fillColor, outlineColor, value, max);
    }

    private static int readInt(RegistryByteBuf buf) {
        return buf.readableBytes() >= Integer.BYTES ? buf.readInt() : 0;
    }

    private static float readFloat(RegistryByteBuf buf) {
        return buf.readableBytes() >= Float.BYTES ? buf.readFloat() : 0.0F;
    }

    private static String readString(RegistryByteBuf buf) {
        // The server writes strings with DataOutputStream.writeUTF (2-byte length prefix),
        // NOT Fabric's readString (VarInt length) — mirror mochila-back-sync's wire pattern.
        try {
            if (buf.readableBytes() >= 2) {
                int len = buf.readUnsignedShort();
                if (len >= 0 && buf.readableBytes() >= len) {
                    byte[] bytes = new byte[len];
                    buf.readBytes(bytes);
                    return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ignored) {
            // fall through to ""
        }
        return "";
    }

    private static int parseHex(String hex) {
        try {
            return (int) Long.parseLong(hex.trim(), 16);
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
