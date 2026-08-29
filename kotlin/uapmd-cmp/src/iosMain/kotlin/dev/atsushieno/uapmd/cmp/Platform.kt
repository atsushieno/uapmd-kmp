package dev.atsushieno.uapmd.cmp

import dev.atsushieno.uapmd.AppModel
import dev.atsushieno.uapmd.cleanupAppModel

/** AU components are described by opening them. */
actual val platformNeedsAudioEngineForScan: Boolean = false

actual val platformNeedsSlowScan: Boolean = true

actual val platformSupportsRemoteScanner: Boolean = false

actual val platformStartsWithAudioEngineEnabled: Boolean = true

actual fun notifyPersistentStorageReadyForPlatform(model: AppModel) = model.notifyPersistentStorageReady()

actual fun cleanupUapmdAppModel() = cleanupAppModel()
