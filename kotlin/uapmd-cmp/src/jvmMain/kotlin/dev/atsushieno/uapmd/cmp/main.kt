package dev.atsushieno.uapmd.cmp

import androidx.compose.ui.window.Window
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.application
import dev.atsushieno.uapmd.initJvmEventLoop
import java.awt.Desktop

/**
 * AppKit's menu Quit never reaches [Window]'s onCloseRequest: it runs
 * `terminate:` -> `applicationShouldTerminate:` -> `System.exit`, and the static
 * `unique_ptr<AppModel>` is then destroyed by `__cxa_finalize_ranges` on the VM
 * thread. CLAP teardown there posts to a main thread already parked inside
 * `vm_exit` waiting for that same exit to finish, and the two wait on each other
 * forever. AWT calls this handler on the AppKit thread, where the post runs
 * inline instead.
 */
private fun installQuitHandler() {
    runCatching {
        val desktop = Desktop.getDesktop()
        if (!desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER))
            return@runCatching
        desktop.setQuitHandler { _, response ->
            runCatching { UapmdHost.current?.shutdown() }
            response.performQuit()
        }
    }
}

fun main() {
    System.setProperty("apple.awt.application.name", "uapmd-cmp")
    // Must run before any uapmd engine/sequencer exists: routes remidy EventLoop
    // tasks to a loop this app controls, so plugin initialisation does not deadlock.
    // See docs/uapmd-cmp-plan.md §2.3.
    initJvmEventLoop()
    installQuitHandler()
    application {
        // -Duapmd.cmp.windowSize=WxH lets the desktop build stand in for a
        // phone screen, so narrow-layout problems show up without a device.
        val sized = System.getProperty("uapmd.cmp.windowSize")
            ?.split("x")?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.size == 2 }
        Window(
            // Teardown must finish while the AppKit run loop is still draining:
            // it goes through the remidy event loop, which exitApplication takes
            // away. Ordering it after the exit is what strands the engine, the
            // plug-in instances and the MCP listener.
            onCloseRequest = {
                runCatching { UapmdHost.current?.shutdown() }
                exitApplication()
            },
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
