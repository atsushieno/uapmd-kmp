import org.gradle.api.file.FileSystemOperations
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.dokka)
}

val repoRoot = rootProject.projectDir.parentFile
val cpmCacheDir = File(System.getProperty("user.home"), ".cache/CPM/uapmd")

// Gradle 9 removed Project.exec()/Project.copy() from task actions. Ad-hoc task
// actions must obtain the corresponding services by injection instead.
interface GradleServices {
    @get:Inject val exec: ExecOperations
    @get:Inject val fs: FileSystemOperations
}
val buildServices = objects.newInstance<GradleServices>()

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.compilations["main"].cinterops {
            create("uapmd") {
                defFile(project.file("cinterop/uapmd.def"))
                includeDirs(repoRoot.resolve("c-api/include"))
            }
        }
    }

    jvm()

    js {
        browser()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    compilerOptions {
        // UInt/UByte/UShort/ULong are stable in Kotlin 2.x; suppress the opt-in noise.
        optIn.add("kotlin.ExperimentalUnsignedTypes")
        optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
    }

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            implementation(libs.jna)
            implementation(libs.jne)
        }
        androidMain.dependencies {
            implementation(libs.oboe)
            implementation(libs.androidaudioplugin)
            implementation(files(repoRoot.resolve("external/uapmd/android/external/SDL3-3.4.0.aar")))
        }
        jsMain.dependencies {
            implementation(libs.kotlinx.coroutinesCore)
        }
        wasmJsMain.dependencies {
            implementation(libs.kotlinx.coroutinesCore)
        }
    }
}

