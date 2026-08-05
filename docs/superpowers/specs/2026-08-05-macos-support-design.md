# Voxy macOS Support — Design

**Date:** 2026-08-05
**Branch:** `macos-support` (off `multiversion`)
**Target:** Minecraft 1.21.1, Fabric loader only

## Goal

Voxy 1.21.1 (Fabric) runs on macOS (Apple Silicon) under a Zink + KosmicKrisp
environment: a singleplayer world loads and Voxy's LOD terrain renders without
crashes or visual corruption. Setting up that environment on the development Mac
is part of the work.

**Out of scope:** performance tuning beyond basic playability, NeoForge/Forge,
1.20.1, bundling Zink natives inside the mod, native Metal or GL 4.1 renderers.

## Background

Voxy's renderer requires OpenGL 4.3–4.6 features (compute shaders, SSBOs, DSA,
persistent-mapped buffer storage, multi-draw indirect). Apple's native OpenGL
caps at 4.1, so macOS support means running under Mesa's Zink driver
(GL 4.6 → Vulkan) on top of KosmicKrisp (Mesa's native Vulkan-on-Metal driver).
A community setup ([gist](https://gist.github.com/lucamignatti/5312f5e937de2ba44256ecba6de54cc2))
confirms Minecraft reports GL 4.6 on macOS with this stack.

The single structural blocker: `GL_ARB_indirect_parameters`
(`glMultiDrawElementsIndirectCountARB`) requires Vulkan's
`VK_KHR_draw_indirect_count`, which Metal cannot support
([MoltenVK #168](https://github.com/KhronosGroup/MoltenVK/issues/168)) — so no
macOS Vulkan driver exposes it. Voxy hard-gates on this capability at
`VoxyClient.java:28` and calls it at exactly two draw sites in
`MDICSectionRenderer.java` (lines 205 and 246).

All other optional capabilities (NVX GPU memory query, sparse buffers,
subgroups, NV mesh shaders, shader int64) already have guarded fallback paths —
verified across `RenderResourceReuse`, `SSAO`, `BasicSectionGeometryData`, and
`MDICSectionRenderer`. Platform-specific CPU affinity code (`CpuLayout`,
`ThreadUtils`) degrades gracefully on unknown platforms.

## Phase 0 — Environment setup

1. Build Mesa (Zink gallium driver + KosmicKrisp Vulkan driver) on the dev Mac,
   following the gist's recipe: meson, EGL on the surfaceless platform, GLX and
   GBM disabled, Vulkan SDK / MoltenVK present as a build dependency.
2. Create a launcher instance (Prism or equivalent) for 1.21.1 Fabric with:
   - `DYLD_INSERT_LIBRARIES` / `DYLD_LIBRARY_PATH` pointing at the Mesa build
   - `VK_DRIVER_FILES` pointing at the KosmicKrisp ICD JSON
   - `MESA_LOADER_DRIVER_OVERRIDE=zink`, `MESA_GL_VERSION_OVERRIDE=4.6`
   - LWJGL JVM args directing it to the Mesa libGL rather than system OpenGL
3. **Gate:** vanilla Minecraft (and Sodium, if used in the instance) runs and F3
   reports an OpenGL 4.6 Mesa context. Dump the full GL extension list
   (`glGetStringi(GL_EXTENSIONS, i)`) for reference; it drives Phase 1 details.

Everything downstream depends on this phase; if the stack cannot be brought up,
stop and reassess rather than writing speculative code.

## Phase 1 — Code changes (branch `macos-support`)

### Zero-tail MDI fallback

Active only when `!Capabilities.INSTANCE.indirectParameters`:

- **`MDICSectionRenderer.buildDrawCalls`:** before the command-gen compute
  dispatch, zero the regions of `viewport.drawCallBuffer` that the subsequent
  draws will consume (`GlBuffer.zeroRange`, same pattern already used for
  `distanceCountBuffer` at line 307). This guarantees stale commands past the
  GPU-written count are all-zero.
- **`renderTerrain` / `renderTranslucent`:** replace
  `glMultiDrawElementsIndirectCountARB(...)` with plain
  `glMultiDrawElementsIndirect(...)` (GL 4.3) using the `maxDrawCount` value
  already computed at each call site. Zeroed commands have
  `count == instanceCount == 0` and draw nothing. Skip the
  `GL_PARAMETER_BUFFER_ARB` bind in `bindRenderingBuffers`.
- **`VoxyClient.initVoxyClient`:** relax the support gate from
  `compute && indirectParameters && !hasBrokenDepthSampler` to
  `compute && !hasBrokenDepthSampler`.

### Debug flag

`-Dvoxy.forceNoIndirectCount=true` forces `indirectParameters = false` in
`Capabilities`, so the fallback path can be exercised and regression-tested on
any platform, not only macOS.

### Init hardening

Guard or fix anything the Phase 0 extension dump reveals as missing that Voxy
touches unguarded at init. (Audit so far found nothing beyond the indirect
count gate, but the dump is authoritative.)

## Phase 2 — Live debugging

Community reports say Voxy crashed during world load even with the gate
bypassed; the cause is unknown. Reproduce in the Phase 0 environment and
diagnose systematically: GL debug output/`KHR_debug`, Mesa debug env vars
(`MESA_DEBUG`, `ZINK_DEBUG`), narrowing by disabling pipeline stages (SSAO,
temporal, translucency). Fix what is actually found rather than guessing. This
phase is open-ended; done means the success criterion below.

## Error handling

- On plain macOS (no Zink, GL 4.1): the existing capability gate disables Voxy
  cleanly with a log message. Keep that; never crash the game.
- Under Zink with an unexpected missing extension: same principle — disable
  with a clear log line naming the missing capability.

## Testing / verification

- `./gradlew build` (1.21.1-fabric) must pass after every change.
- Zero-tail fallback sanity-checked via the debug flag (any platform).
- Final acceptance on the Mac: launch 1.21.1 Fabric + Voxy under the Phase 0
  instance, create/load a singleplayer world, verify LOD terrain renders beyond
  vanilla render distance without crashes or visual corruption.

## Success criteria

Minecraft 1.21.1 + Fabric + Voxy launches under Zink + KosmicKrisp on the dev
Mac, a singleplayer world loads, and Voxy's LOD terrain renders without visual
corruption or crashes. Performance beyond "usable" is a stretch goal, not a
requirement.
