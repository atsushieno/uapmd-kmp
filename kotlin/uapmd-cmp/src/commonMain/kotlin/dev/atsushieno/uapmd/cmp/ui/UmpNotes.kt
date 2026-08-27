package dev.atsushieno.uapmd.cmp.ui

import dev.atsushieno.uapmd.UmpEvent

/**
 * A note recovered from a clip's UMP stream, carrying the indices of the events
 * that produced it so an edit can write back to exactly those.
 */
data class UmpNote(
    val note: Int,
    val channel: Int,
    val group: Int,
    val velocity: Float,
    val startTick: Long,
    val endTick: Long,
    val onIndex: Int,
    val offIndex: Int
) {
    val durationTicks: Long get() = (endTick - startTick).coerceAtLeast(1L)
}

private const val MT_MIDI1 = 0x2
private const val MT_MIDI2 = 0x4
private const val STATUS_NOTE_OFF = 0x8
private const val STATUS_NOTE_ON = 0x9

private fun UInt.messageType() = (this shr 28).toInt() and 0xF
private fun UInt.group() = (this shr 24).toInt() and 0xF
private fun UInt.status() = (this shr 20).toInt() and 0xF
private fun UInt.channel() = (this shr 16).toInt() and 0xF
private fun UInt.noteNumber() = (this shr 8).toInt() and 0x7F

/**
 * Pairs note-ons with their matching note-offs. Handles both MIDI 1.0 (message
 * type 0x2, one word) and MIDI 2.0 (type 0x4, two words) channel voice messages,
 * because a clip's origin decides which it holds.
 *
 * A note-on with zero velocity is a note-off, as in MIDI 1.0.
 */
fun parseUmpNotes(events: List<UmpEvent>): List<UmpNote> {
    val open = mutableMapOf<Triple<Int, Int, Int>, MutableList<Pair<Int, UmpEvent>>>()
    val result = mutableListOf<UmpNote>()

    events.forEachIndexed { index, event ->
        val w0 = event.words.firstOrNull() ?: return@forEachIndexed
        val mt = w0.messageType()
        if (mt != MT_MIDI1 && mt != MT_MIDI2) return@forEachIndexed

        val status = w0.status()
        if (status != STATUS_NOTE_ON && status != STATUS_NOTE_OFF) return@forEachIndexed

        val key = Triple(w0.group(), w0.channel(), w0.noteNumber())
        val velocity = when (mt) {
            MT_MIDI2 -> ((event.words.getOrNull(1) ?: 0u) shr 16).toInt() / 65535f
            else -> (w0.toInt() and 0x7F) / 127f
        }
        val isOn = status == STATUS_NOTE_ON && velocity > 0f

        if (isOn) {
            open.getOrPut(key) { mutableListOf() }.add(index to event)
        } else {
            val pending = open[key]
            val started = pending?.removeFirstOrNull() ?: return@forEachIndexed
            val onEvent = started.second
            result += UmpNote(
                note = key.third,
                channel = key.second,
                group = key.first,
                velocity = ((onEvent.words.getOrNull(1) ?: 0u) shr 16).toInt().let {
                    if (onEvent.words.firstOrNull()?.messageType() == MT_MIDI2) it / 65535f
                    else (onEvent.words.first().toInt() and 0x7F) / 127f
                },
                startTick = onEvent.tick,
                endTick = event.tick,
                onIndex = started.first,
                offIndex = index
            )
        }
    }
    return result.sortedBy { it.startTick }
}

/**
 * Applies tick deltas to individual events and flattens the stream the way
 * `replaceMidiClipContent()` expects: **one tick entry per UMP word**, both
 * arrays sorted by tick.
 */
fun rebuildClipContent(
    events: List<UmpEvent>,
    tickDeltas: Map<Int, Long>
): Pair<UIntArray, LongArray> {
    val moved = events.mapIndexed { index, e ->
        val delta = tickDeltas[index] ?: 0L
        UmpEvent((e.tick + delta).coerceAtLeast(0L), e.words)
    }.sortedBy { it.tick }

    val words = ArrayList<UInt>(moved.sumOf { it.words.size })
    val ticks = ArrayList<Long>(words.size)
    moved.forEach { e ->
        e.words.forEach { w ->
            words += w
            ticks += e.tick          // one tick per word
        }
    }
    return words.toUIntArray() to ticks.toLongArray()
}
