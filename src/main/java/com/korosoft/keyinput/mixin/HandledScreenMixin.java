package com.korosoft.keyinput.mixin;

import com.korosoft.keyinput.AccessoryPortrait;
import com.korosoft.keyinput.ScreenLayout;
import com.korosoft.keyinput.StatPanel;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns an ordinary container screen into a server-defined custom screen.
 *
 * A screen opts in by starting its title with {@link ScreenLayout#SENTINEL} plus a screen id
 * the server has already described over the "keyinput:screen" payload. Anything else is left
 * completely alone, and an id we hold no layout for falls back to vanilla rather than failing:
 * a missing GUI must never be worse than a plain one.
 *
 * Server coordinates are in panel space — relative to the top-left of the blitted texture,
 * which itself sits at (container origin + anchor).
 */
@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin extends Screen {

    @Shadow
    protected int x;

    @Shadow
    protected int y;

    @Shadow
    protected ScreenHandler handler;

    @Shadow
    protected abstract void drawBackground(DrawContext context, float delta, int mouseX, int mouseY);

    @Shadow
    private boolean isPointOverSlot(Slot slot, double pointX, double pointY) {
        throw new AssertionError();
    }

    @Unique
    private ScreenLayout keyinput$layout;

    @Unique
    private boolean keyinput$resolved;

    // ---- Click-to-reveal inventory drawer (FLAG_REVEAL_INVENTORY) ----
    // revealT is the eased 0..1 animation value; revealTarget is where it is heading (a click on a
    // ring slot opens, a click on empty space closes). Everything the drawer moves — the panel, the
    // ring slots, the portrait, the inventory grid — is driven off revealT each frame so draw and
    // hit-test never disagree.
    @Unique
    private float keyinput$revealT;

    @Unique
    private boolean keyinput$revealTarget;

    @Unique
    private long keyinput$revealNanos;

    // Native (vanilla-parked) X/Y of each player slot, captured once before we ever move it. The drawer
    // slides the player inventory in from the right by offsetting every player slot from this native X;
    // the pack-skinned background is translated by the same offset so texture and items stay glued.
    @Unique
    private final java.util.Map<Integer, Integer> keyinput$nativeInvX = new java.util.HashMap<>();
    @Unique
    private final java.util.Map<Integer, Integer> keyinput$nativeInvY = new java.util.HashMap<>();

    // How far up (GUI px) to lift the whole inventory crate from its native container position, so it
    // does not sit so low on screen. Applied to slots, background and scissor together. Tune to taste.
    @Unique
    private static final int KEYINPUT_INV_RAISE = 28;

    // Rightward rest offset (GUI px) for the inventory crate: it settles on the RIGHT of the screen
    // (where the old drawer sat) rather than centred, while the portrait/rings shift left. The slide-in
    // still starts further right than this and eases back to it. Tune to taste.
    @Unique
    private static final int KEYINPUT_INV_SHIFT_X = 148;

    // Small downward nudge of the scissor's TOP edge (GUI px). The vanilla container/inventory boundary
    // formula lands a couple of pixels too high against this pack's redesigned split, leaving a thin
    // sliver of the container crate showing; nudging the cut down hides it without touching the crate's
    // own top frame (there is a gap between them). Tune if a sliver returns or the frame gets clipped.
    @Unique
    private static final int KEYINPUT_INV_TOP_NUDGE = 6;

    @Unique
    private static final float KEYINPUT_REVEAL_EPS = 0.001F;

    // Seconds for a full open/close swipe. Longer = a more visible slide-in.
    @Unique
    private static final float KEYINPUT_REVEAL_DURATION = 0.30F;

    // How far off the right edge the inventory grid starts before sliding in (panel px).
    @Unique
    private static final float KEYINPUT_REVEAL_SLIDE = 260.0F;

    // Orbit: continuously advanced angle (radians) the bubbles rotate by. The near side of the
    // ellipse (front) draws bigger/on top; the far side smaller/behind, selling a 3D orbit.
    @Unique
    private float keyinput$orbitAngle;

    // The aro.png bubble sprite is 32x32. Only used to keep drawTexture UVs honest.
    @Unique
    private static final int KEYINPUT_BUBBLE_TEX = 32;

    @Unique
    private static final float KEYINPUT_ORBIT_DEPTH_MIN = 0.72F;

    @Unique
    private static final float KEYINPUT_ORBIT_DEPTH_RANGE = 0.40F;

    // Radians the orbit turns per pixel of horizontal mouse drag.
    @Unique
    private static final float KEYINPUT_ORBIT_DRAG = 0.006F;

    // A press+release that moved less than this (px) is a click (focus a bubble), not a drag (spin).
    @Unique
    private static final float KEYINPUT_CLICK_SLOP = 5.0F;

    // Ring slot id pressed while the drawer is closed — resolved to click-vs-drag on release.
    @Unique
    private int keyinput$pressBubbleId = -1;

    // Accumulated |horizontal drag| since the press, to tell a click from a spin.
    @Unique
    private float keyinput$dragAccum;

    // While the drawer is open, the focused ring slot; -1 when none. The carousel rotates so this
    // bubble sits at the front-centre — the others stay visible and rotate with it.
    @Unique
    private int keyinput$focusRingId = -1;

    // Visual permutation: data-slot ids in the order the bubbles are drawn. MMOInventory equips a ring
    // into whatever accessory slot it wants (first free), so after each equip we swap this order to show
    // the ring at the socket the player actually selected. Null until first built from the natural order.
    @Unique
    private int[] keyinput$visualOrder;

    // A pending "show the just-equipped ring at the focused socket" remap. When the player equips into a
    // focused bubble we snapshot which sockets were empty; the frame one of them fills, that is the ring
    // MMOInventory placed, and we move it to keyinput$pendingRemapVisual in keyinput$visualOrder.
    @Unique
    private int keyinput$pendingRemapVisual = -1;
    @Unique
    private float keyinput$pendingRemapElapsed;
    @Unique
    private final java.util.Set<Integer> keyinput$pendingRemapEmpty = new java.util.HashSet<>();

    // The orbit angle the carousel eases toward while a bubble is focused (front-centre = PI/2).
    @Unique
    private float keyinput$orbitTargetAngle;

    // How fast the carousel eases to the focused bubble (per second; 1 = snap in ~1s of 1/e steps).
    @Unique
    private static final float KEYINPUT_ORBIT_SNAP = 9.0F;

    // Orbit angle at which a bubble sits front-centre (nearest, horizontally centred).
    @Unique
    private static final float KEYINPUT_ORBIT_FRONT = (float) (Math.PI / 2.0);

    protected HandledScreenMixin() {
        super(null);
    }

    /** Resolved once: a screen's title cannot change while it is open. */
    @Unique
    private ScreenLayout keyinput$layout() {
        if (!keyinput$resolved) {
            keyinput$resolved = true;
            keyinput$layout = ScreenLayout.forTitle(this.getTitle().getString());
        }
        return keyinput$layout;
    }

    @Unique
    private int keyinput$panelX(ScreenLayout l) {
        return this.x + l.anchorX();
    }

    @Unique
    private int keyinput$panelY(ScreenLayout l) {
        return this.y + l.anchorY();
    }

    @Unique
    private static boolean keyinput$isPlayerSlot(Slot slot) {
        return slot.inventory instanceof PlayerInventory;
    }

    @Unique
    private boolean keyinput$revealActive() {
        return keyinput$revealT > KEYINPUT_REVEAL_EPS;
    }

    /** The accessories group (panel + rings + portrait) shrinks toward this factor as it opens. */
    @Unique
    private float keyinput$groupScale(ScreenLayout l) {
        return 1.0F - (l.revealShrinkPct() / 100.0F) * keyinput$revealT;
    }

    /** Panel-space pivot the group scales around: the panel's own centre. */
    @Unique
    private float keyinput$pivotX(ScreenLayout l) {
        return l.anchorX() + l.panelW() / 2.0F;
    }

    @Unique
    private float keyinput$pivotY(ScreenLayout l) {
        return l.anchorY() + l.panelH() / 2.0F;
    }

    /** Map a panel-space X through the current group transform (scale about pivot, then shift left). */
    @Unique
    private float keyinput$groupX(ScreenLayout l, float px) {
        float s = keyinput$groupScale(l);
        return keyinput$pivotX(l) + (px - keyinput$pivotX(l)) * s - l.revealShiftX() * keyinput$revealT;
    }

    @Unique
    private float keyinput$groupY(ScreenLayout l, float py) {
        float s = keyinput$groupScale(l);
        return keyinput$pivotY(l) + (py - keyinput$pivotY(l)) * s;
    }

    /**
     * The fully-open, panel-space top-left of one player inventory slot. The 27 main slots
     * ({@code getIndex()} 9..35) form a 3x9 grid; the 9 hotbar slots (0..8) sit one row below with
     * a small gap, exactly like a vanilla inventory picture.
     */
    @Unique
    private float keyinput$invSlotX(ScreenLayout l, Slot slot) {
        int idx = slot.getIndex();
        int col = idx >= 9 ? (idx - 9) % 9 : idx;
        return l.anchorX() + l.invOriginX() + col * l.invCellW();
    }

    @Unique
    private float keyinput$invSlotY(ScreenLayout l, Slot slot) {
        int idx = slot.getIndex();
        if (idx >= 9) {
            int row = (idx - 9) / 9;
            return l.anchorY() + l.invOriginY() + row * l.invCellH();
        }
        // hotbar: one row below the 3 main rows, with a 4px breather
        return l.anchorY() + l.invOriginY() + 3 * l.invCellH() + 4;
    }

    /**
     * Advance the swipe animation and re-place every slot for this frame. Runs at render HEAD so the
     * positions are fresh before vanilla draws the slots and before any hit-test this frame.
     */
    @Unique
    private void keyinput$layoutReveal(ScreenLayout l) {
        long now = System.nanoTime();
        if (keyinput$revealNanos == 0L) {
            keyinput$revealNanos = now;
        }
        float dt = (now - keyinput$revealNanos) / 1_000_000_000.0F;
        keyinput$revealNanos = now;

        keyinput$tickEquipRemap(l, dt);

        float target = keyinput$revealTarget ? 1.0F : 0.0F;
        float step = dt / KEYINPUT_REVEAL_DURATION;
        if (keyinput$revealT < target) {
            keyinput$revealT = Math.min(target, keyinput$revealT + step);
        } else if (keyinput$revealT > target) {
            keyinput$revealT = Math.max(target, keyinput$revealT - step);
        }

        boolean revealing = keyinput$revealActive();

        // Orbit: spin the ring slots around the (group-transformed) portrait centre on an ellipse.
        // Their real slot.x/y are moved so vanilla hit-testing and our bubble draw both follow.
        if (l.orbit()) {
            if (keyinput$revealTarget) {
                // Focused: ease the whole carousel around so the clicked bubble reaches front-centre.
                float f = Math.min(1.0F, dt * KEYINPUT_ORBIT_SNAP);
                keyinput$orbitAngle += (keyinput$orbitTargetAngle - keyinput$orbitAngle) * f;
            } else {
                // Auto-spin while closed (drag also drives keyinput$orbitAngle directly).
                keyinput$orbitAngle += (float) (l.orbitSpeedCentiDeg() / 100.0 * Math.PI / 180.0) * dt;
            }
            List<Slot> ring = keyinput$orbitRingSlots(l);
            int count = ring.size();
            float s = keyinput$groupScale(l);
            float pcx = this.x + keyinput$groupX(l, l.anchorX() + l.orbitCenterX());
            float pcy = this.y + keyinput$groupY(l, l.anchorY() + l.orbitCenterY());
            float rx = l.orbitRadiusX() * s;
            float ry = l.orbitRadiusY() * s;
            for (int i = 0; i < count; i++) {
                float ang = keyinput$orbitAngle + (float) (2 * Math.PI * i / Math.max(1, count));
                float cx = pcx + rx * (float) Math.cos(ang);
                float cy = pcy + ry * (float) Math.sin(ang);
                Slot slot = ring.get(i);
                ((SlotAccessor) slot).keyinput$setX(Math.round(cx) - this.x - 8);
                ((SlotAccessor) slot).keyinput$setY(Math.round(cy) - this.y - 8);
            }
        }

        for (Slot slot : this.handler.slots) {
            if (keyinput$isPlayerSlot(slot)) {
                // Native-inventory drawer: the player inventory lives at its NATIVE position (so the
                // resource pack skins it), but slides in from the right on reveal. Capture the native X
                // once, then offset every player slot by the same slide as the (translated) background,
                // so texture and items move together. Y stays native — the slide is horizontal.
                int nativeX = keyinput$nativeInvX.computeIfAbsent(slot.id, id -> slot.x);
                int nativeY = keyinput$nativeInvY.computeIfAbsent(slot.id, id -> slot.y);
                float slideX = (1.0F - keyinput$revealT) * KEYINPUT_REVEAL_SLIDE;
                ((SlotAccessor) slot).keyinput$setX(Math.round(nativeX + KEYINPUT_INV_SHIFT_X + slideX));
                ((SlotAccessor) slot).keyinput$setY(nativeY - KEYINPUT_INV_RAISE);
                continue;
            }
            if (l.orbit()) {
                continue;                         // ring slots already placed on the orbit above
            }
            // Container slots: re-place placed ring slots under the group transform. Slots the
            // layout doesn't mention keep vanilla positions.
            ScreenLayout.SlotSpec spec = l.slot(slot.id);
            if (spec == null) {
                continue;
            }
            float baseX = l.anchorX() + spec.x();
            float baseY = l.anchorY() + spec.y();
            ((SlotAccessor) slot).keyinput$setX(Math.round(keyinput$groupX(l, baseX)));
            ((SlotAccessor) slot).keyinput$setY(Math.round(keyinput$groupY(l, baseY)));
        }
    }

    /** The visible ring slots in orbit order (sent order 0,4,8,11,15), i.e. the ones with a drawn spec. */
    @Unique
    private List<Slot> keyinput$orbitRingSlots(ScreenLayout l) {
        List<Slot> natural = keyinput$naturalRingSlots(l);
        if (keyinput$visualOrder == null || keyinput$visualOrder.length != natural.size()) {
            keyinput$visualOrder = new int[natural.size()];
            for (int i = 0; i < natural.size(); i++) {
                keyinput$visualOrder[i] = natural.get(i).id;
            }
        }
        // Re-order the bubbles by the visual permutation so an equipped ring can be shown at the socket
        // the player selected, even though MMOInventory chose a different data slot for it.
        List<Slot> out = new ArrayList<>(natural.size());
        for (int id : keyinput$visualOrder) {
            for (Slot s : natural) {
                if (s.id == id) {
                    out.add(s);
                    break;
                }
            }
        }
        return out.size() == natural.size() ? out : natural;
    }

    /** The ring slots in their fixed data order (payload order), before the visual permutation. */
    @Unique
    private List<Slot> keyinput$naturalRingSlots(ScreenLayout l) {
        List<Slot> out = new ArrayList<>();
        for (ScreenLayout.SlotSpec spec : l.slots()) {
            if (spec.scalePct() <= 0 || spec.index() < 0 || spec.index() >= this.handler.slots.size()) {
                continue;
            }
            Slot s = this.handler.slots.get(spec.index());
            if (!keyinput$isPlayerSlot(s)) {
                out.add(s);
            }
        }
        return out;
    }

    /** 0 = far side of the orbit (smaller, behind), 1 = near side (bigger, in front). */
    @Unique
    private float keyinput$orbitFront(int i, int count) {
        float ang = keyinput$orbitAngle + (float) (2 * Math.PI * i / Math.max(1, count));
        return ((float) Math.sin(ang) + 1.0F) * 0.5F;
    }

    /** Screen-space radius of bubble {@code i}, including its depth (near/far) and group shrink. */
    @Unique
    private float keyinput$bubbleRadius(ScreenLayout l, int i, int count) {
        float depth = KEYINPUT_ORBIT_DEPTH_MIN + KEYINPUT_ORBIT_DEPTH_RANGE * keyinput$orbitFront(i, count);
        return l.bubbleSize() * keyinput$groupScale(l) * depth * 0.5F;
    }

    /** The front-most bubble whose circle contains the cursor, or null. */
    @Unique
    private Slot keyinput$bubbleHit(ScreenLayout l, double mouseX, double mouseY) {
        List<Slot> ring = keyinput$orbitRingSlots(l);
        int count = ring.size();
        Slot best = null;
        float bestFront = -1.0F;
        for (int i = 0; i < count; i++) {
            Slot slot = ring.get(i);
            if (!slot.isEnabled()) {
                continue;
            }
            float cx = this.x + slot.x + 8.0F;
            float cy = this.y + slot.y + 8.0F;
            float r = keyinput$bubbleRadius(l, i, count);
            double dx = mouseX - cx;
            double dy = mouseY - cy;
            float front = keyinput$orbitFront(i, count);
            if (dx * dx + dy * dy <= r * r && front > bestFront) {
                best = slot;
                bestFront = front;
            }
        }
        return best;
    }

    /** Focus a bubble: aim the carousel so it eases around to the front-centre, and open the drawer. */
    @Unique
    private void keyinput$focusBubble(ScreenLayout l, Slot slot) {
        List<Slot> ring = keyinput$orbitRingSlots(l);
        int count = ring.size();
        int idx = ring.indexOf(slot);
        if (idx < 0) {
            return;
        }
        float step = (float) (2 * Math.PI / Math.max(1, count));
        float target = KEYINPUT_ORBIT_FRONT - idx * step;
        // Pick the 2*pi-equivalent target nearest the current angle so it rotates the short way.
        float twoPi = 2.0F * (float) Math.PI;
        float k = Math.round((keyinput$orbitAngle - target) / twoPi);
        keyinput$orbitTargetAngle = target + k * twoPi;
        keyinput$focusRingId = slot.id;
        keyinput$revealTarget = true;
        keyinput$playSelect();
    }

    /** Client-side UI cue when a bubble is clicked (nexo:artefacto.select, shipped in the Nexo pack). */
    @Unique
    private void keyinput$playSelect() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getSoundManager() != null) {
            mc.getSoundManager().play(PositionedSoundInstance.ui(
                    SoundEvent.of(Identifier.of("nexo", "artefacto.select")), 1.0F, 1.0F));
        }
    }

    /** An inventory (player) slot under the cursor while the drawer is open, or null. */
    @Unique
    private Slot keyinput$playerSlotAt(double mouseX, double mouseY) {
        if (!keyinput$revealActive()) {
            return null;
        }
        for (Slot slot : this.handler.slots) {
            if (keyinput$isPlayerSlot(slot) && this.isPointOverSlot(slot, mouseX, mouseY) && slot.isEnabled()) {
                return slot;
            }
        }
        return null;
    }

    /** A placed, drawn ring slot under the cursor (container slot with a visible spec), or null. */
    @Unique
    private Slot keyinput$ringSlotAt(double mouseX, double mouseY) {
        ScreenLayout l = keyinput$layout;
        if (l != null && l.orbit()) {
            return keyinput$bubbleHit(l, mouseX, mouseY);
        }
        for (Slot slot : this.handler.slots) {
            if (keyinput$isPlayerSlot(slot)) {
                continue;
            }
            ScreenLayout.SlotSpec spec = l.slot(slot.id);
            if (spec == null || spec.scalePct() <= 0) {
                continue;
            }
            if (this.isPointOverSlot(slot, mouseX, mouseY) && slot.isEnabled()) {
                return slot;
            }
        }
        return null;
    }

    /** The slot under the cursor that is currently visible/interactive (rings always, inventory while open), or null. */
    @Unique
    private Slot keyinput$revealSlotAt(double mouseX, double mouseY) {
        ScreenLayout l = keyinput$layout;
        if (l != null && l.orbit()) {
            Slot bubble = keyinput$bubbleHit(l, mouseX, mouseY);
            if (bubble != null) {
                return bubble;
            }
            if (keyinput$revealActive()) {
                for (Slot slot : this.handler.slots) {
                    if (keyinput$isPlayerSlot(slot) && this.isPointOverSlot(slot, mouseX, mouseY) && slot.isEnabled()) {
                        return slot;
                    }
                }
            }
            return null;
        }
        for (Slot slot : this.handler.slots) {
            boolean player = keyinput$isPlayerSlot(slot);
            if (player && !keyinput$revealActive()) {
                continue;
            }
            if (!player && keyinput$layout.slot(slot.id) == null) {
                continue;                         // an un-placed container slot is not shown
            }
            if (this.isPointOverSlot(slot, mouseX, mouseY) && slot.isEnabled()) {
                return slot;
            }
        }
        return null;
    }

    /**
     * Move the slots and the anvil's rename box onto the panel, once the handler's slots and
     * the widgets both exist.
     */
    @Inject(method = "init", at = @At("TAIL"))
    private void keyinput$applyLayout(CallbackInfo ci) {
        ScreenLayout l = keyinput$layout();
        if (l == null) {
            return;
        }

        for (ScreenLayout.SlotSpec spec : l.slots()) {
            if (spec.index() < 0 || spec.index() >= this.handler.slots.size()) {
                continue;                       // a stale config must not crash the screen
            }
            Slot slot = this.handler.slots.get(spec.index());
            // Slot coordinates are relative to the container origin, so fold the anchor in to
            // land the slot exactly where the server asked for it in panel space.
            ((SlotAccessor) slot).keyinput$setX(l.anchorX() + spec.x());
            ((SlotAccessor) slot).keyinput$setY(l.anchorY() + spec.y());
        }
        // The anvil's rename box is NOT positioned here: ForgingScreen only creates it in
        // setup(), which runs after this. See AnvilScreenMixin.
    }

    /**
     * Drive the reveal swipe. At render HEAD, before any slot is drawn or hit-tested this frame,
     * advance the animation and re-place every slot for the current {@code revealT}. No-op unless the
     * screen opted into the drawer.
     */
    @Inject(method = "render", at = @At("HEAD"))
    private void keyinput$tickReveal(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        ScreenLayout l = keyinput$layout();
        // Flag OUR portrait screen so EmfGuiGate (via GuiRendererMixin) confines EMF's is_in_gui —
        // and with it the pack's menu pose — to this screen and not the vanilla inventory, which is
        // also a HandledScreen and also is_in_gui. Set every frame, for every HandledScreen (a
        // null/other layout clears it), so it is correct at the deferred entity-batch flush that
        // happens later this frame.
        AccessoryPortrait.MENU_ACTIVE = l != null && l.showPortrait();
        if (l == null || !l.revealInventory()) {
            return;
        }
        keyinput$layoutReveal(l);
    }

    /**
     * Replace the vanilla container texture with the panel.
     *
     * drawBackground is abstract on HandledScreen and therefore cannot be injected into, so
     * the call site inside renderBackground is redirected instead.
     */
    @Redirect(
            method = "renderBackground",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screen/ingame/HandledScreen;"
                            + "drawBackground(Lnet/minecraft/client/gui/DrawContext;FII)V"
            )
    )
    private void keyinput$drawPanel(HandledScreen<?> screen, DrawContext context,
                                    float delta, int mouseX, int mouseY) {
        ScreenLayout l = keyinput$layout();
        if (l == null) {
            this.drawBackground(context, delta, mouseX, mouseY);
            return;
        }
        // When the layout does NOT hide the vanilla background, draw it first so the
        // player's own inventory slots keep their normal texture; the panel is then
        // painted OVER it (an opaque panel hides the container area but leaves the
        // inventory grid below visible). hideBackground=true keeps the old behaviour:
        // panel only, no vanilla.
        if (!l.hideBackground()) {
            if (l.revealInventory()) {
                // Native-inventory drawer: only paint the vanilla background (which the resource pack
                // skins) while the drawer is open, and SCISSOR it to just the player-inventory crate so
                // the container-storage crate above — the accessory slots, shown as orbit bubbles
                // instead — is clipped away. Closed drawer draws no background, so the menu is just the
                // orbiting rings until a ring is clicked.
                if (keyinput$revealActive()) {
                    // Slide the pack-skinned background in from the right by the same offset the player
                    // slots use (see layoutReveal), so texture and items stay glued as it swipes in. The
                    // scissor is set from the (already-offset) slot positions BEFORE the matrix push, so
                    // it is not double-shifted by the translate — it already tracks the slide.
                    float slideX = (1.0F - keyinput$revealT) * KEYINPUT_REVEAL_SLIDE;
                    keyinput$scissorPlayerInv(context);
                    Matrix3x2fStack matrices = context.getMatrices();
                    matrices.pushMatrix();
                    matrices.translate(KEYINPUT_INV_SHIFT_X + slideX, (float) -KEYINPUT_INV_RAISE);
                    this.drawBackground(context, delta, mouseX, mouseY);
                    matrices.popMatrix();
                    context.disableScissor();
                }
            } else {
                this.drawBackground(context, delta, mouseX, mouseY);
            }
        }

        // No custom drawer backdrop any more: the player inventory shows at its native position and the
        // resource pack textures it. Painting our own dark card here would cover that pack texture.

        // In orbit mode the texture is the per-bubble sprite, not a full panel — don't blit it here.
        if (l.orbit()) {
            return;
        }

        float s = l.revealInventory() ? keyinput$groupScale(l) : 1.0F;
        // Panel top-left, run through the same group transform as the slots so texture and slots
        // move together. At revealT==0 this is an identity transform (s==1, shift==0).
        int drawX = this.x + Math.round(keyinput$groupX(l, l.anchorX()));
        int drawY = this.y + Math.round(keyinput$groupY(l, l.anchorY()));
        context.drawTexture(
                RenderPipelines.GUI_TEXTURED, l.texture(),
                drawX, drawY,
                0.0F, 0.0F,
                Math.round(l.panelW() * s), Math.round(l.panelH() * s),
                Math.round(l.panelW() * s), Math.round(l.panelH() * s)
        );
    }

    // Generous left/right/bottom scissor padding (GUI px) so the pack texture's wooden frame + base is
    // never clipped. Being too wide here is harmless: the background layer is empty around the crate,
    // so only the TOP edge (computed exactly below) needs to be tight to hide the container crate.
    @Unique private static final int KEYINPUT_INV_PAD_SIDE = 48;
    @Unique private static final int KEYINPUT_INV_PAD_B = 72;

    /**
     * Clip rendering to the player-inventory crate. Drawing the vanilla (resource-pack-skinned)
     * background inside this scissor paints only the inventory crate and clips the container-storage
     * crate above it. The caller balances this with {@code disableScissor()}.
     */
    @Unique
    private void keyinput$scissorPlayerInv(DrawContext context) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        int containerSlots = 0;
        for (Slot slot : this.handler.slots) {
            if (!keyinput$isPlayerSlot(slot)) {
                containerSlots++;
                continue;
            }
            int sx = this.x + slot.x;
            int sy = this.y + slot.y;
            if (sx < minX) minX = sx;
            if (sx + 16 > maxX) maxX = sx + 16;
            if (sy + 16 > maxY) maxY = sy + 16;
        }
        if (minX > maxX) {
            // No player slots (should not happen for a container): fall back to no clip so the
            // caller's disableScissor() still balances.
            context.enableScissor(0, 0, 1 << 20, 1 << 20);
            return;
        }
        // Top edge = the vanilla container/player-inventory boundary (this.y + rows*18 + 17). Everything
        // above it is the container-storage crate, which is clipped away; the player crate sits below.
        // rows is derived from the container (non-player) slot count.
        int rows = Math.max(1, containerSlots / 9);
        // Lifted by the same raise as the slots/background so the boundary still lands exactly at the
        // top of the (raised) player crate; maxY already reflects the raise via the slot positions.
        int topEdge = this.y + rows * 18 + 17 - KEYINPUT_INV_RAISE + KEYINPUT_INV_TOP_NUDGE;
        context.enableScissor(minX - KEYINPUT_INV_PAD_SIDE, topEdge,
                maxX + KEYINPUT_INV_PAD_SIDE, maxY + KEYINPUT_INV_PAD_B);
    }

    /** The title and the "Inventory" label belong to the vanilla look, not to ours. */
    @Inject(method = "drawForeground", at = @At("HEAD"), cancellable = true)
    private void keyinput$hideLabels(DrawContext context, int mouseX, int mouseY, CallbackInfo ci) {
        ScreenLayout l = keyinput$layout();
        if (l != null && l.hideLabels()) {
            ci.cancel();
        }
    }

    /**
     * Hide the player's own slots, and draw the container's items at the scale the server asked
     * for — a 16px item is lost inside a 45px socket.
     */
    @Inject(method = "drawSlot", at = @At("HEAD"), cancellable = true)
    private void keyinput$drawSlot(DrawContext context, Slot slot, int mouseX, int mouseY,
                                   CallbackInfo ci) {
        ScreenLayout l = keyinput$layout();
        if (l == null) {
            return;
        }

        if (l.hidePlayerInventory() && keyinput$isPlayerSlot(slot)) {
            // Hidden by default, but the reveal drawer shows the player inventory once open. While
            // revealing, let vanilla draw these at 100% at their (re-placed) positions.
            if (l.revealInventory() && keyinput$revealActive()) {
                return;
            }
            ci.cancel();
            return;
        }

        // Orbit mode owns the drawing of every container slot: ring slots are painted as bubbles in
        // the render TAIL (on top of the portrait), and the rest are hidden. Suppress vanilla here.
        if (l.orbit()) {
            ci.cancel();
            return;
        }

        ScreenLayout.SlotSpec spec = l.slot(slot.id);
        if (spec == null) {
            return;
        }
        // The ring art shrinks together with the panel as the drawer opens; fold the group scale in.
        float groupScale = l.revealInventory() ? keyinput$groupScale(l) : 1.0F;
        if (spec.scalePct() == 100 && groupScale >= 1.0F) {
            return;
        }

        // Scale 0 means "this slot is a hitbox, not a picture": it stays clickable, but the
        // art underneath is what the player sees. Used for the throwaway item that keeps the
        // anvil's rename box alive, and for buttons drawn into the panel.
        if (spec.scalePct() <= 0) {
            ci.cancel();
            return;
        }

        ItemStack stack = slot.getStack();
        if (stack.isEmpty()) {
            ci.cancel();
            return;
        }

        float scale = (spec.scalePct() / 100.0F) * groupScale;
        float cx = slot.x + 8.0F;                 // a slot's item box is 16x16
        float cy = slot.y + 8.0F;

        Matrix3x2fStack matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.translate(cx, cy);
        matrices.scale(scale, scale);
        matrices.translate(-cx, -cy);
        context.drawItem(stack, slot.x, slot.y);
        // drawItem paints the icon and NOTHING else. The stack count and the durability bar
        // come from drawStackOverlay, which vanilla's drawSlot calls right after — skip it and
        // a stack of 64 renders as a lone apple with no number on it.
        context.drawStackOverlay(this.getTextRenderer(), stack, slot.x, slot.y);
        matrices.popMatrix();

        ci.cancel();
    }

    /**
     * Kill the hit test on the player's slots. Doing it here rather than on the click handler
     * means the tooltip dies together with the click — an invisible item you can still hover
     * is worse than one you can still move.
     */
    @Inject(method = "getSlotAt", at = @At("HEAD"), cancellable = true)
    private void keyinput$getSlotAt(double mouseX, double mouseY,
                                    CallbackInfoReturnable<Slot> cir) {
        ScreenLayout l = keyinput$layout();
        if (l == null || !l.hidePlayerInventory()) {
            return;
        }
        // With orbiting bubbles the ring hitboxes are circles that follow the animation, not the
        // 16x16 slot boxes — resolve them by bubble radius so tooltips/hover track the bubble.
        if (l.orbit()) {
            Slot bubble = keyinput$bubbleHit(l, mouseX, mouseY);
            if (bubble != null) {
                cir.setReturnValue(bubble);
                return;
            }
            if (keyinput$revealActive()) {
                for (Slot slot : this.handler.slots) {
                    if (keyinput$isPlayerSlot(slot) && this.isPointOverSlot(slot, mouseX, mouseY) && slot.isEnabled()) {
                        cir.setReturnValue(slot);
                        return;
                    }
                }
            }
            cir.setReturnValue(null);
            return;
        }
        for (Slot slot : this.handler.slots) {
            // Player slots stay unclickable while hidden, but become live once the drawer opens.
            if (keyinput$isPlayerSlot(slot) && !keyinput$revealActive()) {
                continue;
            }
            if (this.isPointOverSlot(slot, mouseX, mouseY) && slot.isEnabled()) {
                cir.setReturnValue(slot);
                return;
            }
        }
        cir.setReturnValue(null);
    }

    /**
     * Draws the accessories portrait on top of everything else this frame — panel, slots and any
     * vanilla-left tooltips. It is meant to sit as the centerpiece the ring slots are arranged
     * around, not underneath them, so TAIL (last) is deliberate, not incidental.
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void keyinput$drawPortrait(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        ScreenLayout l = keyinput$layout();
        if (l == null || !l.showPortrait()) {
            return;
        }
        // Portrait rides the same group transform as the panel and rings so it shifts left and
        // shrinks in lockstep as the inventory drawer opens (identity when closed).
        float s = l.revealInventory() ? keyinput$groupScale(l) : 1.0F;
        int centerX = this.x + Math.round(keyinput$groupX(l, l.anchorX() + l.portraitX()));
        int centerY = this.y + Math.round(keyinput$groupY(l, l.anchorY() + l.portraitY()));
        // Bubbles on the FAR side of the orbit draw BEFORE the portrait so the player occludes them
        // (they pass behind him); NEAR-side bubbles draw AFTER, floating in front. GUI draws honour
        // submission order relative to the deferred entity, so this split is what puts the orbit
        // around the player in 3D rather than always in front.
        if (l.orbit()) {
            keyinput$drawBubbles(context, l, mouseX, mouseY, false);   // far half, behind portrait
        }

        AccessoryPortrait.render(context, centerX, centerY, Math.round(l.portraitScale() * s));

        if (l.orbit()) {
            keyinput$drawBubbles(context, l, mouseX, mouseY, true);    // near half, in front
        }
    }

    /**
     * Draws the server-defined stat panel (label + base value, plus an artifact delta in green)
     * over whichever custom screen is open. Deliberately independent of {@code showPortrait} and
     * every other flag: the panel is a separate generic mechanism keyed only by screenId, exactly
     * like {@link StatPanel} itself knows nothing about what a "stat" is — only the server does.
     *
     * {@code spec.scalePct()} scales the GLYPHS only, never the row's own (x, y): each row is
     * pushed through its own {@code translate(rx,ry) -&gt; scale -&gt; translate(-rx,-ry)} matrix, the
     * same pivot-about-a-point recipe {@code keyinput$drawSlot}/{@code keyinput$drawOneBubble}
     * already use for items and bubbles, so row-step and x/y stay in plain panel px for the
     * server regardless of scale.
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void keyinput$drawStatPanel(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        ScreenLayout l = keyinput$layout();
        if (l == null) {
            return;
        }
        StatPanel.Spec spec = StatPanel.forScreen(l.screenId());
        if (spec == null || spec.rows().isEmpty()) {
            return;
        }

        float scale = spec.scalePct() / 100.0F;
        if (scale <= 0.0F) {
            return;                            // 0% = hidden, same convention as SlotSpec.scalePct
        }
        int deltaColor = spec.deltaColorOverride() != 0 ? spec.deltaColorOverride() : StatPanel.DEFAULT_DELTA_COLOR;
        int panelX = this.x + l.anchorX();
        int panelY = this.y + l.anchorY();
        Matrix3x2fStack matrices = context.getMatrices();

        for (StatPanel.Row row : spec.rows()) {
            int rx = panelX + row.x();
            int ry = panelY + row.y();
            String baseText = row.label() + " " + keyinput$formatStat(row.base(), row.decimals());
            String deltaText = null;
            if (row.delta() != 0.0F) {
                String sign = row.delta() >= 0.0F ? "+" : "";
                deltaText = "(" + sign + keyinput$formatStat(row.delta(), row.decimals()) + ")";
            }

            matrices.pushMatrix();
            matrices.translate(rx, ry);
            matrices.scale(scale, scale);
            matrices.translate(-rx, -ry);

            context.drawText(this.getTextRenderer(), baseText, rx, ry, StatPanel.DEFAULT_BASE_COLOR, false);
            if (deltaText != null) {
                int dx = rx + this.getTextRenderer().getWidth(baseText) + 4;
                context.drawText(this.getTextRenderer(), deltaText, dx, ry, deltaColor, false);
            }

            matrices.popMatrix();
        }
    }

    /** {@code base}/{@code delta} formatted to {@code decimals} places, locale-fixed so a comma-decimal
     * locale never turns "5.0" into "5,0" and desyncs the layout the server measured against. */
    @Unique
    private static String keyinput$formatStat(float value, int decimals) {
        return String.format(java.util.Locale.ROOT, "%." + decimals + "f", value);
    }



    /**
     * The stat panel is keyed by screenId, not tied to this screen instance, so a stale panel must
     * not keep showing if the same screenId is ever reused for something else before a fresh
     * payload arrives. Cleared here rather than left to the next payload's overwrite.
     */
    @Inject(method = "removed", at = @At("TAIL"))
    private void keyinput$clearStatPanel(CallbackInfo ci) {
        ScreenLayout l = keyinput$layout();
        if (l != null) {
            StatPanel.clear(l.screenId());
        }
    }

    /**
     * Paint one half of the orbit, far-to-near so the nearer bubbles overlap the further ones.
     * {@code nearHalf} selects the front side (front &gt; 0.5, drawn in front of the portrait) or the
     * back side (drawn behind it).
     */
    @Unique
    private void keyinput$drawBubbles(DrawContext context, ScreenLayout l, int mouseX, int mouseY, boolean nearHalf) {
        List<Slot> ring = keyinput$orbitRingSlots(l);
        int n = ring.size();
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Float.compare(keyinput$orbitFront(a, n), keyinput$orbitFront(b, n)));
        for (int k = 0; k < n; k++) {
            int i = order[k];
            if ((keyinput$orbitFront(i, n) > 0.5F) != nearHalf) {
                continue;                         // this bubble belongs to the other (front/back) layer
            }
            keyinput$drawOneBubble(context, l, ring.get(i), i, n, mouseX, mouseY);
        }
    }

    /** One bubble: the aro sprite plus the equipped item, sized by orbit depth + hover, centred on the slot. */
    @Unique
    private void keyinput$drawOneBubble(DrawContext context, ScreenLayout l, Slot slot, int i, int count,
                                        int mouseX, int mouseY) {
        float cx = this.x + slot.x + 8.0F;
        float cy = this.y + slot.y + 8.0F;
        float baseFactor = keyinput$groupScale(l)
                * (KEYINPUT_ORBIT_DEPTH_MIN + KEYINPUT_ORBIT_DEPTH_RANGE * keyinput$orbitFront(i, count));
        float baseRadius = l.bubbleSize() * baseFactor * 0.5F;
        double dx = mouseX - cx;
        double dy = mouseY - cy;
        boolean hover = dx * dx + dy * dy <= baseRadius * baseRadius;
        float factor = baseFactor * (hover ? l.bubbleHoverPct() / 100.0F : 1.0F);

        // The focused bubble gets a gentle size bump so the selection reads; the others stay full-size
        // and keep orbiting into place — nothing disappears.
        if (keyinput$revealActive() && l.orbit() && slot.id == keyinput$focusRingId) {
            factor *= 1.0F + 0.25F * keyinput$revealT;
        }
        if (factor <= 0.001F) {
            return;
        }

        // Bubble sprite: width == textureWidth makes drawTexture stretch the full 32x32 aro to `size`.
        int size = Math.max(1, Math.round(l.bubbleSize() * factor));
        int bx = Math.round(cx) - size / 2;
        int by = Math.round(cy) - size / 2;
        context.drawTexture(RenderPipelines.GUI_TEXTURED, l.texture(), bx, by, 0.0F, 0.0F, size, size, size, size);

        // Item icon, centred in the bubble and scaled with it.
        ItemStack stack = slot.getStack();
        if (!stack.isEmpty()) {
            float itemScale = (l.bubbleItemScalePct() / 100.0F) * factor;
            Matrix3x2fStack matrices = context.getMatrices();
            matrices.pushMatrix();
            matrices.translate(cx, cy);
            matrices.scale(itemScale, itemScale);
            matrices.translate(-8.0F, -8.0F);
            context.drawItem(stack, 0, 0);
            context.drawStackOverlay(this.getTextRenderer(), stack, 0, 0);
            matrices.popMatrix();
        }
    }

    /**
     * The reveal drawer's whole gesture model, all on the left mouse button:
     * <ul>
     *   <li><b>Closed + click a ring slot</b> → open the drawer (swallowed, so it does not pick up
     *       the equipped accessory).</li>
     *   <li><b>Open + click empty space</b> → close the drawer.</li>
     *   <li><b>Open + click any slot</b> → a one-click equip/unequip. MMOInventory does NOT equip on a
     *       plain pickup — that only lifts the item to the cursor — it equips on a quick-move (its
     *       shift-click path routes the item to the matching accessory slot via findBestSlot). So we
     *       translate the click into a {@code QUICK_MOVE}: an inventory item equips, an equipped ring
     *       unequips, and a quick-move on a placeholder/display item is ignored server-side. This is
     *       the fix for "clicking the ring does not place it in the artifact slot".</li>
     * </ul>
     * Right-click and every non-drawer screen fall through to vanilla untouched.
     */
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void keyinput$revealClick(Click click, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        ScreenLayout l = keyinput$layout();
        if (l == null || !l.revealInventory() || click.button() != 0) {
            return;
        }
        double mx = click.x();
        double my = click.y();

        // Orbit, drawer closed: a press only ARMS a bubble. Release decides — a small move is a click
        // (focus the bubble + open), a bigger move was a drag (spin, handled in mouseDragged).
        if (l.orbit() && !keyinput$revealTarget) {
            Slot pressed = keyinput$bubbleHit(l, mx, my);
            keyinput$pressBubbleId = pressed != null ? pressed.id : -1;
            keyinput$dragAccum = 0.0F;
            if (pressed != null) {
                cir.setReturnValue(true);       // swallow so it never lifts the equipped accessory
            }
            return;
        }

        if (!keyinput$revealTarget) {
            if (keyinput$ringSlotAt(mx, my) != null) {
                keyinput$revealTarget = true;
                cir.setReturnValue(true);
            }
            return;
        }

        // Drawer open + orbit: click another bubble to rotate the carousel to IT; click an inventory
        // item to equip it; click empty space to close.
        if (l.orbit()) {
            // A real item in the player inventory under the cursor is an explicit "equip THIS ring"
            // intent, and it wins over a bubble that merely orbits across the same pixels — otherwise
            // a floating bubble steals the click and the ring can never be placed.
            Slot inv = keyinput$playerSlotAt(mx, my);
            if (inv != null && !inv.getStack().isEmpty()) {
                keyinput$beginEquipRemap(l);
                keyinput$quickMove(inv);
                cir.setReturnValue(true);
                return;
            }
            Slot bubble = keyinput$bubbleHit(l, mx, my);
            if (bubble != null) {
                // Clicking the bubble ALREADY at front-centre, when it holds an accessory, TAKES IT
                // OFF: a quick-move on an equipped ring routes it back to the player inventory (the
                // same unequip path the non-orbit drawer uses). A first click on any other bubble
                // only rotates the carousel to bring it centre — so the gesture is "click to select,
                // click the selected one again to remove". An empty focused bubble has nothing to
                // unequip, so it falls through to re-focus (a harmless no-op spin).
                if (bubble.id == keyinput$focusRingId && !bubble.getStack().isEmpty()) {
                    keyinput$quickMove(bubble);
                    cir.setReturnValue(true);
                    return;
                }
                keyinput$focusBubble(l, bubble);
                cir.setReturnValue(true);
                return;
            }
            keyinput$revealTarget = false;
            keyinput$focusRingId = -1;
            cir.setReturnValue(true);
            return;
        }

        Slot hit = keyinput$revealSlotAt(mx, my);
        if (hit == null) {
            keyinput$revealTarget = false;      // click outside every slot closes the drawer
            keyinput$focusRingId = -1;
            cir.setReturnValue(true);
            return;
        }
        keyinput$quickMove(hit);
        cir.setReturnValue(true);
    }

    /** Route a click into MMOInventory's equip path (see keyinput$revealClick for why quick-move). */
    @Unique
    private void keyinput$quickMove(Slot slot) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.interactionManager != null && mc.player != null) {
            mc.interactionManager.clickSlot(this.handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, mc.player);
        }
    }

    /** True when an accessory socket holds nothing but MMOInventory's "ACCESORIO" placeholder (or air). */
    @Unique
    private boolean keyinput$isEmptyAccessory(Slot slot) {
        ItemStack stack = slot.getStack();
        return stack.isEmpty() || stack.getName().getString().contains("ACCESORIO");
    }

    /**
     * Arm the equip remap: right before a shift-click equip, note the focused socket's visual position and
     * snapshot which sockets are currently empty. MMOInventory equips into a socket of its own choosing, so
     * {@link #keyinput$tickEquipRemap} watches for whichever of those empty sockets fills and moves it here.
     */
    @Unique
    private void keyinput$beginEquipRemap(ScreenLayout l) {
        keyinput$pendingRemapVisual = -1;
        keyinput$pendingRemapEmpty.clear();
        if (keyinput$focusRingId < 0) {
            return;
        }
        List<Slot> ring = keyinput$orbitRingSlots(l);
        int focusedVisual = -1;
        for (int i = 0; i < ring.size(); i++) {
            Slot s = ring.get(i);
            if (s.id == keyinput$focusRingId) {
                focusedVisual = i;
            }
            if (keyinput$isEmptyAccessory(s)) {
                keyinput$pendingRemapEmpty.add(s.id);
            }
        }
        if (focusedVisual < 0) {
            keyinput$pendingRemapEmpty.clear();
            return;
        }
        keyinput$pendingRemapVisual = focusedVisual;
        keyinput$pendingRemapElapsed = 0.0F;
    }

    /**
     * Finish a pending equip remap: once one of the snapshotted-empty sockets holds a real ring, that is the
     * one MMOInventory just equipped — swap the visual order so it sits at the socket the player selected,
     * and move the focus onto it so a follow-up click unequips it. Times out if nothing equips.
     */
    @Unique
    private void keyinput$tickEquipRemap(ScreenLayout l, float dt) {
        if (keyinput$pendingRemapVisual < 0) {
            return;
        }
        keyinput$pendingRemapElapsed += dt;
        for (Slot s : keyinput$orbitRingSlots(l)) {
            if (keyinput$pendingRemapEmpty.contains(s.id) && !keyinput$isEmptyAccessory(s)) {
                keyinput$swapVisual(keyinput$pendingRemapVisual, s.id);
                keyinput$focusRingId = s.id;
                keyinput$pendingRemapVisual = -1;
                keyinput$pendingRemapEmpty.clear();
                return;
            }
        }
        if (keyinput$pendingRemapElapsed > 1.5F) {
            keyinput$pendingRemapVisual = -1;
            keyinput$pendingRemapEmpty.clear();
        }
    }

    /** Swap the visual order so slot id {@code filledId} is shown at visual position {@code visualP}. */
    @Unique
    private void keyinput$swapVisual(int visualP, int filledId) {
        if (keyinput$visualOrder == null || visualP < 0 || visualP >= keyinput$visualOrder.length) {
            return;
        }
        int q = -1;
        for (int i = 0; i < keyinput$visualOrder.length; i++) {
            if (keyinput$visualOrder[i] == filledId) {
                q = i;
                break;
            }
        }
        if (q < 0) {
            return;
        }
        int tmp = keyinput$visualOrder[visualP];
        keyinput$visualOrder[visualP] = keyinput$visualOrder[q];
        keyinput$visualOrder[q] = tmp;
    }

    /** Drag anywhere to spin the carousel — but only while it is auto-orbiting (drawer closed). */
    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void keyinput$orbitDrag(Click click, double offsetX, double offsetY, CallbackInfoReturnable<Boolean> cir) {
        ScreenLayout l = keyinput$layout();
        if (l == null || !l.orbit() || keyinput$revealTarget) {
            return;
        }
        keyinput$orbitAngle -= (float) (offsetX * KEYINPUT_ORBIT_DRAG);
        keyinput$dragAccum += (float) Math.abs(offsetX);
        cir.setReturnValue(true);
    }

    /** Release after a near-still press on a bubble = a click: pull it to the centre and open the drawer. */
    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void keyinput$orbitRelease(Click click, CallbackInfoReturnable<Boolean> cir) {
        ScreenLayout l = keyinput$layout();
        if (l == null || !l.orbit() || keyinput$revealTarget) {
            keyinput$pressBubbleId = -1;
            return;
        }
        if (keyinput$pressBubbleId >= 0 && keyinput$dragAccum < KEYINPUT_CLICK_SLOP) {
            for (Slot s : this.handler.slots) {
                if (s.id == keyinput$pressBubbleId) {
                    keyinput$focusBubble(l, s);   // rotate the carousel to bring it front-centre
                    break;
                }
            }
            cir.setReturnValue(true);
        }
        keyinput$pressBubbleId = -1;
    }

    /**
     * Auction-House price breakdown on demand. AH listing items carry an "ah_tip" string in their
     * custom_data (baked server-side, already personalised to this viewer's balance). While Shift
     * is held the tooltip expands into those lines; otherwise a single hint line invites it. Gated
     * purely by the marker, so ordinary items pay one component read and nothing else, and it needs
     * no screen id — only an item the server chose to mark. Legacy section codes in the baked
     * string render through the vanilla text renderer, same as chest lore.
     */
    @ModifyReturnValue(method = "getTooltipFromItem(Lnet/minecraft/item/ItemStack;)Ljava/util/List;", at = @At("RETURN"))
    private List<Text> keyinput$ahTooltip(List<Text> original, ItemStack stack) {
        NbtCompound nbt = stack.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).copyNbt();
        String tip = nbt.getString("ah_tip", "");
        if (tip.isEmpty()) {
            return original;
        }
        List<Text> out = new ArrayList<>(original);
        if (MinecraftClient.getInstance().isShiftPressed()) {
            for (String line : tip.split("\n")) {
                out.add(Text.literal(line));
            }
        } else {
            out.add(Text.literal("§8» Mantén §7Shift §8para ver el cambio"));
        }
        return out;
    }
}
