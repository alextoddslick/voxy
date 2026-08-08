# Task 3 report: Launch 2 — generation, ingest, and render

**Bottom line up front:** Checkpoint 3 (generation) is a clean PASS across all three launch
attempts. Checkpoint 4a (LOD ingest) is a PASS **only under a heavy caveat**: the on-disk evidence
is a stale artifact from Task 2, not fresh proof from tonight's runs — every attempt died before
its own data could be flushed to disk.

> **[Superseded — see "Fix Round 1 § Finding 1" at the bottom of this file.]** Checkpoint 4a's
> verdict is corrected to **UNPROVEN**, not "PASS, heavily caveated." Three attempts produced zero
> fresh on-disk ingest bytes tonight, so the checkpoint was never actually exercised, not weakly
> passed. Kept above verbatim as the historical record of the original call.

Checkpoint 4b (distant LOD render) is a **FAIL**: one
attempt produced a single non-zero distant-layer log line, but the mandatory visual check (Step 8)
caught exactly the failure mode it exists to catch — the actual window was solid black at that
moment despite the non-zero counters. This required three launch attempts, two of which were
killed by the OS (SIGKILL/exit 137, confirmed OOM via a JetsamEvent report) and two of which
independently hit the identical native GPU-fence-wait stall via `sample`. Full detail below.

## Step 1: config retune

```
$ cd /Users/alextodd/temp/Github-NOTSYNCED/voxy
$ python3 - <<'PY'
import json, pathlib
p = pathlib.Path("versions/1.21.1-fabric/run/config/voxyworldgenv2.json")
cfg = json.loads(p.read_text())

def retune(d):
    hit = False
    if "maxMbpsPerPlayer" in d:
        d["maxMbpsPerPlayer"] = 0.0; hit = True
    if "generationRadius" in d:
        d["generationRadius"] = 16; hit = True
    for v in d.values():
        if isinstance(v, dict):
            hit = retune(v) or hit
    return hit

assert retune(cfg), "neither key found - inspect the file by hand"
p.write_text(json.dumps(cfg, indent=2))
print(p.read_text())
PY
{
  "enabled": true,
  "showF3MenuStats": true,
  "generationRadius": 16,
  "update_interval": 20,
  "maxQueueSize": 20000,
  "maxActiveTasks": 20,
  "maxMbpsPerPlayer": 0.0,
  "logProgressIntervalSeconds": 10,
  "stuckTaskTimeoutSeconds": 60,
  "dimensionChangePauseSeconds": 15,
  "headlessPlayers": [],
  "rememberSentChunks": true,
  "knownChunksTimeoutSeconds": 10,
  "refreshPermissionLevel": 2,
  "refreshDefaultRadius": 16
}
```

Both target keys were at top level, not nested — the recursive walk found them at depth 0.
`maxMbpsPerPlayer: 2.0 → 0.0`, `generationRadius: 64 → 16`. Every other field is untouched.

## Step 2: Voxy's own config

```
$ grep -E "section_render_distance|service_threads" versions/1.21.1-fabric/run/config/voxy-config.json
  "section_render_distance": 16.0,
  "service_threads": 8,
```

Matches expected stock defaults exactly. Confirmed again after all three launch attempts — nothing
touched it.

## Steps 3–9: three launch attempts

The prescribed single launch (Steps 3–9) turned into **three** launches, because the first two
died of causes unrelated to worldgen/render correctness before they could produce a full evidence
set. This section documents all three; the checkpoint verdicts at the end are based on the combined
evidence.

### Attempt 1 (15:53:09–16:05:38 wall clock, ~12.5 min)

Launched per the brief exactly:

```
$ pkill -f devlaunchinjector || true
$ rm -f /tmp/tandem-run.log
$ nohup nice -n 15 ./scripts/macos/run-zink-client.sh -Pworldgen -PvoxyDebugStats \
    -PquickPlayWorld="New World" > /tmp/tandem-run.log 2>&1 &
```

Loaded cleanly:

```
[15:53:21] [Voxy-WorldGen-Worker/INFO] (voxyworldgenv2) voxy integration initialized (enabled: true, raw: true, voxyEnabled: true)
[15:53:21] [Server thread/INFO] (voxyworldgenv2) loaded 304 chunks from voxy generation cache for ResourceKey[minecraft:dimension / minecraft:overworld]
[15:53:21] [Render thread/INFO] (Voxy) [me.cx.vy.ct.ce.VoxyRenderSystem]: Creating Voxy render system
```

Generation completed almost instantly (radius 16 was mostly already cached from Task 2's earlier
runs at this same "New World" save):

