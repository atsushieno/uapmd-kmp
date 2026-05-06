package dev.atsushieno.uapmd_kmp

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.atsushieno.uapmd.initJvmEventLoop

fun main() {
    initJvmEventLoop()
    application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "uapmd-kmp",
    ) {
        App()
    }
    }
}
