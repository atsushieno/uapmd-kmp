package dev.atsushieno.uapmd.cmp

import androidx.compose.runtime.Composable
import dev.atsushieno.uapmd.PluginInstance
import dev.atsushieno.uapmd.initJvmEventLoop
import dev.atsushieno.uapmd.cmp.ui.FloatingWindowManager

// Desktop: remidy gives each plugin its own OS window, so nothing is embedded.
actual fun defaultPluginUiPresentationTarget(instanceId: Int): PluginUiPresentationTarget? = null
actual fun supportsFloatingPluginUiPresentations(): Boolean = true
actual fun unsupportedFloatingPluginUiMessage(): String? = null
actual fun supportsPlatformHostedPluginUi(instance: PluginInstance): Boolean = false

@Composable
actual fun PlatformHostedPluginUiLayer(host: UapmdHost, windows: FloatingWindowManager) = Unit

actual fun initPlatformEventLoop() = initJvmEventLoop()
