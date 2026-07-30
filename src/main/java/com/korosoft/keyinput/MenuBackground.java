package com.korosoft.keyinput;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/**
 * The shared KoroSoft splash art ({@code menu_bg.png}) used by the second disclaimer page and the
 * launcher menu, plus the helpers that paint it.
 *
 * <p>The texture ships inside the mod jar (not the server resource pack) so the menu looks right
 * before the player has connected to anything and received the pack. It is only ever drawn from
 * screens that run <em>after</em> the initial resource reload has completed, which is when the
 * texture manager has actually bound it — the first-page splash reskin deliberately uses flat fills
 * instead, because during that reload this texture is not guaranteed to be loaded yet.
 */
public final class MenuBackground {

    public static final Identifier TEXTURE = Identifier.of("keyinput", "textures/gui/menu_bg.png");

    // The source art's real pixel dimensions; used to preserve its aspect ratio when covering.
    private static final int TEX_W = 1672;
    private static final int TEX_H = 941;

    private MenuBackground() {
    }

    /**
     * Paints the art scaled to COVER the whole screen and centered, so it fills any window aspect
     * ratio without letterbox bars (the overflow is cropped rather than squashed).
     */
    public static void drawCover(DrawContext ctx, int screenW, int screenH) {
        double scale = Math.max((double) screenW / TEX_W, (double) screenH / TEX_H);
        int drawW = (int) Math.ceil(TEX_W * scale);
        int drawH = (int) Math.ceil(TEX_H * scale);
        int x = (screenW - drawW) / 2;
        int y = (screenH - drawH) / 2;
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, 0.0F, 0.0F,
                drawW, drawH, TEX_W, TEX_H, TEX_W, TEX_H);
    }

    /** Lays a black veil of the given 0-255 alpha over the whole screen, for text legibility. */
    public static void dim(DrawContext ctx, int screenW, int screenH, int alpha) {
        ctx.fill(0, 0, screenW, screenH, (alpha & 0xFF) << 24);
    }
}
