package me.cortex.voxy.client.core.rendering.backend.gl41metal;

import java.util.List;
import me.cortex.voxy.client.core.rendering.backend.BackendContext;
import me.cortex.voxy.client.core.rendering.backend.RenderBackendId;
import me.cortex.voxy.client.core.rendering.backend.RenderFrame;
import me.cortex.voxy.client.core.rendering.backend.RenderFrameContext;
import me.cortex.voxy.client.core.rendering.backend.RenderFrameMatrices;
import me.cortex.voxy.client.core.rendering.backend.RenderStage;
import me.cortex.voxy.client.core.rendering.backend.RenderStageContext;
import me.cortex.voxy.client.core.rendering.backend.ShaderPatchBridgePayload;
import me.cortex.voxy.client.core.rendering.backend.VoxyRenderBackend;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;

public final class Gl41MetalRenderBackend implements VoxyRenderBackend {
  private final BackendContext context;
  private final Gl41MetalConfig config;
  private final MetalDistantRenderer metalRenderer;
  private final Gl41MetalSlotScheduler slotScheduler;
  private final GlDistantTerrainBridge bridge;
  private final Gl41MetalFrameProfiler profiler = new Gl41MetalFrameProfiler();
  // GL-only loaded-volume bound (P1): tracks the Sodium-loaded 16-block render sections and
  // produces
  // the per-frame far-boundary depth the bridge clips distant LOD against. Driven purely by
  // Sodium's
  // 16-block near-scene section signals; it changes NO Metal traversal/residency state (per
  // AGENTS.md).
  private final Gl41MetalChunkBoundRenderer boundRenderer = new Gl41MetalChunkBoundRenderer();
  // The loaded-volume bound rasterized at the start of the current frame's sampleFrame, reused by
  // the held-slot translucent pass (sampleTranslucent) which runs later in the SAME frame with the
  // same camera/matrices.
  private LoadedVolumeBound currentBound = LoadedVolumeBound.DISABLED;
  private final Gl41MetalTerrainResources terrainResources;
  private RenderFrameMatrices lastFrameMatrices = RenderFrameMatrices.identity();
  private SharedDistantGbuffer gbuffer;
  private long nextFrameId;
  private String loggedStrictBridgeUnavailableReason = "";
  private String loggedStrictTranslucentUnavailableReason = "";
  private boolean loggedMissingMatrices;
  // Plan A slot lifecycle for distant translucent water. The opaque pass (PRE_TRANSLUCENT at Iris
  // beginHand RETURN) and the translucent pass (TRANSLUCENT at beginTranslucents RETURN) sample the
  // SAME shared slot within one frame, so PRE_TRANSLUCENT holds the slot (does not retire it) and
  // TRANSLUCENT reuses it and retires it afterwards. Retirement is fence-deferred (decoupled from
  // matrix/uniform publishing), so holding it for the extra deferred passes is safe; we never call
  // waitCurrent twice on the same slot. -1 means nothing held.
  private int heldTranslucentSlot = -1;
  private Gl41MetalFrame heldTranslucentFrame;
  private RenderFrameContext heldTranslucentContext;
  private final Matrix4f heldVoxyMvp = new Matrix4f();
  private final Matrix4f heldVanillaMvp = new Matrix4f();
  private boolean heldMvpValid;
  private final Matrix4f heldReprojMvp = new Matrix4f();
  private final Matrix4f heldReprojMvpInv = new Matrix4f();
  private boolean heldReprojValid;

  /**
   * What each slot's gbuffer was rasterized with, captured at submit. When the scheduler samples a
   * STALE slot (bounded wait expired), the composite reconstructs world positions with the slot's
   * OWN matrices and re-projects them with the CURRENT frame's vanilla matrices — so an old frame
   * lands exactly where its terrain belongs no matter how the camera moved since. Without this,
   * stale composites were registered against the current camera and the LOD layer visibly slid
   * with camera motion whenever Metal frames outlasted the wait.
   */
  private static final class SlotSubmitState {
    final Matrix4f drawMvp = new Matrix4f();
    double camX;
    double camY;
    double camZ;
    int originX;
    int originY;
    int originZ;
    long frameId = -1;
    boolean valid;
  }

