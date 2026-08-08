# Voxy + Voxy World Gen V2 Tandem Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Load `voxyworldgenv2` into Voxy's 1.21.1-fabric dev client behind a `-Pworldgen` flag and prove the full generate → ingest → render loop works under Zink + KosmicKrisp on this Mac.

**Architecture:** One optional block in `build.fabric.gradle.kts` adds the sibling repo's built jar plus Cloth Config to the dev runtime only when `-Pworldgen` is passed. Verification is then four log-evidence checkpoints across two client launches: the first proves both mods load and the reflection bridge resolves, the second proves chunks generate, reach Voxy's ingest, and render as distant LOD terrain.

**Tech Stack:** Gradle 9.4.1 + Stonecutter 0.9.4 + Fabric Loom (Kotlin DSL), Fabric Loader 0.19.2, Minecraft 1.21.1, Java 21, Mesa Zink + KosmicKrisp.

**Spec:** `docs/superpowers/specs/2026-08-08-worldgen-tandem-design.md`

## Global Constraints

- Java 21 always, resolved explicitly: every Gradle command is prefixed `JAVA_HOME=$(/usr/libexec/java_home -v 21)`. Gradle 9.4.1 + Stonecutter 0.9.4 refuse to run on Java 17.
- Every code change ends with a passing `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :1.21.1-fabric:build`.
- Work happens on branch `worldgen-tandem` in `/Users/alextodd/temp/Github-NOTSYNCED/voxy`.
- The `-Pworldgen` block must be completely inert when the flag is absent. Ordinary builds and CI cannot change behaviour.
- No changes to the `voxy_worldgen_v2` repo. It is read and built, never edited.
- Worldgen source of truth: `/Users/alextodd/temp/Github-NOTSYNCED/voxy_worldgen_v2`, branch `feature/client-lod-memory` @ `1f1a965`.
- Voxy's own `versions/1.21.1-fabric/run/config/voxy-config.json` stays at stock defaults (`section_render_distance: 16.0`, `service_threads: 8`). A detuned value stops distant LOD layers rendering and would look identical to a worldgen failure.
- Client launches always go through `./scripts/macos/run-zink-client.sh` (it sets `-PzinkRun -PgeomBufMB=512`), always capture stderr, and always run in the background — it is a GUI process that never returns.
- If anything hangs, diagnose with `/usr/bin/sample`, never `jstack`. See `docs/macos.md`.
- `run/` is gitignored. Nothing under it is ever committed; evidence goes into report files instead.

## File Structure

| File | Responsibility |
|---|---|
| `build.fabric.gradle.kts` (modify, `dependencies { }` block, after the `flashback` line at ~286) | The entire `-Pworldgen` wiring: jar discovery, existence guard, Cloth Config |
| `docs/macos.md` (modify, new section before `## Debugging`) | User-facing record of how to run the pairing and what it does |
| `.superpowers/sdd/2026-08-08-worldgen-tandem/task-2-report.md` (create) | Launch-1 evidence: mod list, mixins, integration resolution |
| `.superpowers/sdd/2026-08-08-worldgen-tandem/task-3-report.md` (create) | Launch-2 evidence: generation, ingest, render counters |

Only one production file changes. The reports exist because the deliverable of Tasks 2 and 3 is *evidence*, and `run/` (where the raw logs live) is gitignored.

---

### Task 1: Wire `-Pworldgen` into the dev runtime

**Files:**
- Modify: `build.fabric.gradle.kts` (insert into `dependencies { }` after line ~286, `modCompileOnly("maven.modrinth:flashback:...")`, before `implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))`)
- Build (do not modify): `/Users/alextodd/temp/Github-NOTSYNCED/voxy_worldgen_v2`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: the Gradle property `-Pworldgen` (boolean flag, no value) and `-PworldgenJar=<absolute path>` (optional override). Tasks 2 and 3 launch with `-Pworldgen`.

- [ ] **Step 1: Confirm the worldgen repo is on the expected commit**

```bash
cd /Users/alextodd/temp/Github-NOTSYNCED/voxy_worldgen_v2
git branch --show-current   # expect: feature/client-lod-memory
git rev-parse --short HEAD  # expect: 1f1a965
git status --short          # expect: no output (clean tree)
```

