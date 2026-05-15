package dev.atsushieno.uapmd_kmp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val platformBackgroundDispatcher: CoroutineDispatcher = Dispatchers.IO
