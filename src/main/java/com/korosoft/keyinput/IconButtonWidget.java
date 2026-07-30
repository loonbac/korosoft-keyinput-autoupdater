package com.korosoft.keyinput;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.input.AbstractInput;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * A square, text-less icon button: a small pixel-art glyph (64x64 source, ships in the mod jar) with
 * no vanilla stone panel behind it. It draws its own background and NEVER calls
 * {@link PressableWidget#drawButton(DrawContext)} (the {@code final} method
 * {@code PressableWidgetMixin} cancels to reskin every other button in the game), so this widget is
 * simply invisible to that mixin — its look is entirely self-contained.
 *
 * <p>As of 1.21.11, {@code PressableWidget.renderWidget} is itself {@code final} and delegates to the
 * abstract {@link #drawIcon}, which is the actual override point here (not {@code renderWidget}).
 *
 * <p>The button has no visible label, so the accessible name lives entirely in its tooltip — always
 * set via the {@code tooltipLabel} constructor argument.
 */
public class IconButtonWidget extends PressableWidget {

    // The shipped icon PNGs are all 64x64, glyph-on-transparent.
    private static final int ICON_TEX_SIZE = 64;
    private static final int ICON_PADDING = 3;

    private static final int HIGHLIGHT_FILL = 0x33FFFFFF;
    private static final int HIGHLIGHT_BORDER = 0x55FFFFFF;
    private static final int DISABLED_VEIL = 0x99000000;

    private final Identifier icon;
    private final Runnable action;

    public IconButtonWidget(int x, int y, int size, Identifier icon, Text tooltipLabel, Runnable action) {
        super(x, y, size, size, tooltipLabel);
        this.icon = icon;
        this.action = action;
        this.setTooltip(Tooltip.of(tooltipLabel));
    }

    @Override
    public void onPress(AbstractInput input) {
        this.action.run();
    }

    @Override
    protected void drawIcon(DrawContext ctx, int mouseX, int mouseY, float deltaTicks) {
        int x = this.getX();
        int y = this.getY();
        int w = this.getWidth();
        int h = this.getHeight();

        // Subtle hover/focus highlight — a translucent panel with a 1px lighter border. This is the
        // ENTIRE background: intentionally never PressableWidget.drawButton(), so the reskin mixin
        // (which only fires when that method is actually called) never touches this widget.
        if (this.isSelected()) {
            ctx.fill(x, y, x + w, y + h, HIGHLIGHT_FILL);
            ctx.fill(x, y, x + w, y + 1, HIGHLIGHT_BORDER);
            ctx.fill(x, y + h - 1, x + w, y + h, HIGHLIGHT_BORDER);
            ctx.fill(x, y, x + 1, y + h, HIGHLIGHT_BORDER);
            ctx.fill(x + w - 1, y, x + w, y + h, HIGHLIGHT_BORDER);
        }

        int iconSize = w - ICON_PADDING * 2;
        int iconX = x + (w - iconSize) / 2;
        int iconY = y + (h - iconSize) / 2;
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, this.icon, iconX, iconY, 0.0F, 0.0F,
                iconSize, iconSize, ICON_TEX_SIZE, ICON_TEX_SIZE, ICON_TEX_SIZE, ICON_TEX_SIZE);

        if (!this.active) {
            ctx.fill(iconX, iconY, iconX + iconSize, iconY + iconSize, DISABLED_VEIL);
        }
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        this.appendDefaultNarrations(builder);
    }
}
