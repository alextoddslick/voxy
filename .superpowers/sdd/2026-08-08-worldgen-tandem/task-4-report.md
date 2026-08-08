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

---

## Fix Round 1 (review response)

One Critical and two Minor findings came back. All addressed below; nothing from the original
report is retracted except the specific errors identified.

### Critical: black-window sentence inverted the source report's screen-lock finding

The reviewer is right, and this was a real error, not a wording nit. `docs/macos.md`'s original
sentence read "A separate, earlier confounded run (heavy system memory pressure, screen locked)
showed a solid-black window instead of a frozen frame" — but the solid-black screenshot comes from
`task-3-report.md`'s **Attempt 3**, which states three separate times that the screen was
confirmed **unlocked** for that specific attempt (lines 241, 298-299, 438-439 of that report). The
screen-locked attempt was **Attempt 1**, which never produced a usable screenshot at all — its
`screencapture` output only ever caught the macOS lock screen, which that report explicitly calls
"not evidence of anything about the app."

This is the opposite of a minor mix-up: the correct reading is that the render stall blacked out
the window *despite* the screen being unlocked and actively in use (a YouTube video was on screen
at the time) — the more alarming finding, not a discountable one. My original sentence would have
led a future investigator to wrongly deprioritize the black-window symptom as a probable
screen-lock artifact.

I also over-claimed "heavy system memory pressure" as a concurrent condition for that same
screenshot. Checking `task-3-report.md` again: the only concrete low-memory reading tied to
Attempt 3 is the `vm_stat` free-page jump observed "immediately after" the process's death at
~16:13:50, and the screenshot was taken ~10-15s after the 16:12:18 `RenderStatistics` line, i.e.
~16:12:28-33 — roughly 80 seconds *before* that low-memory reading, not concurrent with it. Fixed
`docs/macos.md` (lines 317-325 as of this fix) to:

- Attribute the black-window screenshot correctly to Attempt 3, explicitly unlocked and in active
  use, and state that this rules out screen-lock as an explanation for that symptom rather than
  supporting it.
- Remove the "heavy system memory pressure" claim as concurrent, and instead note the low-memory
  reading is timestamped roughly 80 seconds after the screenshot — i.e. memory pressure at the
  moment the window went black is not established.
- Keep the original point intact where it was correct: the two visual outcomes (frozen frame in
  the Task 3b A/B vs. solid black in Task 3's Attempt 3) are genuinely different symptoms and
  neither should be assumed to generalize to the other.

### Minor 1: stale `git diff --stat` in the "Scope check" section

The `1 file changed, 81 insertions(+)` pasted in the original "Scope check" section above was taken
from an intermediate `git diff --stat` run before the doc edit was finalized, and was never
re-run before the report was written — so "confirmed via `git diff --stat`" wasn't actually
evidence of what shipped in the commit. The commit that actually landed (`f6f926b0`) is
`1 file changed, 87 insertions(+)`, confirmed via `git show --stat HEAD` immediately after that
commit (see the transcript). Leaving the original "Scope check" paragraph above uncorrected, as
historical record, per this section's job of noting the error rather than silently rewriting it.

This fix round's own doc edit (commit `8f7e0da3`) is `1 file changed, 20 insertions(+), 8
deletions(-)`, confirmed via `git diff --cached --stat` immediately before committing it. The
cumulative diff for `docs/macos.md` across both Task 4 commits, against the pre-Task-4 base
(`0c0ae127`), is `1 file changed, 99 insertions(+)` (`git diff --stat 0c0ae127 -- docs/macos.md`).

### Minor 2: "natural next step" paragraph was generic, carried over verbatim from the brief

The reviewer is right that `task-3b-report.md` has a sharper, more current hypothesis than the
generic "find which GL call the ingest path makes that the Voxy-alone path does not." That
report's Verdict section identifies a specific, testable correlation: both A/B arms independently
hit a macOS GPU firmware-detected-lockup event (`gpuEvent-java-*.ips`,
`restart_reason_desc: "firmware-detected lockup"`) at or immediately after world join, but Voxy
alone recovers cleanly from two such events and keeps rendering, while the paired run's only such
event (3 seconds after join) coincides almost exactly with the onset of the permanent stall.
Rewrote the "next step" paragraph in `docs/macos.md` to lead with this correlation and the sharper
question it raises — whether `voxyworldgenv2` leaves a GPU fence outstanding across that
lockup/restart boundary that Voxy-alone does not — while keeping the original "which GL call the
ingest path makes" framing folded in as the more general form of the same question, per the
reviewer's "instead of, or alongside" guidance.

### Build re-verification

```
$ JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :1.21.1-fabric:build --console=plain 2>&1 | tail -5
> Task :1.21.1-fabric:check UP-TO-DATE
> Task :1.21.1-fabric:build UP-TO-DATE

BUILD SUCCESSFUL in 5s
14 actionable tasks: 14 up-to-date
```

Everything the reviewer marked as independently verified and correct (sample counts,
`RenderStatistics` line counts, the 480-576 chunk range, the UNPROVEN framing of LOD delivery, the
layer-1 caution, and the absence of any "integration gaps" narrative) is unchanged from the
original submission.
