package dev.atsushieno.uapmd.cmp

/**
 * Which sub-lane each clip of a track is drawn in, so overlapping clips stack
 * instead of hiding one another.
 *
 * A port of `uapmd_app_gui::assignLanes`
 * (`uapmd-app/gui/TimelineLaneAssignment.hpp`): greedy first fit — sort by
 * start, then give each clip the first lane whose last clip ends at or before
 * this clip's start. A track that has no overlaps keeps one lane and therefore
 * its original height.
 *
 * Unit-agnostic: `start`/`end` need only share a unit. uapmd-cmp passes samples,
 * which is what `ClipData` carries.
 */
data class ClipLaneAssignment(
    val laneByClipId: Map<Int, Int>,
    val laneCount: Int
) {
    fun laneOf(clipId: Int): Int = laneByClipId[clipId] ?: 0

    companion object {
        /** One lane, nothing in it — a track with no clips is still one row tall. */
        val Single = ClipLaneAssignment(emptyMap(), 1)
    }
}

/** [clips] as (id, start, end) triples in any one unit. */
fun assignClipLanes(clips: List<Triple<Int, Long, Long>>): ClipLaneAssignment {
    if (clips.isEmpty()) return ClipLaneAssignment.Single
    val laneByClipId = HashMap<Int, Int>(clips.size)
    // The end of the last clip placed in each lane.
    val laneEnds = ArrayList<Long>()

    // Ties broken by id so the layout is stable frame to frame: two clips that
    // start together must not swap lanes when the list order changes.
    for ((id, start, end) in clips.sortedWith(compareBy({ it.second }, { it.first }))) {
        var lane = laneEnds.indexOfFirst { it <= start }
        if (lane < 0) {
            lane = laneEnds.size
            laneEnds.add(end)
        } else {
            laneEnds[lane] = end
        }
        laneByClipId[id] = lane
    }
    return ClipLaneAssignment(laneByClipId, maxOf(1, laneEnds.size))
}

/**
 * Where each lane sits inside a track of [totalHeight] pixels.
 *
 * Drawing and hit testing both go through this, so a click can never land on a
 * different clip than the one under the pointer.
 */
class LaneGeometry(val laneCount: Int, val totalHeight: Float) {
    val laneHeight: Float get() = totalHeight / laneCount.coerceAtLeast(1)

    /** Top of a lane's clip rectangle. */
    fun clipTop(lane: Int): Float = lane * laneHeight + ClipInset

    /** Height of a lane's clip rectangle; the inset doubles as the gap between lanes. */
    val clipHeight: Float get() = (laneHeight - ClipInset * 2f).coerceAtLeast(1f)

    /** The lane a pointer at [y] is in. */
    fun laneAt(y: Float): Int =
        (y / laneHeight).toInt().coerceIn(0, laneCount.coerceAtLeast(1) - 1)

    companion object {
        /** Kept at the single-lane value, so a track without overlaps looks unchanged. */
        const val ClipInset = 4f
    }
}
