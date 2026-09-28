package dev.atsushieno.uapmd.cmp.ui

import kotlin.math.abs

/**
 * The handles a clip is edited through on the timeline.
 *
 * A clip's body only selects it; moving and resizing go through a grip at each
 * end, so a stray drag across the lanes can no longer carry a clip off. Each grip
 * is split in two: its upper half resizes that end, its lower half moves the clip.
 * The grip sits outside the clip it belongs to, appended to that end, so the content
 * stays unobscured.
 *
 * Kept free of Compose so the layout can be tested on its own.
 */
internal enum class ClipEdge { Start, End }

/** What a drag on a grip does. */
internal enum class GripAction { Move, ResizeStart, ResizeEnd }

/** One clip as the grip layout sees it: its lane and horizontal extent in px. */
internal data class ClipSpan(val clipId: Int, val lane: Int, val left: Float, val right: Float)

/** A grip's horizontal extent in px, in its clip's lane. */
internal data class ClipGrip(
    val clipId: Int,
    val lane: Int,
    val edge: ClipEdge,
    val left: Float,
    val right: Float,
    /** The clip edge the grip belongs to, which decides between overlapping grips. */
    val edgeX: Float
)

/**
 * Where every grip goes.
 *
 * A grip is [width] wide and appended to its end of the clip. Where two clips in
 * a lane are closer than two grips, appending would put the first clip's end grip
 * over the second clip's start grip, so both are placed around the middle of the
 * gap instead — each on its own side of it, and reaching into its own clip once
 * the gap is narrower still. Butted clips therefore get their grips just inside
 * their own edges, and two grips never share a pixel. The first clip in a lane
 * has no neighbour before it, only the lane's start; a clip too close to that
 * start for the grip to fit gets its start grip inside itself instead.
 */
internal fun layoutClipGrips(clips: List<ClipSpan>, width: Float): List<ClipGrip> {
    val grips = ArrayList<ClipGrip>(clips.size * 2)
    clips.groupBy { it.lane }.forEach { (lane, inLane) ->
        val sorted = inLane.sortedWith(compareBy({ it.left }, { it.clipId }))
        sorted.forEachIndexed { i, clip ->
            val previous = sorted.getOrNull(i - 1)
            val startLeft = when {
                previous != null && clip.left - previous.right < width * 2 ->
                    (previous.right + clip.left) / 2f
                previous == null && clip.left < width -> clip.left
                else -> clip.left - width
            }
            grips += ClipGrip(clip.clipId, lane, ClipEdge.Start, startLeft, startLeft + width, clip.left)

            val next = sorted.getOrNull(i + 1)
            val endRight = if (next != null && next.left - clip.right < width * 2)
                (clip.right + next.left) / 2f
            else
                clip.right + width
            grips += ClipGrip(clip.clipId, lane, ClipEdge.End, endRight - width, endRight, clip.right)
        }
    }
    return grips
}

/**
 * The grip under [x] in [lane], if any. A clip narrower than its two grips has
 * them overlap; the one whose edge is nearer the pointer wins, which is the end
 * the user is visibly reaching for.
 */
internal fun gripAt(grips: List<ClipGrip>, lane: Int, x: Float): ClipGrip? =
    grips.filter { it.lane == lane && x >= it.left && x <= it.right }
        .minByOrNull { abs(it.edgeX - x) }

/** The upper half of a grip resizes its end; the lower half moves the clip. */
internal fun gripAction(edge: ClipEdge, upperHalf: Boolean): GripAction = when {
    !upperHalf -> GripAction.Move
    edge == ClipEdge.Start -> GripAction.ResizeStart
    else -> GripAction.ResizeEnd
}

/** Note durations relative to a whole note; "1/16" is a sixteenth-note grid. uapmd-app's kSnapLabels. */
internal val SnapOptions = listOf("Free", "1/8", "1/16", "1/24", "1/32", "1/48", "1/64")
/** Quarter-note beats matching [SnapOptions]; index 0 is Free. uapmd-app's kSnapValues. */
internal val SnapBeats = listOf(0f, 4f / 8f, 4f / 16f, 4f / 24f, 4f / 32f, 4f / 48f, 4f / 64f)
/** Defaults to 1/16, as uapmd-app does. */
internal const val DefaultSnapIndex = 2

/**
 * [seconds] moved to the nearest multiple of [unitBeats] quarter-note beats,
 * counted from the timeline's start through [secondsToBeats] and back, so the
 * grid follows tempo changes.
 */
internal fun snapToBeats(
    seconds: Double,
    unitBeats: Double,
    secondsToBeats: (Double) -> Double,
    beatsToSeconds: (Double) -> Double
): Double {
    if (unitBeats <= 0.0) return seconds
    val beats = secondsToBeats(seconds)
    return beatsToSeconds(kotlin.math.round(beats / unitBeats) * unitBeats)
}

/** A clip's extent on the timeline, in seconds. */
internal data class ClipExtentSeconds(val start: Double, val end: Double)

/**
 * Where a grip drag of [deltaSeconds] would put the clip. The same answer serves
 * the preview drawn during the drag and the edit committed at its end, so what
 * the user lets go of is what they were shown.
 *
 * With [snap] the edge being dragged snaps — the start for a move — and without
 * it (null) it goes wherever the pointer is. A start resize
 * stops at [earliestStartSeconds]: the timeline's start for a MIDI clip, whose
 * content shifts along, and the source's start for an audio clip. No clip
 * becomes shorter than [minLengthSeconds].
 */
internal fun proposeClipEdit(
    action: GripAction,
    current: ClipExtentSeconds,
    earliestStartSeconds: Double,
    deltaSeconds: Double,
    snap: ((Double) -> Double)?,
    minLengthSeconds: Double
): ClipExtentSeconds {
    fun snap(seconds: Double) = snap?.invoke(seconds) ?: seconds
    return when (action) {
        GripAction.Move -> {
            val start = snap(current.start + deltaSeconds).coerceAtLeast(0.0)
            ClipExtentSeconds(start, current.end + (start - current.start))
        }
        GripAction.ResizeEnd -> ClipExtentSeconds(
            current.start,
            snap(current.end + deltaSeconds).coerceAtLeast(current.start + minLengthSeconds)
        )
        GripAction.ResizeStart -> {
            val latest = current.end - minLengthSeconds
            ClipExtentSeconds(
                snap(current.start + deltaSeconds).coerceIn(minOf(maxOf(0.0, earliestStartSeconds), latest), latest),
                current.end
            )
        }
    }
}
