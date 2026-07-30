package com.korosoft.keyinput;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Shown by {@link ModUpdater#fail(String)} when the update flow errors out (HTTP timeout, SHA
 * mismatch, atomic-move unsupported, etc.). Single button: "Aceptar", which returns to the title
 * screen and resets the {@code applying} latch so a future payload can try again.
 *
 * <p>This screen is intentionally minimal: if the SHA verification fails it means something on the
 * network path tampered with the bytes, and we should NOT auto-retry; the player has to click
 * "Aceptar" and reconnect, which gives the server a chance to re-push a clean manifest.
 */
public class ModUpdateErrorScreen extends Screen {

    private final String reason;

    public ModUpdateErrorScreen(String reason) {
        super(Text.literal("Actualizacion fallida"));
        this.reason = reason == null ? "Error desconocido" : reason;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0xCC000000);
        int cx = this.width / 2;

        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, 60, 0xFFFF6060);
        // Wrap the reason manually at ~50 chars/line so it stays readable on any window size.
        int y = 90;
        for (String line : wrap(this.reason, 50)) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(line), cx, y, 0xFFEEEEEE);
            y += 12;
        }
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Volveras al menu principal. Reconectate para reintentar."),
                cx, this.height - 50, 0xFFAAAAAA);
        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;
        int buttonWidth = 200;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Aceptar"), btn -> {
                    ModUpdater.get().clearApplying();
                    this.client.setScreen(new TitleScreen());
                })
                .dimensions(cx - buttonWidth / 2, this.height - 80, buttonWidth, 20)
                .build());
    }

    private static String[] wrap(String s, int maxLine) {
        // Tiny greedy word-wrap; the reason string is short (<= 200 chars) so this is fine.
        if (s.length() <= maxLine) return new String[]{s};
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : s.split(" ")) {
            if (cur.length() + word.length() + 1 > maxLine) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(word);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}