import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import java.io.File

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
            baseName = "ComposeApp"
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
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
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
    namespace = "dev.atsushieno.uapmd_kmp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.atsushieno.uapmd_kmp"
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
        mainClass = "dev.atsushieno.uapmd_kmp.MainKt"
        jvmArgs += listOf(
            "-Dapple.awt.application.name=uapmd-kmp",
            "-Xdock:name=uapmd-kmp"
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "uapmd-kmp"
            packageVersion = "1.0.0"
        }
    }
}

val jvmMainCompilation = kotlin.targets.getByName("jvm").compilations.getByName("main")
val macAppBundleDir = layout.buildDirectory.dir("compose/binaries/main/app/uapmd-kmp.app")
val macAppExecutable = macAppBundleDir.map { File(it.asFile, "Contents/MacOS/uapmd-kmp").absolutePath }

tasks.register<JavaExec>("runJvmInstantiationProbe") {
    group = "application"
    description = "Runs the CMP desktop instantiation probe under the JVM host runtime."
    dependsOn("jvmJar")
    mainClass.set("dev.atsushieno.uapmd_kmp.InstantiationProbeMainKt")
    classpath(
        files(tasks.named("jvmJar")),
        jvmMainCompilation.runtimeDependencyFiles
    )
    jvmArgs(
        "-Dapple.awt.application.name=uapmd-kmp",
        "-Xdock:name=uapmd-kmp"
    )
    listOf(
        "uapmd.debug.threads",
        "uapmd.probe.formats",
        "uapmd.probe.attemptsPerFormat",
        "uapmd.probe.timeoutMs",
        "uapmd.probe.startAudio"
    ).forEach { key ->
        System.getProperty(key)?.let { value -> systemProperty(key, value) }
    }
}

tasks.register<JavaExec>("runJvmProjectPlaybackProbe") {
    group = "application"
    description = "Loads a project through the KMP desktop host and logs playback/spectrum state."
    dependsOn("jvmJar")
    mainClass.set("dev.atsushieno.uapmd_kmp.ProjectPlaybackProbeMainKt")
    classpath(
        files(tasks.named("jvmJar")),
        jvmMainCompilation.runtimeDependencyFiles
    )
    jvmArgs(
        "-Dapple.awt.application.name=uapmd-kmp",
        "-Xdock:name=uapmd-kmp"
    )
    System.getProperty("uapmd.probe.project")?.let { value ->
        systemProperty("uapmd.probe.project", value)
    }
}

tasks.register<Exec>("runJvmBundle") {
    group = "application"
    description = "Runs the native Compose Desktop app bundle launcher."
    dependsOn("createDistributable")
    doFirst {
        commandLine(macAppExecutable.get())
    }
}

tasks.register<Exec>("runJvmDebugBundle") {
    group = "application"
    description = "Runs the native Compose Desktop app bundle launcher with JDWP enabled for IDE attach debugging."
    dependsOn("createDistributable")
    environment(
        "JAVA_TOOL_OPTIONS",
        System.getProperty("uapmd.debug.javaToolOptions")
            ?: "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
    )
    doFirst {
        commandLine(macAppExecutable.get())
    }
}

afterEvaluate {
    tasks.findByName("wasmJsResolveResourcesFromDependencies")?.dependsOn(":uapmd-binding:wasmJsProcessResources")
}
