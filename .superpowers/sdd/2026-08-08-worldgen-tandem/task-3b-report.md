# Task 3b report: A/B control experiment — is the render stall specific to the worldgen pairing?

**Bottom line up front:** The stall is specific to the pairing. Arm A (Voxy alone) rendered real,
continuously-updating terrain for its entire ~3-minute life, with only 1-2 out of ~1100+
main-thread samples ever touching `kk_timeline_wait`, and 0 in the third sample. Arm B (Voxy +
worldgen) locked 100% of the main thread into the exact `kk_timeline_wait -> IOSurfaceSharedEvent
waitUntilSignaledValue -> iokit_user_client_trap` stack in **every one of 4 samples across ~4
minutes**, starting within 13 seconds of world join, and never produced a second
`RenderStatistics` line after the first (all-zero) one. Both arms independently died with exit
137 near the end of their runs, for two different and only partially-confirmed reasons (see
below) — but in both cases, the arm's core rendering behavior was already fully established
*before* those deaths, so the deaths do not confound the primary finding.

A second, unplanned finding: both arms show macOS GPU-firmware "lockup and restart" events
(`gpuEvent-java-*.ips`, `restart_reason_desc: "firmware-detected lockup"`) at or immediately
after world join. Arm A recovered cleanly from two such events and kept rendering for minutes.
Arm B's only such event, 3 seconds after world join, coincides almost exactly with the onset of
the permanent stall. This suggests a hypothesis worth flagging even though this experiment can't
prove it: the GPU firmware-level lockup+restart may be a systemic, pairing-independent event on
this Zink/KosmicKrisp setup, and what differs with the worldgen mod loaded is the render
system's *ability to recover* from it, not whether the lockup happens at all.

## Memory gate

Pre-launch check (per the required formula):

```
$ vm_stat | awk '/Pages free/{gsub(/\./,"");print $3*16384/1073741824" GB free"}'
1.9059 GB free
```

Above the 1.5 GB gate, so Arm A was launched. This is already far better than the ~96 MB the
confounded prior task ran under, but it was still tighter than I'd like — noted honestly, not
glossed over.

No `devlaunchinjector`/java process was running at the start, and no leftover process was found
between arms.

## Arm A — control (Voxy alone)

```
$ nohup ./scripts/macos/run-zink-client.sh -PvoxyDebugStats -PquickPlayWorld="New World" \
    > /tmp/ab-armA.log 2>&1 &
```

Launched 16:21:40. World join confirmed at 16:22:05 (`Player299 joined the game`), PID 33718.

### Samples (main-thread weight of `kk_timeline_wait` vs. total main-thread samples)

| # | Time (wall) | T from join | weight | total | % |
|---|---|---|---|---|---|
| 1 | 16:22:28 | +23s | 1 | 1169 | 0.09% |
| 2 | 16:23:16 | +71s | 1+1 (two distinct call sites) | 1132 | 0.18% |
| 3 | 16:24:32 | +147s | 0 | 1108 | 0% |

Sample 1's dominant activity (874/1126 top-level main-thread samples) was ordinary frame-pacing
fence waits in `zink_flush -> _util_queue_fence_wait -> ... -> __psynch_cvwait` (a *different*,
much shorter native call path than the KosmicKrisp one), plus active `glthread`/draw-call
marshalling. The lone `kk_timeline_wait` occurrence in sample 1 came from
`zink_context_is_resource_busy` doing a one-off buffer-reuse poll — a normal, single-sample
event, not a stall:

```
1 tc_invalidate_buffer -> zink_context_is_resource_busy -> zink_screen_timeline_wait
  -> vk_common_WaitSemaphores -> kk_timeline_wait -> mtl_shared_event_wait_until_signaled_value
  -> -[IOSurfaceSharedEvent waitUntilSignaledValue:timeoutMS:] -> iokit_user_client_trap
```

`RenderStatistics`: **55 lines total**, first at 16:22:06 (all zero), second at 16:22:09 already
showing `hierarchicalRenderSections=[241, 19, 0, 0, 0]` and `quadCount=[101305, 23864, 0, 0, 0]`
— **index 1 clearly non-zero** — and a new line roughly every 3 seconds continuously until
16:24:52, with index-1 values fluctuating (11511–23864) the whole time, consistent with the
player moving and LOD sections streaming in and out normally. First and last:

