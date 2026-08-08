# Task 2 Report: Launch 1 — both mods load and the reflection bridge resolves

Status: DONE_WITH_CONCERNS. Both spec checkpoints pass on their documented
criteria — checkpoint 1 (both mods loaded, no mixin damage) and checkpoint 2
(`raw: true`, the field that decides it). One value in the checkpoint-2 line
differs from what the brief predicted (`enabled: true` vs. the predicted
`enabled: false`); flagged below per the "report anything unexpected" rule.
It does not block the checkpoint per the brief's own tie-breaker ("only
`raw: true` decides the checkpoint").

**See "Fix Round 1" at the bottom of this file.** Two Important review
findings corrected framing/omissions below without altering the original
observations: (1) the `enabled: true` anomaly in Step 6 is now resolved —
it's the correct, healthy value, not an open mystery — per source at
`VoxelIngestService.java:195` and commit `20cf2b06`; (2) Step 7's "shut down
cleanly" omitted a literal `BUILD FAILED` / exit-143 in the log, which is
benign (SIGTERM from `pkill`) but should have been disclosed. Read Steps 6
and 7 below as the historical record of what was observed and reasoned at
the time, and Fix Round 1 as the corrected/completed account.

Log: `/tmp/tandem-load.log` from the run started 2026-08-08 15:35 (gitignored,
not committed). Client PID launched via `run-zink-client.sh -Pworldgen
-PquickPlayWorld="New World"`, shut down cleanly afterward (no new
crash-reports or hs_err files — all existing ones in
`versions/1.21.1-fabric/run/` are dated Aug 5, predating this run).

---

## Step 1: Clear any leftover client

```
$ pkill -f devlaunchinjector || true
$ sleep 2
$ ps -Ao pid,args | grep -c "[d]evlaunchinjector"
0
```

(A first count immediately after `pkill` transiently read `1`, but a
follow-up check with no `sleep` showed no matching process at all — an
artifact of the process still tearing down, not a real leftover. Re-ran the
count a few seconds later and got a clean `0`.)

## Step 2: Launch the client with the world auto-joined

```
$ cd /Users/alextodd/temp/Github-NOTSYNCED/voxy
$ rm -f /tmp/tandem-load.log
$ nohup nice -n 15 ./scripts/macos/run-zink-client.sh -Pworldgen \
    -PquickPlayWorld="New World" > /tmp/tandem-load.log 2>&1 &
launched
```

## Step 3: Wait for the world to load

Waited via a Monitor watch on `/tmp/tandem-load.log` for
`Loaded .* advancements|Started serving on|Time elapsed:`, with an extra
early-abort branch on `FATAL|Exception in thread "main"|Could not|BUILD
FAILED` to catch a real crash sooner. That abort branch fired first, but on
inspection it was a false positive — it matched two pre-existing benign
lines, not a failure:

```
[15:35:11] [ForkJoinPool-1-worker-9/WARN] (FabricLoader/Metadata) Mod sodium uses the version ${version} which isn't compatible with Loader's extended semantic version format (Could not parse version number component '${version}'!), ...
[15:35:11] [main/WARN] (FabricLoader/Mixin) Reference map 'voxy.refmap.json' for client.voxy.mixins.json could not be read. If this is a development environment you can ignore this message
[15:35:11] [main/WARN] (FabricLoader/Mixin) Reference map 'voxy.refmap.json' for common.voxy.mixins.json could not be read. If this is a development environment you can ignore this message
```

The real success markers followed shortly after:

```
[15:35:20] [Render thread/INFO] (Minecraft) Loaded 1290 recipes
[15:35:20] [Render thread/INFO] (Minecraft) Loaded 1399 advancements
[15:35:25] [Render thread/INFO] (Minecraft) Preparing spawn area: 0%
[15:35:25] [Render thread/INFO] (Minecraft) Time elapsed: 238 ms
[15:35:25] [Server thread/INFO] (Minecraft) Player24[local:E:3341ecb2] logged in with entity id 18 at (31.633505119415872, 155.99871477078705, 83.2330660082498)
[15:35:25] [Server thread/INFO] (Minecraft) Player24 joined the game
```

