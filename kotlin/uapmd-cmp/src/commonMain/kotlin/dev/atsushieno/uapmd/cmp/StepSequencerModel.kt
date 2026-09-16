package dev.atsushieno.uapmd.cmp

import dev.atsushieno.uapmd.UmpEvent
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The editing model behind the step sequencer, and the UMP it bakes.
 *
 * A port of `uapmd_app_gui::StepSequencerEditor`'s non-drawing half
 * (`uapmd-app/gui/StepSequencerEditor.cpp`). The clip is the only storage: a
 * pattern is *baked* into ordinary MIDI 2.0 note-ons and note-offs, and the
 * grid and loop length are recorded beside them as Flex Data metadata text, so
 * the clip plays in any host and still reopens here as a pattern.
 *
 * Kept free of Compose — everything here is arithmetic over UMP words, and the
 * bootstrap probe exercises it directly.
 */
object StepSequencerModel {

    /** The two metadata markers a step clip carries, and their versions. */
    const val LoopMarker = "uapmd.step-loop-end:v1"
    const val GridMarker = "uapmd.step-grid:v1"

    /** Note divisions the grid control offers: whole, half, … 1/32. */
    val Divisions = intArrayOf(1, 2, 4, 8, 16, 32)

    /** Upstream's guard rails: a pattern is at most 128 steps over at most 64 repeats. */
    const val MaxSteps = 128
    const val MaxRepetitions = 64

    /** Which notes get a lane. */
    enum class NoteSet { GmDrums, AllNotes }

    /** One cell of one lane. */
    data class Step(
        val active: Boolean = false,
        val velocity: Float = 0.8f,
        val gate: Float = 0.8f,
        val attributeType: Int = 0,
        val attributeValue: Int = 0
    )

    /** One note's row of steps. */
    data class NoteLane(val note: Int, val steps: List<Step>)

    /**
     * What a step clip's Flex Data says about itself.
     *
     * [stream] is the group/address/channel the markers were written on; every
     * marker in one clip must agree on it, or the clip was not written by this
     * editor and [valid] stays false.
     */
    data class StepMetadata(
        val loopTicks: Long = 0,
        val gridTicks: Long = 0,
        val endTicks: Long = 0,
        val stream: Int = 0,
        val valid: Boolean = false,
        /** Indices, into the flat word list, of the packets the markers occupy. */
        val packetWordIndices: Set<Int> = emptySet()
    ) {
        val group: Int get() = (stream ushr 24) and 0xF
        val channel: Int get() = (stream ushr 16) and 0xF
        val repetitions: Int get() = if (loopTicks > 0) (endTicks / loopTicks).toInt() else 1
    }

    /** The whole editable state of one open pattern. */
    data class Pattern(
        val tickResolution: Int = 480,
        val group: Int = 0,
        /** Channel 10 (zero-based 9) is the General MIDI percussion channel. */
        val channel: Int = 9,
        val noteSet: NoteSet = NoteSet.GmDrums,
        val divisionIndex: Int = 3, // 1/8
        val patternSteps: Int = 16,
        val repetitions: Int = 1,
        val lanes: List<NoteLane> = emptyList()
    ) {
        val stepTicks: Long
            get() = max(1L, tickResolution.toLong() / Divisions[divisionIndex.coerceIn(0, Divisions.size - 1)])
        val patternTicks: Long get() = stepTicks * max(1, patternSteps)

        fun laneIndex(note: Int) = lanes.indexOfFirst { it.note == note }

        fun withStep(note: Int, step: Int, transform: (Step) -> Step): Pattern {
            val li = laneIndex(note)
            if (li < 0) return this
            val lane = lanes[li]
            if (step !in lane.steps.indices) return this
            val steps = lane.steps.toMutableList()
            steps[step] = transform(steps[step])
            val newLanes = lanes.toMutableList()
            newLanes[li] = lane.copy(steps = steps)
            return copy(lanes = newLanes)
        }

        fun toggle(note: Int, step: Int, defaultVelocity: Float, defaultGate: Float) =
            withStep(note, step) {
                if (it.active) it.copy(active = false)
                else it.copy(active = true, velocity = defaultVelocity, gate = defaultGate)
            }

        /** Resizes every lane, keeping the cells that survive the new length. */
        fun resized(steps: Int): Pattern {
            val n = steps.coerceIn(1, MaxSteps)
            return copy(
                patternSteps = n,
                lanes = lanes.map { lane ->
                    lane.copy(steps = List(n) { lane.steps.getOrElse(it) { Step() } })
                }
            )
        }

        /** Rebuilds the lane list for a note set, preserving what still has a lane. */
        fun withNoteSet(set: NoteSet): Pattern {
            if (set == noteSet && lanes.isNotEmpty()) return this
            val existing = lanes.associateBy { it.note }
            return copy(
                noteSet = set,
                lanes = laneNotes(set).map { note ->
                    existing[note] ?: NoteLane(note, List(patternSteps.coerceIn(1, MaxSteps)) { Step() })
                }
            )
        }

        val activeStepCount: Int get() = lanes.sumOf { lane -> lane.steps.count { it.active } }
    }