  private SlotSubmitState[] slotSubmitStates = new SlotSubmitState[0];
  /** Newest Metal frame id observed COMPLETED (via sampling); -1 until the first completes. */
  private long newestCompletedFrameId = -1;

  /**
   * The newest completed slot, HELD instead of retired so a GPU burst that leaves nothing newly
   * finished can re-composite it (reprojected, so still correctly registered) rather than skip
   * the composite — skipping blanked the whole distant layer for a frame, which the user sees as
   * flicker (measured: 189 blink-frames in one 5,438-frame session). A slot is only retired when
   * a NEWER completed frame replaces it.
   */
  private int heldCompletedSlot = -1;

  private int countInFlightSubmits() {
    int inFlight = 0;
    for (SlotSubmitState state : this.slotSubmitStates) {
      if (state.valid && state.frameId > this.newestCompletedFrameId) {
        inFlight++;
      }
    }
    return inFlight;
  }

  /** The camera-section origin (block coords) the frame matrices are relative to. */
  private static int sectionOrigin(double cameraCoord) {
    return (((int) Math.floor(cameraCoord)) >> 5) << 5;
  }

  public Gl41MetalRenderBackend(BackendContext context) {
    this.context = context;
    this.config = Gl41MetalConfig.fromSystemProperties();
    this.metalRenderer = new MetalDistantRenderer();
    this.slotScheduler = new Gl41MetalSlotScheduler(this.config.waitTimeoutMs());
    this.bridge = new GlDistantTerrainBridge();
    this.terrainResources = new Gl41MetalTerrainResources(context);
    Logger.info(
        "Created Voxy GL41Metal shared texture backend with Metal LOD/culling and quad raster.");
  }

  @Override
  public RenderBackendId id() {
    return RenderBackendId.GL41METAL;
  }

  @Override
  public boolean rendersLodTerrain() {
    return true;
  }

  @Override
  public RenderFrame setupFrame(RenderFrameContext context) {
    return this.submitMetalFrame(context);
  }

  @Override
  public RenderFrame runFrameStage(
      RenderStage stage, RenderStageContext context, RenderFrame frame) {
    return switch (stage) {
      case FRAME_BEGIN -> this.submitMetalFrame(context.frameContext());
      // gl46-only setup point inside Iris beginLevelRendering; gl41metal already submitted its
      // frame at FRAME_BEGIN so this is a pass-through.
      case LEGACY_VIEWPORT_SETUP -> frame;
      case SODIUM_SOLID_SYNC, SODIUM_CUTOUT_SYNC -> {
        if (context.shaderPackActive() || frame != null) {
          yield frame;
        }
        yield this.submitMetalFrame(context.frameContext());
      }
      case PRE_TRANSLUCENT -> {
        if (!context.shaderPackActive()) {
          yield frame;
        }
        // Hold the sampled slot so the TRANSLUCENT stage can reuse it for the distant water pass.
        this.sampleFrame(frame, context, true);
        yield frame;
      }
        // Distant translucent (water/glass) composite at Iris beginTranslucents RETURN. Reuses the
        // slot
        // held by PRE_TRANSLUCENT and retires it afterwards (Plan A).
      case TRANSLUCENT -> {
        if (context.shaderPackActive()) {
          this.sampleTranslucent(context);
        } else {
          this.releaseHeldTranslucentSlot();
        }
        yield frame;
      }
      case LEGACY_OPAQUE -> {
        if (context.shaderPackActive()) {
          yield frame;
        }
        RenderFrame renderFrame = frame;
        if (renderFrame == null) {
          renderFrame = this.submitMetalFrame(context.frameContext());
        }
        this.sampleFrame(renderFrame, context, false);
        yield renderFrame;
      }
      case FRAME_END -> frame;
    };
  }

