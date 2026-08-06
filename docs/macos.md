# macOS support (Zink + KosmicKrisp)

Voxy runs on Apple Silicon Macs by routing OpenGL through [Mesa's Zink](https://docs.mesa3d.org/drivers/zink.html)
driver, which translates GL calls to Vulkan, on top of Mesa's `kosmickrisp` Vulkan driver
(Apple's native Vulkan-on-Metal implementation upstreamed into Mesa). There is no bundled
native build — you build Mesa and a patched GLFW yourself and launch the dev client through a
Gradle property that wires up the environment for you.

This has been validated on an Apple M2 Max. Read the status section below before investing time
in the build — there is one known unresolved driver issue that affects every session.

## Status

**Works:**
- A hardware-accelerated OpenGL 4.6 core-profile context via Zink + KosmicKrisp
  (`zink Vulkan 1.3(Apple M2 Max (MESA_KOSMICKRISP))`).
- Voxy initializes, allocates its geometry buffer, and creates its render system
  (`MDICSectionRenderer` backend, `NormalRenderPipeline`).
- Worlds load and the LOD pipeline issues real draws — `RenderStatistics` shows non-zero
  `hierarchicalRenderSections`/`quadCount` for the nearest LOD layer within a few seconds of
  joining a world.
- The zero-tail multi-draw-indirect fallback path (for GPUs without
  `GL_ARB_indirect_parameters`) works correctly, and can be exercised on any platform via
  `-PvoxyForceNoIndirectCount` for testing.

**Known limitation — sessions eventually freeze:** every observed session, however long it
survives, eventually hangs on the render thread inside `glFenceSync`/`glGetSynciv`
(`GlFence`, used throughout Voxy's GPU/CPU synchronization for uploads and downloads). This
presents as a silent freeze, not a crash: no exception, no log line, CPU time frozen across
repeated `jstack` samples. Two earlier, shallower hangs in the same family (a native
buffer-clear call, and a per-frame framebuffer-attachment query used by SSAO) were found and
fixed in this codebase; the `glFenceSync` hang is a driver-level fault in KosmicKrisp's Vulkan
fence/semaphore handling with no code-level workaround, since `GlFence` is core, unavoidable
infrastructure used by every upload/download path. See the "Debugging" section below for how to
confirm you've hit it, and `.superpowers/sdd/2026-08-05-macos-support/task-8-report.md`
("Root cause #3") for the full investigation, including the two fixed hangs and the mitigations
that were tried and reverted.

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
"dedicated" memory via `GL_NVX_gpu_memory_info`, so AUTO always tries to pick BEST. BETTER/BEST's
per-frame framebuffer-attachment query (`glGetNamedFramebufferAttachmentParameteriv`) is one of
the two hangs mentioned above, so on Zink/KosmicKrisp specifically (`Capabilities.isKosmicKrisp`,
not the broader `isZink` — this does not affect Zink on Linux/RADV/ANV/NVK) `SSAOMode.AUTO` is
forced to BASIC, with a log line explaining why. Explicit user overrides to BETTER/BEST are not
gated and will hit the hang.

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

This uses a fork with KosmicKrisp/Zink-on-macOS support
(`lucamignatti/mesa`, Mesa 26.1.0-devel at the time this was built), not upstream Mesa.

```bash
mkdir -p ~/src && cd ~/src
git clone https://github.com/lucamignatti/mesa.git
cd mesa

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
- **Detecting the freeze (jstack-on-freeze pattern):** because the known KosmicKrisp hang (see
  Status) produces no exception and no crash report, "no error in the log" does not mean the
  session is healthy — a frozen render thread trivially produces zero new errors too. To confirm
  a genuine hang rather than a slow frame: find the client PID (`ps aux | grep java`), then take
  several `jstack <pid>` samples a few seconds apart and compare the render thread's stack and
  reported CPU time. If the stack frame and `cpu=` value are *identical* across samples spanning
  10+ seconds, the thread is not making progress — it's a real hang, not a coincidentally slow or
  hot loop. All observed hangs (fixed and unfixed) have shown this exact signature, most recently
  at `GlFence.<init>`/`GlFence.signaled` inside `glFenceSync`/`glGetSynciv`. If you hit this, there
  is currently no code-level workaround; kill the process (`kill`, then `kill -9` if needed, then
  a `pkill -f runClient` sweep) and check `ps aux` afterward to confirm no stray `java` processes
  remain.
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
