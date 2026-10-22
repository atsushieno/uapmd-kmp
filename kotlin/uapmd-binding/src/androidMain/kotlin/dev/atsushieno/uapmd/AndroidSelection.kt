package dev.atsushieno.uapmd

// ─── AndroidProjectSelectionService ──────────────────────────────────────────

internal class AndroidProjectSelectionService(
    private val timeline: Long,
    private val handle: Long
) : ProjectSelectionService {

    /** Called back by name from JNI; the selection handles are valid only during the call. */
    private class ListenerAdapter(val listener: ProjectSelectionListener) {
        @Suppress("unused")
        fun onSelectionChanged(context: Long, previous: Long, current: Long) =
            listener.selectionChanged(
                context,
                if (previous != 0L) readSelection(previous) else ProjectSelection(),
                if (current != 0L) readSelection(current) else ProjectSelection()
            )

        @Suppress("unused")
        fun onActiveContextChanged(previous: Long, current: Long) =
            listener.activeContextChanged(previous.takeIf { it >= 0 }, current.takeIf { it >= 0 })

        @Suppress("unused")
        fun onContextRemoved(context: Long) = listener.selectionContextRemoved(context)
    }

    override val defaultContext: SelectionContextId get() = JniBridge.uapmdSelectionServiceDefaultContext(handle)

    override fun createContext(ownerObjectId: ProjectObjectId?): SelectionContextId =
        JniBridge.uapmdSelectionServiceCreateContext(handle, ownerObjectId)

    override fun hasContext(context: SelectionContextId): Boolean =
        JniBridge.uapmdSelectionServiceHasContext(handle, context)

    override fun removeContext(context: SelectionContextId) =
        JniBridge.uapmdSelectionServiceRemoveContext(handle, context)

    override fun snapshot(context: SelectionContextId): ProjectSelection? {
        val sel = JniBridge.uapmdSelectionServiceSnapshot(handle, context)
        if (sel == 0L) return null
        return try { readSelection(sel) } finally { JniBridge.uapmdSelectionDestroy(sel) }
    }

    override fun activeSelection(): ProjectSelection {
        val sel = JniBridge.uapmdSelectionServiceActiveSelection(handle)
        if (sel == 0L) return ProjectSelection()
        return try { readSelection(sel) } finally { JniBridge.uapmdSelectionDestroy(sel) }
    }

    override fun replace(context: SelectionContextId, selection: ProjectSelection): SelectionUpdateResult {
        val sel = buildSelection(selection)
        return try {
            if (JniBridge.uapmdSelectionServiceReplace(handle, context, sel)) SelectionUpdateResult(true)
            else SelectionUpdateResult(false, JniBridge.uapmdSelectionServiceLastError())
        } finally { JniBridge.uapmdSelectionDestroy(sel) }
    }

    override fun clear(context: SelectionContextId) = JniBridge.uapmdSelectionServiceClear(handle, context)

    override fun activeContext(): SelectionContextId? =
        JniBridge.uapmdSelectionServiceActiveContext(handle).takeIf { it >= 0 }

    override fun setActiveContext(context: SelectionContextId?): Boolean =
        JniBridge.uapmdSelectionServiceSetActiveContext(handle, context ?: -1L)

    // The JNI layer holds a global ref to the adapter until the listener is removed.
    override fun addListener(listener: ProjectSelectionListener): Long =
        JniBridge.uapmdSelectionServiceAddListener(handle, ListenerAdapter(listener))

    override fun removeListener(token: Long) =
        JniBridge.uapmdSelectionServiceRemoveListener(handle, token)

    override fun project(selection: ProjectSelection): ProjectSelectionProjection {
        val sel = buildSelection(selection)
        val prj = try { JniBridge.uapmdSelectionProject(timeline, sel) } finally { JniBridge.uapmdSelectionDestroy(sel) }
        if (prj == 0L) return ProjectSelectionProjection(emptyList(), emptyList(), null)
        try {
            return ProjectSelectionProjection(
                clipIds = List(JniBridge.uapmdSelectionProjectionClipCount(prj)) {
                    JniBridge.uapmdSelectionProjectionClipAt(prj, it) ?: ""
                },
                trackIds = List(JniBridge.uapmdSelectionProjectionTrackCount(prj)) {
                    JniBridge.uapmdSelectionProjectionTrackAt(prj, it) ?: ""
                },
                timeRange = if (JniBridge.uapmdSelectionProjectionHasTimeRange(prj))
                    JniBridge.uapmdSelectionProjectionRangeStartSeconds(prj)..
                        JniBridge.uapmdSelectionProjectionRangeEndSeconds(prj)
                else null
            )
        } finally { JniBridge.uapmdSelectionProjectionDestroy(prj) }
    }
}