android {
    namespace = "dev.atsushieno.uapmd"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        externalNativeBuild {
            cmake {
                arguments.addAll(listOf(
                    "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
                    "-DMIDICCI_SKIP_TOOLS=ON",
                    "-DCPM_SOURCE_CACHE=${cpmCacheDir.absolutePath}",
                    "-DANDROID_STL=c++_shared",
                    // ARA_SDK's ARAInterface.h only supports x86/x86_64 and 64-bit ARM, so it
                    // fails to compile for the 32-bit armeabi-v7a ABI ("unsupported CPU
                    // architecture"). uapmd already defaults UAPMD_ENABLE_ARA to OFF, but
                    // option() cannot lower a value already present in an AGP .cxx CMake cache,
                    // so state it explicitly to keep Android builds reproducible.
                    "-DUAPMD_ENABLE_ARA=OFF",
                    "-DAAP_DIR=placeholder"
                ))
                targets.add("uapmd-jni")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/androidMain/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        prefab = true
    }
}

// ─── Build uapmd-c-api desktop shared library ────────────────────────────────
//
// Uses the top-level CMakeLists.txt which builds the uapmd engine from the
// submodule and then our owned c-api/ wrapper as a SHARED library.
//
// Produces:
//   cmake-build-debug/c-api/libuapmd-c-api.dylib  (macOS)
//   cmake-build-debug/c-api/libuapmd-c-api.so     (Linux)

// ─── uapmd submodule patches ──────────────────────────────────────────────────
//
// uapmd-cmp needs a few embedder hooks that upstream does not have yet. They live
// in external/uapmd, which is a pinned submodule, so a fresh checkout — CI's, in
// particular — has none of them and would compile the unpatched sources. Every
// native build therefore tries to apply patches/uapmd/*.patch first.
//
// This step is deliberately **best-effort and never fatal**. It runs on every build,
// including every incremental one, and the states it meets are messy by nature: the
// patch already applied, applied to some files and not others, or applied and then
// edited further while working on the hook itself. Refusing to build in those cases
// would block ordinary work for no benefit — and it is not needed to keep anyone
// safe, because sources that really lack the hooks fail to compile a few seconds
// later, at the call site, with a far clearer message than a patch-tool error.
//
// `--3way` does the heavy lifting: it merges rather than demanding exact context, so
// a partially applied patch completes instead of being rejected.
val uapmdSubmoduleDir = repoRoot.resolve("external/uapmd")
val uapmdPatchDir     = repoRoot.resolve("patches/uapmd")

fun runGitInSubmodule(vararg args: String): Pair<Int, String> {
    val process = ProcessBuilder(listOf("git") + args)
        .directory(uapmdSubmoduleDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    return process.waitFor() to output
}

tasks.register("applyUapmdPatches") {
    group       = "build"
    description = "Applies patches/uapmd/*.patch to the external/uapmd submodule (best-effort)"

    val patches = uapmdPatchDir.listFiles { f: File -> f.name.endsWith(".patch") }
        ?.sortedBy { it.name }
        .orEmpty()
    inputs.files(patches)

    doLast {
        if (patches.isEmpty())
            return@doLast

        patches.forEach { patch ->
            // Split per file and treat each independently. A patch is routinely half
            // applied — one file edited further, another untouched — and applying it
            // whole would reject the lot because of the parts already in place.
            val perFile = patch.readText()
                .split(Regex("(?m)^(?=diff --git )"))
                .filter { it.startsWith("diff --git ") }
            if (perFile.isEmpty()) {
                logger.warn("uapmd patches: ${patch.name} contains no file diffs; skipping")
                return@forEach
            }

            perFile.forEach { fileDiff ->
                val target = Regex("""^diff --git a/(\S+)""").find(fileDiff)?.groupValues?.get(1)
                    ?: "(unknown)"
                val fragment = File.createTempFile("uapmd-patch-", ".diff")
                try {
                    fragment.writeText(fileDiff)
                    val fragmentPath = fragment.absolutePath

                    if (runGitInSubmodule("apply", "--reverse", "--check", fragmentPath).first == 0) {
                        logger.info("uapmd patches: $target already patched")
                        return@forEach
                    }
                    if (runGitInSubmodule("apply", "--3way", fragmentPath).first == 0) {
                        // --3way stages what it merges. A build has no business
                        // leaving things staged in the submodule, so unstage exactly
                        // the file we touched and leave the working tree alone.
                        runGitInSubmodule("reset", "--quiet", "--", target)
                        logger.lifecycle("uapmd patches: patched $target")
                        return@forEach
                    }
                    // Never fatal, and never destructive: the file is left exactly as
                    // the developer has it. Sources that genuinely lack the hooks fail
                    // to compile moments later at the call site, which says far more
                    // than a patch-tool error would.
                    //
                    // A file that already differs from HEAD is almost always the hook
                    // being worked on — patch applied and then edited further — which
                    // is normal and not worth shouting about. A file identical to HEAD
                    // that still refuses the patch is the case worth seeing: the
                    // submodule has moved and the patch is stale.
                    val locallyModified = runGitInSubmodule("diff", "--quiet", "HEAD", "--", target).first != 0
                    if (locallyModified)
                        logger.lifecycle("uapmd patches: $target is locally modified; leaving it as it is")
                    else
                        logger.warn(
                            "uapmd patches: $target does not take the patch and is unmodified — " +
                                "external/uapmd has probably moved. Refresh it with:\n" +
                                "    git -C external/uapmd diff > ${patch.absolutePath}"
                        )
                } finally {
                    fragment.delete()
                }
            }
        }
    }
}

val os           = org.gradle.internal.os.OperatingSystem.current()
val cmakeBuildDir = repoRoot.resolve("cmake-build-debug")
val cApiDylibDir  = File(cmakeBuildDir, "c-api")

tasks.register("buildUapmdCApiDesktop") {
    group       = "build"
    description = "Builds libuapmd-c-api shared library for JVM desktop via cmake"
    dependsOn("applyUapmdPatches")

    inputs.dir(repoRoot.resolve("c-api"))
    outputs.file(File(cApiDylibDir, when {
        os.isWindows -> "RelWithDebInfo/uapmd-c-api.dll"
        os.isMacOsX  -> "libuapmd-c-api.dylib"
        else         -> "libuapmd-c-api.so"
    }))

    doLast {
        cmakeBuildDir.mkdirs()
        // Configure if this is a fresh build directory.
        if (!File(cmakeBuildDir, "CMakeCache.txt").exists()) {
            val configureArgs = mutableListOf(
                "cmake", "-B", cmakeBuildDir.absolutePath,
                "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
                "-DCPM_SOURCE_CACHE=${cpmCacheDir.absolutePath}",
                "-DUAPMD_BUILD_TESTS=OFF",
                "-DMIDICCI_SKIP_TOOLS=ON"
            )
            when {
                os.isWindows -> configureArgs.addAll(listOf("-G", "Visual Studio 18 2026"))
                os.isLinux   -> configureArgs.addAll(listOf("-G", "Ninja",
                    "-DCMAKE_C_COMPILER=clang", "-DCMAKE_CXX_COMPILER=clang++",
                    "-DCMAKE_POSITION_INDEPENDENT_CODE=ON"))
                else         -> configureArgs.addAll(listOf("-G", "Ninja"))
            }
            buildServices.exec.exec {
                workingDir = repoRoot
                commandLine(configureArgs)
            }
        }
        buildServices.exec.exec {
            workingDir = repoRoot
            commandLine(
                "cmake", "--build", cmakeBuildDir.absolutePath,
                "--target", "uapmd-c-api",
                "--parallel"
            )
        }
    }
}

// ─── Copy dylib into JNE resource path ───────────────────────────────────────
//
// JNE looks for native libraries under jne/{os}/{arch}/{filename} in the
// classpath.  This task stages the built dylib into the jvmMain resources so
// it is included in the JAR and discoverable by JNE.loadLibrary() at runtime.

val jneArch = System.getProperty("os.arch").let {
    if (it == "aarch64") "arm64" else "x86_64"
}
val jneOs = when {
    os.isMacOsX  -> "macos"
    os.isLinux   -> "linux"
    os.isWindows -> "windows"
    else         -> "unknown"
}
// VS generator places outputs in a config subdirectory (e.g. RelWithDebInfo/); Ninja does not.
val jneLibName = when {
    os.isMacOsX  -> "libuapmd-c-api.dylib"
    os.isLinux   -> "libuapmd-c-api.so"
    else         -> "uapmd-c-api.dll"
}
val jneLibFile = if (os.isWindows) File(cApiDylibDir, "RelWithDebInfo/$jneLibName")
                 else File(cApiDylibDir, jneLibName)
val jneResourceDir = project.file("src/jvmMain/resources/jne/$jneOs/$jneArch")

tasks.register<Copy>("copyUapmdDylibToJneResources") {
    group       = "build"
    description = "Copies libuapmd-c-api into the JNE resource path for classpath discovery"
    dependsOn("buildUapmdCApiDesktop")
    from(jneLibFile)
    into(jneResourceDir)
}

// Wire: compileKotlinJvm depends on the shared library being staged in resources
afterEvaluate {
    // AGP generates the CMake tasks, so they can only be wired once they exist.
    tasks.matching { it.name.startsWith("buildCMake") || it.name.startsWith("externalNativeBuild") }
        .configureEach { dependsOn("applyUapmdPatches") }

    tasks.findByName("compileKotlinJvm")?.dependsOn("copyUapmdDylibToJneResources")
    tasks.findByName("jvmProcessResources")?.dependsOn("copyUapmdDylibToJneResources")
    // AGP does not allow local .aar file dependencies when bundling a library AAR.
    // uapmd-binding is not published, so skip the AAR bundling tasks entirely.
    tasks.findByName("bundleReleaseAar")?.enabled = false
    tasks.findByName("bundleDebugAar")?.enabled = false
}

// ─── Build uapmd-c-api Emscripten Wasm module ─────────────────────────────────
//
// Produces:
//   external/uapmd/build-wasm/uapmd-c-api.js
//   external/uapmd/build-wasm/uapmd-c-api.wasm
//
// Prerequisites: emcmake/emcc in PATH (Emscripten SDK activated).
// The output files are bundled as resources in the jsMain and wasmJsMain
// source sets so the KMP binding can load the module at runtime.

val wasmSrcDir    = project.file("src/webMain/cpp")
val wasmOutputDir = repoRoot.resolve("build-wasm")
val wasmBuildDir  = layout.buildDirectory.dir("uapmd-c-api-wasm")
val wclapOverrideDir = repoRoot.resolve("external/uapmd/source/tools/wclap-host/web-overrides")

tasks.register("buildUapmdCApiWasm") {
    dependsOn("applyUapmdPatches")
    group       = "build"
    description = "Builds uapmd-c-api.js + uapmd-c-api.wasm via Emscripten"

    inputs.dir(wasmSrcDir)
    inputs.dir(repoRoot.resolve("c-api"))
    outputs.file(File(wasmOutputDir, "uapmd-c-api.js"))
    outputs.file(File(wasmOutputDir, "uapmd-c-api.wasm"))

    doFirst {
        // Require emcmake — fail early with a clear message
        val emcmake = ProcessBuilder("which", "emcmake")
            .redirectErrorStream(true)
            .start()
            .inputStream.bufferedReader().readText().trim()
        if (emcmake.isEmpty()) {
            throw GradleException(
                "emcmake not found in PATH.\n" +
                "Activate the Emscripten SDK before building:\n" +
                "  source /path/to/emsdk/emsdk_env.sh"
            )
        }
    }

    doLast {
        val buildDir = wasmBuildDir.get().asFile
        buildDir.mkdirs()
        wasmOutputDir.mkdirs()

        val cpmCacheArg = "-DCPM_SOURCE_CACHE=${cpmCacheDir.absolutePath}"

        // ── 1. Configure ──────────────────────────────────────────────────────
        buildServices.exec.exec {
            commandLine(
                "emcmake", "cmake",
                "-S", wasmSrcDir.absolutePath,
                "-B", buildDir.absolutePath,
                "-G", "Ninja",
                "-DCMAKE_BUILD_TYPE=Release",
                cpmCacheArg,
                "-DUAPMD_BUILD_TESTS=OFF",
                "-DMIDICCI_SKIP_TOOLS=ON"
            )
        }

        // ── 2. Build ──────────────────────────────────────────────────────────
        buildServices.exec.exec {
            commandLine(
                "cmake",
                "--build", buildDir.absolutePath,
                "--target", "uapmd-c-api-web", "uapmd-wclap-host",
                "--parallel"
            )
        }

        // ── 3. Copy outputs ───────────────────────────────────────────────────
        listOf("uapmd-c-api.js", "uapmd-c-api.wasm").forEach { name ->
            val built = File(buildDir, name)
            if (!built.exists()) {
                throw GradleException("Expected Emscripten output not found: ${built.absolutePath}")
            }
            built.copyTo(File(wasmOutputDir, name), overwrite = true)
        }

        val wclapHostWasm = File(buildDir, "uapmd/tools/wclap-host/uapmd-wclap-host.wasm")
        if (wclapHostWasm.exists()) {
            wclapHostWasm.copyTo(File(wasmOutputDir, "uapmd-wclap-host.wasm"), overwrite = true)
        }

        val wclapModule = fileTree(cpmCacheDir.resolve("webclap-browser-test-host")) {
            include("**/clap-audionode/wclap-js/wclap.mjs")
        }.files.singleOrNull()
            ?: throw GradleException("Unable to locate WebCLAP runtime module in $cpmCacheDir")
        wclapModule.copyTo(File(wasmOutputDir, "wclap.mjs"), overwrite = true)

        val wclapEs6Dir = wclapModule.parentFile.resolve("es6")
        if (!wclapEs6Dir.isDirectory) {
            throw GradleException("Expected WebCLAP es6 runtime directory at ${wclapEs6Dir.absolutePath}")
        }
        buildServices.fs.copy {
            from(wclapEs6Dir)
            into(File(wasmOutputDir, "es6"))
        }

        val overrideWclap = wclapOverrideDir.resolve("es6/wclap.mjs")
        if (overrideWclap.exists()) {
            overrideWclap.copyTo(File(wasmOutputDir, "es6/wclap.mjs"), overwrite = true)
        }
        logger.lifecycle("uapmd-c-api Wasm: outputs copied to $wasmOutputDir")
    }
}

// Wire: compileKotlinJs and compileKotlinWasmJs must run after the Wasm build
// so the JS/Wasm assets exist before they are bundled as resources.
afterEvaluate {
    listOf("compileKotlinJs", "compileKotlinWasmJs").forEach { taskName ->
        tasks.findByName(taskName)?.dependsOn("buildUapmdCApiWasm")
    }
}

// ── Dokka ─────────────────────────────────────────────────────────────────────
dokka {
    moduleName.set("uapmd-binding")
    dokkaSourceSets {
        // Document the shared API surface
        named("commonMain") {
            displayName.set("Common")
            reportUndocumented.set(false)
            skipDeprecated.set(false)
        }
        // Platform-specific source sets documented but grouped separately
        named("androidMain")  { displayName.set("Android") }
        named("jvmMain")      { displayName.set("JVM") }
        named("jsMain")       { displayName.set("JS") }
        named("wasmJsMain")   { displayName.set("WasmJs") }
    }
}
