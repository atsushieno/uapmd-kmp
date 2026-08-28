package dev.atsushieno.uapmd.cmp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Blocking work here can park for seconds waiting on another process
// (AAP service binds), so use the pool sized for blocking calls.
actual fun backgroundDispatcher(): CoroutineDispatcher = Dispatchers.IO
