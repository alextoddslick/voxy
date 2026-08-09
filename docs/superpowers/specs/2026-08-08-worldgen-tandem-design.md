# Running Voxy World Gen V2 alongside Voxy under Zink — design

**Date:** 2026-08-08
**Branch:** `worldgen-tandem` (off `multiversion`)

## Goal

Load `voxyworldgenv2` into Voxy's 1.21.1-fabric dev client on macOS and prove the full loop end to
end: the mod background-generates chunks, its LOD payloads reach Voxy's `VoxelIngestService`, and
Voxy renders that distant terrain — all under Zink + KosmicKrisp.

This is newly possible. `voxy_worldgen_v2/HANDOFF.md` records that client testing had to happen on
a Windows machine "because macOS caps at OpenGL 4.1 and Voxy needs 4.3+". The Zink work in
`docs/macos.md` removed that constraint, so the pairing can now be exercised locally.

## Context

| | |
|---|---|
| Voxy | this repo, fork of `m3t4f1v3/voxy` — the exact backport lineage the mod targets |
| Voxy World Gen V2 | `~/temp/Github-NOTSYNCED/voxy_worldgen_v2`, fork of `iSeeEthan/voxy_worldgen_v2` |
| Worldgen branch | `feature/client-lod-memory` @ `1f1a965` (2026-08-07), clean tree, 20 commits ahead of `backport/1.21.1` |
| Worldgen artifact | `build/libs/Voxy World Gen V2-1.21.1-2.2.4.jar` |

Both are Minecraft 1.21.1 / Java 21.

`feature/client-lod-memory` is chosen over the stable `backport/1.21.1` because it is the newest
work and the thing most worth validating. Its protocol-v2 change normally makes client/server jar
pairing delicate, but singleplayer runs one jar in one JVM on both sides, so no mismatch surface
exists here.

### Why the two mods compose cleanly

- Worldgen reaches Voxy purely by reflection on fully-qualified `me.cortex.voxy.*` names. There is
  no compile-time dependency in either direction.
- Its five mixins (`ChunkMapMixin`, `MinecraftServerMixin`, `ServerChunkCacheMixin`,
  `ServerLevelMixin`, `BlockUpdateMixin`) all target vanilla classes. No overlap with Voxy's mixins.
- It is one universal jar (`environment: *`, both `main` and `client` entrypoints), so a single
  copy in the dev client covers the integrated server and the client.
- Its declared dependencies are already satisfied by Voxy's dev runtime: loader `>=0.16.0`
  (Voxy dev has 0.19.2), `fabric-api: *` (full API, 0.116.12+1.21.1), ModMenu 11.0.3 (exact match).
  Only Cloth Config is missing.

## Design

### Wiring: a `-Pworldgen` Gradle flag

A block in `build.fabric.gradle.kts`'s `dependencies { }`, mirroring the `-P` idiom the `zinkRun`
block already establishes:

```kotlin
if (project.hasProperty("worldgen")) {
    val jar = file((findProperty("worldgenJar") as String?)
        ?: "$rootDir/../voxy_worldgen_v2/build/libs/Voxy World Gen V2-1.21.1-2.2.4.jar")
    if (!jar.exists()) throw GradleException(
        "worldgen jar not found at $jar - build it in the voxy_worldgen_v2 repo " +
        "(JAVA_HOME=\$(/usr/libexec/java_home -v 21) ./gradlew build), " +
        "or point at another with -PworldgenJar=<path>")
    modRuntimeOnly(files(jar))
    modRuntimeOnly("me.shedaniel.cloth:cloth-config-fabric:15.0.140")
}
```

Loom remaps the production jar's intermediary names to the dev environment's named mappings.
`maven.shedaniel.me` is already configured in `settings.gradle.kts`. The block is inert without the
flag, so ordinary builds and CI are untouched.

Cloth Config is included even though `fabric.mod.json` does not declare it: only
`integration/ModMenuIntegration` imports `me.shedaniel.clothconfig2`, so the mod loads fine without
it and then fails with `NoClassDefFoundError` the moment the config screen is opened.

Launch (the script forwards trailing arguments to `runClient`):

```bash
./scripts/macos/run-zink-client.sh -Pworldgen -PvoxyDebugStats -PquickPlayWorld="<world>"
```

### Rejected alternatives

- **Drop jars into `versions/1.21.1-fabric/run/mods/`.** Zero build changes, fastest one-off. But
  `run/` is gitignored, so nothing is reproducible or documented; Cloth Config must be fetched by
  hand; the jar must be re-copied after every worldgen rebuild.
- **Composite build (`includeBuild`).** Best if both mods needed editing in one invocation. But
  worldgen is a Groovy buildscript on Loom 1.14.10 while Voxy is Stonecutter on a different Loom;
  two mappings/Loom setups in one composite is fragile and risks a long detour before any testing.

### Runtime configuration

Two worldgen defaults must be changed in `versions/1.21.1-fabric/run/config/voxyworldgenv2.json`
or the run will look broken when it is not:

| Field | Stock | Test value | Why |
|---|---|---|---|
| `maxMbpsPerPlayer` | `2.0` | `0` (unlimited) | 2 Mbps ≈ 10 chunks/s. `voxy_worldgen_v2/HANDOFF.md` records this exact value making LOD delivery "look broken" on LAN. Meaningless on loopback. |
| `generationRadius` | `64` | `16` | Radius 64 is ~16.6k chunks, on a machine already paying the Zink translation cost. Raise it only once the loop is proven. |

