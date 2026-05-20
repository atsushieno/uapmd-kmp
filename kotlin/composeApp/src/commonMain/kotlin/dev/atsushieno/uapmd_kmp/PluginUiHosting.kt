package dev.atsushieno.uapmd_kmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.atsushieno.uapmd.PluginInstance
import dev.atsushieno.uapmd.PluginUiHost

data class PluginUiPresentationTarget(
    val host: PluginUiHost,
    val description: String
)

expect fun defaultPluginUiPresentationTarget(instanceId: Int): PluginUiPresentationTarget?

expect fun supportsFloatingPluginUiPresentations(): Boolean

expect fun unsupportedFloatingPluginUiMessage(): String?

expect fun supportsPlatformHostedPluginUi(instance: PluginInstance): Boolean

@Composable
expect fun PlatformHostedPluginUiLayer(
    model: UapmdModel,
    modifier: Modifier = Modifier
)