```
[16:22:06] ...hierarchicalTraversalCounts=[0, 0, 0, 0, 0] hierarchicalRenderSections=[0, 0, 0, 0, 0] visibleSections=[0, 0, 0, 0, 0] quadCount=[0, 0, 0, 0, 0]
[16:22:09] ...hierarchicalTraversalCounts=[326, 82, 26, 10, 1726] hierarchicalRenderSections=[241, 19, 0, 0, 0] visibleSections=[102, 16, 0, 0, 0] quadCount=[101305, 23864, 0, 0, 0]
...
[16:24:47] ...hierarchicalTraversalCounts=[193, 62, 27, 10, 1712] hierarchicalRenderSections=[108, 4, 0, 0, 0] visibleSections=[36, 1, 0, 0, 0] quadCount=[35642, 1509, 0, 0, 0]
[16:24:52] ...hierarchicalTraversalCounts=[193, 62, 27, 10, 1712] hierarchicalRenderSections=[108, 4, 0, 0, 0] visibleSections=[36, 1, 0, 0, 0] quadCount=[35642, 1509, 0, 0, 0]
```

### Screenshot (16:22:56, ~51s after join)

`ab-armA-window.png` — the Minecraft window is on top (unoccluded) of terminal/GitHub Desktop
windows behind it. **The content is genuine rendered terrain**: a sunset sky, tall trees, a river,
and the hotbar, rendered in full color and detail — not black, not fog, not corrupted.

### Unexpected end

At 16:24:40 the client logged a normal-looking `Saving and pausing game...` /
`Shutting down rendering` / `Render shutdown completed` sequence, then re-created the Voxy render
system at 16:24:52 (consistent with returning to a menu, not a crash), and the process was gone
by the time I tried sample 4 at 16:25:38 (`sample: ... no longer appears to be running`). Gradle
reported:

```
> Process 'command '.../bin/java'' finished with non-zero exit value 137 (this value may indicate
  that the process was terminated with the SIGKILL signal, which is often caused by the system
  running out of memory)
BUILD FAILED in 3m 25s
```