Total elapsed from launch to world-joined was well under a minute in this
run — no multi-minute Loom remap wait was observed (Loom had apparently
already remapped this dependency set from Task 1's work). No crash, no
`crash-reports/` or `hs_err_pid*.log` entries were produced by this run.

## Step 4: Assert both mods loaded (checkpoint 1)

```
$ grep -iE "^\s*-\s*voxyworldgenv2|voxyworldgenv2 [0-9]" /tmp/tandem-load.log | head -5
	- voxyworldgenv2 2.2.4

$ grep -iE "cloth[-_]config" /tmp/tandem-load.log | head -5
	- cloth-config 15.0.140
[15:35:18] [Render thread/INFO] (Minecraft) Reloading ResourceManager: vanilla, fabric, chunky, cloth-config, fabric-api, fabric-api-base, fabric-api-lookup-api-v1, fabric-biome-api-v1, fabric-block-api-v1, fabric-block-view-api-v2, fabric-blockrenderlayer-v1, fabric-client-tags-api-v1, fabric-command-api-v1, fabric-command-api-v2, fabric-commands-v0, fabric-content-registries-v0, fabric-convention-tags-v1, fabric-convention-tags-v2, fabric-crash-report-info-v1, fabric-data-attachment-api-v1, fabric-data-generation-api-v1, fabric-dimensions-v1, fabric-entity-events-v1, fabric-events-interaction-v0, fabric-game-rule-api-v1, fabric-gametest-api-v1, fabric-item-api-v1, fabric-item-group-api-v1, fabric-key-binding-api-v1, fabric-keybindings-v0, fabric-lifecycle-events-v1, fabric-loot-api-v2, fabric-loot-api-v3, fabric-message-api-v1, fabric-model-loading-api-v1, fabric-networking-api-v1, fabric-object-builder-api-v1, fabric-particles-v1, fabric-recipe-api-v1, fabric-registry-sync-v0, fabric-renderer-api-v1, fabric-renderer-indigo, fabric-renderer-registries-v1, fabric-rendering-data-attachment-v1, fabric-rendering-fluids-v1, fabric-rendering-v0, fabric-rendering-v1, fabric-resource-conditions-api-v1, fabric-resource-loader-v0, fabric-screen-api-v1, fabric-screen-handler-api-v1, fabric-sound-api-v1, fabric-transfer-api-v1, fabric-transitive-access-wideners-v1, fabricloader, lithium, modmenu, sodium, spark, voxy, voxyworldgenv2

$ grep -i "voxy world gen initialized" /tmp/tandem-load.log
[15:35:25] [Server thread/INFO] (voxyworldgenv2) voxy world gen initialized
```

Matches expected exactly: `voxyworldgenv2` at version `2.2.4`, `cloth-config`
at `15.0.140`, and the `voxy world gen initialized` line present. **Checkpoint
1: PASS.**

For completeness, the full mod-loading sequence for voxyworldgenv2 client-side
was also visible earlier in the log:

```
[15:35:16] [Render thread/INFO] (voxyworldgenv2) voxy world gen v2 initializing
[15:35:16] [Render thread/INFO] (voxyworldgenv2) voxy networking initialized (protocol 2)
[15:35:16] [Render thread/INFO] (voxyworldgenv2) initializing voxy world gen v2 client
```

## Step 5: Assert no mixin damage

```
$ grep -iE "mixin apply|failed to apply|could not apply|MixinApplyError|InvalidInjectionException" \
    /tmp/tandem-load.log | grep -vi "succeeded" | head -20
```

**Output: empty.** No mixin apply failures, no `MixinApplyError`, no
`InvalidInjectionException`. The mod's five mixins (`ChunkMapMixin`,
`MinecraftServerMixin`, `ServerChunkCacheMixin`, `ServerLevelMixin`,
`BlockUpdateMixin`) did not collide with Voxy's mixins.

## Step 6: Assert the reflection bridge resolved (checkpoint 2)

```
$ grep "voxy integration initialized" /tmp/tandem-load.log
[15:35:25] [Voxy-WorldGen-Worker/INFO] (voxyworldgenv2) voxy integration initialized (enabled: true, raw: true, voxyEnabled: true)
```

`raw: true` — the field that decides this checkpoint per the brief — is
present. `voxyEnabled: true` confirms `VoxyConfig.CONFIG` and
`isRenderingEnabled()` were found. **Checkpoint 2: PASS.**

