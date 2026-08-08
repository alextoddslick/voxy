# Task 2 Report: Launch 1 — both mods load and the reflection bridge resolves

Status: DONE_WITH_CONCERNS. Both spec checkpoints pass on their documented
criteria — checkpoint 1 (both mods loaded, no mixin damage) and checkpoint 2
(`raw: true`, the field that decides it). One value in the checkpoint-2 line
differs from what the brief predicted (`enabled: true` vs. the predicted
`enabled: false`); flagged below per the "report anything unexpected" rule.
It does not block the checkpoint per the brief's own tie-breaker ("only
`raw: true` decides the checkpoint").

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
