package com.korosoft.keyinput;

import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A server-defined layout for one custom screen.
 *
 * The mod knows nothing about what a screen is FOR. It only knows how to paint a texture
 * where a container would have been, move the slots somewhere else, and get out of the way.
 * Everything here arrives from the server over {@link ScreenLayoutPayload}, so a new screen
 * costs a PNG and some config — never a new build of this mod.
 *
 * Coordinates are in "panel space": relative to the top-left of the blitted texture, which
 * itself sits at (container origin + anchor).
 */
public record ScreenLayout(
        String screenId,
        Identifier texture,
        int panelW, int panelH,
        int anchorX, int anchorY,
        int flags,
        int fieldX, int fieldY, int fieldW, int fieldH,
        List<SlotSpec> slots,
        boolean showPortrait, int portraitX, int portraitY, int portraitScale,
        int revealShiftX, int revealShrinkPct, int invOriginX, int invOriginY, int invCellW, int invCellH,
        int orbitCenterX, int orbitCenterY, int orbitRadiusX, int orbitRadiusY,
        int orbitSpeedCentiDeg, int bubbleSize, int bubbleItemScalePct, int bubbleHoverPct
) {

    /** Where one container slot goes, and how big its item is drawn. */
    public record SlotSpec(int index, int x, int y, int scalePct) {}

    public static final int FLAG_HIDE_PLAYER_INVENTORY = 1;
    public static final int FLAG_HIDE_BACKGROUND = 1 << 1;
    public static final int FLAG_HIDE_LABELS = 1 << 2;
    /**
     * Turns on the click-to-reveal inventory drawer. When set, the player inventory is hidden by
     * default (still requires {@link #FLAG_HIDE_PLAYER_INVENTORY}) but a click on any placed
     * container slot slides it in from the right while the panel + portrait shift left and shrink.
     * The {@code reveal*}/{@code inv*} geometry fields drive that animation's endpoints.
     */
    public static final int FLAG_REVEAL_INVENTORY = 1 << 3;
    /**
     * Arrange the placed slots as bubbles orbiting the portrait instead of sitting at fixed panel
     * positions. The mod animates each slot around an ellipse ({@code orbitRadiusX/Y}) at
     * {@code orbitSpeedCentiDeg}, draws {@link #texture()} as the bubble sprite at {@code bubbleSize}
     * (the panel texture is NOT drawn as one big panel in this mode), and hit-tests / hovers on the
     * bubble radius rather than a 16x16 box. Hover grows a bubble to {@code bubbleHoverPct}.
     */
    public static final int FLAG_ORBIT = 1 << 4;

    /**
     * Marks an inventory title as belonging to a custom screen. The server prefixes the title
     * with this char plus the screen id.
     *
     * Deliberately NOT a packet: the title already travels with the open-screen packet, so
     * there is no second message to arrive late, out of order, or fail to decode. A malformed
     * custom payload disconnects the player outright — see {@link HudConfigPayload}.
     */
    public static final char SENTINEL = '';

    private static final Map<String, ScreenLayout> LAYOUTS = new HashMap<>();

    public static void put(ScreenLayout layout) {
        LAYOUTS.put(layout.screenId(), layout);
    }

    /** The layout for this title, or null if it is an ordinary vanilla screen. */
    public static ScreenLayout forTitle(String title) {
        if (title == null || title.isEmpty() || title.charAt(0) != SENTINEL) {
            return null;
        }
        return LAYOUTS.get(title.substring(1));
    }

    public static void reset() {
        LAYOUTS.clear();
    }

    public boolean hidePlayerInventory() {
        return (flags & FLAG_HIDE_PLAYER_INVENTORY) != 0;
    }

    public boolean hideBackground() {
        return (flags & FLAG_HIDE_BACKGROUND) != 0;
    }

    public boolean hideLabels() {
        return (flags & FLAG_HIDE_LABELS) != 0;
    }

    public boolean revealInventory() {
        return (flags & FLAG_REVEAL_INVENTORY) != 0;
    }

    public boolean orbit() {
        return (flags & FLAG_ORBIT) != 0;
    }

    public boolean hasField() {
        return fieldW > 0 && fieldH > 0;
    }

    /** The spec for a container slot index, or null to leave that slot where vanilla put it. */
    public SlotSpec slot(int index) {
        for (SlotSpec s : slots) {
            if (s.index() == index) {
                return s;
            }
        }
        return null;
    }
}
