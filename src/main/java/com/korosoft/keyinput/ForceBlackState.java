package com.korosoft.keyinput;

/**
 * Duck-typed marker mixed onto {@code EntityRenderState} (see {@code EntityRenderStateMixin}) so a
 * single render-state instance can be flagged "render me pure black this pass".
 *
 * <p>Why a flag on the state object instead of a static boolean: in 1.21.11 GUI entity rendering is
 * deferred. {@code DrawContext.addEntity(...)} only queues the state into the frame's
 * {@code GuiRenderState}; the model is not drawn until the whole GUI batch flushes at the end of the
 * frame, long after the HUD render method that queued it has returned. A time-scoped static flag
 * would say nothing about which entity is actually rendering when the color hook fires at flush
 * time. The flag has to travel WITH the state, because the deferred renderer hands that exact state
 * back to {@code LivingEntityRenderer#getMixColor} when it finally draws it.
 *
 * <p>The same {@code EntityRenderState} instance is reused by the renderer for the world pass too,
 * so the flag is cleared on every {@code updateRenderState} (see {@code LivingEntityRendererMixin})
 * and set again by hand only for the silhouette draw — otherwise the parried mob would render black
 * in the actual world on the next frame.
 */
public interface ForceBlackState {

    boolean keyinput$isForceBlack();

    void keyinput$setForceBlack(boolean forceBlack);
}
