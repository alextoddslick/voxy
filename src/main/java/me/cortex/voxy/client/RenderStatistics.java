package me.cortex.voxy.client;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class RenderStatistics {
    // Normally flipped on/off reactively by MixinDebugScreenOverlay when the F3 debug screen
    // opens/closes. Task 8: since a headless dev-client run has no F3 key input to drive that,
    // -Dvoxy.forceStatistics=true (see build.fabric.gradle.kts's -PvoxyDebugStats) forces this on
    // from the start instead, so the HAS_STATISTICS shader variant is compiled from the first
    // VoxyRenderSystem/MDICSectionRenderer construction at world join (the F3 mixin only reacts
    // to *changes* in debug-screen state, so it never overwrites an already-true default when F3
    // is never toggled during the run).
    public static boolean enabled = Boolean.getBoolean("voxy.forceStatistics");

    public static final int[] hierarchicalTraversalCounts = new int[WorldEngine.MAX_LOD_LAYER+1];
    public static final int[] hierarchicalRenderSections = new int[WorldEngine.MAX_LOD_LAYER+1];
    public static final int[] visibleSections = new int[WorldEngine.MAX_LOD_LAYER+1];
    public static final int[] quadCount = new int[WorldEngine.MAX_LOD_LAYER+1];

    private static long lastPeriodicLogNanos = 0;
    private static final long PERIODIC_LOG_INTERVAL_NANOS = 3_000_000_000L;

    // Task 8: periodic (throttled) log-file evidence of the same counters addDebug() would
    // otherwise only ever expose via the (headlessly-unavailable) F3 overlay. Safe to call every
    // frame - internally rate-limited and a no-op unless -Dvoxy.forceStatistics=true (or F3 is
    // open) has set `enabled`.
    private static final boolean PERIODIC_LOG_REQUESTED = Boolean.getBoolean("voxy.forceStatistics");

    public static void maybeLogPeriodic() {
        if (!enabled || !PERIODIC_LOG_REQUESTED) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastPeriodicLogNanos < PERIODIC_LOG_INTERVAL_NANOS) {
            return;
        }
        lastPeriodicLogNanos = now;
        Logger.info("RenderStatistics (per LOD layer, layer 0 = closest):"
                + " hierarchicalTraversalCounts=" + Arrays.toString(hierarchicalTraversalCounts)
                + " hierarchicalRenderSections=" + Arrays.toString(hierarchicalRenderSections)
                + " visibleSections=" + Arrays.toString(visibleSections)
                + " quadCount=" + Arrays.toString(quadCount));
    }

    public static void addDebug(List<String> debug) {
        if (!enabled) {
            return;
        }
        debug.add("HTC: [" + Arrays.stream(flipCopy(RenderStatistics.hierarchicalTraversalCounts)).mapToObj(Integer::toString).collect(Collectors.joining(", "))+"]");
        debug.add("HRS: [" + Arrays.stream(flipCopy(RenderStatistics.hierarchicalRenderSections)).mapToObj(Integer::toString).collect(Collectors.joining(", "))+"]");
        debug.add("VS: [" + Arrays.stream(flipCopy(RenderStatistics.visibleSections)).mapToObj(Integer::toString).collect(Collectors.joining(", "))+"]");
        debug.add("QC: [" + Arrays.stream(flipCopy(RenderStatistics.quadCount)).mapToObj(Integer::toString).collect(Collectors.joining(", "))+"]");
    }

    private static int[] flipCopy(int[] array) {
        int[] ret = new int[array.length];
        int i = ret.length;
        for (int j : array) {
            ret[--i] = j;
        }
        return ret;
    }
}
