package com.korosoft.keyinput;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;

/**
 * Fallback HUD bars for GPUs where MythicHUD's shader-based texture glyphs never render
 * (AMD/integrated graphics: the custom rendertype_text vertex shader that repositions the bar
 * glyphs produces invisible bars on those drivers, while the vanilla font numbers still appear).
 *
 * <p>When the server broadcasts {@link HudBarsPayload}, this class re-draws the four bars every
 * frame using the REAL MythicHUD textures (bar outline, proportional fill, state variants, icons),
 * tinted with the server colors — the same visuals the server's resource pack produces, painted
 * through vanilla {@link DrawContext} quad paths that render correctly on every vendor.
 *
 * <p>The mod is deliberately DUMB: every pixel position, size, texture id, tint and value comes
 * from the server payload (replicating/overriding the server's MythicHUD mmohud layout), so the
 * whole fallback can be re-tuned in real time from Skript without a mod rebuild.
 *
 * <p>By default the fallback only draws on AMD hardware (detected once from the OpenGL renderer
 * string) AND only after the server has sent at least one payload; on NVIDIA the MythicHUD bars
 * keep rendering exactly as before, untouched. The server can also set a debug mode that force-
 * renders a single bar on ANY GPU, so the owner can preview each bar one by one against the real
 * MythicHUD bars and tune positions server-side without a rebuild.
 */
public final class HudBarsState {

    private static final float TEXT_SCALE = 0.7F;
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    private static final Identifier[] FILL_TEX = {
            Identifier.of("korosoft-core", "textures/hud/bar_fill.png"),
            Identifier.of("korosoft-core", "textures/hud/bar_fill_hunger.png"),
            Identifier.of("korosoft-core", "textures/hud/bar_fill_poison.png"),
            Identifier.of("korosoft-core", "textures/hud/bar_fill_wither.png"),
            Identifier.of("korosoft-core", "textures/hud/bar_fill_burning.png"),
            Identifier.of("korosoft-core", "textures/hud/bar_fill_freezing.png"),
            Identifier.of("korosoft-core", "textures/hud/bar_fill_absorption.png"),
    };
    private static final Identifier[] OUTLINE_TEX = {
            Identifier.of("korosoft-core", "textures/hud/bar_outline.png"),
            Identifier.of("korosoft-core", "textures/hud/bar_outline_absorption.png"),
    };
    private static final Identifier[] ICON_TEX = {
            null,
            Identifier.of("korosoft-core", "textures/hud/icon_heart.png"),
            Identifier.of("korosoft-core", "textures/hud/icon_mana.png"),
            Identifier.of("korosoft-core", "textures/hud/icon_stamina.png"),
            Identifier.of("korosoft-core", "textures/hud/icon_food.png"),
            Identifier.of("korosoft-core", "textures/hud/icon_heart_absorption.png"),
            Identifier.of("korosoft-core", "textures/hud/icon_heart_poison.png"),
            Identifier.of("korosoft-core", "textures/hud/icon_heart_wither.png"),
            Identifier.of("korosoft-core", "textures/hud/icon_heart_burning.png"),
            Identifier.of("korosoft-core", "textures/hud/icon_heart_freezing.png"),
            Identifier.of("korosoft-core", "textures/hud/icon_food_hunger.png"),
    };
    private static final int FILL_TEX_W = 86;
    private static final int FILL_TEX_H = 7;
    private static final int OUTLINE_TEX_W = 88;
    private static final int OUTLINE_TEX_H = 9;
    private static final int ICON_SIZE = 9;

    private static HudBarsPayload.Bar health = new HudBarsPayload.Bar(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0F, 0.0F);
    private static HudBarsPayload.Bar food = new HudBarsPayload.Bar(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0F, 0.0F);
    private static HudBarsPayload.Bar stamina = new HudBarsPayload.Bar(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0F, 0.0F);
    private static HudBarsPayload.Bar mana = new HudBarsPayload.Bar(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0F, 0.0F);
    private static int debugMode;
    private static boolean received;
    private static Boolean amd;

    private HudBarsState() {
    }

    /** Replaces the cached bars with the freshly received server payload. */
    public static void put(HudBarsPayload p) {
        health = p.health();
        food = p.food();
        stamina = p.stamina();
        mana = p.mana();
        debugMode = p.debug();
        received = true;
    }

    /** Drops everything — called on disconnect so a stale payload never leaks between servers. */
    public static void reset() {
        received = false;
        debugMode = 0;
        health = new HudBarsPayload.Bar(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0F, 0.0F);
        food = new HudBarsPayload.Bar(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0F, 0.0F);
        stamina = new HudBarsPayload.Bar(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0F, 0.0F);
        mana = new HudBarsPayload.Bar(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0F, 0.0F);
    }