If the branch or commit differs, STOP and report — do not check out anything. Someone changed the repo since the spec was written and the plan needs revisiting.

- [ ] **Step 2: Build the worldgen jar**

```bash
cd /Users/alextodd/temp/Github-NOTSYNCED/voxy_worldgen_v2
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build --console=plain
ls -la "build/libs/Voxy World Gen V2-1.21.1-2.2.4.jar"
```

Expected: `BUILD SUCCESSFUL`, and the jar exists with a timestamp from this run. This branch has a JUnit suite (28 tests); `build` runs it, and a failing test produces no jar. If tests fail, STOP and report — that is a pre-existing problem in the other repo and out of scope here.

- [ ] **Step 3: Write the failing check**

The assertion is that Cloth Config 15.0.140 appears in the `modRuntimeOnly` configuration when the flag is passed. Gradle's dependency report does not list `files(...)` dependencies, so Cloth Config is the signal that the block ran; the jar itself is proven by the existence guard in Step 5 and by the mod list in Task 2.

```bash
cd /Users/alextodd/temp/Github-NOTSYNCED/voxy
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :1.21.1-fabric:dependencies \
  --configuration modRuntimeOnly --console=plain -Pworldgen 2>&1 \
  | grep -c "cloth-config-fabric:15.0.140"
```

- [ ] **Step 4: Run it to verify it fails**

Expected output: `0`. `-Pworldgen` is currently an unrecognised property, so the build succeeds and Cloth Config is absent.

- [ ] **Step 5: Implement the block**

Insert into `dependencies { }` in `build.fabric.gradle.kts`, immediately after the `modCompileOnly("maven.modrinth:flashback:${prop("deps.flashback")}")` line:

```kotlin
    // Optional dev-runtime pairing with Voxy World Gen V2, the companion mod that background-
    // generates chunks and streams LOD data into Voxy's ingest service. Off unless -Pworldgen is
    // passed, so ordinary builds and CI are untouched. That mod is one universal jar
    // (environment "*", main + client entrypoints), so this single copy serves both the
    // integrated server and the client in singleplayer - which also means there is no
    // client/server protocol-version mismatch to worry about here.
    // See docs/superpowers/specs/2026-08-08-worldgen-tandem-design.md.
    // Usage: ./scripts/macos/run-zink-client.sh -Pworldgen
    if (project.hasProperty("worldgen")) {
        val worldgenLibs = file("$rootDir/../voxy_worldgen_v2/build/libs")
        val explicitJar = project.findProperty("worldgenJar") as String?
        val worldgenJar = if (explicitJar != null) {
            file(explicitJar)
        } else {
            worldgenLibs.listFiles()
                ?.filter {
                    it.name.startsWith("Voxy World Gen V2-1.21.1-") &&
                        it.name.endsWith(".jar") &&
                        !it.name.endsWith("-sources.jar")
                }
                ?.maxByOrNull { it.lastModified() }
                ?: throw GradleException(
                    "-Pworldgen: no Voxy World Gen V2 jar found in $worldgenLibs. Build it there " +
                        "with `JAVA_HOME=\$(/usr/libexec/java_home -v 21) ./gradlew build`, or " +
                        "point at one with -PworldgenJar=<absolute path>.")
        }
        if (!worldgenJar.exists()) {
            throw GradleException("-PworldgenJar=$worldgenJar does not exist")
        }
        logger.lifecycle("worldgen: adding ${worldgenJar.name} to the client dev runtime")
        modRuntimeOnly(files(worldgenJar))
        // Cloth Config is NOT declared in that mod's fabric.mod.json, so the mod loads happily
        // without it and then throws NoClassDefFoundError the moment its config screen is opened -
        // integration/ModMenuIntegration is the only class importing me.shedaniel.clothconfig2.
        // maven.shedaniel.me is already configured in settings.gradle.kts.
        modRuntimeOnly("me.shedaniel.cloth:cloth-config-fabric:15.0.140")
    }
```

- [ ] **Step 6: Run the check to verify it passes**

```bash
cd /Users/alextodd/temp/Github-NOTSYNCED/voxy
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :1.21.1-fabric:dependencies \
  --configuration modRuntimeOnly --console=plain -Pworldgen 2>&1 \
  | grep -c "cloth-config-fabric:15.0.140"
```