  private RenderFrame submitMetalFrame(RenderFrameContext context) {
    this.profiler.endFrame();
    this.releaseHeldTranslucentSlot();
    float gbufferOverscan =
        this.config.reprojection() ? 1.0f + this.config.overscanPercent() / 100.0f : 1.0f;
    // The gbuffer carries the overscanned frustum at FULL screen density: same FOV-per-texel as
    // the screen, so the composite never minifies (nearest-resampling a minified packed gbuffer
    // shimmers). Overscan therefore costs memory + raster area, never sharpness.
    this.ensureGbuffer(
        Math.round(context.viewportWidth() * gbufferOverscan),
        Math.round(context.viewportHeight() * gbufferOverscan));

    long tTick = this.profiler.begin();
    this.terrainResources.tick(context, this.gbuffer.nativeHandle());
    this.profiler.recordTick(tTick);

    if (this.gbuffer != null) {
      double metalGpuMs = Gl41MetalNative.getLastMetalGpuTimeMs(this.gbuffer.nativeHandle());
      if (metalGpuMs > 0) {
        this.profiler.recordMetalGpuMs(metalGpuMs);
      }
    }

    if (context.matrices() == null) {
      this.lastFrameMatrices = RenderFrameMatrices.identity();
      if (!this.loggedMissingMatrices) {
        this.loggedMissingMatrices = true;
        Logger.warn("GL41Metal skipped Metal submit because real frame matrices were unavailable");
      }
      return new Gl41MetalFrame(
          context, this.nextFrameId++, -1, new Matrix4f(), new Matrix4f(), new Matrix4f());
    }
    float overscan =
        this.config.reprojection() ? 1.0f + this.config.overscanPercent() / 100.0f : 1.0f;
    MetalDistantRenderer.FrameMatrices frameMatrices =
        MetalDistantRenderer.computeFrameMatrices(context, overscan);
    // Pack-facing matrices stay in SCREEN space: the bridge writes vxDepthTex* depths in the
    // current screen voxy NDC, so a pack's vxProj/vxProjInv reconstruction must match that, not
    // the overscanned raster projection.
    this.lastFrameMatrices =
        new RenderFrameMatrices(
            new Matrix4f(frameMatrices.screenProjection()).mul(context.matrices().modelView()),
            context.matrices().modelView(),
            frameMatrices.screenProjection());
    long frameId = this.nextFrameId++;
    // Submit pacing: while the GPU already has enough Metal frames queued, skip this submit
    // entirely. Reprojection makes the composite correct from an older frame anyway, and NOT
    // queueing doomed work keeps the newest completed frame fresher and hands the spare GPU
    // time to vanilla rendering.
    int writeSlot;
    if (this.config.maxInFlightSubmits() > 0
        && this.countInFlightSubmits() >= this.config.maxInFlightSubmits()) {
      this.slotScheduler.recordPacedSubmit();
      writeSlot = -1;
    } else {
      writeSlot = this.slotScheduler.acquireWriteSlot(this.gbuffer);
    }

    long tSubmit = this.profiler.begin();
    if (writeSlot >= 0) {
      this.metalRenderer.submitTraversal(
          this.gbuffer,
          writeSlot,
          frameId,
          context,
          frameMatrices.traversalMvp(),
          frameMatrices.drawMvp(),
          frameMatrices.projection());
      this.slotScheduler.recordSubmitted();
      if (writeSlot < this.slotSubmitStates.length) {
        SlotSubmitState state = this.slotSubmitStates[writeSlot];
        state.drawMvp.set(frameMatrices.drawMvp());
        state.camX = context.cameraX();
        state.camY = context.cameraY();
        state.camZ = context.cameraZ();
        state.originX = sectionOrigin(context.cameraX());
        state.originY = sectionOrigin(context.cameraY());
        state.originZ = sectionOrigin(context.cameraZ());
        state.frameId = frameId;
        state.valid = true;
      }
    } else {
      this.slotScheduler.recordNoFreeSlot();
    }
    this.profiler.recordMetalSubmit(tSubmit);

    return new Gl41MetalFrame(
        context,
        frameId,
        writeSlot,
        frameMatrices.drawMvp(),
        frameMatrices.vanillaDrawMvp(),
        frameMatrices.screenDrawMvp());
  }