    /** True once per process when the OpenGL renderer is AMD/ATI/Radeon. Detected lazily. */
    public static boolean isAmd() {
        if (amd == null) {
            String renderer = GL11.glGetString(GL11.GL_RENDERER);
            amd = renderer != null
                    && (renderer.contains("AMD") || renderer.contains("ATI") || renderer.contains("Radeon"));
        }
        return amd;
    }

    /**
     * Draws the fallback bars. Renders when the server sent a payload AND (AMD hardware OR the
     * server asked for a forced preview of one bar). Debug mode 6 = fully off (the server can
     * disable the fallback even on AMD). No-op otherwise.
     */
    public static void render(DrawContext context) {
        if (!received || debugMode == 6 || (!isAmd() && debugMode == 0)) {
            return;
        }
        // Debug 1..4 shows ONLY that bar; 5 shows all; 0 shows all on AMD.
        if (debugMode == 1 || debugMode == 5 || debugMode == 0) {
            drawBar(context, health);
        }
        if (debugMode == 2 || debugMode == 5 || debugMode == 0) {
            drawBar(context, food);
        }
        if (debugMode == 3 || debugMode == 5 || debugMode == 0) {
            drawBar(context, stamina);
        }
        if (debugMode == 4 || debugMode == 5 || debugMode == 0) {
            drawBar(context, mana);
        }
    }

    /** Resolves the bar's left edge from its align mode: 0 absolute, 1 centered, 2 right-aligned. */
    private static int xFinal(DrawContext context, HudBarsPayload.Bar bar) {
        int sw = context.getScaledWindowWidth();
        return switch (bar.align()) {
            case 1 -> sw / 2 + bar.x() - bar.w() / 2;
            case 2 -> sw - bar.x() - bar.w();
            default -> bar.x();
        };
    }

    private static void drawBar(DrawContext context, HudBarsPayload.Bar bar) {
        if (bar.max() <= 0.0F || bar.w() <= 0 || bar.h() <= 0) {
            return;
        }
        int x = xFinal(context, bar);
        int y = bar.y();

        // Outline texture (MythicHUD bar_outline.png), stretched to the server-provided size.
        Identifier outline = tex(OUTLINE_TEX, bar.outlineTex());
        if (outline != null) {
            context.drawTexture(RenderPipelines.GUI_TEXTURED, outline, x, y,
                    0.0F, 0.0F, bar.w(), bar.h(), OUTLINE_TEX_W, OUTLINE_TEX_H, bar.outlineColor());
        }

        // Proportional fill texture (MythicHUD bar_fill.png), cut to value/max of the inner width.
        int fillWidth = bar.fillWidth();
        if (fillWidth > 0) {
            Identifier fill = tex(FILL_TEX, bar.fillTex());
            if (fill != null) {
                context.drawTexture(RenderPipelines.GUI_TEXTURED, fill, x + 1, y + 1,
                        0.0F, 0.0F, fillWidth, bar.h() - 2, FILL_TEX_W, FILL_TEX_H, bar.fillColor());
            } else {
                context.fill(x + 1, y + 1, x + 1 + fillWidth, y + bar.h() - 1, bar.fillColor());
            }
        }

        // Icon (MythicHUD heart/mana/stamina/food glyph), tinted with the fill color.
        Identifier icon = iconTex(bar.iconId());
        if (icon != null) {
            int cx = x + bar.w() / 2;
            int cy = y + bar.h() / 2;
            int tint = bar.iconTint() == 1 ? bar.fillColor() : 0xFFFFFFFF;
            context.drawTexture(RenderPipelines.GUI_TEXTURED, icon, cx + bar.iconOx() - ICON_SIZE / 2, cy + bar.iconOy() - ICON_SIZE / 2,
                    0.0F, 0.0F, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE, tint);
        }

        // Numbers ("21/22"), centered on the bar at MythicHUD's 0.70 scale.
        drawCenteredText(context, bar, x, y);
    }

    private static void drawCenteredText(DrawContext context, HudBarsPayload.Bar bar, int x, int y) {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        String text = String.format("%.0f/%.0f", bar.value(), bar.max());
        int textW = tr.getWidth(text);
        float scaledTextW = textW * TEXT_SCALE;
        float tx = x + bar.w() / 2.0F - scaledTextW / 2.0F;
        float ty = y + bar.h() / 2.0F - 3.0F;
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(tx, ty);
        context.getMatrices().scale(TEXT_SCALE, TEXT_SCALE);
        context.drawText(tr, text, 0, 0, TEXT_COLOR, true);
        context.getMatrices().popMatrix();
    }

    private static Identifier tex(Identifier[] table, int id) {
        return id >= 0 && id < table.length ? table[id] : null;
    }

    private static Identifier iconTex(int iconId) {
        return iconId > 0 && iconId < ICON_TEX.length ? ICON_TEX[iconId] : null;
    }
}
