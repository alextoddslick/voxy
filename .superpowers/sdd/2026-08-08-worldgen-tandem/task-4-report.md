# Task 4 report: Document the pairing

**Bottom line up front:** Both required edits landed in `docs/macos.md` — the new
`## Running with Voxy World Gen V2` section immediately before `## Debugging`, and a dated
correction to the Status section's "Multi-minute sessions run without hanging" claim, scoping it
to Voxy alone and pointing at the new section. The build passes. Only `docs/macos.md` was
modified — confirmed via `git diff --stat` after the edits (see below).

## Step 1: the new section

Inserted `## Running with Voxy World Gen V2` immediately before `## Debugging` (verified via
`grep -n "^## " docs/macos.md`, which shows the new heading at line 290, directly followed by
`## Debugging` at line 370).

Also amended the Status section: added a `> **Correction (2026-08-08).**` blockquote directly
after the existing `> **Correction (2026-08-07).**` block (which corrects an unrelated hang
misdiagnosis), scoping "Multi-minute sessions run without hanging" to Voxy running alone and
linking to `#running-with-voxy-world-gen-v2`. This follows the document's established pattern of a
dated correction blockquote rather than silently editing the original claim.

## Where I departed from the brief's draft text, and why

The brief's Step 1 instructions explicitly said to use the real observed numbers from
`task-3-report.md` and `task-3b-report.md` and not carry over any illustrative figure the reports
contradict. I checked every number and factual claim in the draft markdown block against the three
evidence sources (`task-3-report.md`, `task-3b-report.md`, `stall-sample-counts.txt`) and made the
following corrections:

1. **`kk_timeline_wait` sample counts, "Voxy alone" row.** Draft said "1 (an ordinary transient
   fence wait)". The actual same-session A/B (`task-3b-report.md`'s Arm A) took three samples with
   counts 1, 2 (two distinct call sites), and 0 out of ~1100+ main-thread samples each. Rounding
   to a single "1" undercounts what's in the report and drops the third (zero) sample entirely.
   Replaced with "1–2 out of ~1100+ per sample (3 samples: 1, 2, 0)", which is the BLUF's own
   phrasing in `task-3b-report.md` ("only 1-2 out of ~1100+ main-thread samples ever touching
   `kk_timeline_wait`, and 0 in the third sample").

2. **`kk_timeline_wait` sample counts, "Voxy + worldgen" row.** Draft said "~1650–1900". The
   same-session A/B's Arm B samples (the specific experiment this table is describing, per the
   surrounding prose "isolated with a same-session A/B") are 1648, 1807, 1713, 1727 — range
   1648–1807, all at 100%. The ~1650–1900 figure appears to blend in the *separate*,
   memory-pressure-confounded `task-3-report.md` attempts (1879, 1898, 1842, from
   `stall-sample-counts.txt`'s `tandem-sample*.txt` entries), which are a different experiment run
   under ~96MB free memory, not the controlled A/B. Corrected the table to the A/B's own numbers
   (1648–1807) and added a separate sentence noting the earlier confounded run's solid-black
   window as a distinct data point rather than conflating the two.

3. **"Window" cell, "Voxy + worldgen" row.** Draft said "frozen / black", implying the two are
   interchangeable. `task-3b-report.md` is explicit that these are different symptoms: the
   controlled A/B's Arm B screenshot showed a static, colorful, non-black frozen frame ("a
   different visual symptom than the 'solid black' window reported in the earlier confounded task,
   and I'm calling that difference out explicitly rather than smoothing over it"), while the solid
   black window was only observed in `task-3-report.md`'s confounded, memory-pressured/screen-lock
   run. Corrected to "frozen on the last good frame (not black — see caution below)" plus a
   sentence in the prose distinguishing the two runs explicitly, per the report's own instruction
   not to smooth this over.

4. **Chunk-generation count.** Draft said "480 chunks with zero failures across repeated runs".
   `task-3-report.md`'s three attempts all show exactly 480 chunks generated (0 failed each time),
   but `task-3b-report.md`'s Arm B shows 576 chunks generated (0 failed). Presenting "480" as the
   sole figure would contradict Arm B. Corrected to "480–576 chunks with zero failures across four
   separate launches (three in the initial evidence pass, one more in the A/B below)".

5. **LOD-ingest/delivery paragraph.** This one wasn't a numeric contradiction so much as a risk the
   task's own instructions flagged explicitly: don't claim LOD delivery was verified, since every
   run in both reports was SIGKILLed before `LodMemory.flush()` ever ran, and the on-disk `.bin`
   file is a stale artifact from an earlier session (`task-3-report.md`'s Fix Round 1, Finding 1,
   corrects that exact checkpoint to UNPROVEN). The draft's opening sentence — "Delivery into Voxy
   is confirmed by non-empty `.bin` files..." — read, out of context, as a claim that delivery had
   been confirmed, even though the very next sentences (mtime caution, quadCount caution) already
   implied otherwise. To remove any ambiguity I changed "is confirmed by" to "would be confirmed
   by" and added an explicit sentence: "this checkpoint is **unproven** from every run captured so
   far: every attempt ended in `SIGKILL`, which the JVM cannot intercept or run shutdown hooks
   against, so neither the tick-gated flush nor the graceful-disconnect flush ever ran, and the one
   `.bin` file present on disk predates every run in this investigation by hours." This is sourced
   directly from `task-3-report.md`'s Fix Round 1 § Finding 1.

Everything else in the draft block — the setup/build instructions, the `-Pworldgen`/
`-PworldgenJar` mechanics, the Cloth Config 15.0.140 note, the two config-value callouts
(`maxMbpsPerPlayer`, `generationRadius`), the "reading the logs" section, the stack trace, and the
"not the previously-fixed `u_vbuf` hang" framing — matched the evidence reports (and, for the
`-Pworldgen` mechanics, `task-1-report.md`) and was kept as given.

## Step 2: build verification

```
$ JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :1.21.1-fabric:build --console=plain 2>&1 | tail -5
> Task :1.21.1-fabric:validateAccessWidener UP-TO-DATE
> Task :1.21.1-fabric:check UP-TO-DATE
> Task :1.21.1-fabric:build UP-TO-DATE

BUILD SUCCESSFUL in 7s
14 actionable tasks: 14 up-to-date
```

## Scope check

```
$ git diff --stat
 docs/macos.md | 81 +++++++++++++++++++++++++++++++++++++++++++++++++++++++++
 1 file changed, 81 insertions(+)
```

Only `docs/macos.md` was touched. `build.fabric.gradle.kts`, Java source, the sibling
`voxy_worldgen_v2` repo, and everything under `run/` are untouched.

## Overall plan status (Tasks 1–4)

Per the brief's amended "Done When": Task 3's checkpoint 3 (generation) is a clean PASS,
checkpoint 4a (LOD ingest) is UNPROVEN (every run SIGKILLed before flush could run), and
checkpoint 4b (distant-LOD rendering) is a FAIL (render thread stalls in a GPU fence wait ~13s
after world join, confirmed by the Task 3b same-session A/B). `docs/macos.md` now records both the
render stall and the A/B that isolated it to the pairing rather than to Zink/KosmicKrisp generally.
The spec's originally-claimed "three integration gaps" were a false alarm from a bad grep and are
not reintroduced anywhere in this doc. Fixing the stall itself is out of scope for this plan; the
documented next step is finding which GL call the ingest path makes that the Voxy-alone path does
not.
