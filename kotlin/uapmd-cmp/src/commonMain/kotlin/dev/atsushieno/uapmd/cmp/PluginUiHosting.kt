package dev.atsushieno.uapmd.cmp

import androidx.compose.runtime.Composable
import dev.atsushieno.uapmd.PluginInstance
import dev.atsushieno.uapmd.PluginUiHost
import dev.atsushieno.uapmd.cmp.ui.FloatingWindowManager

/** Where an embedded plugin UI should attach on this platform. */
data class PluginUiPresentationTarget(val host: PluginUiHost, val description: String)

expect fun defaultPluginUiPresentationTarget(instanceId: Int): PluginUiPresentationTarget?

/** True where the platform can give a plugin its own OS window. */
expect fun supportsFloatingPluginUiPresentations(): Boolean

expect fun unsupportedFloatingPluginUiMessage(): String?

/**
 * True when the plugin's UI is hosted by the platform's own view system rather
 * than by remidy — Android AAP plugins, whose UI is a remote Android View.
 */
expect fun supportsPlatformHostedPluginUi(instance: PluginInstance): Boolean

/**
 * Keeps a floating window open for each platform-hosted plugin UI, so that it
 * stacks, raises and closes like every other window. Draws nothing itself; a
 * no-op where no platform hosts plugin UIs.
 */
@Composable
expect fun PlatformHostedPluginUiLayer(host: UapmdHost, windows: FloatingWindowManager)

/**
 * Installs the platform's remidy EventLoop. MUST run before AppModel exists:
 * AppModel marshals plugin deactivation and history completions through it, and
 * without one they silently never run (docs/uapmd-cmp-plan.md §2.3).
 *
 * Implementations are idempotent, so entry points may also call it early.
 */
expect fun initPlatformEventLoop()
