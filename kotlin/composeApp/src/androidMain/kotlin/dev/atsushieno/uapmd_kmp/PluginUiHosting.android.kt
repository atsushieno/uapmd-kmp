package dev.atsushieno.uapmd_kmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.atsushieno.uapmd.PluginInstance

actual fun defaultPluginUiPresentationTarget(instanceId: Int): PluginUiPresentationTarget? = null

actual fun supportsFloatingPluginUiPresentations(): Boolean = false

actual fun unsupportedFloatingPluginUiMessage(): String? =
    "Android plugin UI hosting is only implemented for direct AAP GUI surfaces."

actual fun supportsPlatformHostedPluginUi(instance: PluginInstance): Boolean =
    instance.aapUiHostDetails != null

@Composable
actual fun PlatformHostedPluginUiLayer(
    model: UapmdModel,
    modifier: Modifier
) {
    AndroidPlatformHostedPluginUiLayer(model = model, modifier = modifier)
}