Expected: `1` or greater, and the `worldgen: adding Voxy World Gen V2-1.21.1-2.2.4.jar to the client dev runtime` lifecycle line appears in the full output.

If Loom errors while remapping because of the spaces in the jar filename, copy it to a space-free path and use the override — this is the one known filename hazard:

```bash
cp "/Users/alextodd/temp/Github-NOTSYNCED/voxy_worldgen_v2/build/libs/Voxy World Gen V2-1.21.1-2.2.4.jar" \
   /tmp/voxyworldgenv2-1.21.1-2.2.4.jar
# then add: -PworldgenJar=/tmp/voxyworldgenv2-1.21.1-2.2.4.jar
```

Record in the commit message if this workaround was needed.

- [ ] **Step 7: Verify the missing-jar guard fails loudly**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :1.21.1-fabric:dependencies \
  --configuration modRuntimeOnly --console=plain \
  -Pworldgen -PworldgenJar=/tmp/definitely-not-here.jar 2>&1 | tail -20
```

Expected: `BUILD FAILED` containing `-PworldgenJar=/tmp/definitely-not-here.jar does not exist`.

- [ ] **Step 8: Verify the block is inert without the flag**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :1.21.1-fabric:dependencies \
  --configuration modRuntimeOnly --console=plain 2>&1 | grep -c "cloth-config"
```

Expected: `0`.

- [ ] **Step 9: Verify the ordinary build still passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :1.21.1-fabric:build --console=plain 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 10: Commit**

```bash
cd /Users/alextodd/temp/Github-NOTSYNCED/voxy
git add build.fabric.gradle.kts
git commit -m "build: -Pworldgen to load Voxy World Gen V2 into the dev client

Adds the sibling repo's built jar plus Cloth Config 15.0.140 (which that
mod needs for its config screen but does not declare) to the 1.21.1-fabric
client dev runtime, only when -Pworldgen is passed. -PworldgenJar overrides
jar discovery. Inert without the flag.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: Launch 1 — both mods load and the reflection bridge resolves

Covers spec checkpoints 1 and 2. This launch also creates `run/config/voxyworldgenv2.json`, which Task 3 edits.

**Files:**
- Create: `.superpowers/sdd/2026-08-08-worldgen-tandem/task-2-report.md`
- Read (gitignored): `versions/1.21.1-fabric/run/logs/latest.log`, `/tmp/tandem-load.log`

**Interfaces:**
- Consumes: `-Pworldgen` from Task 1.
- Produces: `versions/1.21.1-fabric/run/config/voxyworldgenv2.json` at stock defaults, for Task 3 to edit.

- [ ] **Step 1: Clear any leftover client**

```bash
pkill -f devlaunchinjector || true
sleep 2
ps -Ao pid,args | grep -c "[d]evlaunchinjector"
```

Expected: `0`.

- [ ] **Step 2: Launch the client with the world auto-joined**

```bash
cd /Users/alextodd/temp/Github-NOTSYNCED/voxy
rm -f /tmp/tandem-load.log
nohup nice -n 15 ./scripts/macos/run-zink-client.sh -Pworldgen \
  -PquickPlayWorld="New World" > /tmp/tandem-load.log 2>&1 &
echo "launched"
```

`--quickPlaySingleplayer` cannot be passed on the `gradlew` command line — Gradle reads it as an option to the `runClient` task. `-PquickPlayWorld` is the supported route. `nice -n 15` is per `docs/macos.md`, to keep an unfocused Zink window from driving `WindowServer` CPU.

- [ ] **Step 3: Wait for the world to load**

Wait until the log shows the world joined, up to ~5 minutes (the first Loom run remaps dependencies
and is slow). Use the **Monitor tool** with an until-condition on the log — foreground `sleep` is
blocked in this harness, so a shell poll loop will not work:

```
Monitor: until `grep -qE "Loaded .* advancements|Started serving on|Time elapsed:" /tmp/tandem-load.log`
         timeout 300s
