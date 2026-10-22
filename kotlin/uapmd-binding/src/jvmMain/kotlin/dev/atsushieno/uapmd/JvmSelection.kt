package dev.atsushieno.uapmd

import com.sun.jna.Pointer
import dev.atsushieno.uapmd.jna.SelectionActiveContextChangedCb
import dev.atsushieno.uapmd.jna.SelectionChangedCb
import dev.atsushieno.uapmd.jna.SelectionContextRemovedCb
import java.util.concurrent.ConcurrentHashMap

// ─── JvmProjectSelectionService ──────────────────────────────────────────────

internal class JvmProjectSelectionService(
    private val timeline: Pointer,
    private val handle: Pointer
) : ProjectSelectionService {

    // JNA callbacks must stay referenced until the listener is removed.
    private class ListenerRefs(
        val changed: SelectionChangedCb,
        val active: SelectionActiveContextChangedCb,
        val removed: SelectionContextRemovedCb
    )

    override val defaultContext: SelectionContextId get() = lib.uapmd_selection_service_default_context(handle)

    override fun createContext(ownerObjectId: ProjectObjectId?): SelectionContextId =
        lib.uapmd_selection_service_create_context(handle, ownerObjectId)

    override fun hasContext(context: SelectionContextId): Boolean =
        lib.uapmd_selection_service_has_context(handle, context)

    override fun removeContext(context: SelectionContextId) =
        lib.uapmd_selection_service_remove_context(handle, context)

    override fun snapshot(context: SelectionContextId): ProjectSelection? {
        val sel = lib.uapmd_selection_service_snapshot(handle, context) ?: return null
        return try { readSelection(sel) } finally { lib.uapmd_selection_destroy(sel) }
    }

    override fun activeSelection(): ProjectSelection {
        val sel = lib.uapmd_selection_service_active_selection(handle) ?: return ProjectSelection()
        return try { readSelection(sel) } finally { lib.uapmd_selection_destroy(sel) }
    }

    override fun replace(context: SelectionContextId, selection: ProjectSelection): SelectionUpdateResult {
        val sel = buildSelection(selection)
        return try {
            if (lib.uapmd_selection_service_replace(handle, context, sel)) SelectionUpdateResult(true)
            else SelectionUpdateResult(false, lib.uapmd_selection_service_last_error() ?: "")
        } finally { lib.uapmd_selection_destroy(sel) }
    }

    override fun clear(context: SelectionContextId) = lib.uapmd_selection_service_clear(handle, context)

    override fun activeContext(): SelectionContextId? =
        lib.uapmd_selection_service_active_context(handle).takeIf { it >= 0 }

    override fun setActiveContext(context: SelectionContextId?): Boolean =
        lib.uapmd_selection_service_set_active_context(handle, context ?: -1L)

    override fun addListener(listener: ProjectSelectionListener): Long {
        val refs = ListenerRefs(
            object : SelectionChangedCb {
                override fun invoke(context: Long, previous: Pointer?, current: Pointer?, userData: Pointer?) {
                    listener.selectionChanged(
                        context,
                        previous?.let { readSelection(it) } ?: ProjectSelection(),
                        current?.let { readSelection(it) } ?: ProjectSelection()
                    )
                }
            },
            object : SelectionActiveContextChangedCb {
                override fun invoke(previousContext: Long, currentContext: Long, userData: Pointer?) =
                    listener.activeContextChanged(previousContext.takeIf { it >= 0 }, currentContext.takeIf { it >= 0 })
            },
            object : SelectionContextRemovedCb {
                override fun invoke(context: Long, userData: Pointer?) = listener.selectionContextRemoved(context)
            }
        )
        val token = lib.uapmd_selection_service_add_listener(handle, null, refs.changed, refs.active, refs.removed)
        if (token > 0) selectionListenerRefs[ListenerKey(Pointer.nativeValue(handle), token)] = refs
        return token
    }

    override fun removeListener(token: Long) {
        lib.uapmd_selection_service_remove_listener(handle, token)
        selectionListenerRefs.remove(ListenerKey(Pointer.nativeValue(handle), token))
    }

    override fun project(selection: ProjectSelection): ProjectSelectionProjection {
        val sel = buildSelection(selection)
        val prj = try { lib.uapmd_selection_project(timeline, sel) } finally { lib.uapmd_selection_destroy(sel) }
            ?: return ProjectSelectionProjection(emptyList(), emptyList(), null)
        try {
            return ProjectSelectionProjection(
                clipIds = List(lib.uapmd_selection_projection_clip_count(prj)) {
                    lib.uapmd_selection_projection_clip_at(prj, it) ?: ""
                },
                trackIds = List(lib.uapmd_selection_projection_track_count(prj)) {
                    lib.uapmd_selection_projection_track_at(prj, it) ?: ""
                },
                timeRange = if (lib.uapmd_selection_projection_has_time_range(prj))
                    lib.uapmd_selection_projection_range_start_seconds(prj)..
                        lib.uapmd_selection_projection_range_end_seconds(prj)
                else null
            )
        } finally { lib.uapmd_selection_projection_destroy(prj) }
    }
}

