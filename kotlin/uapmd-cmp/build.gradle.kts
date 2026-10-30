import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
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
        iosTarget.binaries.framework {
            baseName = "UapmdCmp"
            isStatic = true
        }
    }

    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidaudioplugin)
            implementation(libs.androidaudioplugin.ara)
            implementation(libs.androidx.startup)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.audio.controls)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(project(":uapmd-binding"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            // Assume, to skip a test whose plug-in or input file is not on this machine.
            implementation(libs.junit)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.jna)
        }
    }
}

android {
    namespace = "dev.atsushieno.uapmd_cmp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.atsushieno.uapmd_cmp"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
        // androidaudioplugin-ara ships arm64-v8a and x86_64 only (ARA_API has no 32-bit ARM
        // support), and Gradle resolves its prefab package for every ABI built. uapmd's own
        // Android app builds the same two.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(libs.compose.uiTooling)
}

// Where the out-of-process plug-in scanner lives.
//
// Remote scanning relaunches the host executable with `--scan-only --ipc-client`,
// which on the JVM is `java` and serves no scanner, so uapmd is told to launch
// uapmd's own `uapmd-scan` instead. Without this the app falls back to scanning
// in process, where a single bad plug-in takes the whole app down mid-scan.
// `:uapmd-binding:buildUapmdScanDesktop` builds it; the Visual Studio generator puts
// it in a per-configuration subdirectory.
val uapmdScanExecutable: File = rootProject.projectDir.parentFile
    .resolve("cmake-build-debug/uapmd-source/tools/uapmd-scan/" +
        if (org.gradle.internal.os.OperatingSystem.current().isWindows) "Release/uapmd-scan.exe" else "uapmd-scan")

// JSFX editors draw their text with a font the app supplies wherever the platform has no
// font stack to borrow (Jsfx.usesOwnFontBackend: Android, iOS, the web). uapmd-app hands
// them the face it uses itself, Roboto SemiBold, so this ships the same file -- taken from
// the uapmd checkout rather than copied into this repository.
val jsfxEditorFontResources = layout.buildDirectory.dir("generated/jsfxEditorFont")
val syncJsfxEditorFont by tasks.registering(Sync::class) {
    from(rootProject.projectDir.parentFile.resolve("external/uapmd/external/font/static/Roboto-SemiBold.ttf"))
    into(jsfxEditorFontResources.map { it.dir("files") })
}

compose.resources {
    packageOfResClass = "dev.atsushieno.uapmd.cmp.generated.resources"
    customDirectory(
        sourceSetName = "commonMain",
        // Mapped from the task, so whatever reads the resources runs the sync first.
        directoryProvider = syncJsfxEditorFont.map { jsfxEditorFontResources.get() }
    )
}

compose.desktop {
    application {
        mainClass = "dev.atsushieno.uapmd.cmp.MainKt"
        jvmArgs += listOf(
            "-Dapple.awt.application.name=uapmd-cmp",
            "-Xdock:name=uapmd-cmp",
            // A JVM crash in native code writes this alongside the system's own report.
            // The system one names no Java frames at all, so it cannot say which call
            // crossed into native code -- which is the only part worth knowing.
            "-XX:ErrorFile=${layout.buildDirectory.get().asFile}/hs_err_pid%p.log"
        ) + listOfNotNull(
            System.getProperty("uapmd.cmp.importMidi")?.let { "-Duapmd.cmp.importMidi=$it" },
            System.getProperty("uapmd.cmp.instantiate")?.let { "-Duapmd.cmp.instantiate=$it" },
            System.getProperty("uapmd.cmp.windowSize")?.let { "-Duapmd.cmp.windowSize=$it" },
            System.getProperty("uapmd.cmp.addTracks")?.let { "-Duapmd.cmp.addTracks=$it" },
            System.getProperty("uapmd.cmp.loadProject")?.let { "-Duapmd.cmp.loadProject=$it" },
            System.getProperty("uapmd.cmp.loadCount")?.let { "-Duapmd.cmp.loadCount=$it" },
            System.getProperty("uapmd.cmp.forceRescan")?.let { "-Duapmd.cmp.forceRescan=$it" },
            System.getProperty("uapmd.cmp.noPoll")?.let { "-Duapmd.cmp.noPoll=$it" },
            System.getProperty("uapmd.cmp.showLoadedUi")?.let { "-Duapmd.cmp.showLoadedUi=$it" },
            System.getProperty("uapmd.cmp.preloadPlugin")?.let { "-Duapmd.cmp.preloadPlugin=$it" },
            System.getProperty("uapmd.cmp.showPreloadUi")?.let { "-Duapmd.cmp.showPreloadUi=$it" },
            (System.getProperty("uapmd.cmp.scannerExe")
                ?: uapmdScanExecutable.takeIf { it.isFile }?.absolutePath)
                ?.let { "-Duapmd.cmp.scannerExe=$it" }
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "uapmd-cmp"
            packageVersion = "1.0.0"
        }
    }
}

