package dev.atsushieno.uapmd.cmp

import kotlinx.coroutines.CoroutineDispatcher

/**
 * A dispatcher for blocking work, off the UI thread.
 *
 * Offline render is the obvious case. On Android it also covers anything that
 * reaches a plug-in: an AAP plug-in lives in another process, so instantiating one
 * binds to its `AudioPluginService` and every further call is a binder transaction,
 * none of which should hold up the UI thread.
 */
expect fun backgroundDispatcher(): CoroutineDispatcher
