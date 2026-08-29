package dev.atsushieno.uapmd.cmp

import androidx.compose.ui.window.Window
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.application
import dev.atsushieno.uapmd.initJvmEventLoop

fun main() {
    System.setProperty("apple.awt.application.name", "uapmd-cmp")
    // Must run before any uapmd engine/sequencer exists: routes remidy EventLoop
    // tasks to a loop this app controls, so plugin initialisation does not deadlock.
    // See docs/uapmd-cmp-plan.md §2.3.
    initJvmEventLoop()
    application {
        // -Duapmd.cmp.windowSize=WxH lets the desktop build stand in for a
        // phone screen, so narrow-layout problems show up without a device.
        val sized = System.getProperty("uapmd.cmp.windowSize")
            ?.split("x")?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.size == 2 }
        Window(
            onCloseRequest = ::exitApplication,
            title = "uapmd-cmp",
            state = androidx.compose.ui.window.rememberWindowState(
                width = (sized?.get(0) ?: 1100).dp,
                height = (sized?.get(1) ?: 800).dp
            ),
        ) {
            App()
        }
    }
}
