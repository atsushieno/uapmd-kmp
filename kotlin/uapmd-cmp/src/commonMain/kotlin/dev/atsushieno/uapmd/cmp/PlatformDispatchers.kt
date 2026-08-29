package dev.atsushieno.uapmd.cmp

import kotlinx.coroutines.CoroutineDispatcher

/**
 * A dispatcher for blocking work, off the UI thread.
 *
 * Offline render is the obvious case, but on Android this is also a correctness
 * requirement for anything that reaches a plug-in: instantiating an AAP plug-in
 * binds to its `AudioPluginService` in another process, and `onServiceConnected`
 * is delivered on the main looper - so a bind waited on from the main thread can
 * never complete. uapmd-app does not hit this because its main loop is not
 * Android's; composeApp avoided it the same way, via `launchPlatformBackground`.
 */
expect fun backgroundDispatcher(): CoroutineDispatcher