  @Override
  public void renderOpaque(RenderFrame frame) {
    this.sampleFrame(frame);
  }

  private void sampleFrame(
      RenderFrame frame, RenderStageContext stageContext, boolean holdForTranslucent) {
    this.sampleFrame(
        frame,
        stageContext.frameContext(),
        stageContext.payload() instanceof ShaderPatchBridgePayload payload ? payload : null,
        holdForTranslucent);
  }

  private void sampleFrame(RenderFrame frame) {
    this.sampleFrame(frame, null, null, false);
  }

  private void sampleFrame(
      RenderFrame frame,
      RenderFrameContext stageContext,
      ShaderPatchBridgePayload bridgePayload,
      boolean holdForTranslucent) {
    if (frame == null) {
      return;
    }
    if (!(frame instanceof Gl41MetalFrame gl41MetalFrame)) {
      throw new IllegalArgumentException(
          "Cannot render frame for backend " + frame.backendId() + " with GL41Metal backend");
    }
    if (this.gbuffer == null) {
      return;
    }

    long tWait = this.profiler.begin();
    int selected =
        this.slotScheduler.selectSlotForSampling(this.gbuffer, gl41MetalFrame.writeSlot());
    this.profiler.recordSlotWait(tWait);
    int sampleSlot;
    if (selected >= 0) {
      if (this.heldCompletedSlot >= 0
          && this.heldCompletedSlot != selected
          && this.heldCompletedSlot != this.heldTranslucentSlot) {
        this.slotScheduler.queueSampledSlotRetirement(this.gbuffer, this.heldCompletedSlot);
      }
      this.heldCompletedSlot = selected;
      sampleSlot = selected;
    } else if (this.heldCompletedSlot >= 0) {
      // Nothing newly completed within the wait budget: blink-free fallback. Reprojection makes
      // re-compositing the held frame exactly as correct as it was last frame.
      sampleSlot = this.heldCompletedSlot;
      this.slotScheduler.recordReusedHeld();
    } else {
      return; // true startup: nothing has ever completed
    }
    try {
      RenderFrameContext renderContext =
          stageContext == null ? gl41MetalFrame.context() : stageContext;
      DistantGbufferSlot slot = this.gbuffer.slot(sampleSlot);

      // Reprojection: composite the sampled slot in the space it was RASTERIZED in. voxyMvp
      // reconstructs positions relative to the slot's camera-section origin; the current vanilla
      // MVP is shifted by the exact integer origin delta so those positions re-project into the
      // CURRENT frame. Sampling the current slot yields a zero delta — identical to the old path.
      SlotSubmitState submitState =
          sampleSlot < this.slotSubmitStates.length ? this.slotSubmitStates[sampleSlot] : null;
      boolean reproject = submitState != null && submitState.valid;
      if (reproject && submitState.frameId > this.newestCompletedFrameId) {
        // Metal completes traversal frames in submission order, so everything at or before the
        // sampled frame is done - this is what the submit pacing counts against.
        this.newestCompletedFrameId = submitState.frameId;
      }
      Matrix4f voxyMvp =
          reproject ? new Matrix4f(submitState.drawMvp) : new Matrix4f(gl41MetalFrame.drawMvp());
      Matrix4f vanillaMvp = new Matrix4f(gl41MetalFrame.vanillaDrawMvp());
      double boundCamX = renderContext.cameraX();
      double boundCamY = renderContext.cameraY();
      double boundCamZ = renderContext.cameraZ();
      Matrix4f reprojMvp = null;
      Matrix4f reprojMvpInv = null;
      if (reproject) {
        RenderFrameContext frameContext = gl41MetalFrame.context();
        vanillaMvp.translate(
            submitState.originX - sectionOrigin(frameContext.cameraX()),
            submitState.originY - sectionOrigin(frameContext.cameraY()),
            submitState.originZ - sectionOrigin(frameContext.cameraZ()));
        // The loaded-volume bound must be rasterized in the SLOT's space too: the shader compares
        // the slot's voxy-NDC depth against it (GLSL_BOUND_CLIP assumes one shared space).
        boundCamX = submitState.camX;
        boundCamY = submitState.camY;
        boundCamZ = submitState.camZ;
        // The positional warp maps each current SCREEN pixel into the slot's RASTER space:
        // R = slotDrawMvp * T(originCur - originSlot) * inverse(currentScreenVoxyMvp). Needed
        // whenever the slot is stale OR the raster is overscanned (then even the current frame's
        // raster NDC differs from screen NDC); skipped only when both are identity.
        boolean stale = submitState.frameId != gl41MetalFrame.frameId();
        boolean overscanActive = this.config.overscanPercent() > 0;
        if ((stale || overscanActive) && this.config.reprojection()) {
          reprojMvp =
              new Matrix4f(submitState.drawMvp)
                  .translate(
                      sectionOrigin(frameContext.cameraX()) - submitState.originX,
                      sectionOrigin(frameContext.cameraY()) - submitState.originY,
                      sectionOrigin(frameContext.cameraZ()) - submitState.originZ)
                  .mul(new Matrix4f(gl41MetalFrame.screenVoxyMvp()).invert());
          reprojMvpInv = reprojMvp.invert(new Matrix4f());
        }
      }
      this.bridge.setReprojection(reprojMvp, reprojMvpInv, this.config.reprojRefine());

      long tBound = this.profiler.begin();
      int worldMinY = -64;
      int worldMaxY = 320;
      var level = Minecraft.getInstance().level;
      if (level != null) {
        worldMinY = level.getMinSection() << 4;
        worldMaxY = level.getMaxSection() << 4;
      }
      int verticalRadiusBlocks = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
      this.currentBound =
          this.boundRenderer.render(
              voxyMvp,
              boundCamX,
              boundCamY,
              boundCamZ,
              worldMinY,
              worldMaxY,
              verticalRadiusBlocks,
              renderContext.viewportWidth(),
              renderContext.viewportHeight());
      this.profiler.recordBoundRender(tBound);

      long tBridge = this.profiler.begin();
      if (bridgePayload != null) {
        if (bridgePayload.strictBridgeAvailable()) {
          boolean rendered =
              this.bridge.render(
                  renderContext,
                  slot,
                  GlDistantTerrainBridge.irisJob(bridgePayload),
                  voxyMvp,
                  vanillaMvp);
          this.profiler.recordBridgeOpaque(tBridge);
          if (rendered && holdForTranslucent) {
            this.heldTranslucentSlot = sampleSlot;
            this.heldTranslucentFrame = gl41MetalFrame;
            this.heldTranslucentContext = renderContext;
            // The translucent pass later this frame samples the SAME slot: reuse the exact
            // matrices this composite used so both layers stay registered.
            this.heldVoxyMvp.set(voxyMvp);
            this.heldVanillaMvp.set(vanillaMvp);
            this.heldMvpValid = true;
            if (reprojMvp != null) {
              this.heldReprojMvp.set(reprojMvp);
              this.heldReprojMvpInv.set(reprojMvpInv);
              this.heldReprojValid = true;
            } else {
              this.heldReprojValid = false;
            }
          }
          return;
        } else {
          this.profiler.recordBridgeOpaque(tBridge);
          if (!bridgePayload.unavailableReason().isEmpty()
              && !bridgePayload
                  .unavailableReason()
                  .equals(this.loggedStrictBridgeUnavailableReason)) {
            this.loggedStrictBridgeUnavailableReason = bridgePayload.unavailableReason();
            Logger.info(
                "Voxy GL41Metal Iris strict bridge unavailable: "
                    + bridgePayload.unavailableReason());
          }
          return;
        }
      }

      this.bridge.render(
          renderContext,
          slot,
          GlDistantTerrainBridge.vanillaJob(renderContext, this.config.visibleComposite()),
          voxyMvp,
          vanillaMvp);
      this.bridge.renderTranslucent(
          renderContext,
          slot,
          GlDistantTerrainBridge.vanillaTranslucentJob(
              renderContext, this.config.visibleComposite()),
          voxyMvp,
          vanillaMvp,
          this.currentBound);
      this.profiler.recordBridgeOpaque(tBridge);
    } finally {
      // Retirement is handled at replacement time (see heldCompletedSlot above); the sampled
      // slot stays resident so a GPU burst next frame can re-composite it blink-free.
    }
  }

