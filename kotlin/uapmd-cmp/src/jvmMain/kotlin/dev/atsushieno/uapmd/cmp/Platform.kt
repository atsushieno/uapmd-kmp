package dev.atsushieno.uapmd.cmp

import dev.atsushieno.uapmd.AppModel
import dev.atsushieno.uapmd.cleanupAppModel

actual val platformStartsWithAudioEngineEnabled: Boolean = true

actual fun notifyPersistentStorageReadyForPlatform(model: AppModel) = model.notifyPersistentStorageReady()

actual fun cleanupUapmdAppModel() = cleanupAppModel()