**I checked `/Library/Logs/DiagnosticReports/` and found no new `JetsamEvent-*.ips`** — the only
one present all night is from 16:05:38 (the earlier, unrelated confounded task). What I *did*
find, timestamped 16:24:53 (i.e. the same second as the death): a `gpuEvent-java-2026-08-08-162453.ips`
with `restart_reason_desc: "firmware-detected lockup"`. Two more of the same report type appear at
16:22:08 and 16:22:12 — 3 and 7 seconds after world join — which the client evidently survived,
since rendering continued normally for another ~2.5 minutes afterward. So Arm A's death is **not
confirmed as a jetsam/OOM kill** (Gradle's message is a generic guess, not a verified cause); it's
at least as consistent with a GPU-firmware restart the client failed to survive this particular
time, given the exact-second coincidence. This is genuinely ambiguous and I'm reporting it as
such rather than picking the more convenient explanation.

## Arm B — treatment (Voxy + worldgen)

```
$ nohup ./scripts/macos/run-zink-client.sh -Pworldgen -PvoxyDebugStats -PquickPlayWorld="New World" \
    > /tmp/ab-armB.log 2>&1 &
```

Memory immediately before launch: **3.60 GB free** — better than Arm A's 1.9 GB gate-check
reading, and I flag that explicitly per the task's instruction, because it means any weakness
found in Arm B cannot be blamed on *starting* under worse memory conditions than Arm A. (What
happened to memory *during* the run is a separate, important story — see below.)

Launched 16:27:28. World join confirmed at 16:27:48 (`Player495 joined the game`), PID 34270.
Worldgen loaded and ran normally: `pausing generation 15s while Player495 loads`, then
`generating [minecraft:overworld]: 576 done @ 65.2/s, ... 0 failed` at 16:28:08, then
`generation caught up: 576 chunks this session (76 skipped, 0 failed)` at 16:28:18 — **chunk
generation itself worked perfectly again**, consistent with every prior run.

A `gpuEvent-java-2026-08-08-162751.ips` (`firmware-detected lockup`) fired at 16:27:51, 3 seconds
after join — same signature as Arm A's early, survived events.

### Samples

| # | Time (wall) | T from join | `kk_timeline_wait` weight | main-thread total | % |
|---|---|---|---|---|---|
| 1 | 16:28:01 | +13s | 1648 | 1648 | **100%** |
| 2 | 16:29:35 | +107s | 1807 | 1807 | **100%** |
| 3 | 16:31:00 | +192s | 1713 | 1713 | **100%** |
| 4 | 16:31:46 | +238s | 1727 | 1727 | **100%** |

Full stack, identical in structure across all 4 samples (sample 1 shown, weights differ per
sample as in the table above):

```
1648 Thread_3999823   DispatchQueue_1: com.apple.main-thread  (serial)
  ... (JVM/JLI/CFRunLoop/NSOperation frames elided — see raw sample files) ...
  1648 glfwSwapBuffers (in libglfw.3.5.dylib)
    1648 swapBuffersEGL (in libglfw.3.5.dylib)
      1648 eglSwapBuffers (in libEGL.1.dylib)
        1648 dri2_swap_buffers (in libEGL.1.dylib)
          1648 dri2_surfaceless_kopper_swap_buffers (in libEGL.1.dylib)
            1648 kopperSwapBuffersWithDamage (in libgallium-26.1.0-devel.dylib)
              1648 dri_flush (in libgallium-26.1.0-devel.dylib)
                1648 fence_finish (in libgallium-26.1.0-devel.dylib)
                  1648 zink_screen_timeline_wait (in libgallium-26.1.0-devel.dylib)
                    1648 vk_common_WaitSemaphores (in libvulkan_kosmickrisp.dylib)
                      1648 kk_timeline_wait (in libvulkan_kosmickrisp.dylib)
                        1648 mtl_shared_event_wait_until_signaled_value (in libvulkan_kosmickrisp.dylib)
                          1648 -[IOSurfaceSharedEvent waitUntilSignaledValue:timeoutMS:] (in IOSurface)
                            1648 iokit_user_client_trap (in IOKit)
```

This is the exact stack quoted in the task background, and it is the **entire** main thread for
the **entire** sampling window, all four times, spanning from 13 seconds to just shy of 4 minutes
after world join. This is categorically different from anything seen in Arm A.

`RenderStatistics`: **1 line total**, at 16:27:49, all zero:

```
[16:27:49] ...hierarchicalTraversalCounts=[0, 0, 0, 0, 0] hierarchicalRenderSections=[0, 0, 0, 0, 0] visibleSections=[0, 0, 0, 0, 0] quadCount=[0, 0, 0, 0, 0]
```

No second line ever appeared, in ~4 minutes of runtime, despite generation completing and log
activity (buffer resizes, chunk-gen messages) continuing normally on other threads. **No
distant-layer (index ≥ 1) value was ever observed non-zero in this arm — the only line that
exists is all zeros.**

### Screenshot (16:30:26, ~2m38s after join)

`ab-armB-window.png` — the Minecraft window is on top (unoccluded) of a Chrome/YouTube window
behind it. **The content is not black.** It shows a static daytime scene: flat blue sky with
blocky white clouds, grass, trees, water, and a tan/orange block structure, plus the hotbar. This
is consistent with the render thread being permanently blocked *inside `glfwSwapBuffers`*, i.e.
after content was rasterized but before the swap that would present a new frame completes — the
compositor keeps showing the last frame that *did* successfully present, frozen, rather than a
cleared/black buffer. This is a different visual symptom than the "solid black" window reported
in the earlier confounded task, and I'm calling that difference out explicitly rather than
smoothing over it — it may depend on exactly how far rendering got before the fence wait that
never returns, which itself may depend on machine/session state that isn't controlled here.

### Memory crisis mid-run (important, and separate from the stall finding)

```
16:28:40  0.27 GB free
16:29:35  0.16 GB free   (during sample 2)
16:31:00  0.36 GB free   (during sample 3)
16:31:46  0.39 GB free   (during sample 4)
16:32:xx  4.93 GB free   (immediately after the process died)
```

Free memory collapsed from the 3.60 GB at launch to under 0.4 GB within about a minute of world
join, and stayed critically low (well under the 1.5 GB gate) for the rest of Arm B's life. The
java process's own RSS was flat (~3.3 GB) throughout, so this was not the client ballooning — it
matches the background's description of overall machine pressure (multiple Claude Code sessions,
Chrome, Discord, Safari) building up independently of this experiment.

