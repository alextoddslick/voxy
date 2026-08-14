package me.cortex.voxy.client.core.rendering.backend.gl41metal;

record Gl41MetalConfig(
    int slotCount,
    int waitTimeoutMs,
    boolean visibleComposite,
    int meshBatchSize,
    double renderScale) {
  static Gl41MetalConfig fromSystemProperties() {
    return new Gl41MetalConfig(
        readInt("voxy.gl41metal.slotCount", 3, 2, 8),
        // Bounded by default: past this the composite falls back to the newest completed Metal
        // frame instead of stalling the render thread (0 = legacy unbounded blocking wait).
        readInt("voxy.gl41metal.waitTimeoutMs", 6, 0, 100),
        readBoolean("voxy.gl41metal.visibleComposite", true),
        readInt("voxy.gl41metal.meshBatchSize", 32, 16, 64),
        // Distant-layer resolution relative to the framebuffer. The bridge maps target pixels
        // to gbuffer texels through uSharedSize/uTargetSize, so any ratio composites correctly;
        // fill cost scales with the square (0.5 = quarter the raster/bandwidth work). Default
        // 0.5: on retina displays that equals the logical resolution, and measured Metal GPU
        // time drops ~40% avg / ~55% worst-case. Set 1.0 for full-resolution distant terrain.
        readDouble("voxy.gl41metal.renderScale", 0.5, 0.25, 1.0));
  }

  private static double readDouble(String property, double fallback, double min, double max) {
    String value = System.getProperty(property);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Math.max(min, Math.min(max, Double.parseDouble(value.trim())));
    } catch (NumberFormatException ignored) {
      return fallback;
    }
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
