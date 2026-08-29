package dev.atsushieno.uapmd.cmp

import dev.atsushieno.uapmd.AppModel
import dev.atsushieno.uapmd.cleanupAppModel

/** AAP's fast list is the whole catalog; its slow scan is a no-op. */
actual val platformNeedsAudioEngineForScan: Boolean = false

actual val platformNeedsSlowScan: Boolean = false

actual val platformSupportsRemoteScanner: Boolean = false

actual val platformStartsWithAudioEngineEnabled: Boolean = true

actual fun notifyPersistentStorageReadyForPlatform(model: AppModel) = model.notifyPersistentStorageReady()

actual fun cleanupUapmdAppModel() = cleanupAppModel()