    /**
     * Lane notes, top row first. GM percussion runs 35..81; the full set is the
     * whole note range, also descending so the highest note is the top row.
     */
    fun laneNotes(set: NoteSet): List<Int> = when (set) {
        NoteSet.GmDrums -> (81 downTo 35).toList()
        NoteSet.AllNotes -> (127 downTo 0).toList()
    }

    /** An empty pattern with lanes already built. */
    fun emptyPattern(tickResolution: Int = 480): Pattern =
        Pattern(tickResolution = max(1, tickResolution)).withNoteSet(NoteSet.GmDrums)

    // ── Flex Data metadata ───────────────────────────────────────────────────

    private const val MessageTypeFlexData = 0xD
    private const val StatusBankMetadataText = 0x01
    private const val MetadataTextStatusUnknown = 0x00
    private const val AddressChannelField = 0
    private const val AddressGroup = 1

    /**
     * Reads the step metadata out of a clip's UMP stream.
     *
     * Flex Data text can arrive split across packets, so text is reassembled per
     * stream key (group, address, channel, status) and only a marker whose
     * packets all carry the same tick is accepted. A clip whose markers disagree
     * about their stream, or whose loop does not divide evenly, is not one this
     * editor wrote, and comes back with [StepMetadata.valid] false.
     */
    fun readStepMetadata(words: UIntArray, ticks: LongArray, tickResolution: Int): StepMetadata {
        var loopTicks = 0L
        var gridTicks = 0L
        var endTicks = 0L
        var stream = 0
        val packetIndices = mutableSetOf<Int>()
        val boundaries = mutableListOf<Pair<Int, Long>>()
        val grids = mutableListOf<Pair<Int, Long>>()

        class TextMessage(val tick: Long) {
            val text = StringBuilder()
            val packets = mutableListOf<Int>()
        }

        val messages = mutableMapOf<Int, TextMessage>()
        var i = 0
        while (i < words.size) {
            val header = words[i]
            val wordCount = umpWordCount(header)
            if (wordCount > words.size - i) break
            val messageType = (header shr 28).toInt() and 0xF
            val statusBank = (header shr 8).toInt() and 0xFF
            val address = (header shr 20).toInt() and 3
            if (messageType == MessageTypeFlexData && wordCount == 4 &&
                statusBank == StatusBankMetadataText && address <= AddressGroup
            ) {
                // Reassemble independently by group, address, channel and status.
                val key = (header and 0x0F3FFFFFu).toInt()
                val format = (header shr 22).toInt() and 3
                val tick = ticks.getOrElse(i) { 0L }
                if (format == 0 || format == 1) messages[key] = TextMessage(tick)
                val message = messages[key]
                if (message != null) {
                    message.packets += i
                    for (word in 1 until 4) {
                        val w = words[i + word]
                        for (shift in intArrayOf(24, 16, 8, 0))
                            message.text.append((((w shr shift).toInt()) and 0xFF).toChar())
                    }
                    val finalPacket = format == 0 || format == 3
                    if (finalPacket)
                        while (message.text.isNotEmpty() && message.text.last() == ' ')
                            message.text.deleteAt(message.text.length - 1)
                    val text = message.text.toString()
                    if (finalPacket && (text == LoopMarker || text == GridMarker) && message.tick == tick) {
                        packetIndices += message.packets
                        if (tick > 0) {
                            if (text == LoopMarker) {
                                boundaries += key to tick
                                if (loopTicks == 0L || tick < loopTicks) {
                                    loopTicks = tick
                                    stream = key
                                }
                                endTicks = max(endTicks, tick)
                            } else {
                                grids += key to tick
                                gridTicks = tick
                            }
                        }
                    }
                    if (finalPacket || message.tick != tick ||
                        (!LoopMarker.startsWith(text) && !GridMarker.startsWith(text))
                    ) messages.remove(key)
                }
            }
            i += wordCount
        }

        // A loop-only clip may omit its later boundary markers; recover the
        // extent that was actually baked.
        if (loopTicks > 0 && grids.isEmpty() && ticks.isNotEmpty()) {
            val extent = ticks.max()
            val repetitions = extent / loopTicks + (if (extent % loopTicks != 0L) 1 else 0)
            if (repetitions > MaxRepetitions)
                return StepMetadata(loopTicks, gridTicks, endTicks, stream, false, packetIndices)
            endTicks = max(endTicks, repetitions * loopTicks)
        }

        var valid = loopTicks > 0 && endTicks % loopTicks == 0L &&
            endTicks / loopTicks <= MaxRepetitions &&
            (gridTicks == 0L || (loopTicks % gridTicks == 0L && loopTicks / gridTicks <= MaxSteps))
        for ((key, tick) in boundaries)
            if (key != stream || tick % loopTicks != 0L) valid = false
        for ((key, tick) in grids)
            if (key != stream || tick != gridTicks) valid = false

        // Do not silently open metadata the grid control cannot represent.
        var supportedGrid = false
        var division = 1
        while (division <= 32) {
            val t = max(1L, tickResolution.toLong() / division)
            if ((gridTicks == 0L || gridTicks == t) && loopTicks > 0 &&
                loopTicks % t == 0L && loopTicks / t <= MaxSteps
            ) supportedGrid = true
            division *= 2
        }
        valid = valid && supportedGrid

        return StepMetadata(loopTicks, gridTicks, endTicks, stream, valid, packetIndices)
    }

