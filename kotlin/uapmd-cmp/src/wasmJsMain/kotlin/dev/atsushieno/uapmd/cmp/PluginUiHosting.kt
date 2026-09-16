package dev.atsushieno.uapmd.cmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.atsushieno.uapmd.PluginInstance
import dev.atsushieno.uapmd.PluginUiHost

/**
 * Web plugin UIs go through the floating-window path, not an embedded one.
 *
 * remidy-gui's ContainerWindow has an Emscripten implementation
 * (`ContainerWindow_Emscripten.cpp`) that builds a draggable DOM window per
 * presentation, with a title bar, a close button and resize callbacks — so
 * several plugin UIs can be open at once and each can be moved, resized and
 * closed. The C API takes that path for `UAPMD_UI_HOST_FLOATING_WINDOW` and
 * hands the plugin the window's body element.
 *
 * Returning no embedded target is what routes us there: a WebEmbedded target
 * would instead drop the plugin into a bare div with no chrome of any kind.
 */
actual fun defaultPluginUiPresentationTarget(instanceId: Int): PluginUiPresentationTarget? = null

actual fun supportsFloatingPluginUiPresentations(): Boolean = true

actual fun unsupportedFloatingPluginUiMessage(): String? = null

// WCLAP UIs come from remidy through createUiPresentation, not from a platform
// view system, so there is nothing for the hosted-UI layer to draw.
actual fun supportsPlatformHostedPluginUi(instance: PluginInstance): Boolean = false

@Composable
actual fun PlatformHostedPluginUiLayer(host: UapmdHost, modifier: Modifier) = Unit

// The browser event loop is the main loop; nothing to install.
actual fun initPlatformEventLoop() = Unit
