# Voxy macOS Support (Zink + KosmicKrisp) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Voxy 1.21.1 (Fabric) renders LOD terrain correctly on macOS (Apple Silicon) under a Zink + KosmicKrisp OpenGL-on-Metal environment.

**Architecture:** Two workstreams. (1) Environment: build Mesa with the Zink gallium driver and KosmicKrisp Vulkan driver on the dev Mac so Minecraft gets a GL 4.6 context. (2) Code: Voxy's only structural blocker is `GL_ARB_indirect_parameters` (impossible on Metal); replace its two call sites with a zero-tail plain multi-draw-indirect fallback, relax the support gate, and debug the remaining runtime issues live.

**Tech Stack:** Java 21, Gradle + Stonecutter (subproject `:1.21.1-fabric`), LWJGL 3 OpenGL bindings, Mesa (meson/ninja), Homebrew.

**Spec:** `docs/superpowers/specs/2026-08-05-macos-support-design.md`

## Global Constraints

- Branch: `macos-support` (already created off `multiversion`). All commits go here.
- Target: **1.21.1 Fabric only**. Do not touch 1.20.1 or forge/neoforge buildscripts.
- Build command (Java 21 required, not the default JVM):
  `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :1.21.1-fabric:build`
  Every code task ends with this passing (user's global rule: change is not done until the build succeeds).
- Never crash the game on an unsupported system — disable Voxy with a clear log line instead.
- Fallback code must activate **only** when `!Capabilities.INSTANCE.indirectParameters`; behavior on Windows/Linux with the extension present must be unchanged.
- This project has no unit-test harness (GPU renderer mod). Verification = compile + targeted runtime checks; TDD steps are replaced by build/run verification steps.
- Do not launch the game without the user's go-ahead on timing — gameplay verification happens on their Mac and they may prefer to drive it.

---

## Phase 0 — Environment

> Tasks 1–5 produce no repo commits except the launch script and docs. If Task 4's gate fails and cannot be fixed, STOP and reassess with the user (spec: no speculative code without a working environment).

### Task 1: Install build prerequisites

**Files:** none (system packages)

**Interfaces:**
- Produces: Homebrew packages `meson ninja bison flex llvm glslang spirv-tools molten-vk`; bison/llvm on `PATH` for Task 2.

- [ ] **Step 1: Install packages**

```bash
brew install meson ninja python3 bison flex llvm glslang spirv-tools molten-vk
```

- [ ] **Step 2: Verify versions**

```bash
export PATH="/opt/homebrew/opt/llvm/bin:/opt/homebrew/opt/bison/bin:$PATH"
meson --version && ninja --version && bison --version | head -1 && ls /opt/homebrew/opt/molten-vk/lib
```

Expected: meson ≥ 1.x, bison ≥ 3.8 (Apple's stock 2.3 is too old — the PATH export above must win), `libMoltenVK.dylib` listed.

### Task 2: Build Mesa (Zink + KosmicKrisp)

**Files:**
- Create (outside repo): `~/src/mesa` (clone), installed tree at `~/mesa-native`

**Interfaces:**
- Produces: `~/mesa-native/lib/libGL.dylib`, `libEGL.dylib`, `lib/dri/zink_dri.so`, KosmicKrisp ICD at `~/mesa-native/share/vulkan/icd.d/kosmickrisp_mesa_icd.aarch64.json`. Tasks 4–5 and 8 consume these exact paths.

- [ ] **Step 1: Clone the known-working fork**

```bash
mkdir -p ~/src && cd ~/src
git clone https://github.com/lucamignatti/mesa.git
cd mesa
```

(This fork is the gist author's; it carries the macOS/interpose patches. If it has diverged or vanished, fall back to upstream `https://gitlab.freedesktop.org/mesa/mesa.git` main — KosmicKrisp and Zink-on-macOS are upstream as of Mesa 25.3/26.x — and note any missing interpose library in Task 5.)

- [ ] **Step 2: Write the native file**

```bash
cat > native.ini <<'EOF'
[binaries]
bison = '/opt/homebrew/opt/bison/bin/bison'
EOF
```

- [ ] **Step 3: Configure**

```bash
export PATH="/opt/homebrew/opt/llvm/bin:/opt/homebrew/opt/bison/bin:$PATH"
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
```

Expected: configuration succeeds listing gallium driver `zink` and vulkan driver `kosmickrisp`.

- [ ] **Step 4: Build and install**

```bash
ninja -C build && ninja -C build install
```

- [ ] **Step 5: Create driver symlinks + Vulkan loader copy**

```bash
mkdir -p $HOME/mesa-native/lib/dri
cd $HOME/mesa-native/lib/dri
ln -sf ../libgallium-*.dylib zink_dri.so
ln -sf ../libgallium-*.dylib swrast_dri.so
cp /opt/homebrew/lib/libvulkan.1.dylib $HOME/mesa-native/lib/
```

(The gist pins `libgallium-26.0.0-devel.dylib`; glob to survive version bumps — verify exactly one match.)

- [ ] **Step 6: Verify artifacts**

```bash
ls $HOME/mesa-native/lib/libGL.dylib $HOME/mesa-native/lib/libEGL.dylib \
   $HOME/mesa-native/share/vulkan/icd.d/kosmickrisp_mesa_icd.aarch64.json \
   $HOME/mesa-native/lib/dri/zink_dri.so
ls $HOME/mesa-native/lib | grep -i interpose || echo "NOTE: no interpose lib — record for Task 5"
```

Expected: all four paths exist. Note whether `libgl_interpose.dylib` was produced.

### Task 3: Build patched GLFW

**Files:**
- Create (outside repo): `~/src/glfw`, shared lib `~/src/glfw/build/src/libglfw.3.dylib`

**Interfaces:**
- Produces: `~/src/glfw/build/src/libglfw.3.dylib` — consumed by Task 5's JVM args (`-Dorg.lwjgl.glfw.libname`).

- [ ] **Step 1: Clone and build**

```bash
cd ~/src
git clone https://github.com/lucamignatti/glfw.git
cd glfw && mkdir -p build && cd build
cmake .. -DGLFW_BUILD_EXAMPLES=OFF -DGLFW_BUILD_TESTS=OFF -DBUILD_SHARED_LIBS=ON
make -j8
```

- [ ] **Step 2: Verify**

```bash
ls -la ~/src/glfw/build/src/libglfw.3.dylib && file ~/src/glfw/build/src/libglfw.3.dylib
```

Expected: arm64 dylib exists.

### Task 4: GL probe — prove GL 4.6 and dump extensions (GATE)

**Files:**
- Create (scratch, outside repo): `glprobe.c`; output saved to repo as `docs/macos-gl-extensions.txt` in Task 9 if useful.

**Interfaces:**
- Produces: proof of GL 4.6 via Zink+KosmicKrisp and the full extension list that drives any extra init hardening in Task 8.

- [ ] **Step 1: Write the probe** (EGL surfaceless + GL core context, prints version/renderer and all extensions)

```c
#include <EGL/egl.h>
#include <GL/gl.h>
#include <stdio.h>
typedef const GLubyte* (*GETSTRINGI)(GLenum, GLuint);
int main(void) {
    EGLDisplay d = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (!eglInitialize(d, NULL, NULL)) { printf("eglInitialize failed\n"); return 1; }
    eglBindAPI(EGL_OPENGL_API);
    EGLint cfgAttr[] = { EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT, EGL_NONE };
    EGLConfig cfg; EGLint n;
    eglChooseConfig(d, cfgAttr, &cfg, 1, &n);
    EGLint ctxAttr[] = { EGL_CONTEXT_MAJOR_VERSION, 4, EGL_CONTEXT_MINOR_VERSION, 6,
                         EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT, EGL_NONE };
    EGLContext c = eglCreateContext(d, cfg, EGL_NO_CONTEXT, ctxAttr);
    if (c == EGL_NO_CONTEXT) { printf("context creation failed\n"); return 1; }
    eglMakeCurrent(d, EGL_NO_SURFACE, EGL_NO_SURFACE, c);
    printf("VERSION:  %s\nRENDERER: %s\n", glGetString(GL_VERSION), glGetString(GL_RENDERER));
    GETSTRINGI getStringi = (GETSTRINGI)eglGetProcAddress("glGetStringi");
    GLint cnt = 0; glGetIntegerv(0x821D /*GL_NUM_EXTENSIONS*/, &cnt);
    for (GLint i = 0; i < cnt; i++) printf("%s\n", getStringi(0x1F03 /*GL_EXTENSIONS*/, (GLuint)i));
    return 0;
}
```

- [ ] **Step 2: Compile and run against the Mesa build**

```bash
cc glprobe.c -o glprobe -I$HOME/mesa-native/include -L$HOME/mesa-native/lib -lEGL -lGL
DYLD_LIBRARY_PATH=$HOME/mesa-native/lib \
LIBGL_DRIVERS_PATH=$HOME/mesa-native/lib/dri \
VK_DRIVER_FILES=$HOME/mesa-native/share/vulkan/icd.d/kosmickrisp_mesa_icd.aarch64.json \
EGL_PLATFORM=surfaceless \
MESA_LOADER_DRIVER_OVERRIDE=zink \
./glprobe | tee /tmp/gl-extensions.txt
grep -c ARB /tmp/gl-extensions.txt
grep -E 'ARB_indirect_parameters|ARB_shader_storage|ARB_compute_shader|ARB_buffer_storage|ARB_sparse_buffer|KHR_shader_subgroup|ARB_gpu_shader_int64' /tmp/gl-extensions.txt
```

Expected (GATE): `VERSION` reports 4.x Mesa on `zink` renderer (KosmicKrisp/Metal underneath). `ARB_compute_shader`, `ARB_shader_storage_buffer_object`, `ARB_buffer_storage` present; `ARB_indirect_parameters` **absent** (confirming the fallback is needed). If context creation fails entirely, STOP — debug the Mesa build before any code work.

### Task 5: Zink launch script for the dev client

**Files:**
- Create: `scripts/macos/run-zink-client.sh`

**Interfaces:**
- Produces: one script that launches the 1.21.1-fabric dev client under Zink. Consumed by Task 8 and by the user for manual testing.

- [ ] **Step 1: Write the script**

```bash
#!/usr/bin/env bash
# Launches the Voxy 1.21.1-fabric dev client under Zink + KosmicKrisp (macOS).
# Prereqs: Mesa in ~/mesa-native (Task 2), patched GLFW in ~/src/glfw (Task 3).
set -euo pipefail
cd "$(dirname "$0")/../.."

MESA="$HOME/mesa-native"
GLFW_LIB="$HOME/src/glfw/build/src/libglfw.3.dylib"

export DYLD_LIBRARY_PATH="$MESA/lib"
export LIBGL_DRIVERS_PATH="$MESA/lib/dri"
export VK_DRIVER_FILES="$MESA/share/vulkan/icd.d/kosmickrisp_mesa_icd.aarch64.json"
export EGL_PLATFORM=surfaceless
export MESA_LOADER_DRIVER_OVERRIDE=zink
export MESA_GL_VERSION_OVERRIDE=4.6
export MESA_GLSL_VERSION_OVERRIDE=460
# Interpose lib (if the Mesa fork built it — optional, see docs/macos.md)
if [ -f "$MESA/lib/libgl_interpose.dylib" ]; then
  export DYLD_INSERT_LIBRARIES="$MESA/lib/libgl_interpose.dylib"
fi

JAVA_HOME=$(/usr/libexec/java_home -v 21) exec ./gradlew :1.21.1-fabric:runClient \
  -Porg.gradle.jvmargs="-Xmx4G" \
  --console=plain \
  "-Dorg.lwjgl.egl.libname=$MESA/lib/libEGL.dylib" \
  "-Dorg.lwjgl.opengl.libname=$MESA/lib/libGL.dylib" \
  "-Dorg.lwjgl.glfw.libname=$GLFW_LIB" "$@"
```

Note for the implementer: gradle does not forward arbitrary `-D` args to the run JVM this way on all loom versions — if the client JVM doesn't pick them up (check the log for LWJGL loading system OpenGL), add them to the loom `runs { client { vmArgs(...) } }` block in `build.fabric.gradle.kts` guarded behind a gradle property (e.g. `-PzinkRun`), and have the script pass `-PzinkRun`. Keep whichever mechanism works; delete the other.

- [ ] **Step 2: Make executable, sanity-run vanilla-level launch (GATE)**

```bash
chmod +x scripts/macos/run-zink-client.sh
./scripts/macos/run-zink-client.sh
```

Expected: client window opens; log shows an OpenGL 4.6 Mesa/zink context (search log for `OpenGL` / `zink`). Known quirks from the community report: initial rendering confined to a corner until the window is resized — acceptable. If `DYLD_*` vars are being stripped (hardened-runtime JVM), symptom is LWJGL loading `/System/.../OpenGL` — fix by using a non-hardened JDK for the run or `codesign --remove-signature` on a copied JRE, and document what worked.

- [ ] **Step 3: Commit the script**

```bash
git add scripts/macos/run-zink-client.sh
git commit -m "Add macOS Zink dev-client launch script"
```

---

## Phase 1 — Code changes

### Task 6: Capabilities debug flag + support-gate relaxation

**Files:**
- Modify: `src/main/java/me/cortex/voxy/client/core/gl/Capabilities.java:57`
- Modify: `src/main/java/me/cortex/voxy/client/VoxyClient.java:28`

**Interfaces:**
- Consumes: existing `Capabilities.INSTANCE.indirectParameters` (public final boolean).
- Produces: `indirectParameters` is `false` when the JVM property `voxy.forceNoIndirectCount` is `true`, regardless of driver support. `VoxyClient` no longer requires `indirectParameters` to enable the mod. Task 7 relies on both.

- [ ] **Step 1: Add the force-off flag in `Capabilities`**

Replace line 57:

```java
        this.indirectParameters = cap.glMultiDrawElementsIndirectCountARB != 0;
```

with:

```java
        //-Dvoxy.forceNoIndirectCount=true exercises the macOS/Zink fallback path on any platform
        this.indirectParameters = cap.glMultiDrawElementsIndirectCountARB != 0
                && !Boolean.getBoolean("voxy.forceNoIndirectCount");
```

- [ ] **Step 2: Relax the gate in `VoxyClient`**

Replace line 28:

```java
        boolean systemSupported = Capabilities.INSTANCE.compute && Capabilities.INSTANCE.indirectParameters && !Capabilities.INSTANCE.hasBrokenDepthSampler;
```

with:

```java
        boolean systemSupported = Capabilities.INSTANCE.compute && !Capabilities.INSTANCE.hasBrokenDepthSampler;
        if (systemSupported && !Capabilities.INSTANCE.indirectParameters) {
            Logger.warn("GL_ARB_indirect_parameters not supported (expected on macOS/Zink), using multi-draw-indirect fallback");
        }
```

- [ ] **Step 3: Build**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :1.21.1-fabric:build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/me/cortex/voxy/client/core/gl/Capabilities.java src/main/java/me/cortex/voxy/client/VoxyClient.java
git commit -m "Relax indirect-parameters requirement, add voxy.forceNoIndirectCount debug flag"
```

### Task 7: Zero-tail MDI fallback in MDICSectionRenderer

**Files:**
- Modify: `src/main/java/me/cortex/voxy/client/core/rendering/section/backend/mdic/MDICSectionRenderer.java` (lines 183, 205, 246, and the `buildDrawCalls` command-gen block at ~306)

**Interfaces:**
- Consumes: `Capabilities.INSTANCE.indirectParameters` (Task 6); `GlBuffer.zero()` (existing, `GlBuffer.java:61`); `glMultiDrawElementsIndirect` (already in scope via `import static org.lwjgl.opengl.GL43.*`).
- Produces: renderer works without `GL_ARB_indirect_parameters`. No new public API.

Background for the implementer: the culling compute shaders write packed draw commands (5 ints, 20 bytes each) into `viewport.drawCallBuffer` — opaque from index 0, translucent from `TRANSLUCENT_OFFSET`, temporal from `TEMPORAL_OFFSET` — and the *count* of commands into `viewport.drawCountCallBuffer`, which the GPU reads via `glMultiDrawElementsIndirectCountARB`. Metal can never support that read (no `VK_KHR_draw_indirect_count`). Fallback: pre-zero the command buffer each frame, then draw `maxDrawCount` commands with plain `glMultiDrawElementsIndirect` — zeroed commands (`count==instanceCount==0`) render nothing. Zeroing the whole 12 MB buffer per frame is a trivially cheap GPU fill and is chosen over per-region ranges for correctness robustness (render-time counts are recomputed and must never exceed the zeroed extent).

- [ ] **Step 1: Skip the parameter-buffer bind when unsupported**

In `bindRenderingBuffers` (line 183), replace:

```java
        glBindBuffer(GL_PARAMETER_BUFFER_ARB, viewport.drawCountCallBuffer.id);
```

with:

```java
        if (Capabilities.INSTANCE.indirectParameters) {
            glBindBuffer(GL_PARAMETER_BUFFER_ARB, viewport.drawCountCallBuffer.id);
        }
```

- [ ] **Step 2: Branch the opaque/temporal draw**

In `renderTerrain` (line 205), replace:

```java
        glMultiDrawElementsIndirectCountARB(GL_TRIANGLES, GL_UNSIGNED_SHORT, indirectOffset, drawCountOffset, maxDrawCount, 0);
```

with:

```java
        if (Capabilities.INSTANCE.indirectParameters) {
            glMultiDrawElementsIndirectCountARB(GL_TRIANGLES, GL_UNSIGNED_SHORT, indirectOffset, drawCountOffset, maxDrawCount, 0);
        } else {
            //Command buffer tail is zeroed in buildDrawCalls; empty commands draw nothing
            glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_SHORT, indirectOffset, maxDrawCount, 0);
        }
```

- [ ] **Step 3: Branch the translucent draw**

At line 246, replace:

```java
        glMultiDrawElementsIndirectCountARB(GL_TRIANGLES, GL_UNSIGNED_SHORT, TRANSLUCENT_OFFSET*5*4, 4*4, Math.min(this.geometryManager.getSectionCount(), TRANSLUCENT_DRAW_COUNT), 0);
```

with:

```java
        int translucentMax = Math.min(this.geometryManager.getSectionCount(), TRANSLUCENT_DRAW_COUNT);
        if (Capabilities.INSTANCE.indirectParameters) {
            glMultiDrawElementsIndirectCountARB(GL_TRIANGLES, GL_UNSIGNED_SHORT, TRANSLUCENT_OFFSET*5*4, 4*4, translucentMax, 0);
        } else {
            glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_SHORT, TRANSLUCENT_OFFSET*5*4, translucentMax, 0);
        }
```

- [ ] **Step 4: Zero the command buffer before command generation**

In `buildDrawCalls`, at the top of the `{//Generate the commands` block (immediately before `this.distanceCountBuffer.zeroRange(0, 1024*4);` at line 307), add:

```java
            if (!Capabilities.INSTANCE.indirectParameters) {
                //No GPU-side draw count: draws read maxDrawCount commands, so stale
                // entries past the generated count must be zero (a zeroed command draws nothing)
                viewport.drawCallBuffer.zero();
            }
```

- [ ] **Step 5: Build**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :1.21.1-fabric:build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Runtime-verify the fallback (any platform, or on the Mac under Zink)**

Coordinate with the user: on their Windows/Linux box (or this Mac under Zink after Task 5), run the client with `-Dvoxy.forceNoIndirectCount=true` and confirm LOD terrain still renders identically. In the dev environment:

```bash
./scripts/macos/run-zink-client.sh -Dvoxy.forceNoIndirectCount=true
```

Expected: log shows the Task 6 fallback warning; LODs render.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/me/cortex/voxy/client/core/rendering/section/backend/mdic/MDICSectionRenderer.java
git commit -m "Add zero-tail MDI fallback for platforms without GL_ARB_indirect_parameters"
```

---

## Phase 2 — Live debugging & acceptance

### Task 8: Debug Voxy under Zink to first correct render

**Files:**
- Modify: whatever the crashes implicate (unknown until reproduced — this task is intentionally open-ended per the spec)

**Interfaces:**
- Consumes: Task 5 script, Task 4 extension dump, Tasks 6–7 code.
- Produces: Voxy loads a singleplayer world and renders LOD terrain under Zink without crashing.

- [ ] **Step 1: Launch with Voxy under Zink** (`./scripts/macos/run-zink-client.sh`), create/enter a singleplayer world, let LODs generate (fly beyond vanilla render distance).

- [ ] **Step 2: If it crashes or corrupts** — REQUIRED SUB-SKILL: use `superpowers:systematic-debugging`. Evidence-gathering levers, in order:
  - Full log + stacktrace from the gradle run console.
  - `ZINK_DEBUG=validation` and `MESA_DEBUG=1` env vars added to the script invocation for driver-side errors.
  - Voxy's own logger output (`me.cortex.voxy.common.Logger`).
  - Cross-check any missing-extension suspicion against `/tmp/gl-extensions.txt` from Task 4.
  - Narrow by disabling pipeline stages (SSAO via Voxy config, temporal, translucency) to isolate the failing stage.
- [ ] **Step 3: Fix root cause, rebuild, relaunch.** One commit per distinct fix, message format: `"Fix <symptom> under Zink/KosmicKrisp: <root cause>"`. Repeat Steps 1–3 until the world loads and LODs render.

### Task 9: Acceptance, docs, wrap-up

**Files:**
- Create: `docs/macos.md`
- Create: `docs/macos-gl-extensions.txt` (from Task 4 output)

**Interfaces:**
- Consumes: everything prior.
- Produces: documented, reproducible macOS setup; branch ready for user review/merge decision.

- [ ] **Step 1: Acceptance run (user-driven):** ask the user to play a few minutes in a singleplayer world under Zink — LOD terrain beyond vanilla render distance, no crash, no visual corruption (spec success criteria). Screenshot for the review inbox (`/post-review`) since this is visual work.
- [ ] **Step 2: Write `docs/macos.md`:** prerequisites, Mesa/GLFW build commands (from Tasks 1–3 as actually executed, including any deviations), launch script usage, known quirks (corner-render-until-resize, performance expectations), and the `voxy.forceNoIndirectCount` flag.
- [ ] **Step 3: Save the extension dump:** `cp /tmp/gl-extensions.txt docs/macos-gl-extensions.txt`
- [ ] **Step 4: Final build + commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :1.21.1-fabric:build
git add docs/macos.md docs/macos-gl-extensions.txt
git commit -m "Document macOS Zink+KosmicKrisp setup"
```

- [ ] **Step 5:** REQUIRED SUB-SKILL: use `superpowers:finishing-a-development-branch` to decide merge/PR handling with the user.
