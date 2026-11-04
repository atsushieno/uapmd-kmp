package dev.atsushieno.uapmd.cmp

import androidx.compose.runtime.Composable
import dev.atsushieno.uapmd.PluginInstance
import dev.atsushieno.uapmd.initAndroidEventLoop
import dev.atsushieno.uapmd.cmp.ui.FloatingWindowManager

// Android AAP plugin UIs are remote Android Views, hosted by the platform's own
// view system rather than by remidy - so no floating presentation here.
actual fun defaultPluginUiPresentationTarget(instanceId: Int): PluginUiPresentationTarget? = null
actual fun supportsFloatingPluginUiPresentations(): Boolean = false
actual fun unsupportedFloatingPluginUiMessage(): String? =
    "Android plugin UI hosting is only implemented for direct AAP GUI surfaces."

actual fun supportsPlatformHostedPluginUi(instance: PluginInstance): Boolean =
    instance.aapUiHostDetails != null

@Composable
actual fun PlatformHostedPluginUiLayer(host: UapmdHost, windows: FloatingWindowManager) {
    AndroidPlatformHostedPluginUiLayer(host = host, windows = windows)
}

// Must be called from the Android main thread: it routes remidy EventLoop tasks to
// the main looper, which makes it uapmd's main thread and so also the model thread.
actual fun initPlatformEventLoop() = initAndroidEventLoop()