  /**
   * TRANSLUCENT stage (Iris beginTranslucents RETURN): composite the distant water into the slot
   * the opaque pass held this frame, then retire it. Skips cleanly if no slot was held (e.g. the
   * opaque strict bridge did not run) or the translucent payload is unavailable.
   */
  private void sampleTranslucent(RenderStageContext context) {
    int slot = this.heldTranslucentSlot;
    if (slot < 0 || this.gbuffer == null || this.heldTranslucentFrame == null) {
      this.releaseHeldTranslucentSlot();
      return;
    }
    ShaderPatchBridgePayload payload =
        context.payload() instanceof ShaderPatchBridgePayload p ? p : null;
    long tTrans = this.profiler.begin();
    try {
      this.bridge.setReprojection(
          this.heldReprojValid ? this.heldReprojMvp : null,
          this.heldReprojValid ? this.heldReprojMvpInv : null,
          this.config.reprojRefine());
      if (payload != null && payload.strictBridgeAvailable()) {
        RenderFrameContext renderContext =
            context.frameContext() != null ? context.frameContext() : this.heldTranslucentContext;
        this.bridge.renderTranslucent(
            renderContext,
            this.gbuffer.slot(slot),
            GlDistantTerrainBridge.translucentJob(payload),
            this.heldMvpValid ? this.heldVoxyMvp : this.heldTranslucentFrame.drawMvp(),
            this.heldMvpValid ? this.heldVanillaMvp : this.heldTranslucentFrame.vanillaDrawMvp(),
            this.currentBound);
      } else if (payload != null
          && !payload.unavailableReason().isEmpty()
          && !payload.unavailableReason().equals(this.loggedStrictTranslucentUnavailableReason)) {
        this.loggedStrictTranslucentUnavailableReason = payload.unavailableReason();
        Logger.info(
            "Voxy GL41Metal Iris strict translucent bridge unavailable: "
                + payload.unavailableReason());
      }
    } finally {
      this.profiler.recordBridgeTranslucent(tTrans);
      this.releaseHeldTranslucentSlot();
    }
  }