private data class ListenerKey(val service: Long, val token: Long)
private val selectionListenerRefs = ConcurrentHashMap<ListenerKey, Any>()

// ─── Selection <-> native snapshot ───────────────────────────────────────────

private fun readSelection(sel: Pointer): ProjectSelection {
    val objects = List(lib.uapmd_selection_object_count(sel)) { lib.uapmd_selection_object_at(sel, it) ?: "" }
    val regions = List(lib.uapmd_selection_region_count(sel)) { r ->
        when (lib.uapmd_selection_region_kind(sel, r)) {
            0 -> TimelineSelectionRegion(
                lib.uapmd_selection_region_start_seconds(sel, r),
                lib.uapmd_selection_region_end_seconds(sel, r),
                if (lib.uapmd_selection_region_tracks_restricted(sel, r))
                    List(lib.uapmd_selection_region_track_count(sel, r)) {
                        lib.uapmd_selection_region_track_at(sel, r, it) ?: ""
                    }
                else null
            )
            1 -> {
                val pMin = lib.uapmd_selection_region_pitch_min(sel, r)
                val pMax = lib.uapmd_selection_region_pitch_max(sel, r)
                MidiNoteSelectionRegion(
                    lib.uapmd_selection_region_clip_id(sel, r) ?: "",
                    lib.uapmd_selection_region_start_tick(sel, r),
                    lib.uapmd_selection_region_end_tick(sel, r),
                    if (pMin >= 0 && pMax >= 0) pMin..pMax else null,
                    lib.uapmd_selection_region_note_group(sel, r).takeIf { it >= 0 },
                    lib.uapmd_selection_region_note_channel(sel, r).takeIf { it >= 0 }
                )
            }
            else -> MidiAutomationSelectionRegion(
                MidiAutomationLane(
                    lib.uapmd_selection_region_clip_id(sel, r) ?: "",
                    lib.uapmd_selection_region_lane_note_id(sel, r),
                    lib.uapmd_selection_region_lane_group(sel, r),
                    lib.uapmd_selection_region_lane_channel(sel, r),
                    lib.uapmd_selection_region_lane_status(sel, r),
                    lib.uapmd_selection_region_lane_parameter(sel, r)
                ),
                lib.uapmd_selection_region_start_tick(sel, r),
                lib.uapmd_selection_region_end_tick(sel, r),
                if (lib.uapmd_selection_region_has_values(sel, r))
                    lib.uapmd_selection_region_value_min(sel, r)..lib.uapmd_selection_region_value_max(sel, r)
                else null
            )
        }
    }
    return ProjectSelection(objects, regions)
}

/** The caller destroys the returned handle. */
private fun buildSelection(selection: ProjectSelection): Pointer {
    val sel = lib.uapmd_selection_create() ?: error("uapmd_selection_create failed")
    selection.objects.forEach { lib.uapmd_selection_add_object(sel, it) }
    for (region in selection.regions) when (region) {
        is TimelineSelectionRegion -> {
            val r = lib.uapmd_selection_add_timeline_region(sel, region.startSeconds, region.endSeconds)
            region.trackIds?.let { ids ->
                lib.uapmd_selection_region_restrict_tracks(sel, r)
                ids.forEach { lib.uapmd_selection_region_add_track(sel, r, it) }
            }
        }
        is MidiNoteSelectionRegion -> lib.uapmd_selection_add_midi_note_region(
            sel, region.clipId, region.startTick, region.endTick,
            region.pitches?.first ?: -1, region.pitches?.last ?: -1,
            region.group ?: -1, region.channel ?: -1
        )
        is MidiAutomationSelectionRegion -> lib.uapmd_selection_add_midi_automation_region(
            sel, region.lane.clipId, region.lane.noteId,
            region.lane.group, region.lane.channel, region.lane.status, region.lane.parameter,
            region.startTick, region.endTick,
            region.values != null, region.values?.start ?: 0.0, region.values?.endInclusive ?: 0.0
        )
    }
    return sel
}
