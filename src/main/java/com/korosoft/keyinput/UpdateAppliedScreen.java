package com.korosoft.keyinput;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Confirmation shown AFTER the update jar was applied, inside the same Minecraft window. The game
 * cannot hot-reload its own code, so it must restart to load the new version — but the restart is
 * ALWAYS player-initiated: a persistent confirmation with one button ("Reiniciar ahora"). Nothing
 * closes the game automatically; ESC does nothing (the restart cannot be skipped accidentally),
 * and the guardian in ModUpdater re-asserts this screen if anything tries to overwrite it.
 */
public class UpdateAppliedScreen extends Screen {

    public UpdateAppliedScreen() {
        super(Text.literal("Actualización aplicada"));
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // sticky: the player must consciously click "Reiniciar ahora"
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0xCC000000);
        int cx = this.width / 2;

        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, 55, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Korosoft-Core se actualizó correctamente."),
                cx, 90, 0xFFCCCCCC);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Haz clic en «Reiniciar ahora» para que el juego se cierre"),
                cx, 106, 0xFFCCCCCC);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("La próxima vez que lo abras, entrarás con la versión nueva."),
                cx, 122, 0xFFAAAAAA);
        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Reiniciar ahora"), btn -> {
                    if (windows) {
                        // The jar swap is done by the native helper after the JVM exits; closing
                        // the game lets it finish. The player reopens the launcher afterwards.
                        this.client.scheduleStop();
                    } else {
                        // Non-Windows: swap already happened in place; the JVM exits and the
                        // launcher relaunches the game (or the player reopens it).
                        this.client.scheduleStop();
                    }
                })
                .dimensions(cx - 100, this.height - 80, 200, 20)
                .build());
    }
}
