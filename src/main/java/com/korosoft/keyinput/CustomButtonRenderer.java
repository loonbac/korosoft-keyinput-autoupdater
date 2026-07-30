package com.korosoft.keyinput;

import net.minecraft.client.gui.DrawContext;

/**
 * Draws the KoroSoft flat-panel button in place of the vanilla stone-textured widget. Called from
 * {@code PressableWidgetMixin} for the shared button-background pass, so every button-like widget in
 * the game (menu buttons, options buttons, toggles) picks up this look while keeping its own label.
 *
 * <p>Deliberately texture-free: it is drawn from fills, so it needs no resource-pack asset and
 * renders identically on every screen, before and after connecting to a server. Three states —
 * idle, hovered, disabled — each a dark translucent panel with a border that brightens to gold on
 * hover.
 */
public final class CustomButtonRenderer {

    private CustomButtonRenderer() {
    }

    private static final int FILL_IDLE = 0xC8141A2E;
    private static final int FILL_HOVER = 0xE01E2947;
    private static final int FILL_DISABLED = 0x96101218;

    private static final int BORDER_IDLE = 0xFF5B6685;
    private static final int BORDER_HOVER = 0xFFE8C170;
    private static final int BORDER_DISABLED = 0xFF2C3244;

    private static final int TOP_SHEEN_HOVER = 0x33FFFFFF;
    private static final int TOP_SHEEN_IDLE = 0x14FFFFFF;

    public static void draw(DrawContext ctx, int x, int y, int width, int height, boolean active, boolean hovered) {
        int x2 = x + width;
        int y2 = y + height;

        int fill = !active ? FILL_DISABLED : (hovered ? FILL_HOVER : FILL_IDLE);
        int border = !active ? BORDER_DISABLED : (hovered ? BORDER_HOVER : BORDER_IDLE);

        // Panel body.
        ctx.fill(x, y, x2, y2, fill);

        // 1px border on all four sides.
        ctx.fill(x, y, x2, y + 1, border);
        ctx.fill(x, y2 - 1, x2, y2, border);
        ctx.fill(x, y, x + 1, y2, border);
        ctx.fill(x2 - 1, y, x2, y2, border);

        // A faint top sheen so it reads as a raised panel, not a flat rectangle.
        if (active) {
            ctx.fill(x + 1, y + 1, x2 - 1, y + 2, hovered ? TOP_SHEEN_HOVER : TOP_SHEEN_IDLE);
        }
    }
}
