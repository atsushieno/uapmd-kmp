import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
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
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// ─── Build libuapmd-c-api.so for each Android ABI ────────────────────────────
//
// These tasks invoke CMake against the uapmd Android source tree to produce the
// pre-built shared library that uapmd-jni links against.  They run before AGP's
// own cmake build tasks so the .so is present when the linker needs it.

val uapmdAndroidAbis = listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86")

// Source tree that contains the full uapmd Android native build (CMakeLists.txt
// already knows how to build uapmd-c-api via CPM-fetched deps).
val uapmdAndroidNativeSrc = rootProject.file("external/uapmd/android/app/src/main/cpp")

// CPM download cache — shared with the uapmd Android app build to avoid
// re-downloading dependencies on every configure.
val cpmCacheDir = File(System.getProperty("user.home"), ".cache/CPM/uapmd")

// Helper: locate the cmake binary bundled with the Android SDK.
fun cmakeExe(): String {
    val sdkDir = android.sdkDirectory
    // AGP installs cmake 3.22.1 under $ANDROID_HOME/cmake/<version>/bin/cmake
    val sdkCmake = File(sdkDir, "cmake/3.22.1/bin/cmake")
    return if (sdkCmake.exists()) sdkCmake.absolutePath else "cmake"
}

// Register one task per ABI.  Each task:
//   1. Runs cmake -S … -B … (configure) — no-op if CMake cache is up to date.
//   2. Runs cmake --build … --target uapmd-c-api (build).
//   3. Copies the resulting .so to external/uapmd/build-android/<abi>/.
uapmdAndroidAbis.forEach { abi ->
    val outputDir  = rootProject.file("external/uapmd/build-android/$abi")
    val outputSo   = File(outputDir, "libuapmd-c-api.so")
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

            val cmake = cmakeExe()
            val ndkDir = android.ndkDirectory

            // ── 1. Configure ───────────────────────────────────────────────
            exec {
                commandLine(
                    cmake,
                    "-S", uapmdAndroidNativeSrc.absolutePath,
                    "-B", buildDir.absolutePath,
                    "-DCMAKE_TOOLCHAIN_FILE=${ndkDir}/build/cmake/android.toolchain.cmake",
                    "-DANDROID_ABI=$abi",
                    "-DANDROID_PLATFORM=android-26",
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
                    "-DCPM_SOURCE_CACHE=${cpmCacheDir.absolutePath}",
                    "-DMIDICCI_SKIP_TOOLS=ON"
                )
            }

            // ── 2. Build target ────────────────────────────────────────────
            exec {
                commandLine(
                    cmake,
                    "--build", buildDir.absolutePath,
                    "--target", "uapmd-c-api",
                    "--parallel"
                )
            }

            // ── 3. Copy .so to expected location ───────────────────────────
            val built = fileTree(buildDir) { include("**/libuapmd-c-api.so") }.singleFile
            built.copyTo(outputSo, overwrite = true)
            logger.lifecycle("uapmd-c-api [$abi]: copied ${built.absolutePath} → $outputSo")
        }
    }
}

// Wire: AGP's cmake build tasks must run AFTER our native build tasks so the
// imported .so is on disk before the linker processes uapmd-jni.
afterEvaluate {
    uapmdAndroidAbis.forEach { abi ->
        val nativeTask = "buildUapmdCApiNative[$abi]"
        listOf("Debug", "Release").forEach { variant ->
            tasks.findByName("buildCMake$variant[$abi]")
                ?.dependsOn(nativeTask)
        }
    }
}
