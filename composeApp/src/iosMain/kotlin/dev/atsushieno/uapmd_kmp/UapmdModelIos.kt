package dev.atsushieno.uapmd_kmp

import dev.atsushieno.uapmd.*

actual fun createUapmdModel(): UapmdModel {
    val dispatcher = getDefaultDeviceIODispatcher()
    val sequencer  = createRealtimeSequencer(
        bufferSize    = 512u,
        umpBufferSize = 8192u,
        sampleRate    = 48000,
        dispatcher    = dispatcher
    )
    sequencer.startAudio()
    return UapmdModel(sequencer)
}