// ─── Selection <-> native snapshot ───────────────────────────────────────────

private fun readSelection(sel: Long): ProjectSelection {
    val objects = List(JniBridge.uapmdSelectionObjectCount(sel)) { JniBridge.uapmdSelectionObjectAt(sel, it) ?: "" }
    val regions = List(JniBridge.uapmdSelectionRegionCount(sel)) { r ->
        when (JniBridge.uapmdSelectionRegionKind(sel, r)) {
            0 -> TimelineSelectionRegion(
                JniBridge.uapmdSelectionRegionStartSeconds(sel, r),
                JniBridge.uapmdSelectionRegionEndSeconds(sel, r),
                if (JniBridge.uapmdSelectionRegionTracksRestricted(sel, r))
                    List(JniBridge.uapmdSelectionRegionTrackCount(sel, r)) {
                        JniBridge.uapmdSelectionRegionTrackAt(sel, r, it) ?: ""
                    }
                else null
            )
            1 -> {
                val pMin = JniBridge.uapmdSelectionRegionPitchMin(sel, r)
                val pMax = JniBridge.uapmdSelectionRegionPitchMax(sel, r)
                MidiNoteSelectionRegion(
                    JniBridge.uapmdSelectionRegionClipId(sel, r) ?: "",
                    JniBridge.uapmdSelectionRegionStartTick(sel, r),
                    JniBridge.uapmdSelectionRegionEndTick(sel, r),
                    if (pMin >= 0 && pMax >= 0) pMin..pMax else null,
                    JniBridge.uapmdSelectionRegionNoteGroup(sel, r).takeIf { it >= 0 },
                    JniBridge.uapmdSelectionRegionNoteChannel(sel, r).takeIf { it >= 0 }
                )
            }
            else -> MidiAutomationSelectionRegion(
                MidiAutomationLane(
                    JniBridge.uapmdSelectionRegionClipId(sel, r) ?: "",
                    JniBridge.uapmdSelectionRegionLaneNoteId(sel, r),
                    JniBridge.uapmdSelectionRegionLaneGroup(sel, r),
                    JniBridge.uapmdSelectionRegionLaneChannel(sel, r),
                    JniBridge.uapmdSelectionRegionLaneStatus(sel, r),
                    JniBridge.uapmdSelectionRegionLaneParameter(sel, r)
                ),
                JniBridge.uapmdSelectionRegionStartTick(sel, r),
                JniBridge.uapmdSelectionRegionEndTick(sel, r),
                if (JniBridge.uapmdSelectionRegionHasValues(sel, r))
                    JniBridge.uapmdSelectionRegionValueMin(sel, r)..JniBridge.uapmdSelectionRegionValueMax(sel, r)
                else null
            )
        }
    }
    return ProjectSelection(objects, regions)
}

/** The caller destroys the returned handle. */
private fun buildSelection(selection: ProjectSelection): Long {
    val sel = JniBridge.uapmdSelectionCreate()
    selection.objects.forEach { JniBridge.uapmdSelectionAddObject(sel, it) }
    for (region in selection.regions) when (region) {
        is TimelineSelectionRegion -> {
            val r = JniBridge.uapmdSelectionAddTimelineRegion(sel, region.startSeconds, region.endSeconds)
            region.trackIds?.let { ids ->
                JniBridge.uapmdSelectionRegionRestrictTracks(sel, r)
                ids.forEach { JniBridge.uapmdSelectionRegionAddTrack(sel, r, it) }
            }
        }
        is MidiNoteSelectionRegion -> JniBridge.uapmdSelectionAddMidiNoteRegion(
            sel, region.clipId, region.startTick, region.endTick,
            region.pitches?.first ?: -1, region.pitches?.last ?: -1,
            region.group ?: -1, region.channel ?: -1
        )
        is MidiAutomationSelectionRegion -> JniBridge.uapmdSelectionAddMidiAutomationRegion(
            sel, region.lane.clipId, region.lane.noteId,
            region.lane.group, region.lane.channel, region.lane.status, region.lane.parameter,
            region.startTick, region.endTick,
            region.values != null, region.values?.start ?: 0.0, region.values?.endInclusive ?: 0.0
        )
    }
    return sel
}