```

Then inspect:

```bash
tail -30 /tmp/tandem-load.log
```

If the client crashes instead, capture the reason before doing anything else: Java-level crashes land in `versions/1.21.1-fabric/run/crash-reports/`, native ones in `versions/1.21.1-fabric/run/hs_err_pid<pid>.log`.

- [ ] **Step 4: Assert both mods loaded (checkpoint 1)**

```bash
grep -iE "^\s*-\s*voxyworldgenv2|voxyworldgenv2 [0-9]" /tmp/tandem-load.log | head -5
grep -iE "cloth[-_]config" /tmp/tandem-load.log | head -5
grep -i "voxy world gen initialized" /tmp/tandem-load.log
```

Expected: `voxyworldgenv2` appears in Fabric Loader's mod list with version `2.2.4`; `cloth-config` appears; and the line `voxy world gen initialized` is present.

- [ ] **Step 5: Assert no mixin damage**

```bash
grep -iE "mixin apply|failed to apply|could not apply|MixinApplyError|InvalidInjectionException" \
  /tmp/tandem-load.log | grep -vi "succeeded" | head -20
```

Expected: no output. The mod's five mixins (`ChunkMapMixin`, `MinecraftServerMixin`, `ServerChunkCacheMixin`, `ServerLevelMixin`, `BlockUpdateMixin`) all target vanilla classes and must not collide with Voxy's.

- [ ] **Step 6: Assert the reflection bridge resolved (checkpoint 2)**

```bash
grep "voxy integration initialized" /tmp/tandem-load.log
```

Expected exactly: `voxy integration initialized (enabled: true, raw: true, voxyEnabled: true)`

All three fields true. `raw: true` is the one the LOD network path depends on. `enabled: true` means
the probe found `VoxelIngestService.tryAutoIngestChunk(LevelChunk)` (line 195, `public static`), so
the server-side direct-ingest path is live too. `voxyEnabled: true` means it found
`VoxyConfig.CONFIG` and `isRenderingEnabled()` — note this makes generation genuinely gated on
Voxy's render state, so `enabled` and `enable_rendering` in `voxy-config.json` must stay true.

If `raw: false`, STOP — the LOD path cannot work, and the reason will be in the following `voxy rawIngest is unavailable (...)` warning. Report it rather than guessing at a fix.

- [ ] **Step 7: Shut the client down**

```bash
pkill -f devlaunchinjector || true
sleep 3
ls -la versions/1.21.1-fabric/run/config/voxyworldgenv2.json
```

Expected: the config file exists (Task 3 needs it).

- [ ] **Step 8: Write the evidence report**

Create `.superpowers/sdd/2026-08-08-worldgen-tandem/task-2-report.md` containing: the exact `voxy integration initialized` line; the `voxyworldgenv2` and `cloth-config` mod-list lines; a statement that the mixin grep was empty (or its output if not); and anything unexpected observed in the log. Paste real output — do not paraphrase.

- [ ] **Step 9: Commit**

```bash
git add .superpowers/sdd/2026-08-08-worldgen-tandem/task-2-report.md
git commit -m "test: launch 1 evidence - both mods load, rawIngest resolves

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: Launch 2 — generation, ingest, and render

Covers spec checkpoints 3 and 4.

**Files:**
- Modify (gitignored, not committed): `versions/1.21.1-fabric/run/config/voxyworldgenv2.json`
- Create: `.superpowers/sdd/2026-08-08-worldgen-tandem/task-3-report.md`

**Interfaces:**
- Consumes: the config file from Task 2, `-Pworldgen` from Task 1.
- Produces: the verified result the whole plan exists for.

- [ ] **Step 1: Retune the two worldgen config values**

Set `maxMbpsPerPlayer` to `0` and `generationRadius` to `16` in `versions/1.21.1-fabric/run/config/voxyworldgenv2.json`, leaving every other field alone:

```bash
cd /Users/alextodd/temp/Github-NOTSYNCED/voxy
python3 - <<'PY'
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
```

The recursive walk is because these fields live on a nested settings object, not necessarily at the top level. Stock `maxMbpsPerPlayer` is `2.0` (≈10 chunks/s — the value `voxy_worldgen_v2/HANDOFF.md` records as making LOD delivery "look broken" on LAN, and meaningless on loopback). Stock `generationRadius` is `64`, about 16.6k chunks.

- [ ] **Step 2: Confirm Voxy's own config is untouched**

```bash
grep -E "section_render_distance|service_threads" versions/1.21.1-fabric/run/config/voxy-config.json
```

