package dev.atsushieno.uapmd.cmp

import dev.atsushieno.uapmd.AppModel
import dev.atsushieno.uapmd.cleanupAppModel

/** uapmd-app's web_main.cpp starts with the engine disabled; browsers also need a user gesture. */
actual val platformStartsWithAudioEngineEnabled: Boolean = false

/**
 * initUapmdWasm() already created /browser/{uploads,remidy-tooling} and mounted
 * IDBFS before Compose started, so storage really is ready here.
 */
actual fun notifyPersistentStorageReadyForPlatform(model: AppModel) = model.notifyPersistentStorageReady()

actual fun cleanupUapmdAppModel() = cleanupAppModel()