```
[15:53:22] [Render thread/INFO] (Voxy) [me.cx.vy.ct.RenderStatistics]: RenderStatistics (per LOD layer, layer 0 = closest): hierarchicalTraversalCounts=[0, 0, 0, 0, 0] hierarchicalRenderSections=[0, 0, 0, 0, 0] visibleSections=[0, 0, 0, 0, 0] quadCount=[0, 0, 0, 0, 0]
[15:53:41] [Server thread/INFO] (voxyworldgenv2) generating [minecraft:overworld]: 480 done @ 63.2/s, 0 remaining in radius, 0 active, 152 skipped, 0 failed
[15:53:51] [Server thread/INFO] (voxyworldgenv2) generation caught up: 480 chunks this session (152 skipped, 0 failed)
```

**Anomaly A — the process then went completely silent.** No log line of any kind appeared from
15:53:51 onward. I checked the process with `/usr/bin/sample` (never `jstack`, per the brief) twice,
45 seconds apart:

```
$ /usr/bin/sample 31409 3 -f /tmp/tandem-sample.txt
$ /usr/bin/sample 31409 3 -f /tmp/tandem-sample2.txt   # 45s later
```

Both samples show the **main/render thread 100% blocked** in the identical native call stack, for
the full 3-second sampling window each time:

```
1898 Thread_3955695   DispatchQueue_1: com.apple.main-thread  (serial)
  ...
  glfwSwapBuffers (in libglfw.3.5.dylib)
    swapBuffersEGL (in libglfw.3.5.dylib)
      eglSwapBuffers (in libEGL.1.dylib)
        dri2_swap_buffers (in libEGL.1.dylib)
          dri2_surfaceless_kopper_swap_buffers (in libEGL.1.dylib)
            kopperSwapBuffersWithDamage (in libgallium-26.1.0-devel.dylib)
              dri_flush (in libgallium-26.1.0-devel.dylib)
                fence_finish (in libgallium-26.1.0-devel.dylib)
                  zink_screen_timeline_wait (in libgallium-26.1.0-devel.dylib)
                    vk_common_WaitSemaphores (in libvulkan_kosmickrisp.dylib)
                      kk_timeline_wait (in libvulkan_kosmickrisp.dylib)
                        mtl_shared_event_wait_until_signaled_value (in libvulkan_kosmickrisp.dylib)
                          -[IOSurfaceSharedEvent waitUntilSignaledValue:timeoutMS:] (in IOSurface)
                            iokit_user_client_trap (in IOKit)
```

This is a **different** stack from the previously-fixed hang documented in `docs/macos.md`
(`u_vbuf_draw_vbo -> tc_buffer_map -> zink_buffer_map -> batch_usage_wait`, fixed by widening
occlusion-cull indices to 16-bit, commit `e6650799`) — so this is not a regression of that bug. This
one is inside `swapBuffers`'s own fence wait, i.e. waiting for the *previous* frame's GPU work to be
acknowledged before presenting the next one. All of Mesa's worker queue threads (`gl0`, `zfq0`,
`zcq0`, `zcfq0`, `gdrv0`, `disk$0`) were idle on their condition variables the whole time — no work
was queued, consistent with the compositor never consuming the outstanding frame.

**Anomaly B — the screen was locked, and had been since before launch.** I confirmed via:

```
$ ioreg -n Root -d1 | grep -i CGSSession
"IOConsoleUsers" = ({... "CGSSessionScreenLockedTime"=1786216179, "CGSSessionScreenIsLocked"=Yes ...})
$ date -r 1786216179
Sat Aug  8 12:09:39 MST 2026
```

