# macOS support (Zink + KosmicKrisp)

Voxy runs on Apple Silicon Macs by routing OpenGL through [Mesa's Zink](https://docs.mesa3d.org/drivers/zink.html)
driver, which translates GL calls to Vulkan, on top of Mesa's `kosmickrisp` Vulkan driver
(Apple's native Vulkan-on-Metal implementation upstreamed into Mesa). There is no bundled
native build — you build Mesa and a patched GLFW yourself and launch the dev client through a
Gradle property that wires up the environment for you.

This has been validated on an Apple M2 Max.

## Status

**Works:** a hardware-accelerated OpenGL 4.6 core-profile context
(`zink Vulkan 1.4(Apple M2 Max (MESA_KOSMICKRISP))`), worlds load, and the LOD pipeline renders
across layers — `RenderStatistics` shows non-zero `hierarchicalRenderSections`/`quadCount` for
layer 0 and beyond. Multi-minute sessions run without hanging. The zero-tail
multi-draw-indirect fallback (for GPUs without `GL_ARB_indirect_parameters`) also works and can
be exercised anywhere via `-PvoxyForceNoIndirectCount`.

> **Correction (2026-08-07).** Earlier revisions of this document described an unresolved
> KosmicKrisp `glFenceSync` driver hang with "no code-level workaround". **That was a
> misdiagnosis.** It was reached from `jstack` output, and `jstack` cannot see past the JNI
> boundary — every stack in that investigation truncated at
> `org.lwjgl.opengl.*.nglXxx(Native Method)`, so the actual blocking frame was never observed.
> Mesa runs GL on a native worker thread named `gl0` (glthread is on by default for Zink,
> `driinfo_zink.h`), which is not a JVM thread at all.
>
> A native `sample` of the process showed the real stack immediately: the block was
> `u_vbuf_draw_vbo -> tc_buffer_map -> zink_buffer_map -> batch_usage_wait`, a mid-draw buffer
> map stalling on GPU completion. `glFenceSync` appeared only as
> `_mesa_marshal_FenceSync -> _mesa_glthread_finish`, i.e. the sync point that forces glthread
> to drain a backlog containing the blocking draw. That also explains the "whack-a-mole" in the
> old investigation: the three supposedly distinct hang sites (`glClearNamedBufferSubData`,
> `glGetNamedFramebufferAttachmentParameteriv`, `glFenceSync`) are all glthread *sync points*
> that force a pipe flush, so removing one merely relocated the symptom to the next.
>
> Root cause: Voxy's occlusion-cull draw used 8-bit indices, which Metal cannot represent
> (`MTLIndexType` is uint16/uint32 only), forcing Mesa's `u_vbuf` fallback to CPU-read the
> indirect buffer every frame. Fixed by widening those indices to 16-bit. **Use
> `sample`/`lldb`, never `jstack`, on this stack.**

> **Correction (2026-08-08).** "Multi-minute sessions run without hanging" above holds for Voxy
> running alone. It does not hold for Voxy paired with Voxy World Gen V2: a same-session A/B on
> the same world showed the solo arm rendering normally for its entire run while the paired arm's
> render thread locked into a GPU fence wait ~13 seconds after world join and never produced
> another frame. See [Running with Voxy World Gen V2](#running-with-voxy-world-gen-v2) below for
> the full evidence.

**MoltenVK is a dead end today.** Zink negotiates the same GL 4.6 core context on top of
MoltenVK as it does on KosmicKrisp (Vulkan 1.4 vs. 1.3), and correctly detects and logs the
`GL_ARB_indirect_parameters` fallback — but the client then crashes with a deterministic native
`SIGSEGV` inside `libMoltenVK.dylib` (`MVKCmdBindGraphicsPipeline::isTessellationPipeline`)
during ordinary startup resource loading (a texture-atlas mipmap blit), before the world is ever
joined and before Voxy's render system is constructed. This reproduces every time. KosmicKrisp is
the only Vulkan backend on which Voxy actually runs on this platform; MoltenVK is left wired up
as a `-PvkDriver=moltenvk` option only in case a future Mesa/MoltenVK release fixes this crash.

**SSAO auto-downgrades to BASIC on KosmicKrisp.** `SSAOMode.AUTO` picks BETTER/BEST based on
reported dedicated GPU memory; KosmicKrisp reports Apple Silicon's unified memory as ~24GB of
"dedicated" memory via `GL_NVX_gpu_memory_info`, so AUTO always tries to pick BEST. BETTER/BEST
issues a per-frame `glGetNamedFramebufferAttachmentParameteriv`, a glthread sync point that
forces a pipe flush every frame, so on Zink/KosmicKrisp specifically
(`Capabilities.isKosmicKrisp`, not the broader `isZink` — this does not affect Zink on
Linux/RADV/ANV/NVK) `SSAOMode.AUTO` is forced to BASIC, with a log line explaining why.
Explicit user overrides to BETTER/BEST are not gated.

### Performance notes

Four fixes took this from a few FPS to usable; if you are reproducing the setup, all four
matter. Each was measured with `sample`, not guessed:

| Fix | Where | Effect |
|---|---|---|
| 16-bit occlusion-cull indices | `MDICSectionRenderer`, `SharedIndexBuffer`, `prep.comp` | `u_vbuf` mid-draw stall: 100% → 0.09% of main-thread samples |
| Upstream Mesa 26.3 (not the old fork base) | see build section | `mtl_new_heap` 1675 → 0, `kk_upload_descriptor_root` 1887 → 3, `vk_cmd_queue_execute` 2459 → 0 |
| Don't poll the fence just created | `UploadStream`, `DownloadStream` | targets `_mesa_GetSynciv`, previously 907/2657 main-thread samples |
| Keep `voxy-config.json` at stock defaults | `run/config/` | a detuned `section_render_distance` (3.875 vs 16) and `service_threads` (1 vs 8) stop distant LOD layers rendering at all |

The Mesa upgrade is the big one for the driver side: the fork's base predated upstream
`b8f0fe6bdca` ("kk: Allocate temporary command memory from pool"), so KosmicKrisp was creating a
whole `MTLHeap` per draw call to hold that draw's descriptor root, plus
`0cd84d45c60` ("kk: Record command buffers live and replay only on resubmit"), which removes the
command-replay pass on every submit.

## Prerequisites

- **Java 21**, resolved via `/usr/libexec/java_home -v 21` — Gradle 9.4.1 + Stonecutter 0.9.4
  refuse to run on Java 17 (a common default `JAVA_HOME` on macOS). Every command below and the
  launch script itself set `JAVA_HOME` explicitly for this reason.
- **Homebrew packages** (arm64):
  ```bash
  /opt/homebrew/bin/brew install meson ninja python3 bison flex llvm glslang spirv-tools molten-vk cmake libclc
  ```
  `libclc` and `cmake` are easy to miss — they aren't needed for a stock Mesa build, but
  `with_kosmickrisp_vk` requires `libclc` (OpenCL-C compute-shader lowering), and `cmake` is used
  as a meson dependency-finder fallback for several deps.

  **Dual-Homebrew warning:** if your Mac also has an x86_64 Homebrew at `/usr/local` (e.g. kept
  around for Rosetta), your shell's default `PATH` may resolve `brew`, `meson`, `pkg-config`, and
  `cmake` to the x86_64 copies at `/usr/local` even though version numbers look identical. This is
  not cosmetic: an x86_64 `meson`/`cc` pair configures Mesa as `Host machine cpu family: x86_64`
  even on Apple Silicon, silently producing an x86_64 build under Rosetta. Always invoke the
  arm64 Homebrew explicitly (`/opt/homebrew/bin/brew ...`) and, for every Mesa build command,
  prepend the arm64 paths ahead of everything else on `PATH`:
  ```bash
  export PATH="/opt/homebrew/bin:/opt/homebrew/sbin:/opt/homebrew/opt/llvm/bin:/opt/homebrew/opt/bison/bin:$PATH"
  ```
  Verify with `file` on the built `.dylib`s afterward (see below) if in doubt.

## Building Mesa (Zink + KosmicKrisp)

**Build from upstream Mesa, not the fork.** Upstream now has native macOS platform support
(`with_platform_macos`, Metal WSI in `platform_surfaceless.c`) plus months of KosmicKrisp
performance work the fork predates — see the performance notes above. Two pieces still have to
come from the fork on top of upstream:

- **`src/glwrapper/`** — builds `libGL.dylib` (which LWJGL `dlopen`s; upstream builds no libGL
  for macOS) and `libgl_interpose.dylib`. It also sets `MESA_EGL_LIBRARY` / `MESA_VULKAN_LIBRARY`
  by locating itself with `dladdr`, which is **required**: SIP strips `DYLD_LIBRARY_PATH` from
  hardened processes, and the Java launcher is one.
- **the EGL window-surface path** — upstream's macOS support covers Metal WSI for Vulkan but not
  the CAMetalLayer EGL window surface GLFW needs. `platform_surfaceless.c`, `eglapi.c` and
  `zink_kopper.h` can be taken from the fork wholesale (upstream has not touched them since the
  fork's base); `zink_kopper.c`, `kopper.c` and `zink_screen.c` need a 3-way merge.

The rebase is recorded on the `upstream-rebase` branch of the local `~/src/mesa` checkout; its
commit message lists exactly what was carried and what was dropped as obsolete. Reproduce with:

```bash
mkdir -p ~/src && cd ~/src
git clone https://github.com/lucamignatti/mesa.git
cd mesa
git remote add upstream https://gitlab.freedesktop.org/mesa/mesa.git
git fetch upstream main
git checkout -b upstream-rebase upstream/main
# then graft the two pieces above (see the upstream-rebase commit for the exact file list)

cat > native.ini <<'EOF'
[binaries]
bison = '/opt/homebrew/opt/bison/bin/bison'
EOF

export PATH="/opt/homebrew/bin:/opt/homebrew/sbin:/opt/homebrew/opt/llvm/bin:/opt/homebrew/opt/bison/bin:$PATH"
export PKG_CONFIG_PATH="/opt/homebrew/Cellar/libclc/22.1.8/share/pkgconfig:$PKG_CONFIG_PATH"
# libclc installs its .pc file under share/pkgconfig, not the usual lib/pkgconfig — meson
# won't find it without this. Adjust the version path to whatever `brew info libclc` shows.

meson setup build --native-file native.ini \
  -Dprefix=$HOME/mesa-native \
  -Dbuildtype=release \
  -Dplatforms=macos \
  -Degl-native-platform=surfaceless \
  -Degl=enabled \
  -Dgallium-drivers=zink \
  -Dvulkan-drivers=kosmickrisp \
  -Dgles1=enabled \
  -Dgles2=enabled \
  -Dglx=disabled \
  -Dgbm=disabled \
  -Dmoltenvk-dir=/opt/homebrew/opt/molten-vk

ninja -C build
ninja -C build install
```

Confirm the configure summary reports `Host machine cpu family: aarch64` (not `x86_64`) — this is
the tell for the dual-Homebrew PATH hazard above. `ninja -C build` runs ~1187 build steps and
takes roughly 30–60 minutes.

Mesa's DRI megadriver isn't automatically symlinked to the names EGL expects; create them, and
grab a Vulkan loader:

```bash
mkdir -p $HOME/mesa-native/lib/dri
cd $HOME/mesa-native/lib/dri
ln -sf ../libgallium-*.dylib zink_dri.so
ln -sf ../libgallium-*.dylib swrast_dri.so
cp /opt/homebrew/lib/libvulkan.1.dylib $HOME/mesa-native/lib/
```

(`libgallium-*.dylib` globs to whatever version string this Mesa checkout produces — e.g.
`libgallium-26.1.0-devel.dylib` — verify it's a single match before symlinking.)

**Verify the artifacts exist and are arm64:**

```bash
ls $HOME/mesa-native/lib/libGL.dylib $HOME/mesa-native/lib/libEGL.dylib \
   $HOME/mesa-native/share/vulkan/icd.d/kosmickrisp_mesa_icd.aarch64.json \
   $HOME/mesa-native/lib/dri/zink_dri.so
ls $HOME/mesa-native/lib | grep -i interpose   # libgl_interpose.dylib should exist

file $HOME/mesa-native/lib/libGL.dylib $HOME/mesa-native/lib/libEGL.dylib \
     $HOME/mesa-native/lib/dri/*.so $HOME/mesa-native/lib/libvulkan_kosmickrisp.dylib \
     $HOME/mesa-native/lib/libgl_interpose.dylib
# every line must read "Mach-O 64-bit dynamically linked shared library arm64"
```

## Building patched GLFW

A stock GLFW works logically but the CMake toolchain on a machine with dual Homebrew/Rosetta
tooling will silently produce an **x86_64** dylib on the first configure pass even on Apple
Silicon — you must force the architecture explicitly:

```bash
cd ~/src
git clone https://github.com/lucamignatti/glfw.git
cd glfw && mkdir -p build && cd build
cmake .. -DGLFW_BUILD_EXAMPLES=OFF -DGLFW_BUILD_TESTS=OFF -DBUILD_SHARED_LIBS=ON -DCMAKE_OSX_ARCHITECTURES=arm64
make -j8
```

Verify:

```bash
file ~/src/glfw/build/src/libglfw.3.dylib
# Mach-O 64-bit dynamically linked shared library arm64
```

If you get `x86_64` here, `rm -rf build` and reconfigure with the flag above — a plain re-run of
`cmake ..` without clearing `build/` will keep the cached (wrong) architecture.

## Running

Launch the 1.21.1-fabric dev client under Zink with:

```bash
./scripts/macos/run-zink-client.sh
```

This assumes Mesa is installed at `~/mesa-native` (previous step) and the patched GLFW at
`~/src/glfw/build/src/libglfw.3.dylib`. It sets `JAVA_HOME` to a Java 21 JDK, passes
`-PzinkRun`, and defaults `-PgeomBufMB=512` (see below) before forwarding any extra arguments you
pass on the command line, e.g.:

```bash
./scripts/macos/run-zink-client.sh -PquickPlayWorld="My World" -PvoxyDebugStats
```

### Why a Gradle property, not shell env vars

Shell `export DYLD_*`/`MESA_*`/`VK_*` variables and `-D` system properties passed on the
`./gradlew` command line do **not** reach the forked client JVM — Loom's `runClient` Exec task
neither inherits the invoking shell's environment nor forwards `-D` args given to `gradlew`
itself. Without the workaround below, the client silently comes up on Apple's native
`OpenGL 4.1 Metal` backend instead of Mesa/Zink. The fix (already wired into
`build.fabric.gradle.kts`) is a `loom { runs { named("client") { ... } } }` block, active only
when the `zinkRun` Gradle property is set, that injects environment variables and JVM args
directly into the run configuration via the Loom DSL — which does reach the forked JVM.

### `-P` properties (all consumed by the `zinkRun` block in `build.fabric.gradle.kts`)

| Property | Default | Purpose |
|---|---|---|
| `-PzinkRun` | — (must be passed) | Activates the whole macOS Zink/KosmicKrisp environment-injection block. Without it none of the below apply and you get the native Metal backend. |
| `-PvkDriver=<kosmickrisp\|moltenvk>` | `kosmickrisp` | Selects which Vulkan ICD Zink runs on top of (`VK_DRIVER_FILES`). Kept for driver-comparison experiments; MoltenVK crashes on startup today (see Status) so there's no reason to pass this in normal use. |
| `-PquickPlayWorld="<name>"` | unset | Auto-joins the named singleplayer world at launch (`--quickPlaySingleplayer <name>`), since the dev client window isn't practical to click through headlessly. Passing `--quickPlaySingleplayer` directly on the `./gradlew` command line does **not** work — Gradle treats it as an unknown option to the `runClient` task itself. |
| `-PvoxyForceNoIndirectCount` | unset | Sets `-Dvoxy.forceNoIndirectCount=true`, forcing Voxy's zero-tail MDI fallback path (normally only used when `GL_ARB_indirect_parameters` is absent) on any driver — useful for testing that path in isolation. |
| `-PgeomBufMB=<n>` | `512` (set by the launch script itself, not the gradle block) | Sets `-Dvoxy.geometryBufferSizeOverrideMB=<n>`. Voxy's default (~4GB) single geometry-buffer allocation reliably fails with `GL_OUT_OF_MEMORY` on Zink/KosmicKrisp — there's no sparse-buffer fallback on this platform (`GL_ARB_sparse_buffer` is absent). 512MB is confirmed to allocate; Voxy also now retries at half the requested size (down to a 512MB floor) if an allocation fails, but starting from a size that's known to work avoids the retry churn. Gradle's last `-P` for a given property wins, so passing your own `-PgeomBufMB=<n>` after the script's default overrides it. |
| `-PvoxyDebugStats` | unset | Sets `-Dvoxy.forceStatistics=true`, forcing Voxy's `RenderStatistics` (normally only active while the F3 debug overlay is open) on from world join and logging per-LOD-layer counters (`hierarchicalRenderSections`, `visibleSections`, `quadCount`) to the log file — the only way to see this evidence when you can't drive the client's UI (headless/automated runs). |

The launch script also unconditionally forces a small window (`--width 854 --height 480`) and
sets `-Djoml.nounsafe=true` — both are permanent fixes, not debug levers (see the Debugging
section and `HANDOFF.md` for why), not something you need to pass yourself.

### The mandatory `MESA_GL_VERSION_OVERRIDE=4.6`

This is set automatically inside the `zinkRun` block (`environmentVariable("MESA_GL_VERSION_OVERRIDE", "4.6")`,
alongside `MESA_GLSL_VERSION_OVERRIDE=460`) — you don't need to set it yourself for the launch
script, but you must understand it if you reproduce a GL context manually (e.g. writing your own
EGL probe) or the game will silently negotiate down to GL 2.1 compat and fail immediately on any
modern GL call.

KosmicKrisp is missing two Vulkan features Zink normally treats as baseline requirements
(`EXT_custom_border_color`, `EXT_line_rasterization`) and prints a warning about it on every
launch. Because of this, Zink's own capability probe conservatively caps the GL version it will
negotiate through EGL's context-attribute matching — explicit requests for GL 4.1, 4.2, or 4.3
core all fail outright with `EGL_BAD_MATCH`, and a context created with no explicit version
request at all only reaches GL 2.1 compatibility profile. `MESA_GL_VERSION_OVERRIDE=4.6` bypasses
that conservative cap entirely and gets you the real GL 4.6 core context the hardware/driver can
actually deliver — this override is the only way that works; there is no supported version
between 2.1 and 4.6 reachable via the normal EGL attribute path on this driver.

The full extension dump behind this context (`docs/macos-gl-extensions.txt`, 205 extensions) was
produced with the environment: `DYLD_LIBRARY_PATH=$HOME/mesa-native/lib`,
`LIBGL_DRIVERS_PATH=$HOME/mesa-native/lib/dri`,
`VK_DRIVER_FILES=$HOME/mesa-native/share/vulkan/icd.d/kosmickrisp_mesa_icd.aarch64.json`,
`EGL_PLATFORM=surfaceless`, `MESA_LOADER_DRIVER_OVERRIDE=zink`, `MESA_GL_VERSION_OVERRIDE=4.6` —
the same set the `zinkRun` block injects for the game itself.

## Running with Voxy World Gen V2

[Voxy World Gen V2](https://github.com/iSeeEthan/voxy_worldgen_v2) background-generates chunks and
streams LOD data into Voxy's ingest service, and pairs with this Voxy build. Until the Zink work
above, the pairing could only be tested from a Windows client, because macOS caps native OpenGL at
4.1 and Voxy needs 4.3+.

**Everything in this section was tested against `alextoddslick/voxy_worldgen_v2` @
`feature/client-lod-memory` commit `1f1a965` (2026-08-07), pinned explicitly because that repo has
since moved on to a Minecraft 26.1.2 port and its default branch, `backport/1.21.1`, does not
contain the code this section describes: it has no `LodMemory` class at all, so the LOD-delivery
check below (non-empty `.bin` files under `run/voxyworldgenv2/lodmemory/`) does not apply there —
building `backport/1.21.1` and then looking for those files will find a directory that build can
never create. Check out `1f1a965` explicitly (`git checkout 1f1a965`) before building; do not
assume whatever is currently checked out in that repo matches what was tested here.

**Status: the pairing does not render on macOS today.** The two mods load and coexist cleanly, and
background generation works perfectly — 480–576 chunks with zero failures across four separate
launches (three in the initial evidence pass, one more in the A/B below). But with
`voxyworldgenv2` loaded, Voxy's render thread locks in a GPU fence wait roughly 13 seconds after
world join and the client stops drawing frames.

The stall was isolated with a same-session A/B, both arms on the same world, launched back to
back:

| Arm | `kk_timeline_wait` main-thread samples | `RenderStatistics` lines | Window |
|---|---|---|---|
| Voxy alone | 1–2 out of ~1100+ per sample (3 samples: 1, 2, 0) | 55, live terrain, layer-1 `quadCount` fluctuating 11511–23864 while actively in-world (dropping to 1509 in the final two samples, taken after the run logged "Saving and pausing game" and started unloading) | renders normally |
| Voxy + `voxyworldgenv2` | 100% of the main thread in all 4 samples taken (1648–1807), from 13s to 4 minutes after join | 1, all zeros, then nothing for 4 minutes | frozen on the last good frame (not black — see caution below) |

The stack is
`kk_timeline_wait -> -[IOSurfaceSharedEvent waitUntilSignaledValue:timeoutMS:] -> iokit_user_client_trap`,
which is **not** the `u_vbuf`/`batch_usage_wait` hang fixed by the 16-bit index change described
above — it is a separate, unresolved problem. The stall begins before any memory pressure appears,
so it is not an out-of-memory artifact, though note that a dev client of this size is the kernel's
first jetsam target if you run it on a loaded machine. A separate run at the same stall stack
(`task-3-report.md`'s Attempt 3) produced a solid-black window instead of a frozen frame — **with
the screen confirmed unlocked and actively in use**, which rules out screen-lock as the
explanation for that symptom and makes it the more alarming of the two data points, not a
discountable one. The only low-memory reading from that run is timestamped roughly 80 seconds
after its screenshot, so memory pressure at the moment the window went black is not established
either. The two visual outcomes (frozen last-good-frame here vs. solid black there) differ and
neither should be assumed to generalize to the other; both are recorded here rather than smoothed
into one story.

This is worth an investigation of its own. Both arms of the A/B independently show a macOS GPU
firmware-detected-lockup event (`gpuEvent-java-*.ips`, `restart_reason_desc:
"firmware-detected lockup"`) at or immediately after world join — Voxy alone recovers cleanly from
two such events (16:22:08, 16:22:12) and keeps rendering, while the paired run's only such event, 3
seconds after join, coincides almost exactly with the onset of the permanent stall. The recovery
claim needs one caveat, though: Voxy alone shows a *third* such event, at 16:24:53, that coincides
to the same second with Arm A's own end-of-run death — so at least one of the two arms' GPU events
did line up with a process ending, on both sides of the comparison, and Arm A's death is itself
unconfirmed as a memory-pressure kill (no matching `JetsamEvent`) versus a GPU-firmware restart it
simply didn't survive that time. That correlation doesn't prove causation, but it points at a
sharper next question than a vague "something about worldgen breaks rendering": does
`voxyworldgenv2` leave a GPU fence outstanding across that lockup/restart boundary that the
Voxy-alone path does not, since `rawIngest` runs on the client and touches Voxy's LOD store while
the render thread is drawing from it. The A/B above already establishes that the
*outcome* is specific to the pairing — Arm A never enters the stall, Arm B is in it within 13
seconds every time — that part isn't in question. What's still open is where in the pairing the
fault sits: the firmware-detected lockup/restart itself may be a platform-level event that would
happen regardless of what's loaded, with the companion mod's difference being whether the render
thread can recover from it — in which case the trigger lives in Zink/KosmicKrisp's own GPU-recovery
path, but the pairing is still what turns a survivable event into a permanent one.

Build the mod in its own checkout — pinned to the tested ref, since that repo's checked-out branch
today is whatever its own, unrelated work left it on — then launch this client with `-Pworldgen`:

```bash
cd ../voxy_worldgen_v2 && git checkout 1f1a965 && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build
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
`generating [minecraft:overworld]: N done @ X/s, ...`.

Delivery into Voxy is confirmed by non-empty `.bin` files under `run/voxyworldgenv2/lodmemory/`,
which are written only for columns Voxy's `rawIngest` fully accepted — and this checkpoint **was
demonstrated once**: `lodmemory/436c2ac36846adbb/minecraft_overworld.bin`, 556 bytes, mtime 15:36,
was written during Task 2's run (world join 15:35:26, stock config, ended 1m09s later via a
deliberate `pkill` that exited 143/SIGTERM — not an OS SIGKILL), consistent with
`LodMemory.tick()`'s 30-second debounce flushing mid-session rather than at shutdown. `record()`
is called from exactly one site
(`NetworkClientHandler.java:104-105`, guarded by `if (allIngested)`, "Record only what actually
reached Voxy in full"), and `dirty` is set only inside `record()` — so a non-empty file is direct
proof at least one column was fully accepted by `rawIngest` that session. It was **not reproduced
in any later run**: every subsequent attempt (Task 3's three attempts, all launched after 15:53)
stalled in the render-thread fence wait before a tick-flush could fire and was then SIGKILLed,
which the JVM cannot intercept or run shutdown hooks against, so neither the tick-gated flush nor
the graceful-disconnect flush ever ran again. Two cautions if you try to verify this yourself.
First, `LodMemory.flush()` only runs from `tick()` (30 s debounce) or a graceful `onDisconnect()`,
so a client killed with `pkill` (or SIGKILLed by the OS) writes nothing and leaves a stale file
from an earlier session — check the mtime against your own run's join time before trusting it; one
success, once, is not the same as this path being robustly verified. Second, **non-zero
`quadCount` at LOD layer 1 or beyond does not prove worldgen ingest**: Voxy's LOD store is also
filled by its own ingest of normally-loaded chunks and persists across runs, and the Voxy-alone
control arm above shows healthy layer-1 values with the companion mod absent entirely.

## Debugging

- **`-Dvoxy.forceNoIndirectCount=true`** (via `-PvoxyForceNoIndirectCount`): forces the zero-tail
  multi-draw-indirect fallback that platforms lacking `GL_ARB_indirect_parameters` use, on any
  driver. Useful for confirming that fallback path in isolation from whatever the current GPU
  would normally pick — on this driver `GL_ARB_indirect_parameters` is actually present (an
  emulated extension), so the fallback isn't exercised by default without this flag.
- **`-Dvoxy.forceStatistics=true`** (via `-PvoxyDebugStats`): forces `RenderStatistics` logging
  from world join, writing per-LOD-layer counters to the log file. This is the practical
  replacement for the F3 debug overlay in a headless/scripted session — grep the log for
  `RenderStatistics` to see `hierarchicalTraversalCounts`/`hierarchicalRenderSections`/
  `visibleSections`/`quadCount` per layer.
- **Profiling and hang diagnosis — use `sample`, never `jstack`.** This is the single most
  important lesson from the earlier investigation. Mesa executes GL on its own native worker
  threads (`gl0` for glthread, `zfq0` for zink's flush queue). Neither is attached to the JVM, so
  `jstack` cannot see them: every Java stack truncates at
  `org.lwjgl.opengl.*.nglXxx(Native Method)`, which tells you only which GL call the render
  thread is parked in — almost always a glthread sync point, and almost never where the real work
  is stuck. A day was lost to that.

  ```bash
  # match the actual java binary, not your own shell (pgrep -f matches its own command line)
  PID=$(ps -Ao pid,args | awk '/bin\/java/ && /devlaunchinjector/ {print $1; exit}')
  /usr/bin/sample "$PID" 10 -f /tmp/voxy-sample.txt
  grep -oE "(zink_[a-z_]+|kk_[a-z_]+|mtl_[a-z_]+|_mesa_[a-zA-Z_]+)" /tmp/voxy-sample.txt \
    | sort | uniq -c | sort -rn | head -15
  ```

  Sanity-check the capture before trusting it: a real one is hundreds of KB to megabytes and
  contains Mesa symbols. A tiny file, or zero counts for symbols you know should appear, means
  you sampled the wrong process.

- **Capture stderr.** Always launch through `2>&1 | tee /tmp/<name>.log`. Mesa's `mesa_loge` and
  the glwrapper's diagnostics go to stderr, which log4j never sees.

- **Time-to-freeze was a random variable** (18s to >154s) back when freezes happened. Any
  before/after comparison needs n≥5 runs per arm and a median; single-run comparisons from the
  old investigation are uninterpretable, and its claims that specific fixes "extended session
  survival" were never actually supported.
- **Where crash reports land:** native JVM crashes (e.g. the MoltenVK `SIGSEGV`) write
  `hs_err_pid<pid>.log` to the client's working directory,
  `versions/1.21.1-fabric/run/hs_err_pid<pid>.log`. Java-level crashes (mod/vanilla exceptions
  that kill the client) write to `versions/1.21.1-fabric/run/crash-reports/`. Shader compile
  failures additionally dump the offending source to
  `versions/1.21.1-fabric/run/SHADER_DUMP.txt`.
- **`ZINK_DEBUG=validation` will crash the client outright** on this Mesa build — there is no
  `VK_LAYER_KHRONOS_validation` layer installed, so requesting it fails the Vulkan loader itself
  (native `SIGSEGV`) rather than degrading gracefully. `MESA_DEBUG=1` is safe and is set by
  default in the `zinkRun` block.
- If the game window drives your Mac's `WindowServer` CPU very high while unfocused during a long
  debugging session, lower `maxFps` in `run/options.txt` and prefix the launch with `nice -n 15` —
  the launch script already forces a small `854x480` window for the same reason.
