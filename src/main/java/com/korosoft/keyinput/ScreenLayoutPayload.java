package com.korosoft.keyinput;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Custom payload sent server -> client on the "keyinput:screen" channel. Carries one screen
 * layout, so the server can place the panel, the slots and the text field pixel by pixel
 * without this mod being rebuilt.
 *
 * Wire format, matching what the Skript side writes with DataOutputStream:
 *
 *   int  version
 *   UTF  screenId
 *   UTF  texture              an Identifier, e.g. "keyinput:textures/gui/screen_code.png"
 *   int  panelW, panelH
 *   int  anchorX, anchorY     may be negative
 *   int  flags
 *   int  fieldX, fieldY, fieldW, fieldH
 *   int  slotCount
 *     repeated slotCount times: int index, x, y, scalePct
 *   int  showPortrait          0 or 1
 *   int  portraitX, portraitY  panel-relative center, GUI px
 *   int  portraitScale         render size in GUI px (the `size` arg to DrawContext.addEntity)
 *
 * The four portrait fields were appended AFTER the slot array, so an older sender that never
 * writes them (e.g. canje's anvil/chest screens) simply runs out of bytes here and every one of
 * them falls back to its default via the same lenient {@code readInt} used everywhere else —
 * showPortrait defaults to 0 (false), so those screens are completely unaffected.
 */
public record ScreenLayoutPayload(ScreenLayout layout) implements CustomPayload {

    public static final CustomPayload.Id<ScreenLayoutPayload> ID =
            new CustomPayload.Id<>(Identifier.of("keyinput", "screen"));

    /** Sanity cap. A hostile or buggy count must not make us allocate for millions of slots. */
    private static final int MAX_SLOTS = 128;

    private static final Identifier FALLBACK_TEXTURE =
            Identifier.of("keyinput", "textures/gui/screen_code.png");

    // Ints are written by hand as fixed 4-byte big-endian, to match Skript's
    // DataOutputStream.writeInt(). PacketCodecs.INTEGER is a VarInt and would read these bytes
    // as garbage. Strings match DataOutputStream.writeUTF(): a 2-byte unsigned big-endian
    // length followed by UTF-8 bytes.
    //
    // The decoder is deliberately LENIENT, because Minecraft kicks the player outright on a
    // custom payload it cannot decode cleanly. A field an older server does not send yet falls
    // back to a default, and any trailing bytes from a newer server are drained — leftover
    // unread bytes are a DecoderException, which the player experiences as "Connection lost".
    public static final PacketCodec<RegistryByteBuf, ScreenLayoutPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                ScreenLayout l = payload.layout();
                buf.writeInt(1);
                writeUtf(buf, l.screenId());
                writeUtf(buf, l.texture().toString());
                buf.writeInt(l.panelW());
                buf.writeInt(l.panelH());
                buf.writeInt(l.anchorX());
                buf.writeInt(l.anchorY());
                buf.writeInt(l.flags());
                buf.writeInt(l.fieldX());
                buf.writeInt(l.fieldY());
                buf.writeInt(l.fieldW());
                buf.writeInt(l.fieldH());
                buf.writeInt(l.slots().size());
                for (ScreenLayout.SlotSpec s : l.slots()) {
                    buf.writeInt(s.index());
                    buf.writeInt(s.x());
                    buf.writeInt(s.y());
                    buf.writeInt(s.scalePct());
                }
                buf.writeInt(l.showPortrait() ? 1 : 0);
                buf.writeInt(l.portraitX());
                buf.writeInt(l.portraitY());
                buf.writeInt(l.portraitScale());
                buf.writeInt(l.revealShiftX());
                buf.writeInt(l.revealShrinkPct());
                buf.writeInt(l.invOriginX());
                buf.writeInt(l.invOriginY());
                buf.writeInt(l.invCellW());
                buf.writeInt(l.invCellH());
                buf.writeInt(l.orbitCenterX());
                buf.writeInt(l.orbitCenterY());
                buf.writeInt(l.orbitRadiusX());
                buf.writeInt(l.orbitRadiusY());
                buf.writeInt(l.orbitSpeedCentiDeg());
                buf.writeInt(l.bubbleSize());
                buf.writeInt(l.bubbleItemScalePct());
                buf.writeInt(l.bubbleHoverPct());
            },
            buf -> {
                readInt(buf, 1);                                  // version, reserved
                String screenId = readUtf(buf, "");
                Identifier texture = parseId(readUtf(buf, ""));

                int panelW = readInt(buf, 0);
                int panelH = readInt(buf, 0);
                int anchorX = readInt(buf, 0);
                int anchorY = readInt(buf, 0);
                int flags = readInt(buf, 0);

                int fieldX = readInt(buf, 0);
                int fieldY = readInt(buf, 0);
                int fieldW = readInt(buf, 0);
                int fieldH = readInt(buf, 0);

                int count = Math.min(Math.max(readInt(buf, 0), 0), MAX_SLOTS);
                List<ScreenLayout.SlotSpec> slots = new ArrayList<>(count);
                for (int i = 0; i < count && buf.readableBytes() >= Integer.BYTES * 4; i++) {
                    slots.add(new ScreenLayout.SlotSpec(
                            buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt()));
                }

                // Same lenient fallback as every other field: a sender that predates this pair of
                // fields (canje's screens) leaves the buffer empty here, and showPortrait defaults
                // to 0 — false — so those screens decode exactly as they always have.
                boolean showPortrait = readInt(buf, 0) != 0;
                int portraitX = readInt(buf, 0);
                int portraitY = readInt(buf, 0);
                int portraitScale = readInt(buf, 0);

                // Reveal-drawer geometry, appended after the portrait block — same lenient tail:
                // a sender that predates these (canje, or an older accessories build) leaves the
                // buffer empty and every field defaults to 0, which reads as "reveal disabled".
                int revealShiftX = readInt(buf, 0);
                int revealShrinkPct = readInt(buf, 0);
                int invOriginX = readInt(buf, 0);
                int invOriginY = readInt(buf, 0);
                int invCellW = readInt(buf, 0);
                int invCellH = readInt(buf, 0);

                // Orbit geometry, appended after the reveal block — same lenient tail.
                int orbitCenterX = readInt(buf, 0);
                int orbitCenterY = readInt(buf, 0);
                int orbitRadiusX = readInt(buf, 0);
                int orbitRadiusY = readInt(buf, 0);
                int orbitSpeedCentiDeg = readInt(buf, 0);
                int bubbleSize = readInt(buf, 0);
                int bubbleItemScalePct = readInt(buf, 0);
                int bubbleHoverPct = readInt(buf, 0);

                buf.skipBytes(buf.readableBytes());

                return new ScreenLayoutPayload(new ScreenLayout(
                        screenId, texture, panelW, panelH, anchorX, anchorY, flags,
                        fieldX, fieldY, fieldW, fieldH, List.copyOf(slots),
                        showPortrait, portraitX, portraitY, portraitScale,
                        revealShiftX, revealShrinkPct, invOriginX, invOriginY, invCellW, invCellH,
                        orbitCenterX, orbitCenterY, orbitRadiusX, orbitRadiusY,
                        orbitSpeedCentiDeg, bubbleSize, bubbleItemScalePct, bubbleHoverPct));
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

    /** A bad identifier must not throw here: throwing inside a codec disconnects the player. */
    private static Identifier parseId(String s) {
        Identifier id = Identifier.tryParse(s);
        return id != null ? id : FALLBACK_TEXTURE;
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
