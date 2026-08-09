# Final fix-wave report: worldgen-tandem branch review response

Applies the fix wave from the final whole-branch review. Every finding below was verified against
primary sources (`task-2-report.md`, `task-3-report.md`, `task-3b-report.md`, the sibling repo's
`LodMemory.java`/`NetworkClientHandler.java`, and live Gradle runs) before editing, not just
reworded on the reviewer's say-so.

## C1 (Critical) — `docs/macos.md` LOD-delivery verdict corrected

Verified independently:
- `versions/1.21.1-fabric/run/voxyworldgenv2/lodmemory/436c2ac36846adbb/minecraft_overworld.bin`
  is 556 bytes, mtime Aug 8 15:36.
- Task 2's run joined the world at 15:35:26 and its own log shows `BUILD FAILED in 1m 9s` with
  exit 143 (SIGTERM, from the task's own `pkill` in Step 7) — i.e. it ended at ≈15:36:20, and the
  `.bin` file's 15:36 mtime falls *inside* that run's lifetime, not hours before it.
- `LodMemory.tick()` flushes on a 30-second debounce during ordinary play (confirmed by reading
  `feature/client-lod-memory`'s `LodMemory.java`: `dirty` is set only inside `record()`, and
  `flush()` early-returns unless `dirty`). `record()` is called from exactly one site,
  `NetworkClientHandler.java:104-105`, guarded by `if (allIngested)`.
- Task 3's three attempts (earliest launch 15:53:09, 17 minutes after the `.bin`'s mtime) all
  ended in SIGKILL (exit 137) before any tick-flush or disconnect-flush could run.

