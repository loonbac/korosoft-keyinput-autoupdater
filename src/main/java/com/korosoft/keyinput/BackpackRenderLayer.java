package com.korosoft.keyinput;

import com.korosoft.keyinput.mixin.BasicItemModelAccessor;
import com.korosoft.keyinput.mixin.ItemRenderStateAccessor;
import com.korosoft.keyinput.mixin.ItemRenderStateInvoker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.json.Transformation;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.HeldItemContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws the worn backpack model on a player's back, purely client-side.
 *
 * <p>This is the whole point of the design: a server-side display entity always trails the
 * player (the client renders your own body from its predicted position, the entity from the
 * server's last known position — at sprint speed that is ~half a block). Rendering the backpack
 * as part of the player's own entity render binds it to the client's body pose with zero lag,
 * exactly like the Travelers Backpack mod does, and it rotates with the BODY yaw, not the head.
 *
 * <p>ALL placement values (anchor height/depth, extra scale, yaw) come from the server payload —
 * nothing here is a tuning constant. The feature-layer matrices live in the entity model space
 * where 16 units = 1 block, so the anchor is scaled by 16 before translation; the model's own
 * {@code fixed} display transform (the baked 0.65 scale) applies inside {@code ItemRenderState}.
 */
public class BackpackRenderLayer extends FeatureRenderer<PlayerEntityRenderState, PlayerEntityModel> {

    /** Model space: 16 units = 1 block. */

    /** One immutable paper+CMD stack per variant, reused across frames. */
    private static final Map<Integer, ItemStack> STACK_CACHE = new ConcurrentHashMap<>();

    private static final Logger LOGGER = LoggerFactory.getLogger("korosoft-core/backpack");
    private static final Set<String> DIAGNOSTIC_LOGGED = ConcurrentHashMap.newKeySet();

    /** The renderer context handed to the layer — gives us the shared player model to inherit
     *  the animated torso pose from (the same ModelPart the entity pass draws each frame). */
    private final FeatureRendererContext<PlayerEntityRenderState, PlayerEntityModel> layerContext;

    public BackpackRenderLayer(FeatureRendererContext<PlayerEntityRenderState, PlayerEntityModel> context) {
        super(context);
        this.layerContext = context;
    }

    @Override
    public void render(MatrixStack matrices, OrderedRenderCommandQueue queue, int light,
                       PlayerEntityRenderState state, float limbAngle, float limbDistance) {
        UUID playerUuid = ((BackpackRenderState) state).keyinput$getPlayerUuid();
        BackpackState.WornBackpack worn = BackpackState.getWorn(playerUuid);
        if (worn == null) {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;
        if (world == null) {
            return;
        }

        ItemStack stack = stackFor(worn.cmd());
        // TEST MODE: cmd 99999 renders a vanilla diamond instead of the SB model — if the diamond
        // is colored, the layer pipeline works and the SB sprites are the problem; if white too,
        // the layer context itself renders white. Server-driven via BACK_TEST_CMD in the sync script.
        if (worn.cmd() == 99999) {
            stack = new ItemStack(Items.DIAMOND);
        }
        ItemRenderState itemState = new ItemRenderState();
        HeldItemContext heldContext = new HeldItemContext() {
            @Override
            public net.minecraft.world.World getEntityWorld() {
                return world;
            }

            @Override
            public Vec3d getEntityPos() {
                return Vec3d.ZERO;
            }

            @Override
            public float getBodyYaw() {
                return 0.0F;
            }
        };
        mc.getItemModelManager().update(itemState, stack, ItemDisplayContext.FIXED, world, heldContext, 0);

        ItemRenderState.LayerRenderState layer = ((ItemRenderStateInvoker) (Object) itemState).keyinput$invokeGetFirstLayer();
        if (layer != null) {
            // Force the render layer to the atlas that actually holds this model's sprites. The
            // baker may stitch the model's textures into the items atlas while the layer binds
            // the blocks atlas (or vice versa) — the mismatch samples wrong UVs and renders the
            // backpack solid white.
            var quads = layer.getQuads();
            if (!quads.isEmpty()) {
                net.minecraft.util.Identifier spriteAtlas = quads.get(0).sprite().getAtlasId();
                java.util.function.Function<ItemStack, RenderLayer> getter =
                        spriteAtlas.getPath().contains("items")
                                ? BasicItemModelAccessor.keyinput$getItemsAtlasGetter()
                                : BasicItemModelAccessor.keyinput$getBlocksAtlasGetter();
                RenderLayer wanted = getter.apply(stack);
                if (wanted != ((ItemRenderStateAccessor) (Object) layer).keyinput$getRenderLayer()) {
                    ((ItemRenderStateAccessor) (Object) layer).keyinput$setRenderLayer(wanted);
                }
            }
        }

        // TEMPORARY DIAGNOSTIC — one line per (cmd, uuid): atlas, transform, sprite atlas.
        String diagKey = worn.cmd() + "|" + (playerUuid == null ? "?" : playerUuid.toString());
        if (!DIAGNOSTIC_LOGGED.contains(diagKey)) {
            DIAGNOSTIC_LOGGED.add(diagKey);
            Identifier itemModelId = stack.get(DataComponentTypes.ITEM_MODEL);
            String layerInfo = "none";
            String transformInfo = "none";
            String spriteAtlasInfo = "none";
            if (layer != null) {
                RenderLayer rl = ((ItemRenderStateAccessor) (Object) layer).keyinput$getRenderLayer();
                Transformation tr = ((ItemRenderStateAccessor) (Object) layer).keyinput$getTransform();
                layerInfo = rl == null ? "null" : rl.toString();
                transformInfo = tr == null ? "null" : tr.toString();
                if (!layer.getQuads().isEmpty()) {
                    net.minecraft.client.texture.Sprite spr = layer.getQuads().get(0).sprite();
                    spriteAtlasInfo = spr.getAtlasId() + " u=[" + spr.getMinU() + ".." + spr.getMaxU()
                            + "] v=[" + spr.getMinV() + ".." + spr.getMaxV() + "]";
                }
            }
            LOGGER.info("[KORO-BACK] cmd={} itemModel={} empty={} bbox={} layer={} transform={} spriteAtlas={}",
                    worn.cmd(), itemModelId, itemState.isEmpty(), itemState.getModelBoundingBox(),
                    layerInfo, transformInfo, spriteAtlasInfo);
        }

        if (itemState.isEmpty()) {
            return;
        }

        // TEMPORARY DIAGNOSTIC — base matrix state to identify the layer coordinate space.
        String baseDiagKey = worn.cmd() + "|base|" + (playerUuid == null ? "?" : playerUuid.toString());
        if (!DIAGNOSTIC_LOGGED.contains(baseDiagKey)) {
            DIAGNOSTIC_LOGGED.add(baseDiagKey);
            org.joml.Matrix4f base = matrices.peek().getPositionMatrix();
            LOGGER.info("[KORO-BASE] cmd={} trans=({},{},{}) m00={} m11={} m22={}",
                    worn.cmd(), base.m30(), base.m31(), base.m32(),
                    base.m00(), base.m11(), base.m22());
        }

        matrices.push();
        // The feature layer runs with matrices in the player's ROOT frame (renderer flip
        // applied). To glue the backpack to the ANIMATED torso — exactly like the armor
        // (chestplate) layers do — apply the renderer model's real body part transform: the
        // same ModelPart the entity pass drew this frame, so vanilla + EMF/Fresh Animations
        // poses (running lean, crouch, body sway) are inherited 1:1. No pose replication, no
        // hardcoded animation: whatever the model draws, the backpack follows.
        PlayerEntityModel playerModel = layerContext.getModel();
        if (playerModel != null) {
            playerModel.body.applyTransform(matrices);
        }

        // Feature-layer space: the matrices now sit in the torso (body) frame, with +Y down and
        // +Z pointing at the model's back. The backpack item model is authored "Y up", so:
        //   - height: -backY (server BACK_Y, calibrated against the torso frame)
        //   - depth:  +backZ (server BACK_Z, pushes it behind the body)
        //   - orientation: FULLY server-driven — yawDeg (BACK_YAW) and flip (BACK_FLIP).
        //     Tuning is a Skript edit + /mochilarepush, never a mod rebuild.
        matrices.translate(0.0, -worn.backY(), worn.backZ());
        matrices.scale(worn.scale(), worn.scale(), worn.scale());
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(worn.yawDeg()));
        if (worn.flip()) {
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(180.0f));
        }

        // TEMPORARY DIAGNOSTIC — measure the WORLD anchor: camera + final translation.
        String afterDiagKey = worn.cmd() + "|after|" + (playerUuid == null ? "?" : playerUuid.toString());
        if (!DIAGNOSTIC_LOGGED.contains(afterDiagKey)) {
            DIAGNOSTIC_LOGGED.add(afterDiagKey);
            org.joml.Matrix4f after = matrices.peek().getPositionMatrix();
            Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
            LOGGER.info("[KORO-AFTER] cmd={} layerTrans=({},{},{}) cam=({},{},{}) worldAnchor=({},{},{}) m00={} m11={} m22={}",
                    worn.cmd(), after.m30(), after.m31(), after.m32(),
                    cam.x, cam.y, cam.z,
                    cam.x + after.m30(), cam.y + after.m31(), cam.z + after.m32(),
                    after.m00(), after.m11(), after.m22());
        }

        // Render through ItemRenderer.renderItem with the layer's own quads + render layer +
        // tints — the SAME path the world renderer uses (dropped-item rendering), which is the
        // only path that textures correctly in 1.21.11. itemState.render() draws the pack solid
        // white inside the entity feature-layer context (verified repeatedly), so it is NOT used
        // here. The entity vertex consumers come from the global buffer builder storage — the
        // feature layer's OrderedRenderCommandQueue does not expose them, but this is the exact
        // provider the entity render pass draws into.
        ((ItemRenderStateAccessor) (Object) layer).keyinput$getTransform().apply(false, matrices.peek());
        RenderLayer renderLayer = ((ItemRenderStateAccessor) (Object) layer).keyinput$getRenderLayer();
        int[] tints = ((ItemRenderStateAccessor) (Object) layer).keyinput$getTints();
        VertexConsumerProvider consumers = mc.getBufferBuilders().getEntityVertexConsumers();
        ItemRenderer.renderItem(ItemDisplayContext.FIXED, matrices, consumers, light,
                OverlayTexture.DEFAULT_UV, tints, layer.getQuads(), renderLayer, ItemRenderState.Glint.NONE);
        matrices.pop();
    }

    private static ItemStack stackFor(int cmd) {
        return STACK_CACHE.computeIfAbsent(cmd, c -> {
            ItemStack stack = new ItemStack(Items.PAPER);
            stack.set(DataComponentTypes.CUSTOM_MODEL_DATA,
                    new CustomModelDataComponent(List.of((float) c), List.of(), List.of(), List.of()));
            return stack;
        });
    }
}
