package dev.atsushieno.uapmd.cmp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Wasm has no worker pool for Kotlin coroutines; Default is the main dispatcher,
// so an offline render will block the page until it completes.
actual fun backgroundDispatcher(): CoroutineDispatcher = Dispatchers.Default