    /** Words in a UMP message, from its message type. */
    fun umpWordCount(header: UInt): Int = when ((header shr 28).toInt() and 0xF) {
        0x0, 0x1, 0x2, 0x6, 0x7 -> 1
        0x3, 0x4, 0x8, 0x9, 0xA -> 2
        0xB, 0xC -> 3
        else -> 4 // 0x5 (data 128), 0xD (flex), 0xF (stream)
    }

    // ── Reading a clip back into a pattern ───────────────────────────────────

    /**
     * Rebuilds the pattern a clip was baked from.
     *
     * Returns null when the clip carries no usable step metadata — that is the
     * "this clip did not come from the step sequencer" case, which upstream
     * turns into a confirmation before it overwrites anything.
     */
    fun readPattern(words: UIntArray, ticks: LongArray, tickResolution: Int): Pattern? {
        val tickRes = if (tickResolution > 0) tickResolution else 480
        val metadata = readStepMetadata(words, ticks, tickRes)
        if (!metadata.valid) return null

        // The finest supported grid that still divides the loop. Walking down
        // from the finest means an older loop-only clip, which records no grid,
        // opens at the finest division that fits rather than the coarsest.
        var divisionIndex = 3
        var patternSteps = 16
        for (d in Divisions.indices.reversed()) {
            val t = max(1L, tickRes.toLong() / Divisions[d])
            if ((metadata.gridTicks == 0L || t == metadata.gridTicks) &&
                metadata.loopTicks % t == 0L && metadata.loopTicks / t <= MaxSteps
            ) {
                divisionIndex = d
                patternSteps = (metadata.loopTicks / t).toInt()
                break
            }
        }

        val notes = decodeNotes(words, ticks)
        // A clip holding anything outside GM percussion needs the full range,
        // or its notes would have no lane to land in.
        val noteSet =
            if (notes.any { it.note < 35 || it.note > 81 }) NoteSet.AllNotes else NoteSet.GmDrums

        var pattern = Pattern(
            tickResolution = tickRes,
            group = metadata.group,
            channel = metadata.channel,
            noteSet = noteSet,
            divisionIndex = divisionIndex,
            patternSteps = patternSteps.coerceIn(1, MaxSteps),
            repetitions = metadata.repetitions.coerceIn(1, MaxRepetitions)
        ).withNoteSet(noteSet)

        val stepTicks = pattern.stepTicks
        for (note in notes) {
            if (note.channel != pattern.channel || note.group != pattern.group) continue
            // Only the first repetition is the pattern; the rest are its copies.
            if (note.onTick >= metadata.loopTicks) continue
            val index = (note.onTick.toDouble() / stepTicks).roundToInt()
            val lane = pattern.laneIndex(note.note)
            if (lane < 0 || index !in 0 until pattern.patternSteps) continue
            pattern = pattern.withStep(note.note, index) {
                it.copy(
                    active = true,
                    velocity = note.velocity,
                    gate = ((note.offTick - note.onTick).toFloat() / stepTicks).coerceIn(0.05f, 1.0f),
                    attributeType = note.attributeType,
                    attributeValue = note.attributeValue
                )
            }
        }
        return pattern
    }

