package dev.atsushieno.uapmd.cmp

import kotlinx.coroutines.CoroutineDispatcher

/** A dispatcher for blocking work (offline render), off the UI thread. */
expect fun backgroundDispatcher(): CoroutineDispatcher
