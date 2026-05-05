import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.dokka)
}

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
                includeDirs(rootProject.file("external/uapmd/source/uapmd-c-api/include"))
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
            implementation(files(rootProject.file("external/uapmd/android/external/SDL3-3.4.0.aar")))
        }
    }
}

val cpmCacheDir = File(System.getProperty("user.home"), ".cache/CPM/uapmd")

android {
    namespace = "dev.atsushieno.uapmd"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        externalNativeBuild {
            cmake {
                arguments.addAll(listOf(
                    "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
                    "-DMIDICCI_SKIP_TOOLS=ON",
                    "-DCPM_SOURCE_CACHE=${cpmCacheDir.absolutePath}",
                    "-DANDROID_STL=c++_shared",
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
// Produces:
//   external/uapmd/cmake-build-debug/source/uapmd-c-api/libuapmd-c-api.dylib  (macOS)
//   external/uapmd/cmake-build-debug/source/uapmd-c-api/libuapmd-c-api.so     (Linux)
//
// The dylib is then copied into the JNE resource path so JNE.loadLibrary() can
// find and extract it at runtime without requiring java.library.path tricks.

val uapmdSrcDir      = rootProject.file("external/uapmd")
val uapmdBuildDir    = rootProject.file("external/uapmd/cmake-build-debug")
val uapmdDylibDir    = File(uapmdBuildDir, "source/uapmd-c-api")
val os               = org.gradle.internal.os.OperatingSystem.current()

tasks.register("buildUapmdCApiDesktop") {
    group       = "build"
    description = "Builds libuapmd-c-api shared library for JVM desktop via cmake"

    inputs.dir(File(uapmdSrcDir, "source/uapmd-c-api"))
    if (os.isMacOsX)
        outputs.file(File(uapmdDylibDir, "libuapmd-c-api.dylib"))
    else
        outputs.file(File(uapmdDylibDir, "libuapmd-c-api.so"))

    doLast {
        val patchFile = rootProject.file("external/uapmd-jvm-shared.patch")
        uapmdBuildDir.mkdirs()

        // Apply the patch from this (parent) repo to the submodule working tree.
        exec {
            workingDir = uapmdSrcDir
            commandLine("git", "apply", "--whitespace=fix", patchFile.absolutePath)
        }

        try {
            exec {
                workingDir = uapmdSrcDir
                commandLine(
                    "cmake", "--build", uapmdBuildDir.absolutePath,
                    "--target", "uapmd-c-api",
                    "--parallel"
                )
            }
        } finally {
            // Always restore the submodule to HEAD so it stays clean.
            exec {
                workingDir = uapmdSrcDir
                commandLine("git", "checkout", "--", ".")
            }
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
val jneLibName = if (os.isMacOsX || os.isLinux) "libuapmd-c-api.${if (os.isMacOsX) "dylib" else "so"}"
                 else "uapmd-c-api.dll"
val jneResourceDir = project.file("src/jvmMain/resources/jne/$jneOs/$jneArch")

tasks.register<Copy>("copyUapmdDylibToJneResources") {
    group       = "build"
    description = "Copies libuapmd-c-api into the JNE resource path for classpath discovery"
    dependsOn("buildUapmdCApiDesktop")
    from(File(uapmdDylibDir, jneLibName))
    into(jneResourceDir)
}

// Wire: compileKotlinJvm depends on the shared library being staged in resources
afterEvaluate {
    tasks.findByName("compileKotlinJvm")?.dependsOn("copyUapmdDylibToJneResources")
    tasks.findByName("jvmProcessResources")?.dependsOn("copyUapmdDylibToJneResources")
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
val wasmOutputDir = rootProject.file("external/uapmd/build-wasm")
val wasmBuildDir  = layout.buildDirectory.dir("uapmd-c-api-wasm")

tasks.register("buildUapmdCApiWasm") {
    group       = "build"
    description = "Builds uapmd-c-api.js + uapmd-c-api.wasm via Emscripten"

    inputs.dir(wasmSrcDir)
    inputs.dir(rootProject.file("external/uapmd/source/uapmd-c-api"))
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
        exec {
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
        exec {
            commandLine(
                "cmake",
                "--build", buildDir.absolutePath,
                "--target", "uapmd-c-api-web",
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
