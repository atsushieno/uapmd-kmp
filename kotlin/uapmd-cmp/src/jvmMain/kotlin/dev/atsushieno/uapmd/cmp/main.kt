package dev.atsushieno.uapmd.cmp

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.atsushieno.uapmd.initJvmEventLoop

fun main() {
    System.setProperty("apple.awt.application.name", "uapmd-cmp")
    // Must run before any uapmd engine/sequencer exists: routes remidy EventLoop
    // tasks to a loop this app controls, so plugin initialisation does not deadlock.
    // See docs/uapmd-cmp-plan.md §2.3.
    initJvmEventLoop()
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "uapmd-cmp",
        ) {
            App()
        }
    }
}
