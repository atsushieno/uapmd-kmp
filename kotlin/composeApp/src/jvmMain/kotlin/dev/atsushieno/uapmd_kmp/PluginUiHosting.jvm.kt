package dev.atsushieno.uapmd_kmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.atsushieno.uapmd.PluginInstance

actual fun defaultPluginUiPresentationTarget(instanceId: Int): PluginUiPresentationTarget? = null

actual fun supportsFloatingPluginUiPresentations(): Boolean = true

actual fun unsupportedFloatingPluginUiMessage(): String? = null

actual fun supportsPlatformHostedPluginUi(instance: PluginInstance): Boolean = false

@Composable
actual fun PlatformHostedPluginUiLayer(
    model: UapmdModel,
    modifier: Modifier
) {
}
