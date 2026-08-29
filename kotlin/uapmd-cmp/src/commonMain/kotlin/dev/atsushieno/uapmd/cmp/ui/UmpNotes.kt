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

/** One event's share of an edit: where it moves to and what it becomes. */
data class EventEdit(
    val tickDelta: Long = 0L,
    /** New MIDI note number, or null to keep the existing one. */
    val note: Int? = null,
    /** New velocity in 0..1, or null to keep the existing one. */
    val velocity: Float? = null
)

/**
 * Rewrites a note-on/off event's note number and velocity in place, in whichever
 * MIDI version the event already uses. Editing must not silently promote a MIDI 1.0
 * clip to MIDI 2.0: the clip's origin decides its encoding, and a mixed stream
 * would parse back inconsistently.
 */
private fun UmpEvent.rewritten(note: Int?, velocity: Float?): UmpEvent {
    if (note == null && velocity == null) return this
    val w0 = words.firstOrNull() ?: return this
    val mt = w0.messageType()
    if (mt != MT_MIDI1 && mt != MT_MIDI2) return this

    val newWords = words.copyOf()
    note?.let {
        val clamped = it.coerceIn(0, 127)
        newWords[0] = (w0 and 0xFFFF80FFu) or (clamped.toUInt() shl 8)
    }
    velocity?.let { v ->
        val clamped = v.coerceIn(0f, 1f)
        if (mt == MT_MIDI2 && newWords.size > 1) {
            val v16 = (clamped * 65535f).toInt().coerceIn(0, 65535).toUInt()
            newWords[1] = (newWords[1] and 0x0000FFFFu) or (v16 shl 16)
        } else {
            val v7 = (clamped * 127f).toInt().coerceIn(0, 127).toUInt()
            newWords[0] = (newWords[0] and 0xFFFFFF80u) or v7
        }
    }
    return UmpEvent(tick, newWords)
}

/** A MIDI 2.0 note-on/off pair for a newly drawn note. */
fun midi2NotePair(
    group: Int,
    channel: Int,
    note: Int,
    velocity: Float,
    startTick: Long,
    durationTicks: Long
): List<UmpEvent> {
    fun word0(status: Int) =
        (MT_MIDI2.toUInt() shl 28) or
            ((group and 0xF).toUInt() shl 24) or
            (status.toUInt() shl 20) or
            ((channel and 0xF).toUInt() shl 16) or
            (note.coerceIn(0, 127).toUInt() shl 8)
    val v16 = (velocity.coerceIn(0f, 1f) * 65535f).toInt().coerceIn(0, 65535).toUInt()
    return listOf(
        UmpEvent(startTick.coerceAtLeast(0L), uintArrayOf(word0(STATUS_NOTE_ON), v16 shl 16)),
        UmpEvent(
            (startTick + durationTicks.coerceAtLeast(1L)).coerceAtLeast(1L),
            uintArrayOf(word0(STATUS_NOTE_OFF), 0u)
        )
    )
}

/**
 * Applies an edit to the event stream and flattens it the way
 * `replaceMidiClipContent()` expects: **one tick entry per UMP word**, both arrays
 * sorted by tick.
 *
 * Events the edit does not name are carried through untouched, so a clip's
 * controllers, pitch bends and everything else survive a note edit.
 */
fun editClipContent(
    events: List<UmpEvent>,
    edits: Map<Int, EventEdit> = emptyMap(),
    removed: Set<Int> = emptySet(),
    added: List<UmpEvent> = emptyList()
): Pair<UIntArray, LongArray> {
    val kept = events.mapIndexedNotNull { index, e ->
        if (index in removed) return@mapIndexedNotNull null
        val edit = edits[index] ?: return@mapIndexedNotNull e
        UmpEvent((e.tick + edit.tickDelta).coerceAtLeast(0L), e.words)
            .rewritten(edit.note, edit.velocity)
    }
    val moved = (kept + added).sortedBy { it.tick }

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

/** Kept for the plain tick-shift case. */
fun rebuildClipContent(
    events: List<UmpEvent>,
    tickDeltas: Map<Int, Long>
): Pair<UIntArray, LongArray> =
    editClipContent(events, tickDeltas.mapValues { EventEdit(tickDelta = it.value) })
