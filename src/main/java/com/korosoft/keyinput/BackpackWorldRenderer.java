package com.korosoft.keyinput;

import com.korosoft.keyinput.mixin.ItemRenderStateAccessor;
import com.korosoft.keyinput.mixin.ItemRenderStateInvoker;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.item.ItemModelManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.HeldItemContext;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws worn backpacks in the WORLD pass instead of as an entity feature layer.
 *
 * <p>Why: the item-command path renders SOLID WHITE inside the entity render context (verified
 * with a vanilla diamond through the feature layer), but the same quads render textured from the
 * world pass's vertex consumer provider — the exact path dropped item entities use. Rendering in
 * the world also automatically excludes the backpack from the entity renderer's parry mix-color
 * tint, since it is no longer part of the entity's model at all.
 *
 * <p>Positions come from the client-side interpolated entity state (lerped pos + body yaw), so
 * the backpack hugs the player's back in real time, rotating with the BODY, not the head. All
 * placement values (anchor, scale, yaw) come from the server payload.
 */
public final class BackpackWorldRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("korosoft-core/backpack");
    private static final Set<String> DIAGNOSTIC_LOGGED = ConcurrentHashMap.newKeySet();

    /** One immutable paper+CMD stack per variant, reused across frames. */
    private static final Map<Integer, ItemStack> STACK_CACHE = new ConcurrentHashMap<>();

    private BackpackWorldRenderer() {
    }

    public static void render(WorldRenderContext context) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;
        MatrixStack matrices = context.matrices();
        if (world == null || matrices == null) {
            return;
        }
        VertexConsumerProvider consumers = context.consumers();
        if (consumers == null) {
            return;
        }
        float tickDelta = mc.getRenderTickCounter().getTickProgress(true);
        Vec3d cam = context.worldState().cameraRenderState.pos;
        ItemModelManager manager = mc.getItemModelManager();
        TorsoMatrixCache.cleanup(world);
        TorsoPoseCache.cleanup(world);

        for (PlayerEntity player : world.getPlayers()) {
            BackpackState.WornBackpack worn = BackpackState.getWorn(player.getUuid());
            if (worn == null) {
                continue;
            }

            ItemStack stack = stackFor(worn.cmd());
            // TEST MODE: cmd 99999 renders a vanilla diamond instead of the SB model.
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
                    return new Vec3d(player.getX(), player.getY(), player.getZ());
                }

                @Override
                public float getBodyYaw() {
                    return player.getBodyYaw();
                }
            };
            manager.update(itemState, stack, ItemDisplayContext.FIXED, world, heldContext, 0);

            ItemRenderState.LayerRenderState layer =
                    ((ItemRenderStateInvoker) (Object) itemState).keyinput$invokeGetFirstLayer();
            if (layer == null) {
                continue;
            }
            List<BakedQuad> quads = layer.getQuads();
            if (quads.isEmpty()) {
                continue;
            }

            String diagKey = "world|" + worn.cmd() + "|" + player.getUuid();
            if (!DIAGNOSTIC_LOGGED.contains(diagKey)) {
                DIAGNOSTIC_LOGGED.add(diagKey);
                LOGGER.info("[KORO-WORLD] cmd={} player={} quads={} layer={} spriteAtlas={}",
                        worn.cmd(), player.getName().getString(), quads.size(),
                        ((ItemRenderStateAccessor) (Object) layer).keyinput$getRenderLayer(),
                        quads.get(0).sprite().getAtlasId());
            }

            // Torso matrix captured during the real entity render: the exact body-to-world
            // transform that was drawn (EMF animations, crouch lean, body turns — everything a
            // chestplate would inherit). The backpack is drawn INSIDE that frame, so it behaves
            // like a chestplate glued to the animated torso instead of a rigid object replicated
            // with a hand-built transform chain (the old angle-based chain could never match the
            // real render order — pivot translates, quaternion composition, EMF pivots).
            Matrix4f torso = TorsoMatrixCache.getMatrixFor(player);
            if (torso == null) {
                continue; // player was not rendered this frame (culled? first frame?) — skip
            }

            matrices.push();
            // The captured torso matrix is the COMPLETE body-to-world transform in camera-relative
            // space: it already contains the player's position, the body yaw rotation, the vanilla
            // flip scale(-1,-1,1) + translate(0,-1.501,0), and the root+body pivot chain (EMF
            // included). Drawing inside this frame is exactly what a chest plate does — the backpack
            // inherits every animation. NO extra position translate, NO extra yaw align: the matrix
            // already has both.
            matrices.multiplyPositionMatrix(torso);
            // Backpack offset relative to the torso cuboid, in the body frame: hang behind the body
            // center. The flip inside the torso matrix already inverted Y, so the sign of the offset
            // flips with it (verified by calibration: backZ pushes the pack behind the player).
            matrices.translate(0.0f, -worn.backY() + 1.501f, -worn.backZ());
            // Model front (+Z) faces away from the player (yawDeg from the server, 0 default).
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(worn.yawDeg()));
            // Apply the model's own fixed display transform (pack-side scale). The item render
            // path applies the 1/16 model-space conversion itself — do NOT scale 1/16 here.
            ((ItemRenderStateAccessor) (Object) layer).keyinput$getTransform().apply(false, matrices.peek());
            matrices.scale(worn.scale(), worn.scale(), worn.scale());

            int light = WorldRenderer.getLightmapCoordinates(world, player.getBlockPos());
            RenderLayer renderLayer = ((ItemRenderStateAccessor) (Object) layer).keyinput$getRenderLayer();
            int[] tints = ((ItemRenderStateAccessor) (Object) layer).keyinput$getTints();
            ItemRenderer.renderItem(ItemDisplayContext.FIXED, matrices, consumers, light,
                    OverlayTexture.DEFAULT_UV, tints, quads, renderLayer, ItemRenderState.Glint.NONE);
            matrices.pop();
        }
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