// ── Tests ────────────────────────────────────────────────────────────────────
//
// Classes named *IntegrationTest drive the real AppModel through the native library:
// they need an audio device, and most of them installed plug-ins or MIDI files, which
// CI machines do not have. `jvmTest` (what CI runs) leaves them out; run them with
//
//   ./gradlew :uapmd-cmp:jvmIntegrationTest [--tests '*FreezeIntegrationTest'] [-Duapmd.probe.x=...]
//
// AppModel is a process-wide singleton, so every class gets a JVM of its own.
val integrationTestPattern = "*IntegrationTest"

tasks.named<Test>("jvmTest") {
    filter { excludeTestsMatching(integrationTestPattern) }
}

val jvmTestCompilation = kotlin.targets.getByName("jvm").compilations.getByName("test")

tasks.register<Test>("jvmIntegrationTest") {
    group = "verification"
    description = "Runs the tests that drive the real engine, audio device and installed plug-ins."
    useJUnit()
    testClassesDirs = jvmTestCompilation.output.classesDirs
    classpath = files(jvmTestCompilation.output.allOutputs, jvmTestCompilation.runtimeDependencyFiles)
    filter { includeTestsMatching(integrationTestPattern) }
    forkEvery = 1
    // What they exercise lives outside the build: plug-ins, devices, files.
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    jvmArgs("-XX:ErrorFile=${layout.buildDirectory.get().asFile}/hs_err_pid%p.log")
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX)
        jvmArgs("-Dapple.awt.application.name=uapmd-cmp", "-Xdock:name=uapmd-cmp")
    systemProperty("uapmd.test.repoRoot", rootProject.projectDir.parentFile.absolutePath)
    // A scan run inside the test JVM loads every plug-in into it, and one that installs
    // its own SIGSEGV handler (Floe) catches the JVM's routine internal faults and
    // wedges the JVM. Built before the tests run, so the path is passed unconditionally.
    dependsOn(":uapmd-binding:buildUapmdScanDesktop")
    systemProperty("uapmd.cmp.scannerExe",
        System.getProperty("uapmd.cmp.scannerExe") ?: uapmdScanExecutable.absolutePath)
    // The switches the tests document (-Duapmd.probe.smf=..., -Duapmd.cmp.snapshotView=...)
    // only reach them if the task passes them on.
    System.getProperties().stringPropertyNames()
        .filter { it.startsWith("uapmd.probe.") || it.startsWith("uapmd.cmp.snapshot") ||
            it == "uapmd.cmp.rollScrollSeconds" || it == "uapmd.cmp.resizeProbe" }
        .forEach { systemProperty(it, System.getProperty(it)) }
}

afterEvaluate {
    tasks.findByName("wasmJsResolveResourcesFromDependencies")?.dependsOn(":uapmd-binding:wasmJsProcessResources")

    // webpack.config.d/uapmd-alias.js resolves modules straight out of
    // uapmd-binding's build directory — the wasm adapter from its processed
    // resources, and uapmd-c-api plus the WebCLAP assets from the Emscripten
    // output. Neither is something Gradle can infer from the project
    // dependency, because the reference lives in a JavaScript config file.
    // Without these, a clean checkout fails with "Can't resolve
    // 'uapmd-wasm-adapter'"; a developer machine hides it, because an earlier
    // build has already left the files in place.
    listOf(
        "wasmJsBrowserProductionWebpack",
        "wasmJsBrowserDevelopmentWebpack",
        "wasmJsBrowserProductionRun",
        "wasmJsBrowserDevelopmentRun",
    ).forEach { name ->
        // Warn rather than throw: this block configures on every invocation, so a
        // task renamed by a future Kotlin plugin must not take unrelated builds
        // (jvmRun, the tests) down with it. The warning is there because a silent
        // no-op here comes back as a confusing webpack resolve failure.
        val task = tasks.findByName(name)
        if (task == null)
            logger.warn("uapmd-cmp: task $name not found; wasm alias inputs are not wired for it")
        else
            task.dependsOn(":uapmd-binding:wasmJsProcessResources", ":uapmd-binding:buildUapmdCApiWasm")
    }
}
