@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.atsushieno.uapmd

import kotlinx.cinterop.*
import uapmd.*

// ─── NativeProjectSelectionService ───────────────────────────────────────────

internal class NativeProjectSelectionService(
    private val timeline: uapmd_timeline_facade_t,
    private val handle: uapmd_selection_service_t
) : ProjectSelectionService {

    override val defaultContext: SelectionContextId get() = uapmd_selection_service_default_context(handle)

    override fun createContext(ownerObjectId: ProjectObjectId?): SelectionContextId =
        uapmd_selection_service_create_context(handle, ownerObjectId)

    override fun hasContext(context: SelectionContextId): Boolean =
        uapmd_selection_service_has_context(handle, context)

    override fun removeContext(context: SelectionContextId) =
        uapmd_selection_service_remove_context(handle, context)

    override fun snapshot(context: SelectionContextId): ProjectSelection? {
        val sel = uapmd_selection_service_snapshot(handle, context) ?: return null
        return try { readSelection(sel) } finally { uapmd_selection_destroy(sel) }
    }

    override fun activeSelection(): ProjectSelection {
        val sel = uapmd_selection_service_active_selection(handle) ?: return ProjectSelection()
        return try { readSelection(sel) } finally { uapmd_selection_destroy(sel) }
    }

    override fun replace(context: SelectionContextId, selection: ProjectSelection): SelectionUpdateResult {
        val sel = buildSelection(selection)
        return try {
            if (uapmd_selection_service_replace(handle, context, sel)) SelectionUpdateResult(true)
            else SelectionUpdateResult(false, uapmd_selection_service_last_error()?.toKString() ?: "")
        } finally { uapmd_selection_destroy(sel) }
    }

    override fun clear(context: SelectionContextId) = uapmd_selection_service_clear(handle, context)

    override fun activeContext(): SelectionContextId? =
        uapmd_selection_service_active_context(handle).takeIf { it >= 0 }

    override fun setActiveContext(context: SelectionContextId?): Boolean =
        uapmd_selection_service_set_active_context(handle, context ?: -1L)

    override fun addListener(listener: ProjectSelectionListener): Long {
        val ref = StableRef.create(listener)
        val token = uapmd_selection_service_add_listener(
            handle, ref.asCPointer(),
            staticCFunction { context, previous, current, userData ->
                val l = userData?.asStableRef<ProjectSelectionListener>()?.get() ?: return@staticCFunction
                l.selectionChanged(
                    context,
                    previous?.let { readSelection(it) } ?: ProjectSelection(),
                    current?.let { readSelection(it) } ?: ProjectSelection()
                )
            },
            staticCFunction { previous, current, userData ->
                userData?.asStableRef<ProjectSelectionListener>()?.get()
                    ?.activeContextChanged(previous.takeIf { it >= 0 }, current.takeIf { it >= 0 })
            },
            staticCFunction { context, userData ->
                userData?.asStableRef<ProjectSelectionListener>()?.get()?.selectionContextRemoved(context)
            }
        )
        if (token > 0) nativeSelectionListenerRefs[SelectionListenerKey(handle.rawValue.toLong(), token)] = ref
        else ref.dispose()
        return token
    }

    override fun removeListener(token: Long) {
        uapmd_selection_service_remove_listener(handle, token)
        nativeSelectionListenerRefs.remove(SelectionListenerKey(handle.rawValue.toLong(), token))?.dispose()
    }

    override fun project(selection: ProjectSelection): ProjectSelectionProjection {
        val sel = buildSelection(selection)
        val prj = try { uapmd_selection_project(timeline, sel) } finally { uapmd_selection_destroy(sel) }
            ?: return ProjectSelectionProjection(emptyList(), emptyList(), null)
        try {
            return ProjectSelectionProjection(
                clipIds = List(uapmd_selection_projection_clip_count(prj).toInt()) {
                    uapmd_selection_projection_clip_at(prj, it.toUInt())?.toKString() ?: ""
                },
                trackIds = List(uapmd_selection_projection_track_count(prj).toInt()) {
                    uapmd_selection_projection_track_at(prj, it.toUInt())?.toKString() ?: ""
                },
                timeRange = if (uapmd_selection_projection_has_time_range(prj))
                    uapmd_selection_projection_range_start_seconds(prj)..
                        uapmd_selection_projection_range_end_seconds(prj)
                else null
            )
        } finally { uapmd_selection_projection_destroy(prj) }
    }
}

