package dev.atsushieno.uapmd_kmp

import dev.atsushieno.uapmd.*

actual fun createUapmdModel(): UapmdModel {
    // Must be called before creating any engine/sequencer. The desktop entry
    // point installs the JVM event loop first; this call remains as an
    // idempotent fallback for tests or alternate launchers.
    initJvmEventLoop()
    val dispatcher = getDefaultDeviceIODispatcher()
    val sequencer  = createRealtimeSequencer(
        bufferSize   = 512u,
        umpBufferSize = 8192u,
        sampleRate   = 48000,
        dispatcher   = dispatcher
    )
    sequencer.startAudio()
    return UapmdModel(sequencer)
}
