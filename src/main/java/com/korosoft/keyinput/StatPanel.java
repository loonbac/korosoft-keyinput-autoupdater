package com.korosoft.keyinput;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-defined stat rows drawn over a custom screen (e.g. the accessories menu): a label, a base
 * value and — when artifacts contribute — a delta shown in green. Exactly like {@link ScreenLayout},
 * this mod does not know what a "stat" is: the row text, values and pixel positions all arrive from
 * the server over {@link StatPanelPayload}, keyed by screenId so a new stat costs a Skript edit,
 * never a rebuild of this mod.
 */
public final class StatPanel {

    /** One row: a label, its base value, and the artifact delta (0 when nothing contributes). */
    public record Row(String label, float base, float delta, int x, int y, int decimals) {}

    /**
     * One screen's whole panel. {@code deltaColorOverride} is an ARGB color for every row's delta
     * text in this panel; 0 (no alpha) means "use {@link #DEFAULT_DELTA_COLOR}", since a sender
     * that predates this field simply never writes it and the lenient decoder defaults it to 0.
     * {@code scalePct} scales every row's GLYPHS only (100 = normal size); each row stays anchored
     * at its own (x, y) in panel space regardless of scale — see
     * {@code HandledScreenMixin#keyinput$drawStatPanel} for how the pivot is applied.
     */
    public record Spec(String screenId, List<Row> rows, int deltaColorOverride, int scalePct) {}

    /** Full-alpha green. ARGB 0xFFFFFF without the alpha byte is invisible — see ScreenLayoutPayload. */
    public static final int DEFAULT_DELTA_COLOR = 0xFF55FF55;

    public static final int DEFAULT_BASE_COLOR = 0xFFFFFFFF;

    /** 100 = unscaled text, matching every other *Pct field in this mod (e.g. SlotSpec.scalePct). */
    public static final int DEFAULT_SCALE_PCT = 100;

    private static final Map<String, Spec> PANELS = new ConcurrentHashMap<>();

    private StatPanel() {
    }

    /** Replaces any previously cached panel for this screenId. */
    public static void put(Spec spec) {
        PANELS.put(spec.screenId(), spec);
    }

    /** The cached panel for this screenId, or null if the server never sent one (or it was cleared). */
    public static Spec forScreen(String screenId) {
        return PANELS.get(screenId);
    }

    /** Drops the cached panel for one screen — called when that screen closes. */
    public static void clear(String screenId) {
        PANELS.remove(screenId);
    }

    /** Drops every cached panel — called on disconnect, same as {@link ScreenLayout#reset()}. */
    public static void reset() {
        PANELS.clear();
    }
}
