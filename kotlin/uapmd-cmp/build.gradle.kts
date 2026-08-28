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

compose.desktop {
    application {
        mainClass = "dev.atsushieno.uapmd.cmp.MainKt"
        jvmArgs += listOf(
            "-Dapple.awt.application.name=uapmd-cmp",
            "-Xdock:name=uapmd-cmp"
        ) + listOfNotNull(
            System.getProperty("uapmd.cmp.importMidi")?.let { "-Duapmd.cmp.importMidi=$it" },
            System.getProperty("uapmd.cmp.instantiate")?.let { "-Duapmd.cmp.instantiate=$it" },
            System.getProperty("uapmd.cmp.windowSize")?.let { "-Duapmd.cmp.windowSize=$it" },
            System.getProperty("uapmd.cmp.addTracks")?.let { "-Duapmd.cmp.addTracks=$it" }
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "uapmd-cmp"
            packageVersion = "1.0.0"
        }
    }
}

val jvmMainCompilation = kotlin.targets.getByName("jvm").compilations.getByName("main")

tasks.register<JavaExec>("renderUiSnapshot") {
    group = "verification"
    description = "Renders the timeline off-screen to a PNG, so layout changes can be seen without a device."
    dependsOn("jvmJar")
    mainClass.set("dev.atsushieno.uapmd.cmp.UiSnapshotMainKt")
    classpath(
        files(tasks.named("jvmJar")),
        jvmMainCompilation.runtimeDependencyFiles
    )
    jvmArgs("-Djava.awt.headless=true")
    listOf(
        "uapmd.cmp.snapshot", "uapmd.cmp.snapshotSize", "uapmd.cmp.snapshotDensity"
    ).forEach { key -> System.getProperty(key)?.let { systemProperty(key, it) } }
}

tasks.register<JavaExec>("runBootstrapProbe") {
    group = "verification"
    description = "Headless check that the AppModel bootstrap starts, cleanly stops, and restarts audio."
    dependsOn("jvmJar")
    mainClass.set("dev.atsushieno.uapmd.cmp.BootstrapProbeMainKt")
    classpath(
        files(tasks.named("jvmJar")),
        jvmMainCompilation.runtimeDependencyFiles
    )
    jvmArgs("-Dapple.awt.application.name=uapmd-cmp", "-Xdock:name=uapmd-cmp")
    listOf("uapmd.probe.removeInstance", "uapmd.probe.pluginUi", "uapmd.probe.midi").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}

afterEvaluate {
    tasks.findByName("wasmJsResolveResourcesFromDependencies")?.dependsOn(":uapmd-binding:wasmJsProcessResources")
}