  /** Fence-deferred retirement of the held translucent slot, if any. */
  private void releaseHeldTranslucentSlot() {
    if (this.heldTranslucentSlot >= 0
        && this.gbuffer != null
        && this.heldTranslucentSlot != this.heldCompletedSlot) {
      this.slotScheduler.queueSampledSlotRetirement(this.gbuffer, this.heldTranslucentSlot);
    }
    this.heldTranslucentSlot = -1;
    this.heldTranslucentFrame = null;
    this.heldTranslucentContext = null;
    this.heldMvpValid = false;
  }

  /** Drops the held slot reference WITHOUT retiring it (used when the native slots are reset). */
  private void dropHeldTranslucentSlot() {
    this.heldTranslucentSlot = -1;
    this.heldTranslucentFrame = null;
    this.heldTranslucentContext = null;
    this.heldMvpValid = false;
  }

  @Override
  public void setRenderDistance(float renderDistance) {
    this.terrainResources.setRenderDistance(renderDistance);
  }

  @Override
  public void addDebugInfo(List<String> debug) {
    debug.add("Voxy backend: GL41METAL");
    debug.add("Voxy GL41Metal status: Metal LOD/culling worklist and quad raster active");
    debug.add("Voxy GL41Metal selection: " + this.context.selection().reason());
    debug.add("Voxy GL41Metal config: " + this.config);
    if (this.gbuffer != null) {
      debug.add("Voxy GL41Metal native: " + this.gbuffer.description());
    }
    this.slotScheduler.addDebugInfo(debug);
    this.terrainResources.addDebugInfo(debug);
    this.profiler.addDebugInfo(debug);
  }