Expected: `"section_render_distance": 16.0,` and `"service_threads": 8,`. If either differs, restore it — `docs/macos.md` records that detuned values stop distant LOD layers rendering entirely, which would be indistinguishable from a worldgen failure.

- [ ] **Step 3: Launch with statistics forced on**

```bash
pkill -f devlaunchinjector || true
sleep 2
rm -f /tmp/tandem-run.log
nohup nice -n 15 ./scripts/macos/run-zink-client.sh -Pworldgen -PvoxyDebugStats \
  -PquickPlayWorld="New World" > /tmp/tandem-run.log 2>&1 &
echo "launched"
```

`-PvoxyDebugStats` sets `-Dvoxy.forceStatistics=true`, which is the only way to see `RenderStatistics` without driving the F3 overlay by hand.

- [ ] **Step 4: Let it run for about 8 minutes**

Use the **Monitor tool** to wait — foreground `sleep` is blocked in this harness. Wait either for
generation to finish or for the 8-minute cap, whichever comes first:

```
Monitor: until `grep -q "generation caught up" /tmp/tandem-run.log`
         timeout 480s   (a timeout here is fine — it just means the radius is still filling)
```

Then:

```bash
grep -c "generating \[" /tmp/tandem-run.log
```

Radius 16 is 33×33 ≈ 1089 chunks per dimension; progress logs every 10 s. Expected: a non-zero count.

- [ ] **Step 5: Assert generation is running (checkpoint 3)**

```bash
grep "generating \[" /tmp/tandem-run.log | tail -10
grep "generation caught up" /tmp/tandem-run.log
```

Expected: lines of the form
`generating [minecraft:overworld]: 412 done @ 8.3/s, 677 remaining in radius (~1m 21s), 12 active, 0 skipped, 0 failed`
with `done` increasing across successive lines. A `[TPS-THROTTLED]` or `[PAUSED: player loading]` suffix is informational, not a failure — but note it. `generation caught up: N chunks this session` appearing means the radius was fully filled, which is a stronger pass.

If every line reads `0 done` with a rising `failed` count, STOP and report the first stack trace in the log.

- [ ] **Step 6: Assert LOD data reached Voxy (checkpoint 4a)**

```bash
find versions/1.21.1-fabric/run/voxyworldgenv2/lodmemory -name "*.bin" -exec ls -la {} \;
grep -i "voxy rawIngest is unavailable" /tmp/tandem-run.log
```

Expected: at least one `.bin` file with non-zero size under a world-key directory, and **no** `rawIngest is unavailable` warning. `LodMemory` records a column only when `VoxelIngestService.rawIngest` returned true for every one of its sections, so a non-empty file is direct proof that Voxy accepted the data.

- [ ] **Step 7: Assert distant LOD terrain is rendering (checkpoint 4b)**

```bash
grep "RenderStatistics" /tmp/tandem-run.log | tail -5
```

Expected lines of the form:
`RenderStatistics (per LOD layer, layer 0 = closest): hierarchicalTraversalCounts=[...] hierarchicalRenderSections=[...] visibleSections=[...] quadCount=[...]`

The pass condition is **non-zero entries at index 1 or beyond** in `quadCount` and `hierarchicalRenderSections` — layer 0 alone is near-field terrain Voxy would draw regardless, so it proves nothing about worldgen. Compare the earliest and latest lines: the distant-layer values should grow as generation proceeds.

- [ ] **Step 8: Look at the actual window**

The client is running at 854×480 on screen. Look at it, or take a screenshot, and confirm distant terrain is visibly present rather than fog or void. The counters can be non-zero while the picture is wrong; this is the only step that catches that.

- [ ] **Step 9: Shut down and check for late failures**

```bash
pkill -f devlaunchinjector || true
sleep 3
ls versions/1.21.1-fabric/run/crash-reports/ 2>/dev/null | tail -3
grep -icE "exception|error" /tmp/tandem-run.log
grep -iE "exception|error" /tmp/tandem-run.log | grep -vi "errorprone" | head -20
```

Note anything new. Some log noise is normal; a fresh crash report dated today is not.

- [ ] **Step 10: Write the evidence report**

