package dev.atsushieno.uapmd.cmp

import androidx.compose.runtime.Composable
import dev.atsushieno.uapmd.PluginInstance
import dev.atsushieno.uapmd.cmp.ui.FloatingWindowManager

actual fun defaultPluginUiPresentationTarget(instanceId: Int): PluginUiPresentationTarget? = null
actual fun supportsFloatingPluginUiPresentations(): Boolean = false
actual fun unsupportedFloatingPluginUiMessage(): String? =
    "Plugin UI hosting is not available on iOS yet."
actual fun supportsPlatformHostedPluginUi(instance: PluginInstance): Boolean = false

@Composable
actual fun PlatformHostedPluginUiLayer(host: UapmdHost, windows: FloatingWindowManager) = Unit

// iOS uses the platform's own run loop; nothing to install.
actual fun initPlatformEventLoop() = Unit