    /** A note recovered from the UMP stream, in clip ticks. */
    data class DecodedNote(
        val note: Int,
        val channel: Int,
        val group: Int,
        val velocity: Float,
        val onTick: Long,
        val offTick: Long,
        val attributeType: Int,
        val attributeValue: Int,
        val onWordIndex: Int
    )

    /**
     * Pairs note-ons with their note-offs.
     *
     * An unmatched note-on is dropped rather than given an invented length: a
     * step whose gate was guessed would be rebaked with that guess and quietly
     * become the truth.
     */
    fun decodeNotes(words: UIntArray, ticks: LongArray): List<DecodedNote> {
        val open = HashMap<Int, MutableList<DecodedNote>>()
        val done = mutableListOf<DecodedNote>()
        var i = 0
        while (i < words.size) {
            val header = words[i]
            val wordCount = umpWordCount(header)
            if (wordCount > words.size - i) break
            val messageType = (header shr 28).toInt() and 0xF
            val status = (header shr 20).toInt() and 0xF
            val group = (header shr 24).toInt() and 0xF
            val channel = (header shr 16).toInt() and 0xF
            val tick = ticks.getOrElse(i) { 0L }

            val isMidi1 = messageType == 0x2
            val isMidi2 = messageType == 0x4
            if ((isMidi1 || isMidi2) && (status == 0x8 || status == 0x9)) {
                val note = (header shr 8).toInt() and 0x7F
                val attributeType = if (isMidi2) (header.toInt() and 0xFF) else 0
                val velocity16 =
                    if (isMidi2 && wordCount >= 2) ((words[i + 1] shr 16).toInt() and 0xFFFF)
                    else ((header.toInt() and 0x7F) shl 9)
                val attributeValue =
                    if (isMidi2 && wordCount >= 2) (words[i + 1].toInt() and 0xFFFF) else 0
                // A MIDI 1 note-on with velocity 0 is a note-off, as always.
                val isOn = status == 0x9 && !(isMidi1 && (header.toInt() and 0x7F) == 0)
                val key = (group shl 12) or (channel shl 8) or note
                if (isOn) {
                    open.getOrPut(key) { mutableListOf() } += DecodedNote(
                        note, channel, group, velocity16 / 65535f, tick, tick,
                        attributeType, attributeValue, i
                    )
                } else {
                    open[key]?.removeFirstOrNull()?.let { done += it.copy(offTick = tick) }
                }
            }
            i += wordCount
        }
        return done.sortedBy { it.onTick }
    }

    // ── Baking a pattern back into UMP ───────────────────────────────────────

