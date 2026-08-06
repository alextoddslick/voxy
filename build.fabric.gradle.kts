@file:Suppress("UnstableApiUsage")

import me.cortex.voxy.gradle.prop
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile

extra["loaderName"] = "fabric"
extra["loaderDisplayName"] = "Fabric"
extra["archiveTaskName"] = "remapJar"
extra["sourceJavaDir"] = "src/fabric/java"
extra["additionalSourceJavaDirs"] = listOf(
    "versions/${prop("deps.minecraft", "minecraft_version")}/src/java",
    "versions/${prop("deps.minecraft", "minecraft_version")}-fabric/src/fabric/java"
)

plugins {
    id("fabric-loom") version "1.16.2"
    id("me.modmuss50.mod-publish-plugin") version "2.0.0-beta.1"
}

apply(from = rootProject.file("build.common.gradle.kts"))

repositories {
    mavenCentral()
}

tasks.named<Jar>("sourcesJar").configure {
    dependsOn("stonecutterPrepare", "stonecutterGenerate")
}

afterEvaluate {
    extensions.findByType(SourceSetContainer::class.java)?.let { ssc ->
        val versionedFabricRes = rootProject.file("versions/${minecraftVersion}-fabric/src/fabric/resources")
        ssc.named("main") {
            java.setSrcDirs(
                listOf(
                    // Stonecutter-processed shared core (src/main/java)
                    file("build/generated/stonecutter/main/java"),
                    // loader-shared sources
                    rootProject.file("src/fabric/java"),
                    // per-Minecraft-version sources shared across loaders
                    rootProject.file("versions/${minecraftVersion}/src/java"),
                    // per-variant fabric sources
                    rootProject.file("versions/${minecraftVersion}-fabric/src/fabric/java")
                )
            )
            // Stonecutter only chisels Java into its generated tree; resources are not
            // redirected automatically. Point the resource roots at the chiseled mirror so
            // version/loader-conditional files (the mixin configs, accesswidener) are valid
            // for this variant instead of the raw templated src/main/resources.
            resources.setSrcDirs(
                listOfNotNull(
                    file("build/generated/stonecutter/main/resources"),
                    rootProject.file("src/main/generated"),
                    versionedFabricRes.takeIf { it.exists() }
                )
            )
        }
    }
    // Ensure stonecutter generation runs before compilation/resource processing for this variant
    tasks.matching { it.name == "compileJava" || it.name == "processResources" }.configureEach {
        dependsOn("stonecutterPrepare", "stonecutterGenerate")
    }
}


val modId: String by extra
val minecraftVersion: String by extra
val jedisVersion: String by extra
val rocksdbVersion: String by extra
val commonsPoolVersion: String by extra
val lz4Version: String by extra
val xzVersion: String by extra
val sqliteJdbcVersion: String by extra

val lwjglVersion = "3.3.1"

// Force LWJGL to the 1.20.1-compatible version for 1.20.1 variant projects.
if (project.name.startsWith("1.20.1")) {
    configurations.configureEach {
        resolutionStrategy {
            // Force specific LWJGL artifacts
            force("org.lwjgl:lwjgl:3.3.1", "org.lwjgl:lwjgl-lmdb:3.3.1", "org.lwjgl:lwjgl-zstd:3.3.1", "org.lwjgl:lwjgl-bom:3.3.1")
            // Ensure any org.lwjgl dependency uses 3.3.1
            eachDependency {
                if (requested.group == "org.lwjgl") {
                    useVersion("3.3.1")
                }
            }
        }
    }
}


