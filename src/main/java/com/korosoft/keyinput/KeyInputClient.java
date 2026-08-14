package com.korosoft.keyinput;

import com.korosoft.keyinput.auth.AuthSession;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Registers the outgoing payloads so the client can send them to the server (key presses, the
 * "curtain is fully white" ready signal, and the hello handshake the server gates entry on), and
 * the incoming payloads the server pushes back:
 * the HUD config (to tune the HUD animation), the parry flash (fired only when a parry actually
 * lands), the screen layout, the transfer curtain, and the belowname label scale.
 */
public class KeyInputClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Restore a persisted KoroAuth session (if any) before the launcher menu opens, so a
        // returning player is already logged in and gets greeted instead of shown the login card.
        AuthSession.load();

        // Restore the saved atlas minimap size (if any).
        AtlasMinimap.load();

        // Before any server is joined: if a newer keyinput release exists, download and apply it
        // right on the title screen. In-game players are covered by the server-side push instead.
        // On Windows, also surface a failed jar swap from the previous session's native helper.
        ModUpdater.get().consumeWindowsHelperResult();
        ModUpdater.get().checkForUpdatesAtLaunch();

        PayloadTypeRegistry.playC2S().register(KeyPayload.ID, KeyPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ScrollPayload.ID, ScrollPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(CutreadyPayload.ID, CutreadyPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(HelloPayload.ID, HelloPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(AuthTokenPayload.ID, AuthTokenPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(PingPayload.ID, PingPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(AtlasActionPayload.ID, AtlasActionPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(HudConfigPayload.ID, HudConfigPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ParryFlashPayload.ID, ParryFlashPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ScreenLayoutPayload.ID, ScreenLayoutPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(StatPanelPayload.ID, StatPanelPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CutscenePayload.ID, CutscenePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CutsceneConfigPayload.ID, CutsceneConfigPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(NameTagConfigPayload.ID, NameTagConfigPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ModUpdatePayload.ID, ModUpdatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(BackpackPayload.ID, BackpackPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(HeadAccessoryPayload.ID, HeadAccessoryPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PingBroadcastPayload.ID, PingBroadcastPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(AtlasPayload.ID, AtlasPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(HudBarsPayload.ID, HudBarsPayload.CODEC);
        // Paraglider (port of tictim's Paraglider): one channel per message (Fabric allows a
            // single payload type per channel per direction). C2S toggle + S2C state + S2C canuse
            // each have their own channel. The server (Skript) registers the legacy C2S
            // "keyinput:paraglider" incoming as well; the client always uses korosoft-core.
        PayloadTypeRegistry.playC2S().register(ParagliderPayload.ID, ParagliderPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ParagliderStatePayload.ID, ParagliderStatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ParagliderCanUsePayload.ID, ParagliderCanUsePayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(ParagliderStatePayload.ID, (payload, context) ->
                context.client().execute(() -> ParagliderState.put(payload)));
        ClientPlayNetworking.registerGlobalReceiver(ParagliderCanUsePayload.ID, (payload, context) ->
                context.client().execute(() -> ParagliderState.put(payload)));
        System.out.println("[korosoft-core][paraglider] payload types + receivers registered");
        ClientPlayNetworking.registerGlobalReceiver(HudBarsPayload.ID, (payload, context) ->
                context.client().execute(() -> HudBarsState.put(payload)));
        System.out.println("[korosoft-core][hudbars] payload type + receiver registered");

        // KoroAuth's login-stage responder (see KOROSOFT_AUTH_DESIGN.md and the KoroAuth plugin's
        // LoginGatekeeper.java, which is the source of truth for this wire format): the proxy
        // challenges every connecting client on this channel during the login phase, before any
        // play-stage packet exists. We ignore the challenge payload itself (the signed token is
        // self-contained and not bound to it) and reply with the raw UTF-8 bytes of the session
        // token AuthSession is holding -- no length prefix, matching LoginGatekeeper's
        // `new String(response, StandardCharsets.UTF_8)` read of the whole response payload.
        // If there is no token (never logged in this game session), completing the future with
        // null tells Fabric to report "client did not understand the query", which surfaces to
        // the proxy as response == null -- exactly the "vanilla client" denial path in
        // LoginGatekeeper.handleChallengeResponse, so a player who dismisses the login card and
        // tries to connect anyway gets kicked with the normal launcher-required message instead
        // of this throwing.
        org.slf4j.Logger authLog = org.slf4j.LoggerFactory.getLogger("korosoft-core/koroauth");
        authLog.info("KoroAuth: registering korosoft:auth login responder");
        ClientLoginNetworking.registerGlobalReceiver(Identifier.of("korosoft", "auth"),
                (client, handler, buf, callbacksConsumer) -> {
                    String token = AuthSession.getToken();
                    authLog.info("KoroAuth: received korosoft:auth login challenge — token present={}", token != null);
                    if (token == null) {
                        return CompletableFuture.completedFuture(null);
                    }
                    PacketByteBuf response = PacketByteBufs.create();
                    response.writeBytes(token.getBytes(StandardCharsets.UTF_8));
                    return CompletableFuture.completedFuture(response);
                });

        // AscendBeam is stateless, like the rest of the cinematic's derived effects — this hook
        // just hands it a frame to draw into, every frame, forever; it decides for itself whether
        // there is anything to draw by reading Cutscene/AscendCamera.
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(AscendBeam::render);

        // Atlas minimap: drawn as a HUD element in the top-right corner via DrawContext.drawMap
        // (the deferred GUI pipeline captures the map quad; MapRenderer.update is cheap on the
        // render thread, same as vanilla's cartography table). The old world-pass billboard was
        // occluded by terrain and depth-tested, so it never rendered reliably — replaced by this.
        HudRenderCallback.EVENT.register(AtlasMinimap::render);

        // Worn backpacks render as a PLAYER FEATURE LAYER (chestplate-style, glued to the
        // animated torso) — see BackpackRenderLayer, wired by PlayerBackpackFeatureMixin.
        // The old world-pass renderer (BackpackWorldRenderer) is DISABLED: it drew the pack
        // with a rigid offset in the already-rotated torso frame, so it orbited away from the
        // body whenever the torso pitched (Fresh Animations running/crouching). Keep this
        // registration commented out unless re-enabling that path.
        // WorldRenderEvents.BEFORE_TRANSLUCENT.register(BackpackWorldRenderer::render);

        ClientPlayNetworking.registerGlobalReceiver(HudConfigPayload.ID, (payload, context) ->
                context.client().execute(() -> HudConfig.apply(payload.sidebarYOffset(), payload.slideMillis())));

        ClientPlayNetworking.registerGlobalReceiver(ParryFlashPayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    // Freeze the parried entity's position NOW, on the main thread, while it is still
                    // resolvable — a melee attacker is a couple of blocks away and always loaded. If it
                    // cannot be resolved (a distant projectile shooter, a non-living damager), the flash
                    // still plays with no silhouette. Reading the world/entity here, not in the render
                    // loop, is what makes the silhouette hang where the enemy was at the moment of impact.
                    int parriedId = ParryFlash.NO_ENTITY;
                    double cx = 0.0;
                    double cy = 0.0;
                    double cz = 0.0;
                    int wireId = payload.attackerEntityId();
                    if (wireId >= 0 && context.client().world != null) {
                        Entity attacker = context.client().world.getEntityById(wireId);
                        if (attacker instanceof LivingEntity living) {
                            Vec3d feet = living.getLerpedPos(1.0F);
                            double height = living.getBoundingBox().getLengthY();
                            parriedId = wireId;
                            cx = feet.x;
                            cy = feet.y + height / 2.0;
                            cz = feet.z;
                        }
                    }
                    ParryFlash.start(payload.flashMillis(), payload.holdPercent(), payload.peakPercent(),
                            parriedId, cx, cy, cz);
                }));

        ClientPlayNetworking.registerGlobalReceiver(ScreenLayoutPayload.ID, (payload, context) ->
                context.client().execute(() -> ScreenLayout.put(payload.layout())));

        ClientPlayNetworking.registerGlobalReceiver(StatPanelPayload.ID, (payload, context) ->
                context.client().execute(() -> StatPanel.put(payload.spec())));
        ClientPlayNetworking.registerGlobalReceiver(CutscenePayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    int command = payload.command();
                    if (command == CutscenePayload.COMMAND_START) {
                        Cutscene.start(payload.fadeMillis(), Cutscene.kindFromWire(payload.kind()));
                    } else if (command == CutscenePayload.COMMAND_RUSH) {
                        Cutscene.rush(payload.fadeMillis());
                    } else {
                        // fail-open direction: any unknown command clears the curtain rather than
                        // raising it, same rule CutscenePayload's own lenient decoder follows
                        Cutscene.end();
                    }
                }));

        ClientPlayNetworking.registerGlobalReceiver(CutsceneConfigPayload.ID, (payload, context) ->
                context.client().execute(() -> CutsceneConfig.apply(payload.spec())));

        ClientPlayNetworking.registerGlobalReceiver(NameTagConfigPayload.ID, (payload, context) ->
                context.client().execute(() -> NameTagConfig.apply(payload.spec())));

        // Worn backpack sync: the server broadcasts the FULL render spec (variant cmd, anchor,
        // scale, yaw) and the render layer draws it client-side on the back (zero lag — see
        // BackpackRenderLayer). Nothing about placement is hardcoded client-side.
        ClientPlayNetworking.registerGlobalReceiver(BackpackPayload.ID, (payload, context) ->
                context.client().execute(() -> BackpackState.put(payload.playerUuid(), payload.cmd(),
                        payload.backY(), payload.backZ(), payload.scale(), payload.yawDeg(),
                        payload.originY(), payload.flip())));

        // Worn head accessory sync: server broadcasts variant CMD, anchor, scale, 3-axis rotation and flip
        // rendered client-side on player head (zero lag — see HeadAccessoryRenderLayer).
        ClientPlayNetworking.registerGlobalReceiver(HeadAccessoryPayload.ID, (payload, context) ->
                context.client().execute(() -> HeadAccessoryState.put(payload.playerUuid(), payload.cmd(),
                        payload.headX(), payload.headY(), payload.headZ(), payload.scale(),
                        payload.yawDeg(), payload.pitchDeg(), payload.rollDeg(), payload.flip())));

        // Ping wheel: the server re-broadcasts a player's ping to everyone else (author, point,
        // optional target entity, sequence, dimension). Draw it and play the ping sound.
        ClientPlayNetworking.registerGlobalReceiver(PingBroadcastPayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    PingManager.addOrReplace(new PingState(
                            payload.x(), payload.y(), payload.z(),
                            payload.author(), payload.entity(),
                            payload.sequence(), payload.dimension()));
                    PingSound.play(payload.x(), payload.y(), payload.z());
                }));

        // Atlas: the server pushes the full atlas state (map ids, scale, anchor). The client
        // stores it for the minimap, and opens the overview screen when the server asks.
        ClientPlayNetworking.registerGlobalReceiver(AtlasPayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    AtlasState.put(payload);
                    if (payload.open()) {
                        context.client().setScreen(new AtlasOverviewScreen());
                    }
                }));

        // Fallback HUD bars (AMD): the server broadcasts the four bar specs (values AND positions)
        // and HudBarsState re-draws them as plain vanilla rectangles — see HudBarsState for why.

        // ModUpdatePayload triggers the hot-reload flow. Only one update can run at a time;
        // ModUpdater's latch enforces that. A mandatory update that the player tries to dismiss
        // is the same path as a failed one -- the next JOIN will receive the payload again, and
        // the server should kick on disconnect for players who keep refusing.
        ClientPlayNetworking.registerGlobalReceiver(ModUpdatePayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    if (ModUpdater.get().isApplying()) {
                        if (ModUpdater.get().isApplied()) {
                            // Already applied and the restart screen is up; a server re-push after
                            // a target bump must not overwrite it with the download window.
                            return;
                        }
                        // Server re-pushed while downloading: just reinstall the overlay, do NOT
                        // start a second download.
                        MinecraftClient mc = MinecraftClient.getInstance();
                        mc.setScreen(new ModUpdateScreen(
                                "Actualizando Korosoft-Core",
                                payload.message(),
                                mc.currentScreen));
                        return;
                    }
                    ModUpdater.get().beginUpdate(payload);
                }));

        // the next server may send nothing at all, so never inherit this session's state
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            HudAnimator.reset();
            HudConfig.reset();
            CutsceneConfig.reset();
            NameTagConfig.reset();
            ParryFlash.reset();
            ScreenLayout.reset();
            StatPanel.reset();
            BackpackState.clear();
            HeadAccessoryState.clear();
            PingManager.clear();
            AtlasState.clear();
            HudBarsState.reset();
            ParagliderState.reset();
            // a real disconnect/kick, never a backend switch (that does not fire DISCONNECT),
            // so nobody reconnects staring at a curtain nothing will ever lift
            Cutscene.reset();
        });

        // A backend switch never fires DISCONNECT but does fire JOIN again (measured), so this
        // runs once per backend: each one gets its own handshake and can gate independently, and
        // Cutscene's settle-grace failsafe learns the new backend joined.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            // Sent unguarded, unlike cutready's canSend() check. canSend() only reports whether the
            // server's channel REGISTER has reached us yet, which races with this event — and the
            // consequence of losing that race is inverted here: the server kicks players who do not
            // say hello, so a skipped send would kick a player who HAS the mod. Sending into a
            // server that is not listening is harmless (it drops unknown channels); staying silent
            // is not. send() itself does no channel validation, only requiring a network handler,
            // which JOIN hands us.
            ClientPlayNetworking.send(new HelloPayload(ModVersion.encoded()));

            // Present the KoroAuth session token so the proxy's PlayAuthGatekeeper can authenticate
            // this connection (play-phase auth — the login-phase challenge is not delivered by the
            // proxy build in use). No token means the player never logged in; the proxy's deadline
            // then kicks them, same as a vanilla client.
            String authToken = AuthSession.getToken();
            if (authToken != null) {
                ClientPlayNetworking.send(new AuthTokenPayload(authToken));
            }

            Cutscene.onJoin();
        });
    }
}
