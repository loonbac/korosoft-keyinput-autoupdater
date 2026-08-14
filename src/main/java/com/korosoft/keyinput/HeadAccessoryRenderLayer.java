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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws head accessory models (portable radio, hats, etc.) on a player's head, purely client-side.
 *
 * <p>Binds to the player's animated HEAD model part with zero server latency, matching the player's
 * head pitch, yaw, roll, and custom EMF / Fresh Animations poses in real time.
 */
public class HeadAccessoryRenderLayer extends FeatureRenderer<PlayerEntityRenderState, PlayerEntityModel> {

    private static final Map<Integer, ItemStack> STACK_CACHE = new ConcurrentHashMap<>();

    private final FeatureRendererContext<PlayerEntityRenderState, PlayerEntityModel> layerContext;

    public HeadAccessoryRenderLayer(FeatureRendererContext<PlayerEntityRenderState, PlayerEntityModel> context) {
        super(context);
        this.layerContext = context;
    }

    @Override
    public void render(MatrixStack matrices, OrderedRenderCommandQueue queue, int light,
                       PlayerEntityRenderState state, float limbAngle, float limbDistance) {
        UUID playerUuid = ((BackpackRenderState) state).keyinput$getPlayerUuid();
        HeadAccessoryState.WornHeadAccessory worn = HeadAccessoryState.getWorn(playerUuid);
        if (worn == null || worn.cmd() <= 0) {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;
        if (world == null) {
            return;
        }

        ItemStack stack = stackFor(worn.cmd());
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
            var quads = layer.getQuads();
            if (!quads.isEmpty()) {
                Identifier spriteAtlas = quads.get(0).sprite().getAtlasId();
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

        if (itemState.isEmpty() || layer == null) {
            return;
        }

        matrices.push();
        // Anchor to the torso/body frame (same coordinate frame as the arms), so the radio
        // stays glued to the player's hands while the head tilts independently underneath.
        PlayerEntityModel playerModel = layerContext.getModel();
        if (playerModel != null) {
            playerModel.body.applyTransform(matrices);
        }

        // Apply server-driven placement and rotations inside the head frame
        matrices.translate(worn.headX(), -worn.headY(), worn.headZ());
        matrices.scale(worn.scale(), worn.scale(), worn.scale());
        if (worn.yawDeg() != 0.0F) {
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(worn.yawDeg()));
        }
        if (worn.pitchDeg() != 0.0F) {
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(worn.pitchDeg()));
        }
        if (worn.rollDeg() != 0.0F) {
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(worn.rollDeg()));
        }
        if (worn.flip()) {
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(180.0F));
        }

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