loom {
    accessWidenerPath = sc.process(rootProject.file("src/main/resources/voxy.accesswidener"), "build/processed.accesswidener")

    // macOS Zink/KosmicKrisp dev-client support (Task 5): `-D` system properties passed on
    // the `./gradlew` command line are NOT forwarded to the forked run-client JVM by this
    // Loom version, and the run task's Exec environment does not inherit DYLD_* variables
    // exported in the invoking shell either (verified: without this block the client picks
    // up Apple's native "OpenGL 4.1 Metal" renderer, not Mesa/Zink). So when invoked with
    // -PzinkRun (see scripts/macos/run-zink-client.sh), inject the library-path -D args and
    // DYLD_*/MESA_*/VK_* env vars directly into the "client" run config via the Loom DSL.
    if (project.hasProperty("zinkRun")) {
        val home = System.getProperty("user.home")
        val mesa = "$home/mesa-native"
        val glfwLib = "$home/src/glfw/build/src/libglfw.3.dylib"
        val interposeLib = file("$mesa/lib/libgl_interpose.dylib")

        runs {
            named("client") {
                environmentVariable("DYLD_LIBRARY_PATH", "$mesa/lib")
                environmentVariable("LIBGL_DRIVERS_PATH", "$mesa/lib/dri")
                environmentVariable("VK_DRIVER_FILES", "$mesa/share/vulkan/icd.d/kosmickrisp_mesa_icd.aarch64.json")
                environmentVariable("EGL_PLATFORM", "surfaceless")
                environmentVariable("MESA_LOADER_DRIVER_OVERRIDE", "zink")
                environmentVariable("MESA_GL_VERSION_OVERRIDE", "4.6")
                environmentVariable("MESA_GLSL_VERSION_OVERRIDE", "460")
                // Task 8 debugging lever (brief-sanctioned): driver-side debug output.
                // NOTE: ZINK_DEBUG=validation was also tried and causes a hard native SIGSEGV
                // here ("MESA: error: Failed to load validation layer") - this Mesa build has no
                // VK_LAYER_KHRONOS_validation installed, so requesting the validation debug
                // channel crashes the loader itself rather than degrading gracefully. Left out.
                environmentVariable("MESA_DEBUG", "1")
                if (interposeLib.exists()) {
                    environmentVariable("DYLD_INSERT_LIBRARIES", interposeLib.absolutePath)
                }
                vmArg("-Dorg.lwjgl.egl.libname=$mesa/lib/libEGL.dylib")
                vmArg("-Dorg.lwjgl.opengl.libname=$mesa/lib/libGL.dylib")
                vmArg("-Dorg.lwjgl.glfw.libname=$glfwLib")
                // Task 8: JOML 1.10.5's "MemUtilUnsafe" fast path validates its Unsafe
                // field-offset assumptions for Matrix4f et al at class-init time
                // (MemUtil$MemUtilUnsafe.checkMatrix4f) and throws UnsupportedOperationException
                // ("Unexpected Matrix4f element offset") on this JDK 21 aarch64 build, where the
                // JVM's actual field layout doesn't match what JOML 1.10.5 assumed. This is a
                // pure JVM/library incompatibility (unrelated to the GL backend) that surfaces
                // the moment anything calls the affected methods - Voxy's own call sites were
                // rewritten (see JomlAddressWriter) to avoid them entirely, so this flag is now
                // defense-in-depth: it forces JOML's NIO-based fallback for any remaining/future
                // caller (mods, Iris shaderpacks) instead of silently corrupting or hard-crashing.
                vmArg("-Djoml.nounsafe=true")

                // Task 8: headless world auto-load for the dev client, since the client is
                // launched with no visible window to click "Singleplayer" -> world in. Gradle's
                // runClient task interprets `./gradlew ... --quickPlaySingleplayer foo` as an
                // (unknown) command-line option to the *task itself*, not a program arg forwarded
                // to the client JVM (same class of forwarding gap as Task 5's -D/env findings) -
                // so it must go through the Loom DSL's programArg(), gated on a Gradle property.
                // Usage: ./gradlew :1.21.1-fabric:runClient -PzinkRun -PquickPlayWorld="New World"
                if (project.hasProperty("quickPlayWorld")) {
                    programArg("--quickPlaySingleplayer")
                    programArg(project.property("quickPlayWorld") as String)
                }
                // Task 7/8: exercises Voxy's zero-tail MDI fallback path (for platforms without
                // GL_ARB_indirect_parameters) on any driver, to verify it independently of
                // whichever path the current GPU would normally take.
                // Usage: add -PvoxyForceNoIndirectCount to the runClient invocation.
                if (project.hasProperty("voxyForceNoIndirectCount")) {
                    vmArg("-Dvoxy.forceNoIndirectCount=true")
                }
                // Task 8: KosmicKrisp/Zink cannot service Voxy's default ~4GB single geometry
                // buffer allocation in one glNamedBufferStorage call (fails with
                // GL_OUT_OF_MEMORY - see RenderResourceReuse.getGeometryBufferSize()'s log line
                // and task-8-report.md for the investigation). No sparse-buffer fallback is
                // available here (ARB_sparse_buffer absent per Task 4's extension dump), so this
                // is a documented operational workaround rather than a code fix: pick a smaller
                // geometry buffer size that KosmicKrisp can actually allocate in one call.
                // Usage: ./gradlew :1.21.1-fabric:runClient -PzinkRun -PgeomBufMB=512
                if (project.hasProperty("geomBufMB")) {
                    vmArg("-Dvoxy.geometryBufferSizeOverrideMB=" + project.property("geomBufMB"))
                }
            }
        }
    }
}

