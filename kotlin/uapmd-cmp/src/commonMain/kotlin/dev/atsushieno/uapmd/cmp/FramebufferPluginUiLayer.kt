package dev.atsushieno.uapmd.cmp

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.cmp.ui.FloatingWindowManager

private const val FramebufferUiWindowKeyPrefix = "plugin-ui:"

/** The window key for one instance's drawn editor. */
fun framebufferUiWindowKey(instanceId: Int): String = "$FramebufferUiWindowKeyPrefix$instanceId"

/**
 * Keeps a floating window open for each plugin that draws its own editor.
 *
 * These are ordinary windows of the application's own windowing system rather than
 * anything special: they drag, resize and stack with the Details and Plugin Selector
 * windows, and clicking one raises it. That matters because the reason to open an
 * effect's editor is usually to tweak it against the sequence, with both on screen.
 */
@Composable
fun FramebufferPluginUiLayer(host: UapmdHost, windows: FloatingWindowManager) {
    val ids = host.framebufferUiInstanceIds
    val density = LocalDensity.current

    LaunchedEffect(ids) {
        ids.forEach { instanceId ->
            val key = framebufferUiWindowKey(instanceId)
            if (windows.isOpen(key))
                return@forEach

            val instance = host.model.sequencer.engine.getPluginInstance(instanceId)
            val ui = instance?.framebufferUi
            if (ui == null) {
                host.hidePluginUi(instanceId)
                return@forEach
            }

            // The plugin's size is in pixels; the window's is in dp. Opening it at the
            // size the editor actually wants saves the user resizing it by hand, and the
            // chrome is allowed for so that the frame is not clipped from the start.
            val preferred = ui.preferredSize
            val initialSize = with(density) {
                DpSize(
                    (preferred?.width ?: 640).toDp() + 16.dp,
                    (preferred?.height ?: 480).toDp() + 56.dp
                )
            }

            windows.open(
                key = key,
                title = instance.displayName,
                initialSize = initialSize,
                resizable = true,
                onClose = { host.hidePluginUi(instanceId) }
            ) {
                // Fills the window and tells the plugin so: a JSFX script lays out
                // against whatever gfx_w and gfx_h it is given, so resizing the window
                // resizes the editor rather than cropping or scrolling it. Wrapping this
                // in a scroller would hand it unbounded constraints and it would never
                // learn the window's size.
                FramebufferPluginEditor(ui, Modifier.fillMaxSize())
            }
        }

        // An editor that has been hidden, or whose plugin is gone, takes its window with
        // it. Closing calls onClose, which hides it again -- harmless, and it keeps the
        // two in step whichever side closed first.
        windows.closeWhere { key ->
            if (!key.startsWith(FramebufferUiWindowKeyPrefix)) return@closeWhere false
            val id = key.removePrefix(FramebufferUiWindowKeyPrefix).toIntOrNull()
            id != null && id !in ids
        }
    }
}
