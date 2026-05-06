package dev.atsushieno.uapmd_kmp

import dev.atsushieno.uapmd.PluginUiHost

data class PluginUiPresentationTarget(
    val host: PluginUiHost,
    val description: String
)

expect fun defaultPluginUiPresentationTarget(instanceId: Int): PluginUiPresentationTarget?

expect fun supportsFloatingPluginUiPresentations(): Boolean

expect fun unsupportedFloatingPluginUiMessage(): String?
