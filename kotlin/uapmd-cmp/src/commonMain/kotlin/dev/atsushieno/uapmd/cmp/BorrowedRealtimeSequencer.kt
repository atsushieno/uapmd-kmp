package dev.atsushieno.uapmd.cmp

import dev.atsushieno.uapmd.RealtimeSequencer
import dev.atsushieno.uapmd.SequencerEngine

/**
 * A [RealtimeSequencer] the app does not own.
 *
 * `AppModel` owns its sequencer and destroys it in `uapmd_app_cleanup()`, but
 * `RealtimeSequencer` is `AutoCloseable`, so a stray `close()` — or an innocent
 * `use { }` — would free a handle AppModel still owns. Delegating everything and
 * dropping `close()` removes that trap.
 *
 * This lives in the app rather than in `uapmd-binding` because "who owns this
 * handle" is a fact about how *this* app uses the API, not part of the uapmd API
 * the binding mirrors.
 */
class BorrowedRealtimeSequencer(private val delegate: RealtimeSequencer) : RealtimeSequencer {
    override val engine: SequencerEngine get() = delegate.engine

    override fun startAudio(): Int = delegate.startAudio()
    override fun stopAudio(): Int = delegate.stopAudio()
    override fun isAudioPlaying(): Int = delegate.isAudioPlaying()
    override fun clearOutputBuffers() = delegate.clearOutputBuffers()

    override var sampleRate: Int
        get() = delegate.sampleRate
        set(value) { delegate.sampleRate = value }

    override fun reconfigureAudioDevice(
        inputDeviceIndex: Int,
        outputDeviceIndex: Int,
        sampleRate: UInt,
        bufferSize: UInt
    ): Boolean = delegate.reconfigureAudioDevice(inputDeviceIndex, outputDeviceIndex, sampleRate, bufferSize)

    /** Deliberately a no-op: AppModel owns this handle. */
    override fun close() = Unit
}