private data class SelectionListenerKey(val service: Long, val token: Long)
private val nativeSelectionListenerRefs = mutableMapOf<SelectionListenerKey, StableRef<*>>()

// ─── Selection <-> native snapshot ───────────────────────────────────────────

private fun readSelection(sel: uapmd_selection_t): ProjectSelection {
    val objects = List(uapmd_selection_object_count(sel).toInt()) {
        uapmd_selection_object_at(sel, it.toUInt())?.toKString() ?: ""
    }
    val regions = List(uapmd_selection_region_count(sel).toInt()) { i ->
        val r = i.toUInt()
        when (uapmd_selection_region_kind(sel, r)) {
            0 -> TimelineSelectionRegion(
                uapmd_selection_region_start_seconds(sel, r),
                uapmd_selection_region_end_seconds(sel, r),
                if (uapmd_selection_region_tracks_restricted(sel, r))
                    List(uapmd_selection_region_track_count(sel, r).toInt()) {
                        uapmd_selection_region_track_at(sel, r, it.toUInt())?.toKString() ?: ""
                    }
                else null
            )
            1 -> {
                val pMin = uapmd_selection_region_pitch_min(sel, r)
                val pMax = uapmd_selection_region_pitch_max(sel, r)
                MidiNoteSelectionRegion(
                    uapmd_selection_region_clip_id(sel, r)?.toKString() ?: "",
                    uapmd_selection_region_start_tick(sel, r),
                    uapmd_selection_region_end_tick(sel, r),
                    if (pMin >= 0 && pMax >= 0) pMin..pMax else null,
                    uapmd_selection_region_note_group(sel, r).takeIf { it >= 0 },
                    uapmd_selection_region_note_channel(sel, r).takeIf { it >= 0 }
                )
            }
            else -> MidiAutomationSelectionRegion(
                MidiAutomationLane(
                    uapmd_selection_region_clip_id(sel, r)?.toKString() ?: "",
                    uapmd_selection_region_lane_note_id(sel, r)?.toKString(),
                    uapmd_selection_region_lane_group(sel, r),
                    uapmd_selection_region_lane_channel(sel, r),
                    uapmd_selection_region_lane_status(sel, r),
                    uapmd_selection_region_lane_parameter(sel, r)
                ),
                uapmd_selection_region_start_tick(sel, r),
                uapmd_selection_region_end_tick(sel, r),
                if (uapmd_selection_region_has_values(sel, r))
                    uapmd_selection_region_value_min(sel, r)..uapmd_selection_region_value_max(sel, r)
                else null
            )
        }
    }
    return ProjectSelection(objects, regions)
}

/** The caller destroys the returned handle. */
private fun buildSelection(selection: ProjectSelection): uapmd_selection_t {
    val sel = uapmd_selection_create() ?: error("uapmd_selection_create failed")
    selection.objects.forEach { uapmd_selection_add_object(sel, it) }
    for (region in selection.regions) when (region) {
        is TimelineSelectionRegion -> {
            val r = uapmd_selection_add_timeline_region(sel, region.startSeconds, region.endSeconds)
            region.trackIds?.let { ids ->
                uapmd_selection_region_restrict_tracks(sel, r)
                ids.forEach { uapmd_selection_region_add_track(sel, r, it) }
            }
        }
        is MidiNoteSelectionRegion -> uapmd_selection_add_midi_note_region(
            sel, region.clipId, region.startTick, region.endTick,
            region.pitches?.first ?: -1, region.pitches?.last ?: -1,
            region.group ?: -1, region.channel ?: -1
        )
        is MidiAutomationSelectionRegion -> uapmd_selection_add_midi_automation_region(
            sel, region.lane.clipId, region.lane.noteId,
            region.lane.group, region.lane.channel, region.lane.status, region.lane.parameter,
            region.startTick, region.endTick,
            region.values != null, region.values?.start ?: 0.0, region.values?.endInclusive ?: 0.0
        )
    }
    return sel
}
