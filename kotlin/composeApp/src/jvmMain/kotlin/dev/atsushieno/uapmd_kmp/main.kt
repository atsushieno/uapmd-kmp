package dev.atsushieno.uapmd_kmp

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.runtime.SideEffect
import dev.atsushieno.uapmd.initJvmEventLoop
import dev.atsushieno.uapmd.debugJvmThread

fun main() {
    System.setProperty("apple.awt.application.name", "uapmd-kmp")
    debugJvmThread("composeApp.main.beforeInit")
    initJvmEventLoop()
    application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "uapmd-kmp",
    ) {
        SideEffect {
            debugJvmThread("composeApp.Window.SideEffect")
        }
        App()
    }
    }
}
