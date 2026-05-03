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
    }

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            implementation(libs.jna)
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
                cppFlags("-std=c++17")
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
}

// ─── Build libuapmd-c-api.so for each Android ABI ────────────────────────────
val uapmdAndroidAbis   = listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86")
val uapmdAndroidNativeSrc = rootProject.file("external/uapmd/android/app/src/main/cpp")

uapmdAndroidAbis.forEach { abi ->
    val outputDir      = rootProject.file("external/uapmd/build-android/$abi")
    val outputSo       = File(outputDir, "libuapmd-c-api.so")
    val nativeBuildDir = layout.buildDirectory.dir("uapmd-c-api-native/$abi")

    tasks.register("buildUapmdCApiNative[$abi]") {
        group       = "build"
        description = "Builds libuapmd-c-api.so for Android ABI $abi"
        inputs.dir(uapmdAndroidNativeSrc)
        outputs.file(outputSo)

        doLast {
            val buildDir = nativeBuildDir.get().asFile
            buildDir.mkdirs()
            outputDir.mkdirs()

            exec {
                commandLine(
                    "cmake",
                    "-S", uapmdAndroidNativeSrc.absolutePath,
                    "-B", buildDir.absolutePath,
                    "-DCMAKE_TOOLCHAIN_FILE=${android.ndkDirectory}/build/cmake/android.toolchain.cmake",
                    "-DANDROID_ABI=$abi",
                    "-DANDROID_PLATFORM=android-${android.defaultConfig.minSdk}",
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
                    "-DCPM_SOURCE_CACHE=${cpmCacheDir.absolutePath}",
                    "-DMIDICCI_SKIP_TOOLS=ON"
                )
            }
            exec {
                commandLine("cmake", "--build", buildDir.absolutePath,
                    "--target", "uapmd-c-api", "--parallel")
            }
            val built = fileTree(buildDir) { include("**/libuapmd-c-api.so") }.singleFile
            built.copyTo(outputSo, overwrite = true)
        }
    }
}

afterEvaluate {
    uapmdAndroidAbis.forEach { abi ->
        listOf("Debug", "Release").forEach { variant ->
            tasks.findByName("buildCMake$variant[$abi]")
                ?.dependsOn("buildUapmdCApiNative[$abi]")
        }
    }
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
