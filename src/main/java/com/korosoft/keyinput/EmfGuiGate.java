package com.korosoft.keyinput;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Confines EMF's {@code is_in_gui} animation variable to OUR accessories screen by gating the
 * FIELD that backs it, {@code EMFAnimationEntityContext.setIsInGui} (a {@code public static
 * boolean}).
 *
 * <p>Why the field and not the {@code isInGui()} method: EMF 3.2.4 sets the field to true at the
 * HEAD of {@code GuiRenderer.render} (its {@code Mixin_GuiEntityTester}) and back to false at
 * TAIL, and every read path — the compiled ASM animation's per-eval variable fill, plus EMF's own
 * internal {@code isInGui()} call sites — bottoms out in this one field. Forcing the field itself
 * to false while a foreign GUI is rendering is therefore upstream of EVERY reader, including any
 * that bypass {@code isInGui()}. A previous attempt that gated the method's return value through a
 * {@code @Pseudo} mixin on EMF's class was observed to have no effect in game (pseudo mixins fail
 * silently), which is why this gate runs from a hard mixin on the VANILLA GuiRenderer instead
 * (see {@code GuiRendererMixin}) and touches EMF only through reflection.
 *
 * <p>Soft dependency: the field is resolved once, by name, and every failure path is a silent
 * no-op — if EMF is absent or renamed the worst case is the original leak (menu pose in every
 * GUI), never a crash. A one-time log line reports whether the gate armed, so the state of this
 * integration is always visible in latest.log.
 */
public final class EmfGuiGate {

    private static final Logger LOGGER = LoggerFactory.getLogger("korosoft-core");

    private static final String EMF_CONTEXT_CLASS =
            "traben.entity_model_features.models.animation.EMFAnimationEntityContext";
    private static final String EMF_IN_GUI_FIELD = "setIsInGui";

    /** Resolved once; null when EMF is absent, incompatible, or a write ever failed. */
    private static Field inGuiField;
    private static boolean resolveAttempted;

    private EmfGuiGate() {
    }

    /**
     * Called by {@code GuiRendererMixin} once per frame, after EMF has raised its in-GUI flag and
     * before any GUI entity is rendered to texture. When our accessories screen is NOT the one
     * rendering, the flag is forced false so the pack's {@code is_in_gui}-gated menu pose stays
     * off; when it IS, the flag is left exactly as EMF set it.
     */
    public static void gate(boolean menuActive) {
        if (menuActive) {
            return;
        }
        Field field = resolve();
        if (field == null) {
            return;
        }
        try {
            if (field.getBoolean(null)) {
                field.setBoolean(null, false);
            }
        } catch (Throwable t) {
            // Disarm permanently rather than throw inside the render loop every frame.
            inGuiField = null;
            LOGGER.warn("[keyinput] EMF is_in_gui gate failed and was disarmed: {}", t.toString());
        }
    }

    private static Field resolve() {
        if (!resolveAttempted) {
            resolveAttempted = true;
            try {
                Field field = Class.forName(EMF_CONTEXT_CLASS).getField(EMF_IN_GUI_FIELD);
                if (field.getType() == boolean.class && Modifier.isStatic(field.getModifiers())) {
                    inGuiField = field;
                    LOGGER.info("[keyinput] EMF is_in_gui gate armed ({}.{})",
                            EMF_CONTEXT_CLASS, EMF_IN_GUI_FIELD);
                } else {
                    LOGGER.warn("[keyinput] EMF {}.{} has an unexpected shape; menu pose will show"
                                    + " in every GUI", EMF_CONTEXT_CLASS, EMF_IN_GUI_FIELD);
                }
            } catch (Throwable t) {
                LOGGER.info("[keyinput] EMF not present or incompatible ({}); is_in_gui gating"
                        + " disabled", t.toString());
            }
        }
        return inGuiField;
    }
}
