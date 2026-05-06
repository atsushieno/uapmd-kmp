package dev.atsushieno.uapmd_kmp

import dev.atsushieno.uapmd.PluginUiHost

actual fun defaultPluginUiPresentationTarget(instanceId: Int): PluginUiPresentationTarget =
    PluginUiPresentationTarget(
        host = PluginUiHost.WebEmbedded("plugin-ui-host"),
        description = "embedded web editor surface"
    )

actual fun supportsFloatingPluginUiPresentations(): Boolean = false

actual fun unsupportedFloatingPluginUiMessage(): String? = null