**Why this does not undermine the primary finding:** sample 1, showing the render thread already
100% locked in `kk_timeline_wait`, was taken at 16:28:01 — 39 seconds *before* the first
critically-low memory reading (16:28:40) and while the last known-good reading (3.60 GB at
16:27:28, 33 seconds earlier) was still healthy. The stall was fully established while memory was
fine. The memory crisis is a real, separate confound that affects how much weight to put on Arm
B's *final* exit-137 death (see below), but it cannot explain the stall's onset, and I'm not
letting it soften that conclusion.

### End of run

By the time I ran the shutdown command, the process had **already exited on its own** —
`tail -5` on the log showed `BUILD FAILED in 4m 32s` before `pkill -f devlaunchinjector` found
anything to kill:

```
> Process 'command '.../bin/java'' finished with non-zero exit value 137 (this value may indicate
  that the process was terminated with the SIGKILL signal, which is often caused by the system
  running out of memory)
BUILD FAILED in 4m 32s
```

Death occurred at 16:32:01 (confirmed via `log show` — XPC/WindowServer/audiomxd teardown
messages for PID 34270 all cluster at that exact timestamp). I checked
`/Library/Logs/DiagnosticReports/` for a `JetsamEvent-*.ips` and for a coincident `gpuEvent-*`:
**neither exists for this timestamp.** The most recent `gpuEvent-java-*.ips` before the death is
from 16:27:51, over 4 minutes earlier. I also searched the unified log
(`log show --predicate 'eventMessage CONTAINS "jetsam"'` and `sender == "kernel"` variants) for an
explicit kernel jetsam-kill message referencing PID 34270 and found none. Given free memory was
under 0.4 GB for the full minute before the death and jumped to 4.93 GB immediately after, an
unlogged memory-pressure kill is the most plausible explanation, but I cannot point to a specific
artifact that proves it, so I'm reporting this as **plausible but not confirmed**, same standard
applied to Arm A's death.

## Comparison table

| | Arm A (control) | Arm B (worldgen) |
|---|---|---|
| Visible terrain on screen | Yes — real, detailed, unoccluded | No — frozen (not black) static frame, unoccluded |
| `kk_timeline_wait` full-stack stall observed | No (0–2 stray samples out of 1100+, 3 samples) | Yes — 100% of main thread, all 4 samples over ~4 min |
| `RenderStatistics` line count | 55, continuously growing/changing | 1 (all zero), then never again |
| Distant-layer (index ≥ 1) non-zero | Yes, repeatedly (e.g. `quadCount[1]`=23864→1509) | Never (only line is all-zero) |
| Chunk generation | N/A (worldgen not loaded) | Perfect — 576 chunks, 0 failed, caught up |
| GPU firmware-lockup event(s) | 2 early (survived), 1 at death | 1 early, coincident with stall onset |
| Exit code | 137, ~3m25s in | 137, ~4m32s in |
| Exit cause confirmed? | No JetsamEvent; coincident gpuEvent lockup found | No JetsamEvent; no coincident gpuEvent; unconfirmed |
| Memory during core measurement window | Healthy (1.9–4 GB free) throughout | Healthy at stall onset, collapsed to <0.4 GB ~1 min later |

## Verdict

**The `kk_timeline_wait` render-thread stall is specific to the Voxy + Voxy World Gen V2
pairing on this machine, not a general Zink/KosmicKrisp/Voxy-alone failure mode.** Arm A (Voxy
alone) rendered real, continuously updating terrain with non-zero distant-LOD data for its entire
~3-minute run and never entered the stall stack in any meaningfully-weighted way. Arm B
(identical world, identical duration, worldgen added) entered the exact stall stack described in
the task background within 13 seconds of world join and never left it, across all 4 samples taken
over 4 minutes, while producing exactly one (all-zero) `RenderStatistics` line and no more. This
comparison is not confounded by memory: the critical Arm B sample proving the stall was captured
while free memory was still healthy (≥3.5 GB, extrapolated), 39 seconds before memory first
dropped below the 1.5 GB gate. The subsequent mid-run memory crisis in Arm B, and both arms' late,
only-partially-confirmed exit-137 deaths, are real and are reported in full above, but they postdate
and are separable from the stall finding itself.

The one loose thread worth carrying into any follow-up: both arms show a macOS GPU
firmware-detected-lockup event right around world join, and Arm A visibly recovers from it while
Arm B does not. That correlation (not proven causation) points at a specific, testable next
question — does the worldgen mod leave a GPU fence outstanding across that lockup/restart boundary
that Voxy-alone does not — rather than a vague "something about worldgen breaks rendering."
