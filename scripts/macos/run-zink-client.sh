#!/usr/bin/env bash
# Launches the Voxy 1.21.1-fabric dev client under Zink + KosmicKrisp (macOS).
# Prereqs: Mesa in ~/mesa-native (Task 2), patched GLFW in ~/src/glfw (Task 3).
#
# NOTE: the DYLD_*/MESA_*/VK_* env vars and LWJGL library-path -D args are NOT set here as
# shell exports passed through to `./gradlew`. Verified during Task 5: Loom's runClient task
# does not forward -D args given on the gradlew command line to the forked client JVM, and its
# Exec environment does not inherit DYLD_* vars from the invoking shell either — the client
# fell back to Apple's native "OpenGL 4.1 Metal" renderer instead of Mesa/Zink. Instead, the
# -PzinkRun gradle property below activates a `loom { runs { client { ... } } }` block in
# build.fabric.gradle.kts that injects these directly into the run config, which does work.
set -euo pipefail
cd "$(dirname "$0")/../.."

# Task 8 finding I1: Voxy's default (~4GB) geometry buffer allocation genuinely fails with
# GL_OUT_OF_MEMORY on Zink/KosmicKrisp (no sparse-buffer fallback exists on this platform) - the
# client hard-crashes at world join without an override. Default to a size that's been verified
# to allocate successfully here (512MB); pass a later -PgeomBufMB=<n> of your own on the command
# line to override it (Gradle's last -P for a given property wins, so it appearing after this
# default in "$@" takes precedence).
JAVA_HOME=$(/usr/libexec/java_home -v 21) exec ./gradlew :1.21.1-fabric:runClient \
  -Porg.gradle.jvmargs="-Xmx4G" \
  -PzinkRun \
  -PgeomBufMB=512 \
  --console=plain "$@"
