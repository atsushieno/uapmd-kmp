package dev.atsushieno.uapmd.cmp.ui

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicIntArray
import kotlin.concurrent.atomics.AtomicLongArray
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Hand-off of `parameterUpdateEvent` notifications to the UI thread.
 *
 * The event is notified from whichever thread changed the value -- for AAP that
 * includes the audio thread, which forwards the plug-in's output parameter changes
 * from `processAudio()`. [post] therefore only does lock-free stores into
 * preallocated slots (latest value wins per parameter); the UI thread collects
 * them with [drain].
 */
@OptIn(ExperimentalAtomicApi::class)
internal class ParameterValueMailbox(capacity: Int) {
    private val bits = AtomicLongArray(capacity)
    private val dirty = AtomicIntArray(capacity)
    private val anyDirty = AtomicInt(0)

    /** Any thread, realtime included: no allocation, no locks. */
    fun post(parameterIndex: Int, value: Double) {
        if (parameterIndex < 0 || parameterIndex >= dirty.size)
            return
        bits.storeAt(parameterIndex, value.toRawBits())
        dirty.storeAt(parameterIndex, 1)
        anyDirty.store(1)
    }

    /** UI thread. Reports each parameter posted since the last drain, once. */
    fun drain(onValue: (parameterIndex: Int, value: Double) -> Unit) {
        // Clearing the summary flag first means a post racing with the scan
        // below is seen by the next drain at the latest.
        if (anyDirty.exchange(0) == 0)
            return
        for (i in 0 until dirty.size)
            if (dirty.exchangeAt(i, 0) != 0)
                onValue(i, Double.fromBits(bits.loadAt(i)))
    }

    companion object {
        /** Parameter indices beyond this are not tracked (no live value updates). */
        const val MAX_CAPACITY = 1 shl 16
    }
}