**Flagging as unexpected:** the brief's tie-breaker note predicted
`enabled: false` as "the correct, healthy value," reasoning that this Voxy
backport's `enqueueIngest(WorldEngine, LevelChunk)` (two-argument) method
would not match the companion mod's one-argument probe. The actual line
shows `enabled: true` instead. No corresponding `voxy rawIngest is
unavailable (...)` warning appears anywhere in the log (confirmed via
`grep -i "rawIngest is unavailable" /tmp/tandem-load.log` → no output), so
this isn't the failure path the brief describes either — it's a third
outcome the brief didn't enumerate. Per the brief's own instruction ("only
`raw: true` decides the checkpoint"), this does not block checkpoint 2, and
per its explicit STOP condition ("if raw: false, STOP"), it does not trigger
a stop since `raw` is `true`. Reported here rather than diagnosed further,
per the "do not attempt a fix" instruction — this task's scope is launch
verification, not investigating why `enabled` differs from prediction.

> **[Superseded — see "Fix Round 1 § Finding 1" at the bottom of this
> file.]** The "third outcome"/open-mystery framing above is resolved:
> `enabled: true` is the correct, healthy value, confirmed against Voxy's
> source. Kept above verbatim as the historical record of what was observed
> and reasoned at commit time.

## Step 7: Shut the client down

```
$ pkill -f devlaunchinjector || true
$ sleep 3
$ ls -la versions/1.21.1-fabric/run/config/voxyworldgenv2.json
-rw-r--r--@ 1 alextodd  staff  432 Aug  8 15:35 versions/1.21.1-fabric/run/config/voxyworldgenv2.json
```

Config file exists, confirmed at stock defaults for Task 3 to edit:

```json
{
  "enabled": true,
  "showF3MenuStats": true,
  "generationRadius": 64,
  "update_interval": 20,
  "maxQueueSize": 20000,
  "maxActiveTasks": 20,
  "maxMbpsPerPlayer": 2.0,
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

(Note: this file's own `"enabled": true` is the companion mod's *config*
default for its generation-ahead feature — a separate flag from the
`enabled:` field inside the "voxy integration initialized" log line
discussed in Step 6, which reflects the reflective-ingest-path probe
result, not this config value.)

Process confirmed fully stopped:

```
$ ps -Ao pid,args | grep -c "[d]evlaunchinjector"
0
```

No new crash artifacts from this run — everything in
`versions/1.21.1-fabric/run/crash-reports/` and the `hs_err_pid*.log` files
in `versions/1.21.1-fabric/run/` are dated Aug 5, before this run (Aug 8).

> **[Incomplete — see "Fix Round 1 § Finding 2" at the bottom of this
> file.]** "Shut down cleanly" above was checked only in the narrow sense
> that no *new* crash-report/hs_err files appeared. It omitted a literal
> `BUILD FAILED` in the log tail, which is disclosed and explained (benign)
> in Fix Round 1.

## Anything else unexpected in the log

None of the following blocked either checkpoint; noted for completeness per
the brief's "anything unexpected" instruction:

- **Expected Zink/KosmicKrisp warning, present as documented:**
  ```
  WARNING: Some incorrect rendering might occur because the selected Vulkan device (Apple M2 Max) doesn't support base Zink requirements: have_EXT_custom_border_color have_EXT_line_rasterization
  ```
- Voxy's own optional-integration mixins (`iris.*`, `nvidium.*`,
  `flashback.*`) logged `ClassNotFoundException`/"was not found" warnings at
  `[15:35:11]` because Iris, Nvidium, and Flashback are not present in this
  mod set. These are Voxy's pre-existing conditional mixins for optional
  shader/recording mods, unrelated to voxyworldgenv2 or this task's mixins.
- `[15:35:25] [Server thread/WARN] (voxyworldgenv2) tellus not found for sampling integration: com.yucareux.tellus.worldgen.EarthChunkGenerator` —
  voxyworldgenv2 probes for an optional Tellus integration; Tellus isn't in
  this mod set, so this is an expected no-op warning, not an error.
- `[15:35:26] [Async Node Manager/WARN] (Voxy) [me.cx.vy.ct.ce.rg.hl.NodeManager]: Tried processing a node that already has a request in flight: 2127 pos: 1@[2, 0, 4] ignoring` —
  internal Voxy render-graph warning, appears benign/self-recovering (single
  occurrence).
- Sodium's own version-string warning (`${version}` not SemVer) and the
  `voxy.refmap.json ... could not be read (dev environment ... ignore)`
  warnings are pre-existing dev-environment noise unrelated to this task.

## Summary

| Checkpoint | Result |
|---|---|
| 1. Both mods loaded, no mixin damage | PASS — `voxyworldgenv2 2.2.4`, `cloth-config 15.0.140`, `voxy world gen initialized` present; mixin-damage grep empty |
| 2. Reflection bridge resolved | PASS — `raw: true`, `voxyEnabled: true`; `enabled: true` differs from the brief's predicted `enabled: false` (see Step 6 note), not a blocker |

`versions/1.21.1-fabric/run/config/voxyworldgenv2.json` was created at stock
defaults, ready for Task 3.

---

## Fix Round 1 (review response)

Two Important findings and one Minor finding came back from review of this
report. No client was re-run for this round — everything below is drawn
from the same `/tmp/tandem-load.log` already captured (still present on
disk, untouched since Step 7). Steps 6 and 7 above are left as originally
written, marked with pointers to this section, per the reviewer's
instruction to keep the original observation visible as history rather than
silently rewriting it as though it were right the first time.

### Finding 1 (Important): the Step 6 "unmodeled third outcome" is resolved — `enabled: true` is correct

Step 6 above treated `enabled: true` as an open mystery ("a third outcome
the brief didn't enumerate"). That framing is wrong and superseded.

**Ground truth, verified directly in Voxy's source:**

```
$ sed -n '194,198p' src/main/java/me/cortex/voxy/common/world/service/VoxelIngestService.java
    //Try to automatically ingest the chunk into the correct world
    public static boolean tryAutoIngestChunk(LevelChunk chunk) {
        return tryIngestChunk(WorldIdentifier.of(chunk.getLevel()), chunk);
    }
```

`tryAutoIngestChunk(LevelChunk chunk)` is `public static` and takes exactly
one argument — it is the one-argument ingest method `VoxyIntegration`
probes for. So the probe succeeds and `enabled: true` is the correct,
healthy value, not a coincidence or gap.

The spec's original prediction of `enabled: false` traced to a grep pattern
(`public .*Ingest(`) that required `Ingest` to be followed immediately by
`(`. That matched `rawIngest(` but missed `tryAutoIngestChunk(` (the literal
substring is `Ingest` followed by `Chunk`, not `(`), so the pattern never
saw the real one-argument method and the spec concluded — wrongly — that no
such method existed. This is corrected in commit `20cf2b06` ("docs: correct
the 'integration gaps' claim - there are none" — verified present in this
repo's history), which withdraws the "three known integration gaps" claim
and everything that depended on it in
`.superpowers/sdd/2026-08-08-worldgen-tandem/plans/2026-08-08-worldgen-tandem.md`
and `.superpowers/sdd/2026-08-08-worldgen-tandem/specs/2026-08-08-worldgen-tandem-design.md`.

**The one real consequence, for Task 3/4:** because `voxyEnabled: true` also
resolved (i.e. `VoxyConfig.CONFIG` and `isRenderingEnabled()` were actually
found via reflection, not stubbed out), `isVoxyRenderingEnabled()` genuinely
consults Voxy's live config rather than short-circuiting to a constant. That
means chunk generation reaching this path is gated on `enabled` /
`enable_rendering` staying `true` in `voxy-config.json` — it is a real,
live dependency, not a no-op. Anyone editing that config in Task 3 should
keep this in mind.

Checkpoint 2's result is unchanged (PASS) — only the reason is now
correctly understood instead of an open question.

### Finding 2 (Important): undisclosed `BUILD FAILED` at shutdown

Step 7's "shut down cleanly" claim was true only in the narrow sense
actually checked (no *new* `crash-reports/` or `hs_err_pid*.log` files). It
omitted a literal `BUILD FAILED` sitting in the log tail, which the brief's
"report anything unexpected" instruction exists to catch. Disclosing it now.

Literal `tail -30 /tmp/tandem-load.log`, captured against the same log file
(still present, unchanged since Step 7 — confirmed via `ls -la
/tmp/tandem-load.log` showing the original Aug 8 15:36 mtime before this
read):

```
[15:35:26] [Render thread/WARN] (Voxy) [me.cx.vy.ct.ce.gl.sr.Shader$Builder]: 0:451(40): warning: some implementations may not support implicit int -> uint conversions for `&' operators; consider casting explicitly for portability

[15:35:26] [Render thread/INFO] (Voxy) [me.cx.vy.ct.ce.VoxyRenderSystem]: Voxy render system created with 536870912 geometry capacity, using pipeline 'NormalRenderPipeline' with renderer 'MDICSectionRenderer'
[15:35:26] [Render thread/INFO] (ChunkBuilder) Started 6 worker threads
[15:35:26] [Render thread/INFO] (Voxy) [me.cx.vy.cl.VoxyInstance]: Dedicated voxy thread pool size: 2
[15:35:26] [Render thread/INFO] (Minecraft) Loaded 2 advancements
[15:35:26] [Render thread/INFO] (voxyworldgenv2) uploaded 0 known LOD regions for minecraft:overworld in 1 packet(s)
[15:35:26] [Server thread/INFO] (voxyworldgenv2) Player24 reports 0 known chunks in minecraft:overworld; skipping re-send
[15:35:35] [Async Node Manager/WARN] (Voxy) [me.cx.vy.ct.ce.rg.hl.NodeManager]: Tried processing a node that already has a request in flight: 2127 pos: 1@[2, 0, 4] ignoring
[15:35:45] [Server thread/INFO] (voxyworldgenv2) generating [minecraft:overworld]: 208 done @ 22.4/s, 12544 remaining in radius (~9m 20s), 0 active, 16 skipped, 0 failed
[15:35:55] [Server thread/INFO] (voxyworldgenv2) generating [minecraft:overworld]: 240 done @ 3.2/s, 12512 remaining in radius (~65m 10s), 0 active, 16 skipped, 0 failed
[15:36:05] [Server thread/INFO] (voxyworldgenv2) generating [minecraft:overworld]: 272 done @ 3.2/s, 12480 remaining in radius (~65m 00s), 0 active, 16 skipped, 0 failed
WARN StatusConsoleListener Unable to register Log4j shutdown hook because JVM is shutting down. Using SimpleLogger

> Task :1.21.1-fabric:runClient FAILED

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':1.21.1-fabric:runClient'.
> Process 'command '/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin/java'' finished with non-zero exit value 143 (this value may indicate that the process was terminated with the SIGTERM signal)

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights from a Build Scan (powered by Develocity).
> Get more help at https://help.gradle.org.

BUILD FAILED in 1m 9s
12 actionable tasks: 3 executed, 9 up-to-date
```

**Why this is benign:** Step 7's own shutdown command is
`pkill -f devlaunchinjector`, which sends SIGTERM to the Gradle-forked JVM
running the game. Gradle's `runClient` task observes its child process exit
with a non-zero code — 143 = 128 + 15, where 15 is `SIGTERM`'s signal number
— and reports the task, and therefore the whole build invocation, as
`FAILED`. This is simply what killing a `runClient` process from the
outside looks like from Gradle's perspective; there is no other way to end
`runClient` (it's a long-running foreground game process, not something
that exits on its own). It is not a build breakage, compile error, test
failure, or crash — it postdates every checkpoint assertion. Steps 4-6's
grep evidence is all timestamped `15:35:1x`-`15:35:2x`; the `BUILD FAILED`
text appears only after the `15:36:05` generation-progress line, i.e. after
`pkill` was sent in Step 7, well after every checkpoint had already been
captured. Task 3 (and Task 4) will use this same `pkill`-based shutdown and
should expect the identical `BUILD FAILED (exit 143)` signature at the end
of their logs — it is not a regression when it appears there either.

As a secondary observation surfaced by pasting the full tail (outside this
task's scope, but visible in the same evidence and directly relevant to
what Task 3/4 verify): server-side generation was already running by
shutdown time —
`generating [minecraft:overworld]: 272 done @ 3.2/s, 12480 remaining in
radius (~65m 00s), 0 active, 16 skipped, 0 failed` — meaning the ingest path
whose reflection bridge Finding 1 confirms was live and producing chunks
during this same launch, ahead of Task 3's config work.

### Minor: Step 3's literal `tail -30` output

Step 3 above substituted curated excerpts for the literal `tail -30` output
at the moment the Monitor wait condition fired. `/tmp/tandem-load.log`
still exists (confirmed above), so here is the actual, unedited
`tail -30 /tmp/tandem-load.log` output as captured for this fix round —
this is the same command and file used for Finding 2 immediately above,
since the log file kept accumulating lines (generation progress, then the
`BUILD FAILED` block) all the way through Step 7's shutdown, so a `tail -30`
run now necessarily reflects the end of the file's life, not the exact byte
offset at the instant Step 3's wait condition fired. That earlier moment's
evidence was not lost, though — it's the literal grep output already quoted
in Step 3 and Step 4 above (`Loaded 1399 advancements`, `Player24 joined
the game`, the mod-list `- voxyworldgenv2 2.2.4` line, etc.), all pulled
directly from this same file with `grep`/`sed`, not paraphrased. This
section corrects the specific claim of having pasted a literal `tail -30`
in Step 3 when curated excerpts were pasted instead — it does not claim to
recover the exact `tail -30` window from that earlier moment, since that
window no longer exists as a `tail` of the (longer) file today.
