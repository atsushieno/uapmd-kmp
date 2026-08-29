package dev.atsushieno.uapmd.cmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.atsushieno.uapmd.PluginInstance
import dev.atsushieno.uapmd.initJvmEventLoop

// Desktop: remidy gives each plugin its own OS window, so nothing is embedded.
actual fun defaultPluginUiPresentationTarget(instanceId: Int): PluginUiPresentationTarget? = null
actual fun supportsFloatingPluginUiPresentations(): Boolean = true
actual fun unsupportedFloatingPluginUiMessage(): String? = null
actual fun supportsPlatformHostedPluginUi(instance: PluginInstance): Boolean = false

@Composable
actual fun PlatformHostedPluginUiLayer(host: UapmdHost, modifier: Modifier) = Unit

actual fun initPlatformEventLoop() = initJvmEventLoop()