    /**
     * The clip content a pattern bakes to: the events this editor owns, replaced,
     * and everything else in the clip carried through untouched.
     *
     * Ownership is the same test upstream applies: a note on this group, channel
     * and one of these lanes is ours, and so is a metadata packet we wrote. Any
     * other event — a controller, a note on another channel, someone else's
     * text — survives at its original tick.
     */
    fun bake(pattern: Pattern, existingWords: UIntArray, existingTicks: LongArray): List<UmpEvent> {
        val metadata = readStepMetadata(existingWords, existingTicks, pattern.tickResolution)
        val laneNotes = pattern.lanes.map { it.note }.toHashSet()

        // order: -1 note-off, 0 everything kept, 1 note-on. At one tick an off
        // must precede an on so a repeated step re-articulates rather than
        // cutting its own new note short.
        class Timed(val tick: Long, val words: List<UInt>, val order: Int, val sequence: Int)
        val events = mutableListOf<Timed>()
        var sequence = 0

        var i = 0
        while (i < existingWords.size) {
            val header = existingWords[i]
            val wordCount = min(umpWordCount(header), existingWords.size - i)
            val messageType = (header shr 28).toInt() and 0xF
            val status = (header shr 20).toInt() and 0xF
            val isNote = (messageType == 0x2 || messageType == 0x4) && (status == 0x8 || status == 0x9)
            val ownsNote = isNote &&
                ((header shr 24).toInt() and 0xF) == pattern.group &&
                ((header shr 16).toInt() and 0xF) == pattern.channel &&
                laneNotes.contains((header shr 8).toInt() and 0x7F)
            val ownsMetadata = metadata.packetWordIndices.contains(i)
            if (!ownsNote && !ownsMetadata)
                events += Timed(
                    existingTicks.getOrElse(i) { 0L },
                    (i until i + wordCount).map { existingWords[it] },
                    0, sequence++
                )
            i += wordCount
        }

        fun marker(text: String, tick: Long) {
            events += Timed(tick, flexDataText(pattern.group, pattern.channel, text), 0, sequence++)
        }

        val stepTicks = pattern.stepTicks
        val loopTicks = pattern.patternTicks
        marker(GridMarker, stepTicks)
        for (repetition in 0 until max(1, pattern.repetitions)) {
            val baseTick = loopTicks * repetition
            marker(LoopMarker, baseTick + loopTicks)
            for (lane in pattern.lanes) {
                lane.steps.forEachIndexed { index, step ->
                    if (!step.active) return@forEachIndexed
                    val onTick = baseTick + index.toLong() * stepTicks
                    val offTick = onTick + max(1L, (step.gate * stepTicks).roundToLong())
                    events += Timed(
                        onTick,
                        midi2Note(
                            onOff = true, group = pattern.group, channel = pattern.channel,
                            note = lane.note, attributeType = step.attributeType,
                            velocity16 = (step.velocity.coerceIn(0f, 1f) * 65535f).roundToInt(),
                            attributeValue = step.attributeValue
                        ),
                        1, sequence++
                    )
                    events += Timed(
                        offTick,
                        midi2Note(
                            onOff = false, group = pattern.group, channel = pattern.channel,
                            note = lane.note, attributeType = step.attributeType,
                            velocity16 = 0, attributeValue = step.attributeValue
                        ),
                        -1, sequence++
                    )
                }
            }
        }

        // Stable within a tick and an order class, so events that were adjacent
        // in the source stay adjacent.
        events.sortWith(compareBy({ it.tick }, { it.order }, { it.sequence }))
        return events.map { UmpEvent(it.tick, it.words.toUIntArray()) }
    }

    /**
     * A MIDI 2.0 note-on or note-off, as two words.
     *
     * Word 1: `0x4` | group | status | channel | note | attribute type.
     * Word 2: 16-bit velocity | 16-bit attribute value.
     */
    fun midi2Note(
        onOff: Boolean, group: Int, channel: Int, note: Int,
        attributeType: Int, velocity16: Int, attributeValue: Int
    ): List<UInt> {
        val status = if (onOff) 0x9 else 0x8
        val w1 = (0x4u shl 28) or
            ((group and 0xF).toUInt() shl 24) or
            (status.toUInt() shl 20) or
            ((channel and 0xF).toUInt() shl 16) or
            ((note and 0x7F).toUInt() shl 8) or
            (attributeType and 0xFF).toUInt()
        val w2 = ((velocity16.coerceIn(0, 0xFFFF)).toUInt() shl 16) or
            (attributeValue and 0xFFFF).toUInt()
        return listOf(w1, w2)
    }