Voxy's own `run/config/voxy-config.json` stays at stock defaults — `docs/macos.md` records that a
detuned `section_render_distance` or `service_threads` stops distant LOD layers rendering at all,
which would be indistinguishable from a worldgen failure.

### Verification

Four checkpoints, each with its own log evidence, so a failure localises to one stage:

| Stage | Evidence |
|---|---|
| Both mods load | `voxyworldgenv2` in the loader's mod list; all five mixins apply; client reaches the title screen |
| Integration resolves | `voxy integration initialized (enabled: true, raw: true, voxyEnabled: true)` — all three true; `raw: true` is the field the LOD path depends on. See Integration surface below. |
| Generation runs | `logProgress` lines (10 s interval): dimension, chunks done, chunks/s, remaining-in-radius with ETA |
| Ingest and render | `LodMemory` records columns under `run/voxyworldgenv2/lodmemory/<worldkey>/`, and `-PvoxyDebugStats` shows `quadCount` / `hierarchicalRenderSections` non-zero and growing on LOD layers beyond layer 0 |

Per `docs/macos.md`: capture stderr (`2>&1 | tee`), and use `sample`, never `jstack`, if anything
hangs.

## Integration surface

> **Correction (2026-08-08, during Task 2).** An earlier revision of this section claimed three
> integration gaps, all derived from the premise that `VoxyIntegration.enabled` would stay `false`
> because this backport has no single-argument ingest method. **That premise was wrong.**
> `VoxelIngestService.tryAutoIngestChunk(LevelChunk)` exists at line 195 — `public static`, exactly
> the one-argument shape the probe looks for. The claim came from a grep whose pattern
> (`public .*Ingest(`) required `Ingest` to be immediately followed by `(`, so it matched
> `rawIngest(` and missed `tryAutoIngestChunk(`. The live run confirms the truth:
> `voxy integration initialized (enabled: true, raw: true, voxyEnabled: true)`.
>
> There are no integration gaps. `isVoxyAvailable()` returns true so the tab-list HUD reports
> "voxy: enabled" correctly; `ingestChunk()` resolves so the server-side direct-ingest path is live
> alongside the network path; and `isVoxyRenderingEnabled()` genuinely consults
> `VoxyConfig.CONFIG.isRenderingEnabled()` rather than short-circuiting. That last one has a
> practical consequence for testing: generation really is gated on Voxy's render state, so
> `enabled`/`enable_rendering` in `voxy-config.json` must stay true or the worker will idle.

Every reflected member lines up:

| Reflected member | Present in this Voxy |
|---|---|
| `VoxelIngestService.rawIngest(WorldIdentifier, LevelChunkSection, int, int, int, DataLayer, DataLayer)` | yes, `public static`, exact signature |
| `WorldIdentifier.of(Level)` | yes, `public static` |
| `VoxyConfig.CONFIG` (static singleton) | yes |
| `VoxyConfig.isRenderingEnabled()` | yes, instance method — resolvable via `CONFIG` |

These are reported as findings, not fixed, unless the loop proves to depend on one.

## Scope

**In:** the `-Pworldgen` block; the documented run procedure; executing the four-checkpoint
verification; a short `docs/macos.md` section recording how to run the pairing and what was
observed.

**Out:** changes to the `voxy_worldgen_v2` repo; macOS/Zink-specific performance work on the
combined workload; Voxy code changes; any non-1.21.1-fabric version. If the loop fails, fix it only
where the fix is small and clearly within this scope — otherwise report and stop.

## Success criteria

All four verification checkpoints pass in one session, and
`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :1.21.1-fabric:build` still succeeds.

> **Correction (2026-08-08, after Tasks 3 and 3b).** They did not all pass in one session. The real
> outcome, per `task-3-report.md`, `task-3b-report.md`, and `docs/macos.md`'s "Running with Voxy
> World Gen V2" section:
>
> - **Checkpoint 3 (generation runs): PASS.** Clean `generating [...]`/`generation caught up` lines,
>   0 failures, across every launch attempted.
> - **Checkpoint 4a (LOD data reached Voxy ingest): PASS, demonstrated once.** Task 2's run (stock
>   config, world join 15:35:26) wrote a non-empty `lodmemory/.../minecraft_overworld.bin` during
>   its own lifetime via `LodMemory.tick()`'s debounce flush — direct proof `rawIngest` accepted at
>   least one column that session. It was not reproduced in any later run: every subsequent attempt
>   stalled in the render-thread fence wait before a tick-flush could fire and was then SIGKILLed
>   before a shutdown flush could run either. One column-level success, once — not a robustly
>   re-verified pathway. The build did succeed (Task 1's build.fabric.gradle.kts change), so that
>   half of this line held.
> - **Checkpoint 4b (distant LOD terrain rendering): FAIL.** The render thread locks in a GPU fence
>   wait roughly 13 seconds after world join and the client stops drawing frames — isolated as
>   specific to the pairing by the Task 3b A/B (Voxy alone never enters the stall; Voxy +
>   `voxyworldgenv2` is in it within 13s every time).
>
> Fixing the stall is out of scope for this spec — see `docs/macos.md` for the full evidence and the
> open next question. Do not read the unamended line above as the actual result.
