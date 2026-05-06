package dev.atsushieno.uapmd_kmp

import dev.atsushieno.uapmd.*

actual fun createUapmdModel(): UapmdModel {
    // Must be called before creating any engine/sequencer: installs a
    // dedicated JVM thread as the remidy "native main" thread so that
    // plugin instantiation (which uses EventLoop::runTaskOnMainThread) does
    // not deadlock against the absent Cocoa/Win32 run-loop.
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
