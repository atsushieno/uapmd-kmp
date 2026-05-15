package dev.atsushieno.uapmd_kmp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

expect val platformBackgroundDispatcher: CoroutineDispatcher

inline fun CoroutineScope.launchPlatformBackground(
    crossinline block: () -> Unit
) = launch(platformBackgroundDispatcher) {
    block()
}