    /**
     * Flex Data metadata text, as one or more 4-word packets.
     *
     * Each packet carries 12 bytes of UTF-8 payload in words 2-4. The format
     * field says where the packet sits: 0 for a complete message, then 1/2/3 for
     * start, continue and end — which is what lets the reader above reassemble a
     * marker that did not fit in one packet.
     */
    fun flexDataText(group: Int, channel: Int, text: String): List<UInt> {
        val bytes = text.encodeToByteArray()
        val packets = max(1, (bytes.size + 11) / 12)
        val out = ArrayList<UInt>(packets * 4)
        for (p in 0 until packets) {
            val format = when {
                packets == 1 -> 0
                p == 0 -> 1
                p == packets - 1 -> 3
                else -> 2
            }
            val header = (MessageTypeFlexData.toUInt() shl 28) or
                ((group and 0xF).toUInt() shl 24) or
                (format.toUInt() shl 22) or
                (AddressChannelField.toUInt() shl 20) or
                ((channel and 0xF).toUInt() shl 16) or
                (StatusBankMetadataText.toUInt() shl 8) or
                MetadataTextStatusUnknown.toUInt()
            out += header
            for (word in 0 until 3) {
                var w = 0u
                for (b in 0 until 4) {
                    val index = p * 12 + word * 4 + b
                    val byte = if (index < bytes.size) (bytes[index].toInt() and 0xFF) else 0
                    w = w or (byte.toUInt() shl (24 - b * 8))
                }
                out += w
            }
        }
        return out
    }

    // ── Labels ───────────────────────────────────────────────────────────────

    private val NoteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    fun noteLabel(note: Int, includeDrumName: Boolean = false): String {
        val base = "${NoteNames[note % 12]}${note / 12 - 1} ($note)"
        val drum = if (includeDrumName) gmDrumName(note) else null
        return if (drum != null) "$base  $drum" else base
    }

    /** The General MIDI percussion map, 35..81. */
    fun gmDrumName(note: Int): String? = when (note) {
        35 -> "Acoustic Bass Drum"; 36 -> "Bass Drum 1"; 37 -> "Side Stick"
        38 -> "Acoustic Snare"; 39 -> "Hand Clap"; 40 -> "Electric Snare"
        41 -> "Low Floor Tom"; 42 -> "Closed Hi-Hat"; 43 -> "High Floor Tom"
        44 -> "Pedal Hi-Hat"; 45 -> "Low Tom"; 46 -> "Open Hi-Hat"
        47 -> "Low-Mid Tom"; 48 -> "Hi-Mid Tom"; 49 -> "Crash Cymbal 1"
        50 -> "High Tom"; 51 -> "Ride Cymbal 1"; 52 -> "Chinese Cymbal"
        53 -> "Ride Bell"; 54 -> "Tambourine"; 55 -> "Splash Cymbal"
        56 -> "Cowbell"; 57 -> "Crash Cymbal 2"; 58 -> "Vibraslap"
        59 -> "Ride Cymbal 2"; 60 -> "Hi Bongo"; 61 -> "Low Bongo"
        62 -> "Mute Hi Conga"; 63 -> "Open Hi Conga"; 64 -> "Low Conga"
        65 -> "High Timbale"; 66 -> "Low Timbale"; 67 -> "High Agogo"
        68 -> "Low Agogo"; 69 -> "Cabasa"; 70 -> "Maracas"
        71 -> "Short Whistle"; 72 -> "Long Whistle"; 73 -> "Short Guiro"
        74 -> "Long Guiro"; 75 -> "Claves"; 76 -> "Hi Wood Block"
        77 -> "Low Wood Block"; 78 -> "Mute Cuica"; 79 -> "Open Cuica"
        80 -> "Mute Triangle"; 81 -> "Open Triangle"
        else -> null
    }

    fun divisionLabel(index: Int) = "1/${Divisions[index.coerceIn(0, Divisions.size - 1)]}"
}
