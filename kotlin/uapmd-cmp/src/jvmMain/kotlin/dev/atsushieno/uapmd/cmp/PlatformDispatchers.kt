package dev.atsushieno.uapmd.cmp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Blocking work here can park for seconds, so use the pool sized for it.
actual fun backgroundDispatcher(): CoroutineDispatcher = Dispatchers.IO
