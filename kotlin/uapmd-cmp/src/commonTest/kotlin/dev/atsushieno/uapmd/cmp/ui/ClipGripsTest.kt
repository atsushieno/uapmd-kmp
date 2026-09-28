package dev.atsushieno.uapmd.cmp.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClipGripsTest {
    private val w = 10f

    private fun grip(grips: List<ClipGrip>, clipId: Int, edge: ClipEdge) =
        grips.single { it.clipId == clipId && it.edge == edge }

    @Test
    fun gripsAreAppendedOutsideAnIsolatedClip() {
        val grips = layoutClipGrips(listOf(ClipSpan(1, 0, 100f, 200f)), w)
        assertEquals(90f to 100f, grip(grips, 1, ClipEdge.Start).let { it.left to it.right })
        assertEquals(200f to 210f, grip(grips, 1, ClipEdge.End).let { it.left to it.right })
    }

    @Test
    fun buttedClipsGetTheirGripsInsideTheirOwnEdges() {
        val grips = layoutClipGrips(listOf(ClipSpan(1, 0, 0f, 100f), ClipSpan(2, 0, 100f, 200f)), w)
        assertEquals(90f to 100f, grip(grips, 1, ClipEdge.End).let { it.left to it.right })
        assertEquals(100f to 110f, grip(grips, 2, ClipEdge.Start).let { it.left to it.right })
        // Neither overlaps the other, so a press between them is never ambiguous.
        assertEquals(1, gripAt(grips, 0, 95f)?.clipId)
        assertEquals(2, gripAt(grips, 0, 105f)?.clipId)
    }

    @Test
    fun aNarrowGapIsSharedAroundItsMiddle() {
        val grips = layoutClipGrips(listOf(ClipSpan(1, 0, 0f, 100f), ClipSpan(2, 0, 106f, 200f)), w)
        assertEquals(93f to 103f, grip(grips, 1, ClipEdge.End).let { it.left to it.right })
        assertEquals(103f to 113f, grip(grips, 2, ClipEdge.Start).let { it.left to it.right })
    }

    @Test
    fun aWideGapKeepsBothGripsAppended() {
        val grips = layoutClipGrips(listOf(ClipSpan(1, 0, 0f, 100f), ClipSpan(2, 0, 120f, 200f)), w)
        assertEquals(100f to 110f, grip(grips, 1, ClipEdge.End).let { it.left to it.right })
        assertEquals(110f to 120f, grip(grips, 2, ClipEdge.Start).let { it.left to it.right })
    }

    @Test
    fun aClipAtTheLaneStartHasItsStartGripInside() {
        val grips = layoutClipGrips(listOf(ClipSpan(1, 0, 0f, 100f)), w)
        assertEquals(0f to 10f, grip(grips, 1, ClipEdge.Start).let { it.left to it.right })
    }

    @Test
    fun clipsInOtherLanesDoNotAffectEachOther() {
        val grips = layoutClipGrips(listOf(ClipSpan(1, 0, 0f, 100f), ClipSpan(2, 1, 100f, 200f)), w)
        assertEquals(100f to 110f, grip(grips, 1, ClipEdge.End).let { it.left to it.right })
        assertEquals(90f to 100f, grip(grips, 2, ClipEdge.Start).let { it.left to it.right })
        assertNull(gripAt(grips, 1, 105f))
    }

    @Test
    fun overlappingGripsOfAShortClipGoToTheNearerEdge() {
        // 12px wide: its inward start grip and appended end grip do not overlap,
        // but its start grip reaches past the middle.
        val grips = layoutClipGrips(listOf(ClipSpan(1, 0, 0f, 6f)), w)
        assertEquals(ClipEdge.Start, gripAt(grips, 0, 2f)?.edge)
        assertEquals(ClipEdge.End, gripAt(grips, 0, 8f)?.edge)
    }

    @Test
    fun upperHalfResizesAndLowerHalfMoves() {
        assertEquals(GripAction.ResizeStart, gripAction(ClipEdge.Start, upperHalf = true))
        assertEquals(GripAction.ResizeEnd, gripAction(ClipEdge.End, upperHalf = true))
        assertEquals(GripAction.Move, gripAction(ClipEdge.Start, upperHalf = false))
        assertEquals(GripAction.Move, gripAction(ClipEdge.End, upperHalf = false))
    }

    // Half-second steps: 1/8 notes at 120 BPM.
    private val grid: (Double) -> Double = { snapToBeats(it, 1.0, { s -> s * 2.0 }, { b -> b / 2.0 }) }
    private val clip = ClipExtentSeconds(2.0, 4.0)

    @Test
    fun snappingFollowsTheBeatUnit() {
        val toBeats = { s: Double -> s * 2.0 }
        val toSeconds = { b: Double -> b / 2.0 }
        // 1/16 at 120 BPM is 0.125 s.
        assertEquals(1.125, snapToBeats(1.14, 0.25, toBeats, toSeconds), 1e-9)
        assertEquals(1.14, snapToBeats(1.14, 0.0, toBeats, toSeconds), 1e-9)
    }

    @Test
    fun aSmallMoveSnapsBackToWhereTheClipWas() {
        assertEquals(clip, proposeClipEdit(GripAction.Move, clip, 0.0, 0.1, grid, 0.001))
    }

    @Test
    fun aMoveSnapsTheStartAndKeepsTheLength() {
        assertEquals(ClipExtentSeconds(3.0, 5.0), proposeClipEdit(GripAction.Move, clip, 0.0, 0.9, grid, 0.001))
    }

    @Test
    fun withoutAGridAMoveGoesWhereThePointerIs() {
        assertEquals(ClipExtentSeconds(2.1, 4.1), proposeClipEdit(GripAction.Move, clip, 0.0, 0.1, null, 0.001))
    }

    @Test
    fun aMoveStopsAtZero() {
        assertEquals(ClipExtentSeconds(0.0, 2.0), proposeClipEdit(GripAction.Move, clip, 0.0, -5.0, grid, 0.001))
    }

    @Test
    fun aStartResizeStopsAtTheEarliestStart() {
        // MIDI: the content shifts along, so only the timeline's start stops it.
        assertEquals(ClipExtentSeconds(0.5, 4.0), proposeClipEdit(GripAction.ResizeStart, clip, 0.0, -1.4, grid, 0.001))
        assertEquals(ClipExtentSeconds(0.0, 4.0), proposeClipEdit(GripAction.ResizeStart, clip, 0.0, -5.0, grid, 0.001))
        // Audio trimmed by a second: it grows back that far and no further.
        assertEquals(ClipExtentSeconds(1.0, 4.0), proposeClipEdit(GripAction.ResizeStart, clip, 1.0, -1.7, grid, 0.001))
    }

    @Test
    fun resizesNeverEmptyTheClip() {
        assertEquals(ClipExtentSeconds(4.0 - 0.001, 4.0), proposeClipEdit(GripAction.ResizeStart, clip, 0.0, 5.0, grid, 0.001))
        assertEquals(ClipExtentSeconds(2.0, 2.0 + 0.001), proposeClipEdit(GripAction.ResizeEnd, clip, 0.0, -5.0, grid, 0.001))
    }
}