Create `.superpowers/sdd/2026-08-08-worldgen-tandem/task-3-report.md` with, as real pasted output: the config values used; three or more `generating [...]` lines showing progress over time; the `lodmemory` file listing; the first and last `RenderStatistics` lines with the distant-layer values called out; what the window actually looked like; and every anomaly seen, including ones that did not block the result.

- [ ] **Step 11: Commit**

```bash
git add .superpowers/sdd/2026-08-08-worldgen-tandem/task-3-report.md
git commit -m "test: launch 2 evidence - generation, ingest and distant LOD render

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: Document the pairing

**Files:**
- Modify: `docs/macos.md` (new `## Running with Voxy World Gen V2` section, inserted immediately before `## Debugging`)

**Interfaces:**
- Consumes: the verified results from Tasks 2 and 3 — the numbers written here must be the ones actually observed, not the plan's illustrative examples.

- [ ] **Step 1: Add the section**

Insert before `## Debugging` in `docs/macos.md` (the outer fence below is four backticks so the
inner ` ```bash ` block survives copy/paste — do not copy the outer fence itself):

````markdown
## Running with Voxy World Gen V2

[Voxy World Gen V2](https://github.com/iSeeEthan/voxy_worldgen_v2) background-generates chunks and
streams LOD data into Voxy's ingest service. Its 1.21.1 backport lives on the `backport/1.21.1`
branch of `alextoddslick/voxy_worldgen_v2` and pairs with this Voxy build. Until the Zink work
above, the pairing could only be tested from a Windows client, because macOS caps native OpenGL at
4.1 and Voxy needs 4.3+.

Build the mod in its own checkout, then launch this client with `-Pworldgen`:

```bash
cd ../voxy_worldgen_v2 && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build
cd -  && ./scripts/macos/run-zink-client.sh -Pworldgen -PvoxyDebugStats
```

`-Pworldgen` picks the newest `Voxy World Gen V2-1.21.1-*.jar` out of `../voxy_worldgen_v2/build/libs`
and adds it, plus Cloth Config 15.0.140, to the client dev runtime. Point it elsewhere with
`-PworldgenJar=<absolute path>`. Cloth Config is added because the mod needs it for its config
screen but does not declare it in `fabric.mod.json`. The block is inert without the flag.

The mod is one universal jar, so in singleplayer a single copy serves both the integrated server
and the client — none of the client/server protocol-version pairing rules in that repo's HANDOFF
apply here.

**Two config values to change** in `run/config/voxyworldgenv2.json`, or the run looks broken when
it is not: set `maxMbpsPerPlayer` to `0` (stock `2.0` is ≈10 chunks/s, meaningless on loopback and
known to make LOD delivery look dead), and drop `generationRadius` from `64` to about `16` while
testing — radius 64 is ~16.6k chunks on a machine already paying the Zink translation cost. Leave
Voxy's own `voxy-config.json` at stock defaults.

**Reading the logs.** `voxy integration initialized (enabled: true, raw: true, voxyEnabled: true)`
is the healthy line — all three fields true. `raw: true` carries the LOD network path,
`enabled: true` means the direct-ingest path resolved too, and `voxyEnabled: true` means the mod can
read Voxy's render state, which it uses to decide whether to generate at all: if `enabled` or
`enable_rendering` is false in `voxy-config.json`, the worker idles by design.
Generation progress logs every 10 s as
`generating [minecraft:overworld]: N done @ X/s, ...`. Delivery is confirmed by non-empty
`.bin` files under `run/voxyworldgenv2/lodmemory/`, which are written only for columns Voxy's
`rawIngest` fully accepted, and by non-zero `quadCount` at LOD layer 1 or beyond in the
`RenderStatistics` lines that `-PvoxyDebugStats` emits.
````

Replace the illustrative numbers with the real ones from Task 3's report where they differ.

- [ ] **Step 2: Verify the build still passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :1.21.1-fabric:build --console=plain 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add docs/macos.md
git commit -m "docs/macos: how to run Voxy World Gen V2 alongside Voxy under Zink

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Done When

All four spec checkpoints have passed with pasted log evidence in the task reports, `docs/macos.md`
documents the pairing, and `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :1.21.1-fabric:build`
succeeds. Then hand back to the user, noting that the spec's original "three known integration gaps"
turned out to be a false alarm from a bad grep — see the correction in the spec's Integration
surface section. There are no gaps to report.
