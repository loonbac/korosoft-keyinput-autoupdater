package com.korosoft.keyinput;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Custom payload sent server -&gt; client on the "keyinput:statpanel" channel. Carries the stat rows
 * drawn over one custom screen (the same {@code screenId} used by {@link ScreenLayoutPayload}), so
 * the server controls every label (already localized), value and pixel position — this mod only
 * knows how to paint a row, never what stat it names or where it goes.
 *
 * Wire format, matching what the Skript side writes with DataOutputStream:
 *
 *   int    version               reserved, currently always 1
 *   UTF    screenId              which screen this panel belongs to (matches ScreenLayoutPayload)
 *   int    rowCount
 *     repeated rowCount times:
 *       UTF   label              already localized, e.g. "Ataque"
 *       float base
 *       float delta              0 when no artifact contributes; drawn as "(+delta)" in green
 *                                 otherwise (a negative delta draws as "(-delta)")
 *       int   x, y               panel-space position: relative to anchorX/anchorY, same
 *                                 convention ScreenLayout.SlotSpec uses for slots
 *       int   decimals           0..2, how many decimal places to format base/delta with
 *   int    deltaColorOverride    OPTIONAL tail field: ARGB for every row's delta text in this
 *                                panel. 0 (no alpha) means "use the built-in green" — an older
 *                                sender that predates this field simply never writes it, and the
 *                                lenient decoder below defaults it to 0 exactly like every other
 *                                tail field in this mod (see ScreenLayoutPayload's portrait block).
 *   int    scalePct              OPTIONAL tail field, appended AFTER deltaColorOverride: percent
 *                                to scale every row's GLYPHS by (100 = normal size). Missing bytes
 *                                (an older sender that predates this field) default to 100. Row
 *                                positions themselves are never scaled — only the text glyphs.
 *
 * The decoder is deliberately LENIENT, because Minecraft kicks the player outright on a custom
 * payload it cannot decode cleanly: a short/garbled payload falls back to as many whole rows as it
 * could read plus safe defaults for the rest, and any trailing bytes from a newer sender are
 * drained instead of left over (unread bytes are a DecoderException, which the player experiences
 * as "Connection lost").
 */
public record StatPanelPayload(StatPanel.Spec spec) implements CustomPayload {

    public static final CustomPayload.Id<StatPanelPayload> ID =
            new CustomPayload.Id<>(Identifier.of("keyinput", "statpanel"));

    /** Sanity cap, mirrors ScreenLayoutPayload's MAX_SLOTS: a hostile/buggy count must not make us
     * allocate for millions of rows. */
    private static final int MAX_ROWS = 64;

    private static final String DEFAULT_SCREEN_ID = "";
    private static final int DEFAULT_COLOR_OVERRIDE = 0;
    private static final int DEFAULT_SCALE_PCT = StatPanel.DEFAULT_SCALE_PCT;

    public static final PacketCodec<RegistryByteBuf, StatPanelPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                StatPanel.Spec s = payload.spec();
                buf.writeInt(1);
                writeUtf(buf, s.screenId());
                buf.writeInt(s.rows().size());
                for (StatPanel.Row row : s.rows()) {
                    writeUtf(buf, row.label());
                    buf.writeFloat(row.base());
                    buf.writeFloat(row.delta());
                    buf.writeInt(row.x());
                    buf.writeInt(row.y());
                    buf.writeInt(row.decimals());
                }
                buf.writeInt(s.deltaColorOverride());
                buf.writeInt(s.scalePct());
            },
            buf -> {
                readInt(buf, 1);                                  // version, reserved
                String screenId = readUtf(buf, DEFAULT_SCREEN_ID);

                int rawCount = readInt(buf, 0);
                int count = Math.min(Math.max(rawCount, 0), MAX_ROWS);
                List<StatPanel.Row> rows = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    // Not even the label's length prefix is left: a truncated payload stops here
                    // with whatever whole rows it already decoded, rather than manufacturing an
                    // empty-label row forever.
                    if (buf.readableBytes() < Short.BYTES) {
                        break;
                    }
                    String label = readUtf(buf, "");
                    float base = readFloat(buf, 0.0F);
                    float delta = readFloat(buf, 0.0F);
                    int x = readInt(buf, 0);
                    int y = readInt(buf, 0);
                    int decimals = Math.min(Math.max(readInt(buf, 0), 0), 2);
                    rows.add(new StatPanel.Row(label, base, delta, x, y, decimals));
                }

                // Tail fields, same lenient fallback as ScreenLayoutPayload's portrait/reveal/orbit
                // blocks: a sender that predates either simply leaves the buffer empty here, and
                // each one defaults independently — an old sender missing BOTH still gets
                // deltaColorOverride=0 and scalePct=100, exactly as if it had sent neither.
                int deltaColorOverride = readInt(buf, DEFAULT_COLOR_OVERRIDE);
                int scalePct = readInt(buf, DEFAULT_SCALE_PCT);

                buf.skipBytes(buf.readableBytes());

                return new StatPanelPayload(
                        new StatPanel.Spec(screenId, List.copyOf(rows), deltaColorOverride, scalePct));
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