  @Override
  public RenderFrameMatrices getLastFrameMatrices() {
    return this.lastFrameMatrices;
  }

  @Override
  public int voxyDistantDepthTextureId() {
    return this.bridge.voxyDistantDepthTextureId();
  }

  @Override
  public void onChunkTrackerReset() {
    // GL-only loaded-volume bound: Sodium rebuilt its render section manager (world load or vanilla
    // render-distance change), so drop the tracked near-scene volume. This touches no Metal state.
    this.boundRenderer.reset();
    this.terrainResources.onChunkTrackerReset();
  }

  @Override
  public void onSectionRenderStateChanged(long sectionPos, boolean present) {
    // GL-only loaded-volume bound: track Sodium's 16-block near-scene render sections so the bridge
    // can clip distant LOD that overlaps the near scene (P1). This is exactly the kind of 16-block
    // near-scene signal AGENTS.md allows here; it does NOT change Metal traversal/residency.
    if (present) {
      this.boundRenderer.addSection(sectionPos);
    } else {
      this.boundRenderer.removeSection(sectionPos);
    }
    this.terrainResources.onSectionRenderStateChanged(sectionPos, present);
  }

  @Override
  public void close() {
    Logger.info("Shutting down Voxy GL41Metal shared texture backend");
    this.dropHeldTranslucentSlot();
    this.terrainResources.close();
    this.bridge.close();
    this.boundRenderer.close();
    if (this.gbuffer != null) {
      this.slotScheduler.closeRetiringSlots(this.gbuffer);
      Logger.info("Voxy GL41Metal slot stats: " + this.slotScheduler.summary());
      this.gbuffer.close();
      this.gbuffer = null;
    }
  }

  private void resetSlotSubmitStates() {
    // The native slots were forced back to Free, so every stored raster-space is stale.
    this.slotSubmitStates = new SlotSubmitState[this.config.slotCount()];
    for (int i = 0; i < this.slotSubmitStates.length; i++) {
      this.slotSubmitStates[i] = new SlotSubmitState();
    }
    this.newestCompletedFrameId = -1;
    // Native slots were forced back to Free; the held slot id would be stale.
    this.heldCompletedSlot = -1;
  }

  private void ensureGbuffer(int width, int height) {
    if (width <= 0 || height <= 0) {
      throw new IllegalArgumentException("Invalid GL41Metal viewport size " + width + "x" + height);
    }
    if (this.gbuffer != null && this.gbuffer.width() == width && this.gbuffer.height() == height) {
      return;
    }
    if (this.gbuffer != null) {
      // Resize the screen-sized gbuffer textures in place, preserving the native context handle and
      // all terrain/world/atlas residency. The bridge's own depth textures self-heal on size change
      // and the slot scheduler is reset because the native slots are forced back to Free.
      // Any held translucent slot is dropped (not retired): the native slots are about to be reset
      // to Free, so retiring a now-stale slot id would be wrong.
      this.dropHeldTranslucentSlot();
      this.slotScheduler.closeRetiringSlots(this.gbuffer);
      this.gbuffer.resize(width, height);
      this.slotScheduler.reset();
      this.resetSlotSubmitStates();
      Logger.info("Voxy GL41Metal shared gbuffer resized: " + this.gbuffer.description());
      return;
    }
    this.gbuffer =
        SharedDistantGbuffer.create(this.config.slotCount(), width, height);
    this.slotScheduler.reset();
    this.resetSlotSubmitStates();
    Logger.info("Voxy GL41Metal shared gbuffer initialized: " + this.gbuffer.description());
  }
}