The lock predates the 15:53:09 launch by nearly 4 hours — this was not something that happened
during the run. `screencapture -x` during this period only ever captured the macOS lock screen
("Alex Todd — Touch ID or Enter Password"), never real window content, regardless of what the app
was doing — screenshots taken while locked are not evidence of anything about the app. I sent a
push notification asking for the screen to be unlocked, and it was (detected by the
`CGSSessionScreenIsLocked` key disappearing from the session dictionary — note it disappears
entirely on unlock rather than flipping to `No`, which meant my own Monitor poll script, which
checked for the literal string `No`, never caught the transition and reported "STILL LOCKED after
timeout" even though it *had* unlocked — a script bug on my part, caught by manual re-check).

**Anomaly C — the process was then killed by the OS, not by me.** Before I ran any shutdown
command, the build log show:

```
> Task :1.21.1-fabric:runClient FAILED
FAILURE: Build failed with an exception.
> Process 'command '/Library/Java/.../java'' finished with non-zero exit value 137 (this value may
  indicate that the process was terminated with the SIGKILL signal, which is often caused by the
  system running out of memory)
BUILD FAILED in 13m 19s
```

Exit 137 (SIGKILL), not the 143 (SIGTERM) the brief describes as the benign pkill-triggered
shutdown pattern — this is a genuinely different, unplanned termination. I confirmed it was a real
kernel memory-pressure kill via the OS's own diagnostic report:

```
$ ls /Library/Logs/DiagnosticReports/ | grep -i jetsam
JetsamEvent-2026-08-08-160538.ips
```

```python
timestamp: 2026-08-08 16:05:38 -0700
largestProcess: java
free pages at kill time: 6141 pages (~96 MB free system-wide)
our process entry: {"pid": 31409, "name": "java", "physicalPages": {"internal": [101211, 132114]},
                     "cpuTime": 143.49, "lifetimeMax": 326143}
```

132114 pages × 16KB ≈ 2.1 GB resident (5.1 GB peak/`lifetimeMax`), and the kernel's own report names
our process `largestProcess` on a system with only ~96 MB free. This machine was running 4
concurrent Claude Code sessions plus Chrome, Discord, and Safari at the time — real, independently
confirmed system-wide memory pressure, not a Voxy defect. `pkill -f devlaunchinjector` at this point
was a no-op; the process was already gone.

No LodMemory data was ever persisted this session (see Attempt 3 discussion below for why), and no
Minecraft crash-report was generated (SIGKILL bypasses the JVM's own crash handler entirely).

### Attempt 2 (16:07:43–~16:09:01, 1m 18s)

Screen confirmed unlocked (`CGSSessionScreenIsLocked` key absent) for this entire attempt. Loaded
identically, generation caught up again almost instantly:

```
[16:08:03] [Render thread/INFO] (Voxy) [me.cx.vy.ct.RenderStatistics]: RenderStatistics (...): hierarchicalTraversalCounts=[0, 0, 0, 0, 0] hierarchicalRenderSections=[0, 0, 0, 0, 0] visibleSections=[0, 0, 0, 0, 0] quadCount=[0, 0, 0, 0, 0]
[16:08:22] [Server thread/INFO] (voxyworldgenv2) generating [minecraft:overworld]: 480 done @ 81.6/s, 0 remaining in radius, 0 active, 336 skipped, 0 failed
[16:08:32] [Server thread/INFO] (voxyworldgenv2) generation caught up: 480 chunks this session (336 skipped, 0 failed)
```

Then, again with **zero further log output**, it died:

```
> Task :1.21.1-fabric:runClient FAILED
> Process ... finished with non-zero exit value 137 (SIGKILL ...)
BUILD FAILED in 1m 18s
```

No corresponding `JetsamEvent-*.ips` was written for this specific kill (only one exists all
night, timestamped for Attempt 1), so I cannot independently confirm this one was jetsam by the same
direct method — but the symptom (exit 137, Gradle's own OOM inference, and a large jump in free
memory immediately after) is identical, and I did not run `sample` on this attempt before it died
(I was watching for log growth instead, which cost me the chance) — so I cannot say from direct
evidence whether the render thread was hung here or was still making progress and just got jetsam'd
mid-flight. **This is a procedural gap on my part**, noted honestly rather than glossed over.

I also failed to preserve this attempt's full log file before overwriting it for Attempt 3 — the
lines quoted above are everything I have, salvaged from my own tool-output history; the rest of the
log is gone. Also a procedural mistake.

### Attempt 3 (16:11:54–~16:13:50, 1m 56s) — the useful one

Screen confirmed unlocked throughout. This is the attempt that actually produced real evidence.

```
[16:12:08] [Render thread/INFO] (Minecraft) OpenGL Renderer: zink Vulkan 1.3(Apple M2 Max (MESA_KOSMICKRISP))
WARNING: Some incorrect rendering might occur because the selected Vulkan device (Apple M2 Max) doesn't support base Zink requirements: have_EXT_custom_border_color have_EXT_line_rasterization
[16:12:14] [Voxy-WorldGen-Worker/INFO] (voxyworldgenv2) voxy integration initialized (enabled: true, raw: true, voxyEnabled: true)
```

The `EXT_custom_border_color`/`EXT_line_rasterization` warning is the expected, harmless
KosmicKrisp warning called out in the task context.

**RenderStatistics — first and last lines, with the distant-layer values called out:**

```
[16:12:15] RenderStatistics (per LOD layer, layer 0 = closest):
  hierarchicalTraversalCounts=[0, 0, 0, 0, 0]
  hierarchicalRenderSections=[0, 0, 0, 0, 0]
  visibleSections=[0, 0, 0, 0, 0]
  quadCount=[0, 0, 0, 0, 0]

[16:12:18] RenderStatistics (per LOD layer, layer 0 = closest):
  hierarchicalTraversalCounts=[313, 79, 26, 10, 1726]
  hierarchicalRenderSections=[190, 12, 0, 0, 0]      <- layer index 1 = 12, non-zero
  visibleSections=[77, 6, 0, 0, 0]                    <- layer index 1 = 6, non-zero
  quadCount=[80092, 10777, 0, 0, 0]                   <- layer index 1 = 10777, non-zero
```

This is only the **second** line ever logged (3 seconds after the first) and, per the ambiguity
resolution in this task's instructions, layer index 1 (not just layer 0) is genuinely non-zero here
— `hierarchicalRenderSections[1]=12`, `quadCount[1]=10777`. Taken purely as a log-line check, this
satisfies checkpoint 4b's stated pass condition. **But there is no third line** — I never observed
growth across successive lines, only a single before/after pair, because:

> **[See "Fix Round 1 § Finding 3" at the bottom of this file.]** This is the freshest in-session
> data point from tonight, and I originally failed to cross-reference it against checkpoint 4a at
> all. It turns out it would not have helped checkpoint 4a even if I had — see Finding 3 for why.

Generation caught up normally in the background:

```
[16:12:34] [Server thread/INFO] (voxyworldgenv2) generating [minecraft:overworld]: 480 done @ 83.5/s, 0 remaining in radius, 0 active, 355 skipped, 0 failed
[16:12:44] [Server thread/INFO] (voxyworldgenv2) generation caught up: 480 chunks this session (355 skipped, 0 failed)
```

...but no further `RenderStatistics` line ever appeared. I sampled the process at 16:12:56 (~38s
after the last stats line) and got the **identical** stack to Attempt 1's hang:

```
$ /usr/bin/sample 33146 3 -f /tmp/tandem-sample3.txt
1842 Thread_3981542  DispatchQueue_1: com.apple.main-thread (serial)
  ... glfwSwapBuffers -> swapBuffersEGL -> eglSwapBuffers -> dri2_swap_buffers ->
      dri2_surfaceless_kopper_swap_buffers -> kopperSwapBuffersWithDamage -> dri_flush ->
      fence_finish -> zink_screen_timeline_wait -> vk_common_WaitSemaphores -> kk_timeline_wait ->
      mtl_shared_event_wait_until_signaled_value -> -[IOSurfaceSharedEvent waitUntilSignaledValue:] ->
      iokit_user_client_trap
```

**This rules out screen-lock as the sole or even primary cause** — the screen was confirmed
unlocked (actively displaying a YouTube video in the browser, per the screenshot below) and the
identical GPU-fence-wait stall happened anyway, two frames into rendering. This looks like a
genuine, reproducible stall in the Zink/KosmicKrisp swap path under this specific test's load
(unthrottled `maxMbpsPerPlayer=0` plus radius-16 LOD ingest, compounded by heavy GPU contention from
the other apps sharing this Mac at the time), separate from the previously-fixed 16-bit-index bug.
It is a real finding worth follow-up, but out of scope to fix in this evidence-only task.

**Step 8 — the visual check, taken while the process was still alive:**

```
$ screencapture -x /tmp/tandem-window.png   # 4.4 MB, taken ~10-15s after the 16:12:18 stats line
```

The screenshot (saved to
`.superpowers/sdd/2026-08-08-worldgen-tandem/tandem-window.png`) shows the genuine "Minecraft*
1.21.1 - Singleplayer" window, correctly titled, sized, and frontmost-capable — proving the app was
a real, live foreground window this time, not a lock-screen artifact. **Its content area is
entirely solid black.** No terrain, no fog, no HUD, no void-gradient — just black, despite the log
showing non-zero layer-1 `quadCount`/`hierarchicalRenderSections` moments earlier. This is exactly
the scenario the task brief warned about: "the counters can be non-zero while the picture is wrong."
My working theory is that the RenderStatistics readback happens on the GPU-compute side of the
pipeline before the corresponding `swapBuffers`/present call completes, and since a `swapBuffers`
call was — per the `sample` evidence above — stuck in the same fence wait shortly after, that
frame's content, despite being computed, was never actually presented to the screen.

A second screenshot taken ~90s later, after the process had already been killed, shows the desktop
with Safari frontmost and no Minecraft window at all — confirming the process really was gone by
then, consistent with the "BUILD FAILED in 1m 56s" timing.

**The process was killed again, same signature, before Step 9's own shutdown command ran:**

```
> Task :1.21.1-fabric:runClient FAILED
> Process ... finished with non-zero exit value 137 (SIGKILL ...)
BUILD FAILED in 1m 56s
```

No corresponding `JetsamEvent-*.ips` for this specific kill either, but `vm_stat` showed free pages
jump from ~28K to ~312K (≈450MB → ≈4.9GB) immediately after — consistent with reclaiming a
multi-GB process. Current system memory at the time of writing this report:

```
$ vm_stat | head -5
Pages free:  312071.   (≈4.9 GB)
```

### Step 9: shutdown and late-failure check (run against the current state after attempt 3)

```
$ pkill -f devlaunchinjector || true      # no-op; process was already dead
$ ls versions/1.21.1-fabric/run/crash-reports/ | tail -5
crash-2026-08-05_16.04.49-client.txt
crash-2026-08-05_16.05.34-client.txt
crash-2026-08-05_16.17.57-client.txt
crash-2026-08-05_16.21.11-client.txt
crash-2026-08-05_16.28.50-client.txt
```

No fresh crash report — all five are from Aug 5, predating this task entirely (SIGKILL bypasses the
JVM's own crash-report generation, so this is expected given how the process actually died, not
evidence of a clean run).

```
$ grep -icE "exception|error" /tmp/tandem-run.log
14
$ grep -iE "exception|error" /tmp/tandem-run.log | grep -vi "errorprone" | head -20
[main/WARN] (FabricLoader/Mixin) Error loading class: com/moulberry/flashback/record/FlashbackMeta (ClassNotFoundException) [Flashback - optional dep, absent]
[main/WARN] (FabricLoader/Mixin) Error loading class: net/irisshaders/iris/* (ClassNotFoundException) x8 [Iris - optional dep, absent]
[main/WARN] (FabricLoader/Mixin) Error loading class: me/cortex/nvidium/RenderPipeline (ClassNotFoundException) [Nvidium - optional dep, absent]
FAILURE: Build failed with an exception.   [the SIGKILL, already covered above]
```

All benign, standard optional-mod class-not-found noise, same pattern as prior tasks in this plan.

## Step 6 evidence, revisited: checkpoint 4a (LOD ingest)

```
$ find versions/1.21.1-fabric/run/voxyworldgenv2/lodmemory -name "*.bin" -exec ls -la {} \;
-rw-r--r--@ 1 alextodd  staff  556 Aug  8 15:36 versions/1.21.1-fabric/run/voxyworldgenv2/lodmemory/436c2ac36846adbb/minecraft_overworld.bin

$ grep -i "voxy rawIngest is unavailable" /tmp/tandem-run-attempt1.log /tmp/tandem-run-attempt3.log
(no output — the warning never appeared, in any attempt)
```

**This file's mtime (15:36) predates all three of tonight's launches (earliest 15:53:09).** It is
unchanged, byte-for-byte and timestamp-for-timestamp, from Task 2. I checked
`voxy_worldgen_v2/src/main/java/com/ethan/voxyworldgenv2/client/LodMemory.java` (read-only) to
understand why: `flush()` to disk only happens from `LodMemory.tick()`, which only runs as part of
the client's per-tick loop — the same main/render thread that was stuck in the `swapBuffers` fence
wait in Attempts 1 and 3, and whatever killed Attempt 2 before three log lines even had time to
accumulate. `onDisconnect()` also calls `flush()`, but that only runs on a graceful disconnect —
every attempt here ended in `SIGKILL`, which the JVM cannot intercept, so no disconnect handler ever
ran either. **No attempt tonight produced a fresh on-disk artifact.**

That said, the literal check specified in the brief — "at least one `.bin` file with non-zero size...
and no `rawIngest is unavailable` warning" — is satisfied, and the absence of that warning held
across all three attempts, alongside consistent, successful `generation caught up` completions and
`voxy ingester initialized successfully (reflective)` on every launch. I'm calling this a **pass on
the letter of the check, with a hard caveat**: it is not fresh proof from tonight's radius-16/
unthrottled configuration, only from Task 2's session.

> **[Superseded — see "Fix Round 1 § Finding 1" and "§ Finding 2" at the bottom of this file.]**
> Two problems with the paragraph above: (1) the "pass on the letter, with a caveat" framing is
> wrong — the checkpoint was never exercised tonight, so the correct verdict is UNPROVEN, not a
> caveated pass; (2) "on every launch" for the `voxy ingester initialized successfully (reflective)`
> line was asserted without pasting the actual log output. Both corrected below. Kept above verbatim
> as the historical record of the original call.

## Checkpoint verdicts

**Checkpoint 3 (generation is running): PASS.** All three attempts show clean `generating [...]`
progress lines followed by `generation caught up: 480 chunks this session (N skipped, 0 failed)` —
the strongest form of evidence the brief describes, with 0 failures in every attempt. (Only one
progress line appeared per attempt, not three-plus, because the radius-16 area was already
substantially warm-cached from earlier task runs in this same "New World" save, so generation
finished in under 30 seconds each time — faster than the 10-second progress-log interval could
produce multiple samples. This is a real, honest limitation of the evidence, not a fabricated
substitute — combined across all three attempts there are three independent
`generating`/`caught-up` pairs, all consistent.)

**Checkpoint 4a (LOD data reached Voxy ingest): PASS, heavily caveated.** The specified grep checks
pass, but on a file whose timestamp predates every launch tonight — it is Task 2 evidence, not fresh
proof that tonight's radius-16/`maxMbpsPerPlayer=0` configuration's data reached disk. The absence
of any `rawIngest is unavailable` warning across three attempts, plus consistent successful
generation-catch-up cycles through the same reflective ingester, is corroborating-but-indirect
evidence that the pathway itself still works; it is not the direct, fresh proof the checkpoint asks
for.

> **[Superseded — see "Fix Round 1 § Finding 1" at the bottom of this file.]** Corrected verdict:
> **UNPROVEN**, not "PASS, heavily caveated." No fresh on-disk ingest bytes were produced in any of
> the three attempts, so this checkpoint was not exercised tonight — see Fix Round 1 for the full
> reasoning. Kept above verbatim as the historical record of the original call.

**Checkpoint 4b (distant LOD terrain rendering): FAIL.** One single log line (Attempt 3, 16:12:18)
showed genuinely non-zero values at LOD layer index 1 (`hierarchicalRenderSections[1]=12`,
`quadCount[1]=10777`), which taken alone would satisfy the numeric pass condition. But per this
task's own explicit instruction not to skip or fake Step 8: the screenshot taken minutes later,
while the process was still alive, shows the real, correctly-titled Minecraft window with **solid
black content** — no terrain, no fog, no void gradient, nothing. Immediately after, `sample`
confirmed the render thread was stuck in the same GPU-fence-wait stall seen in Attempt 1, this time
with the screen confirmed unlocked, ruling out screen-lock as the explanation. No second or third
`RenderStatistics` line ever appeared to show the required growth over time. I'm reporting this as a
clean FAIL rather than rounding a single ambiguous data point up to a pass — this is exactly the
"counters non-zero, picture wrong" failure mode the task instructions pre-empted.

## All anomalies (including non-blocking ones)

1. **Screen was locked since 12:09 PM, hours before Attempt 1's 15:53 launch** — not something that
   happened during the run. Made all `screencapture` output before the unlock meaningless (it only
   ever captured the macOS lock screen).
2. **Genuine render-thread GPU-fence-wait stall**, confirmed twice independently via `sample` (never
   `jstack`, per this task's instructions) in two separate launches — once under a locked screen,
   once under a confirmed-unlocked, actively-used screen. Stack terminates in
   `kk_timeline_wait -> IOSurfaceSharedEvent waitUntilSignaledValue -> iokit_user_client_trap`,
   distinct from the previously-fixed 16-bit-index hang documented in `docs/macos.md`. Worth
   follow-up; out of scope to fix here.
3. **All three launches were killed by SIGKILL (exit 137), not the SIGTERM/143 the task brief
   describes as the benign shutdown pattern.** Attempt 1's kill is directly confirmed as a kernel
   jetsam (OOM) event via `/Library/Logs/DiagnosticReports/JetsamEvent-2026-08-08-160538.ips`,
   which names our `java` process `largestProcess` on a system with ~96MB free. This machine was
   running 4 concurrent Claude Code sessions plus Chrome, Discord, and Safari at the time — real
   external memory pressure, not a Voxy defect. Attempts 2 and 3 show the identical symptom but
   without a corresponding diagnostic report to independently confirm the exact same cause.
4. **My own Monitor-based unlock-detection script had a bug**: it checked for
   `CGSSessionScreenIsLocked=No`, but macOS actually removes that key entirely on unlock rather than
   setting it to `No`, so the monitor reported "still locked" after its timeout even though the
   screen had, in fact, already been unlocked. Caught by a manual recheck, not by the monitor.
5. **I did not run `sample` on Attempt 2 before it died**, and **did not preserve Attempt 2's full
   log** before overwriting it to launch Attempt 3 — both procedural gaps on my part. The quoted
   Attempt 2 lines above are everything that survives, salvaged from my own tool-output history.
6. **No fresh LodMemory disk artifact was produced in any of the three attempts** (see checkpoint 4a
   discussion) — every attempt ended via SIGKILL before the client's own tick-gated flush or
   disconnect handler ever got a chance to run.
7. Standard, expected, benign noise present in every attempt: the KosmicKrisp
   `EXT_custom_border_color`/`EXT_line_rasterization` warning, and `ClassNotFoundException` log
   spam for optional mods not present (Flashback, Iris, Nvidium) — same pattern seen in prior tasks
   in this plan.
8. `versions/1.21.1-fabric/run/config/voxy-config.json` was reconfirmed at stock values
   (`section_render_distance: 16.0`, `service_threads: 8`) both before Step 3 and again after all
   three attempts — nothing in this task's process touched it.

## Recommendation

> **[Superseded — see "Fix Round 1 § Finding 1" at the bottom of this file.]** "Indirectly 4a...
> solid" overstates it — checkpoint 4a is UNPROVEN, not solid, per the correction below. Kept above
> verbatim as the historical record of the original call.

The generation → ingest pipeline (checkpoint 3, and indirectly 4a) is solid across three
independent attempts. The render side (checkpoint 4b) needs a retest under conditions where (a) the
Mac isn't under the kind of memory pressure that makes a ~5GB dev client the kernel's first jetsam
target, and (b) ideally with a `sample` taken every ~15s from launch so a stall is caught within
seconds rather than inferred after the fact. The GPU-fence-wait stack captured twice here — occurring
even with the screen unlocked — looks like a real, reproducible issue in the Zink/KosmicKrisp swap
path under this test's specific load pattern, separate from the previously-fixed indices bug, and is
worth its own investigation.

---

## Fix Round 1 (review response)

Three Important findings and one Minor came back from review. No client was re-run for this round
— everything below is drawn from evidence already captured tonight (the surviving
`/tmp/tandem-run-attempt1.log` and `/tmp/tandem-run-attempt3.log`, still present on disk unchanged,
plus `task-2-report.md` and the sibling repo's `LodMemory.java`, all read-only). Every section above
is left as originally written, marked with inline pointers to this section, per the convention this
plan already established in `task-2-report.md`'s own Fix Round 1 — keeping the original observation
visible as history rather than silently rewriting it as though it were right the first time.

### Finding 1 (Important): checkpoint 4a corrected to UNPROVEN, not "PASS, heavily caveated"

The reviewer is right and I was wrong to round this up to a caveated pass. Restating the mechanism
I'd already found, now drawing the correct conclusion from it:

`LodMemory.flush()` (`voxy_worldgen_v2/src/main/java/com/ethan/voxyworldgenv2/client/LodMemory.java`,
read-only, not modified) only ever runs from two call sites: `tick()`, gated by a 30-second debounce
against the client's per-tick loop, and `onDisconnect()`, which only fires on a *graceful*
disconnect. All three of tonight's attempts ended in `SIGKILL` (exit 137) — a signal the JVM cannot
intercept or run shutdown hooks against — so neither call site ever had a chance to run, in any
attempt. There is no code path by which tonight's retuned run (`maxMbpsPerPlayer=0.0`,
`generationRadius=16`) could have written a single fresh byte to `lodmemory/`, independent of how
long any attempt survived.

The `.bin` file's Aug 8 15:36 mtime is independently corroborated as Task 2's artifact, not
tonight's, by `task-2-report.md` itself:

```
task-2-report.md:161: -rw-r--r--@ 1 alextodd  staff  432 Aug  8 15:35 versions/1.21.1-fabric/run/config/voxyworldgenv2.json
task-2-report.md:170:   "generationRadius": 64,
task-2-report.md:174:   "maxMbpsPerPlayer": 2.0,
task-2-report.md:323: [15:36:05] [Server thread/INFO] (voxyworldgenv2) generating [minecraft:overworld]: 272 done @ 3.2/s, 12480 remaining in radius (~65m 00s), 0 active, 16 skipped, 0 failed
```

Task 2's own config snapshot (15:35) shows the *old*, un-retuned values (`generationRadius: 64`,
`maxMbpsPerPlayer: 2.0`), and Task 2's own log shows generation actively producing chunks at
15:36:05 — the same minute as the `.bin` file's mtime. That file is Task 2's artifact, produced
under the old config, full stop. Tonight's three attempts collectively produced **zero** fresh
ingest bytes on disk.

**Corrected verdict: checkpoint 4a is UNPROVEN.** Not "passed on the letter of the check" — the
literal grep conditions being satisfied by a leftover file from a previous task's session is not the
checkpoint being exercised; it's the checkpoint never having been given the chance to fail. "PASS,
heavily caveated" implied I'd tested the thing and it mostly worked; the truth is I never tested it.
This changes the BLUF and the "Checkpoint verdicts" section above (both marked with pointers to
here) and the "Recommendation" section's "indirectly 4a... is solid" claim (also marked).

### Finding 2 (Important): sourcing the "voxy ingester initialized successfully (reflective)" claim

The reviewer is right that this was a bare assertion. It should not have been — I had the surviving
logs the whole time and simply didn't check. Correcting that now:

```
$ grep -n "voxy ingester initialized" /tmp/tandem-run-attempt1.log /tmp/tandem-run-attempt3.log
/tmp/tandem-run-attempt1.log:225:[15:53:21] [Server thread/INFO] (voxyworldgenv2) voxy ingester initialized successfully (reflective)
/tmp/tandem-run-attempt3.log:226:[16:12:14] [Server thread/INFO] (voxyworldgenv2) voxy ingester initialized successfully (reflective)
```

The line is real and present, confirmed for **Attempts 1 and 3** — the two attempts whose logs
survive. I cannot confirm it for **Attempt 2**: that log was overwritten before I saved a copy (see
the original "procedural mistake" disclosure under Attempt 2 above), so "on every launch" in the
original text overstated what I could actually check. The honest claim is "confirmed present in the
two surviving logs (Attempts 1 and 3); not independently verifiable for Attempt 2."

This line does not change the Finding 1 correction — it was never disk-persistence evidence, only a
same-session log confirmation that the reflective ingester object itself constructed without error.
It's real, sourced corroboration that the pathway initializes cleanly; it says nothing about whether
any chunk's data actually reached disk, which Finding 1 already establishes it did not.

### Finding 3 (Important): cross-referencing Attempt 3's fresh RenderStatistics data — and why it doesn't rescue checkpoint 4a

I should have connected Attempt 3's 16:12:18 line (`hierarchicalRenderSections=[190, 12, 0, 0, 0]`,
`quadCount=[80092, 10777, 0, 0, 0]`) to checkpoint 4a explicitly — it's the freshest in-session data
point I have, and silently leaving it uncited in the 4a section while using it elsewhere (for 4b)
was an oversight. Doing that now, with the correct conclusion:

That data point does **not** demonstrate worldgen ingest happened tonight. Per the coordinator, a
control-arm launch of Voxy *without* `-Pworldgen` also produced non-zero layer-1 values
(`hierarchicalRenderSections=[162, 19, 0, 0, 0]`, `quadCount=[66280, 11511, 0, 0, 0]`) — meaning
Voxy's own LOD store is populated by its normal chunk-loading ingest as well as by worldgen's
`rawIngest`, and that store persists across runs regardless of which mod loaded it. Layer-1-and-beyond
non-zero values, on their own, are consistent with *either* source and cannot be attributed to
worldgen ingest without something that isolates the two paths (e.g., a scratch world Voxy has never
touched, or a value that only worldgen's ingest could have produced). I don't have that isolation
tonight, so this data point is neutral with respect to checkpoint 4a — it cannot be used to firm up
the verdict in either direction. This reinforces, rather than weakens, the UNPROVEN call in
Finding 1: I have no data tonight, fresh or stale, that specifically demonstrates worldgen's ingest
path reaching Voxy.

**Additional context from the coordinator, reported here for completeness but explicitly not used to
draw a conclusion:** the coordinator compared my three `sample` captures' hit counts for
`kk_timeline_wait` directly — 1879/1879 samples (Attempt 1, first capture), 1898/1898 (Attempt 1,
second capture), and 1842/1842 (Attempt 3) — i.e. the render thread was pinned in that exact frame
for effectively 100% of every 3-second sampling window, all three times. Their own control-arm
`sample` (also without `-Pworldgen`) showed a count of 1, consistent with an ordinary, brief,
resolving fence wait rather than a stall. The coordinator was explicit that this is not yet
attributable: my three captures ran with the system at roughly 96MB free (per the Attempt 1
JetsamEvent report already cited above), while their control ran at roughly 4GB free, so memory
pressure itself is an open confound. A second control arm — with `-Pworldgen`, at high free memory —
was reported as running at the time of this fix round, specifically to separate those two variables.
I am not drawing an attribution conclusion here; I'm reporting the coordinator's own comparison of my
data because it's directly relevant context, and noting explicitly that it is pending further
evidence I did not generate and have not seen.

### Minor: tension with `docs/macos.md`

`docs/macos.md`'s Status section currently states, unqualified: "Multi-minute sessions run without
hanging." Attempts 1 and 3 in this report directly sit in tension with that — both hung in an
identical native stack, one surviving 12.5 minutes before an external SIGKILL, the other stalling
after only two frames. I have not edited `docs/macos.md` — per this task's scope, that file belongs
to Task 4. Flagging the tension here so Task 4 has it on record rather than discovering it cold.