Rewrote the paragraph in `docs/macos.md` (## Running with Voxy World Gen V2) to state: LOD
delivery was demonstrated once (Task 2's run, stock config, 15:36), and was not reproduced in any
later run because those clients stalled before a tick-flush could fire and were then SIGKILLed.
Kept the practical caution about stale files and killed clients writing nothing. Did not overstate
this as a robustly re-verified pathway — one column-level success, once.

Checked the rest of `docs/macos.md` for other summaries of this outcome (grepped for
`unproven|SIGKILL|predates every run|delivery`) — no other passage restated the old, incorrect
verdict, so no further propagation was needed within that file. Also added a matching correction
blockquote to `docs/superpowers/specs/2026-08-08-worldgen-tandem-design.md`'s Success Criteria
(see Minor 6 below), since that section's checkpoint-4a language would otherwise still imply an
unqualified pass.

## I1 (Important) — companion-mod branch/commit pinned

Confirmed directly against the sibling repo (read-only):
- `feature/client-lod-memory` contains `src/main/java/com/ethan/voxyworldgenv2/client/LodMemory.java`.
- `origin/backport/1.21.1` contains **no** file matching `lodmemory` anywhere in its tree.
- The sibling repo's `build/libs/` today holds only `1.21.11` and `26.1.2` artifacts (it has moved
  on to a 26.1.2 port on branch `port/26.1.2`), confirming it can drift out from under the doc.

`docs/macos.md` now states explicitly that this section was tested against
`alextoddslick/voxy_worldgen_v2` @ `feature/client-lod-memory` commit `1f1a965`, that
`backport/1.21.1` lacks `LodMemory` entirely (so the delivery check doesn't apply there), and that
a reader must `git checkout 1f1a965` rather than trust whatever is currently checked out. The build
recipe now includes `git checkout 1f1a965` before `./gradlew build`.

## I2 (Important) — subproject guard added to `build.fabric.gradle.kts`

Added `project.name == "1.21.1-fabric" &&` to the `-Pworldgen` block's guard condition, with a
comment explaining why (Stonecutter shares this buildscript with `1.20.1-fabric`; the companion
mod is 1.21.1-only). Verified both required behaviors with a real jar from the sibling repo's
current `build/libs` (`-PworldgenJar=` override, since the discovery default finds no
`1.21.1`-named jar there right now):

### Verification 1 — `-Pworldgen` still works on `:1.21.1-fabric`

```
$ JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :1.21.1-fabric:dependencies --configuration runtimeClasspath --console=plain -Pworldgen -PworldgenJar="/Users/alextodd/temp/Github-NOTSYNCED/voxy_worldgen_v2/build/libs/Voxy World Gen V2-1.21.11-2.2.4.jar"
```

Result: `remapped.me.shedaniel.cloth:cloth-config-fabric-37f46004:15.0.140` and
`remapped.unspecified:Voxy World Gen V2-1.21.11-2.2.4-37f46004:3b2a503721` both appear in the
resolved `runtimeClasspath`, Loom remap succeeds, `worldgen: adding ... to the client dev runtime`
prints once. `BUILD SUCCESSFUL in 10s`.

### Verification 2 — `:1.20.1-fabric:dependencies --configuration runtimeClasspath -Pworldgen` no longer pulls in the jar or Cloth Config

```
$ JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :1.20.1-fabric:dependencies --configuration runtimeClasspath --console=plain -Pworldgen -PworldgenJar="/Users/alextodd/temp/Github-NOTSYNCED/voxy_worldgen_v2/build/libs/Voxy World Gen V2-1.21.11-2.2.4.jar"
```

Result: the resolved `Project ':1.20.1-fabric'` `runtimeClasspath` tree contains **zero**
occurrences of `cloth-config` or `Voxy World Gen V2` (`grep -iE "cloth-config|Voxy World Gen V2"`
over the tree section after `> Task :1.20.1-fabric:dependencies` → no matches). The
`worldgen: adding ...` lifecycle line does still print once during this invocation, but it fires
during Gradle's normal cross-project configuration pass for the *sibling* `1.21.1-fabric` project
(it appears in the log before `> Task :1.20.1-fabric:dependencies` even starts) — confirmed by line
position, not assumed. That project legitimately resolves the block for itself; `1.20.1-fabric`'s
own dependency graph is untouched. `BUILD SUCCESSFUL in 5s`.

Before the fix, both invocations printed the lifecycle line **twice** per invocation (once per
`*-fabric` subproject evaluated during configuration) and `1.20.1-fabric`'s own `runtimeClasspath`
did include the jar and Cloth Config, remapped against 1.20.1 mappings. After the fix, the
lifecycle line prints once per invocation (only the matching subproject's block body executes),
and `1.20.1-fabric`'s resolved classpath is clean.

## Minor findings

1. Reworded the "Nothing here suggests the companion mod is at fault..." sentence in
   `docs/macos.md` to state plainly that the A/B already establishes the *outcome* (the stall) is
   specific to the pairing, while keeping the fair distinction that the *trigger* (a GPU
   firmware-detected lockup/restart) may itself be a platform-level event — the pairing changes
   whether the render thread recovers from it, not necessarily whether it happens.
2. The layer-1 `quadCount` range in the A/B table is now scoped to "while actively in-world," with
   a note that the final two samples (16:24:47, 16:24:52 — taken after the run logged "Saving and
   pausing game" at 16:24:40) dropped to 1509 during the unload/menu transition, matching
   `task-3b-report.md`'s own quoted lines.
3. "never produced a second frame" → "never produced another frame" in the Status blockquote.
4. Added that Arm A's *third* GPU firmware-lockup event (16:24:53) coincided to the second with
   Arm A's own end-of-run death, and that the death itself is unconfirmed as a memory-pressure
   kill versus an unsurvived GPU-firmware restart — so the "recovers cleanly from two such events"
   framing now carries that caveat before the next sentence leans on it.
5. Added the four Arm B `kk_timeline_wait` sample-count lines (1648, 1807, 1713, 1727) to
   `stall-sample-counts.txt`, sourced directly from `/tmp/ab-armB-sample-{1,2,3,4}.txt` (confirmed
   present on disk and re-extracted with the same `grep kk_timeline_wait | sort -rn | head -1`
   method already used for the other entries in that file) — these are the exact numbers
   `docs/macos.md`'s A/B table cites.
6. Added a dated correction blockquote to `docs/superpowers/specs/2026-08-08-worldgen-tandem-design.md`'s
   Success Criteria section, in the same style as its own "Integration surface" correction and the
   plan's "Done When" amendment: checkpoint 3 PASS, checkpoint 4a PASS-demonstrated-once (per the
   C1 correction), checkpoint 4b FAIL.

## Final build verification

```
$ JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :1.21.1-fabric:build --console=plain
Daemon will be stopped at the end of the build
Running Stonecutter 0.9.4
> Task :buildSrc:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :buildSrc:compileKotlin UP-TO-DATE
> Task :buildSrc:compileJava NO-SOURCE
> Task :buildSrc:compileGroovy NO-SOURCE
> Task :buildSrc:pluginDescriptors UP-TO-DATE
> Task :buildSrc:processResources NO-SOURCE
> Task :buildSrc:classes UP-TO-DATE
> Task :buildSrc:jar UP-TO-DATE

> Configure project :1.20.1-fabric
Fabric Loom: 1.16.2

> Configure project :1.21.1-fabric
Fabric Loom: 1.16.2

> Task :1.21.1-fabric:stonecutterPrepare UP-TO-DATE
> Task :1.21.1-fabric:stonecutterGenerate UP-TO-DATE
> Task :1.21.1-fabric:compileJava UP-TO-DATE
> Task :1.21.1-fabric:processResources UP-TO-DATE
> Task :1.21.1-fabric:classes UP-TO-DATE
> Task :1.21.1-fabric:jar UP-TO-DATE
> Task :1.21.1-fabric:processIncludeJars UP-TO-DATE
> Task :1.21.1-fabric:remapJar UP-TO-DATE
> Task :1.21.1-fabric:sourcesJar UP-TO-DATE
> Task :1.21.1-fabric:remapSourcesJar UP-TO-DATE
> Task :1.21.1-fabric:assemble UP-TO-DATE
> Task :1.21.1-fabric:stonecutterPrepareTest UP-TO-DATE
> Task :1.21.1-fabric:stonecutterGenerateTest NO-SOURCE
> Task :1.21.1-fabric:compileTestJava NO-SOURCE
> Task :1.21.1-fabric:processTestResources NO-SOURCE
> Task :1.21.1-fabric:testClasses UP-TO-DATE
> Task :1.21.1-fabric:test NO-SOURCE
> Task :1.21.1-fabric:validateAccessWidener UP-TO-DATE
> Task :1.21.1-fabric:check UP-TO-DATE
> Task :1.21.1-fabric:build UP-TO-DATE

BUILD SUCCESSFUL in 6s
14 actionable tasks: 14 up-to-date
```

`UP-TO-DATE` across the board is expected and correct: the `-Pworldgen` block is inert without the
flag (this invocation didn't pass it), so nothing about the ordinary `1.21.1-fabric:build` output
changed as a result of adding the subproject guard.

The sibling repo `voxy_worldgen_v2` was not checked out, built, committed, or modified at any point
in this fix wave, per the hard constraint — it was only read (`git ls-tree`, `git show`) to verify
the branch/commit claims above.
