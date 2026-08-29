package dev.atsushieno.uapmd.cmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.atsushieno.uapmd.PluginInstance
import dev.atsushieno.uapmd.PluginUiHost

/**
 * Web plugins (WCLAP) present their UI as web content, so the binding's
 * `PluginUiHost.WebEmbedded(containerId)` is the right target: the plugin
 * attaches into a DOM element we name.
 *
 * Compose renders to a canvas and cannot host that element itself, so the
 * container has to be a real DOM node positioned over the canvas. That part is
 * not built yet; the target below names the element uapmd-cmp will create.
 */
private const val PluginUiContainerId = "uapmd-plugin-ui"

actual fun defaultPluginUiPresentationTarget(instanceId: Int): PluginUiPresentationTarget? =
    PluginUiPresentationTarget(
        PluginUiHost.WebEmbedded("$PluginUiContainerId-$instanceId"),
        "web plugin UI container"
    )

actual fun supportsFloatingPluginUiPresentations(): Boolean = false
actual fun unsupportedFloatingPluginUiMessage(): String? =
    "Web plugin UIs need a DOM container over the Compose canvas, which uapmd-cmp does not create yet."
actual fun supportsPlatformHostedPluginUi(instance: PluginInstance): Boolean = false

@Composable
actual fun PlatformHostedPluginUiLayer(host: UapmdHost, modifier: Modifier) = Unit

// The browser event loop is the main loop; nothing to install.
actual fun initPlatformEventLoop() = Unit
