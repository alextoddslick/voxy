package me.cortex.voxy.client.core.rendering.backend.gl41metal;

record Gl41MetalConfig(
    int slotCount,
    int waitTimeoutMs,
    boolean visibleComposite,
    int meshBatchSize,
    boolean reprojection,
    boolean reprojRefine,
    int maxInFlightSubmits,
    int overscanPercent) {
  static Gl41MetalConfig fromSystemProperties() {
    return new Gl41MetalConfig(
        readInt("voxy.gl41metal.slotCount", 3, 2, 8),
        // Bounded by default: past this the composite falls back to the newest completed Metal
        // frame instead of stalling the render thread (0 = legacy unbounded blocking wait).
        readInt("voxy.gl41metal.waitTimeoutMs", 6, 0, 100),
        readBoolean("voxy.gl41metal.visibleComposite", true),
        readInt("voxy.gl41metal.meshBatchSize", 32, 16, 64),
        // Reproject stale-slot composites into the current camera (fixes the LOD layer sliding
        // with camera motion under load). Off = pre-reprojection behaviour, for A/B testing.
        readBoolean("voxy.gl41metal.reprojection", true),
        // Second warp iteration using the depth found at the first guess: corrects the
        // camera-TRANSLATION residual of the far-plane mapping on nearer LODs.
        readBoolean("voxy.gl41metal.reprojRefine", true),
        // Cap on Metal frames in flight. Submitting every screen frame while the GPU needs
        // several frames per Metal pass just queues stale work and saturates the GPU; capping
        // keeps the newest completed frame FRESHER and hands the spare GPU time to vanilla.
        // 0 = uncapped (old behaviour).
        readInt("voxy.gl41metal.maxInFlightSubmits", 2, 0, 8),
        // Extra FOV rendered past the screen edges (percent). Costs raster area but gives stale
        // frames real content at the edges instead of blanks, and pre-refines LOD just outside
        // the view. Only effective while reprojection is on (the warp is what reads the margin).
        readInt("voxy.gl41metal.overscanPercent", 12, 0, 50));
  }

  private static boolean readBoolean(String property, boolean fallback) {
    String value = System.getProperty(property);
    return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.trim());
  }

  private static int readInt(String property, int fallback, int min, int max) {
    String value = System.getProperty(property);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Math.max(min, Math.min(max, Integer.parseInt(value.trim())));
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }
}
