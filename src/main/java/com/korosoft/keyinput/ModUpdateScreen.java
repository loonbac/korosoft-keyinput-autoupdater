package com.korosoft.keyinput;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.text.Text;

/**
 * Fullscreen overlay shown during a {@link ModUpdater} flow. Sticky on purpose: there is no close
 * button, ESC is intercepted and ignored, and the only way out is the flow itself completing (the
 * updater kills the JVM and spawns a fresh one) or failing (which swaps this screen for
 * {@link ModUpdateErrorScreen}).
 *
 * <p>Renders three lines, centered:
 * <ul>
 *   <li>"Actualizando Korosoft-Core" (title)</li>
 *   <li>the server-supplied reason ("Soporte para teclas del mouse", etc.)</li>
 *   <li>the live status from {@link ModUpdater#currentStatus()} ("Descargando... 1024 KB", etc.)</li>
 * </ul>
 *
 * <p>Wraps the screen that was active when the payload arrived (typically {@link TitleScreen}) so
 * when the new process boots and lands on the title screen, the player sees the same surface they
 * left, not a black void.
 */
public class ModUpdateScreen extends Screen {

    private final Screen parent;
    private final String title;
    private final String subtitle;

    public ModUpdateScreen(String title, String subtitle, Screen parent) {
        super(Text.literal(title));
        this.title = title;
        this.subtitle = subtitle == null ? "" : subtitle;
        this.parent = parent;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // sticky: ESC does nothing while updating
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Dim background -- match the vanilla "incompatible mod" overlay style for consistency.
        ctx.fill(0, 0, this.width, this.height, 0xCC000000);

        int cx = this.width / 2;
        int cy = this.height / 2;

        // Title (white, large).
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(this.title), cx, cy - 30, 0xFFFFFFFF);

        // Subtitle (gray, the user-facing reason the server sent).
        if (!this.subtitle.isEmpty()) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(this.subtitle), cx, cy - 10, 0xFFCCCCCC);
        }

        // Live status (light gray, updated every frame from ModUpdater).
        String status = ModUpdater.get().currentStatus();
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(status), cx, cy + 14, 0xFFAAAAAA);

        // Progress bar (download progress from ModUpdater).
        float prog = ModUpdater.get().currentProgress();
        int barWidth = Math.min(320, this.width / 2);
        int bx = cx - barWidth / 2;
        int by = cy + 26;
        ctx.fill(bx, by, bx + barWidth, by + 5, 0xFF444444);
        if (prog > 0f) {
            int fill = (int) (barWidth * Math.min(1f, prog));
            ctx.fill(bx, by, bx + fill, by + 5, 0xFF55FF55);
        }

        // Footer hint: "No cierres el juego". Spells out the only failure mode we cannot recover from.
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("No cierres el juego. La actualización terminará automáticamente."),
                cx, this.height - 30, 0xFF666666);
    }
}