dependencies {
//    annotationProcessor("net.fabricmc:sponge-mixin:0.17.2+mixin.0.8.7")
    annotationProcessor("io.github.llamalad7:mixinextras-common:0.5.4")
    compileOnly("io.github.llamalad7:mixinextras-common:0.5.4")
    compileOnly("io.github.llamalad7:mixinextras-fabric:0.5.4")
    compileOnly("net.fabricmc:sponge-mixin:0.17.2+mixin.0.8.7")

    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.layered {
        officialMojangMappings()
        providers.gradleProperty("deps.parchment").orNull?.let { parchment("org.parchmentmc.data:parchment-$it@zip") }
    })

    val fabricLoaderVersion = prop("deps.fabric-loader", "fabric_loader_version")
    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")

    val fabricApiVersion = prop("deps.fabric-api", "fabric_api_version")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    val modules = listOf("transitive-access-wideners-v1", "registry-sync-v0", "resource-loader-v0")
    for (module in modules) modImplementation(fabricApi.module("fabric-$module", fabricApiVersion))

    val sodiumFabricVer = prop("deps.sodium")
    val useModrinth = prop("deps.sodium.modrinth").toBoolean()
    if (useModrinth) {
        modImplementation("maven.modrinth:sodium:$sodiumFabricVer")
    } else {
        modImplementation("net.caffeinemc:sodium-fabric:$sodiumFabricVer")
        modImplementation("net.caffeinemc:sodium-fabric-api:$sodiumFabricVer")
    }

    val lithiumFabricVer = prop("deps.lithium")
    modImplementation("maven.modrinth:lithium:$lithiumFabricVer")

    val nvidiumMaven = prop("deps.nvidium_maven")
    modCompileOnly("${nvidiumMaven}:nvidium:${prop("deps.nvidium")}")

    val modmenuVer = prop("deps.modmenu")
    modCompileOnly("maven.modrinth:modmenu:$modmenuVer")
    modRuntimeOnly("maven.modrinth:modmenu:$modmenuVer")

    val irisFabricVer = prop("deps.iris")
    modCompileOnly("maven.modrinth:iris:$irisFabricVer")

    modRuntimeOnly("io.github.douira:glsl-transformer:2.0.1")
    modRuntimeOnly("org.anarres:jcpp:1.4.14")

    val sodiumExtraFabric = prop("deps.sodium.extra")
    modCompileOnly("maven.modrinth:sodium-extra:$sodiumExtraFabric")
    // modRuntimeOnly("maven.modrinth:sodium-extra:$sodiumExtraFabric")

    val chunkyFabric = prop("deps.chunky")
    modCompileOnly("maven.modrinth:chunky:$chunkyFabric")
    modRuntimeOnly("maven.modrinth:chunky:$chunkyFabric")

    val sparkFabric = prop("deps.spark")
    modRuntimeOnly("maven.modrinth:spark:$sparkFabric")

    val fabricPerms = prop("deps.fabric.permissions")
    modRuntimeOnly("maven.modrinth:fabric-permissions-api:$fabricPerms")

    val viveFabric = prop("deps.vivecraft")
    modCompileOnly("maven.modrinth:vivecraft:$viveFabric")
    modCompileOnly("maven.modrinth:flashback:${prop("deps.flashback")}")

    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-lmdb:$lwjglVersion")
    implementation("org.lwjgl:lwjgl-zstd:$lwjglVersion")
    runtimeOnly("org.lwjgl:lwjgl:$lwjglVersion:natives-windows")
    runtimeOnly("org.lwjgl:lwjgl:$lwjglVersion:natives-linux")
    runtimeOnly("org.lwjgl:lwjgl-lmdb:$lwjglVersion:natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-zstd:$lwjglVersion:natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-lmdb:$lwjglVersion:natives-linux")
    runtimeOnly("org.lwjgl:lwjgl-zstd:$lwjglVersion:natives-linux")
    // macOS (Task 8): these two LWJGL modules are extras Voxy adds on top of what Minecraft's
    // own vanilla LWJGL set already provides (which Mojang already ships natives-macos for) -
    // lwjgl-lmdb/lwjgl-zstd only had windows/linux natives declared here, so on macOS
    // liblwjgl_zstd.dylib/liblwjgl_lmdb.dylib were never on the classpath, and Voxy's
    // background storage-compression workers (ZSTDCompressor) threw UnsatisfiedLinkError
    // the first time they ran (LMDB storage would hit the same gap once exercised).
    runtimeOnly("org.lwjgl:lwjgl-lmdb:$lwjglVersion:natives-macos")
    runtimeOnly("org.lwjgl:lwjgl-zstd:$lwjglVersion:natives-macos")
    runtimeOnly("org.lwjgl:lwjgl-lmdb:$lwjglVersion:natives-macos-arm64")
    runtimeOnly("org.lwjgl:lwjgl-zstd:$lwjglVersion:natives-macos-arm64")

    implementation(include("redis.clients:jedis:$jedisVersion")!!)
    implementation(include("org.rocksdb:rocksdbjni:$rocksdbVersion")!!)
    implementation(include("org.apache.commons:commons-pool2:$commonsPoolVersion")!!)
    implementation(include("org.lz4:lz4-java:$lz4Version")!!)
    implementation(include("org.tukaani:xz:$xzVersion")!!)
    implementation(include("org.xerial:sqlite-jdbc:$sqliteJdbcVersion")!!)
    implementation(include("org.lwjgl:lwjgl-lmdb:$lwjglVersion")!!)
    implementation(include("org.lwjgl:lwjgl-zstd:$lwjglVersion")!!)
    implementation(include("org.lwjgl:lwjgl-lmdb:$lwjglVersion:natives-windows")!!)
    implementation(include("org.lwjgl:lwjgl-zstd:$lwjglVersion:natives-windows")!!)
    implementation(include("org.lwjgl:lwjgl-lmdb:$lwjglVersion:natives-linux")!!)
    implementation(include("org.lwjgl:lwjgl-zstd:$lwjglVersion:natives-linux")!!)
    implementation(include("org.lwjgl:lwjgl-lmdb:$lwjglVersion:natives-macos")!!)
    implementation(include("org.lwjgl:lwjgl-zstd:$lwjglVersion:natives-macos")!!)
    implementation(include("org.lwjgl:lwjgl-lmdb:$lwjglVersion:natives-macos-arm64")!!)
    implementation(include("org.lwjgl:lwjgl-zstd:$lwjglVersion:natives-macos-arm64")!!)
    minecraftRuntimeLibraries("org.xerial:sqlite-jdbc:$sqliteJdbcVersion")
}

fabricApi {
    configureDataGeneration() {
        outputDirectory = file("$rootDir/src/main/generated")
        client = true
    }
}

// The mixin configs (client/common.voxy.mixins.json) live in src/main/resources and are
// chiseled by Stonecutter into build/generated/stonecutter/main/resources, which is the
// main resource source dir. processResources copies them as-is; Fabric's mixin loader
// reads the chiseled output (which retains `//?` markers as comments) without issue.