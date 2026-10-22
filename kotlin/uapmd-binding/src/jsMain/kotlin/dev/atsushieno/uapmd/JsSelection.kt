package dev.atsushieno.uapmd

// The module is linked with -sWASM_BIGINT, so int64_t parameters, results and callback
// arguments cross as BigInt. Contexts, ticks and listener tokens go through these helpers.
private fun bigInt(v: Long): dynamic = js("BigInt")(v.toString())
private fun longOf(v: dynamic): Long = v.toString().toLong()

private fun str(ptr: Int): String? = if (ptr == 0) null else jsMod.UTF8ToString(ptr) as String

// ─── JsProjectSelectionService ───────────────────────────────────────────────

internal class JsProjectSelectionService(
    private val timeline: Int,
    private val handle: Int
) : ProjectSelectionService {

    override val defaultContext: SelectionContextId
        get() = longOf(jsMod._uapmd_selection_service_default_context(handle))

    override fun createContext(ownerObjectId: ProjectObjectId?): SelectionContextId =
        withJsCString(ownerObjectId) { p -> longOf(jsMod._uapmd_selection_service_create_context(handle, p)) }

    override fun hasContext(context: SelectionContextId): Boolean =
        jsMod._uapmd_selection_service_has_context(handle, bigInt(context)) as Boolean

    override fun removeContext(context: SelectionContextId) {
        jsMod._uapmd_selection_service_remove_context(handle, bigInt(context))
    }

    override fun snapshot(context: SelectionContextId): ProjectSelection? {
        val sel = jsMod._uapmd_selection_service_snapshot(handle, bigInt(context)) as Int
        if (sel == 0) return null
        return try { readSelection(sel) } finally { jsMod._uapmd_selection_destroy(sel) }
    }

    override fun activeSelection(): ProjectSelection {
        val sel = jsMod._uapmd_selection_service_active_selection(handle) as Int
        if (sel == 0) return ProjectSelection()
        return try { readSelection(sel) } finally { jsMod._uapmd_selection_destroy(sel) }
    }

    override fun replace(context: SelectionContextId, selection: ProjectSelection): SelectionUpdateResult {
        val sel = buildSelection(selection)
        return try {
            if (jsMod._uapmd_selection_service_replace(handle, bigInt(context), sel) as Boolean)
                SelectionUpdateResult(true)
            else SelectionUpdateResult(false, str(jsMod._uapmd_selection_service_last_error() as Int) ?: "")
        } finally { jsMod._uapmd_selection_destroy(sel) }
    }

    override fun clear(context: SelectionContextId) {
        jsMod._uapmd_selection_service_clear(handle, bigInt(context))
    }

    override fun activeContext(): SelectionContextId? =
        longOf(jsMod._uapmd_selection_service_active_context(handle)).takeIf { it >= 0 }

    override fun setActiveContext(context: SelectionContextId?): Boolean =
        jsMod._uapmd_selection_service_set_active_context(handle, bigInt(context ?: -1L)) as Boolean

    override fun addListener(listener: ProjectSelectionListener): Long {
        val changed: (dynamic, dynamic, dynamic, dynamic) -> Unit = { context, previous, current, _ ->
            listener.selectionChanged(
                longOf(context),
                if ((previous as Int) != 0) readSelection(previous) else ProjectSelection(),
                if ((current as Int) != 0) readSelection(current) else ProjectSelection()
            )
        }
        val active: (dynamic, dynamic, dynamic) -> Unit = { previous, current, _ ->
            listener.activeContextChanged(longOf(previous).takeIf { it >= 0 }, longOf(current).takeIf { it >= 0 })
        }
        val removed: (dynamic, dynamic) -> Unit = { context, _ -> listener.selectionContextRemoved(longOf(context)) }
        val ptrs = intArrayOf(
            addJsCallback(changed.asDynamic(), "vjiii"),
            addJsCallback(active.asDynamic(), "vjji"),
            addJsCallback(removed.asDynamic(), "vji")
        )
        val token = longOf(jsMod._uapmd_selection_service_add_listener(handle, 0, ptrs[0], ptrs[1], ptrs[2]))
        if (token > 0) jsSelectionListenerPtrs["$handle:$token"] = ptrs
        else ptrs.forEach { removeJsCallback(it) }
        return token
    }

    override fun removeListener(token: Long) {
        jsMod._uapmd_selection_service_remove_listener(handle, bigInt(token))
        jsSelectionListenerPtrs.remove("$handle:$token")?.forEach { removeJsCallback(it) }
    }

    override fun project(selection: ProjectSelection): ProjectSelectionProjection {
        val sel = buildSelection(selection)
        val prj = try { jsMod._uapmd_selection_project(timeline, sel) as Int } finally { jsMod._uapmd_selection_destroy(sel) }
        if (prj == 0) return ProjectSelectionProjection(emptyList(), emptyList(), null)
        try {
            return ProjectSelectionProjection(
                clipIds = List(jsMod._uapmd_selection_projection_clip_count(prj) as Int) {
                    str(jsMod._uapmd_selection_projection_clip_at(prj, it) as Int) ?: ""
                },
                trackIds = List(jsMod._uapmd_selection_projection_track_count(prj) as Int) {
                    str(jsMod._uapmd_selection_projection_track_at(prj, it) as Int) ?: ""
                },
                timeRange = if (jsMod._uapmd_selection_projection_has_time_range(prj) as Boolean)
                    (jsMod._uapmd_selection_projection_range_start_seconds(prj) as Double)..
                        (jsMod._uapmd_selection_projection_range_end_seconds(prj) as Double)
                else null
            )
        } finally { jsMod._uapmd_selection_projection_destroy(prj) }
    }
}

// The table entries of a listener's three callbacks, kept until it is removed.
private val jsSelectionListenerPtrs = mutableMapOf<String, IntArray>()

// ─── Selection <-> native snapshot ───────────────────────────────────────────

private fun readSelection(sel: Int): ProjectSelection {
    val objects = List(jsMod._uapmd_selection_object_count(sel) as Int) {
        str(jsMod._uapmd_selection_object_at(sel, it) as Int) ?: ""
    }
    val regions = List(jsMod._uapmd_selection_region_count(sel) as Int) { r ->
        when (jsMod._uapmd_selection_region_kind(sel, r) as Int) {
            0 -> TimelineSelectionRegion(
                jsMod._uapmd_selection_region_start_seconds(sel, r) as Double,
                jsMod._uapmd_selection_region_end_seconds(sel, r) as Double,
                if (jsMod._uapmd_selection_region_tracks_restricted(sel, r) as Boolean)
                    List(jsMod._uapmd_selection_region_track_count(sel, r) as Int) {
                        str(jsMod._uapmd_selection_region_track_at(sel, r, it) as Int) ?: ""
                    }
                else null
            )
            1 -> {
                val pMin = jsMod._uapmd_selection_region_pitch_min(sel, r) as Int
                val pMax = jsMod._uapmd_selection_region_pitch_max(sel, r) as Int
                MidiNoteSelectionRegion(
                    str(jsMod._uapmd_selection_region_clip_id(sel, r) as Int) ?: "",
                    longOf(jsMod._uapmd_selection_region_start_tick(sel, r)),
                    longOf(jsMod._uapmd_selection_region_end_tick(sel, r)),
                    if (pMin >= 0 && pMax >= 0) pMin..pMax else null,
                    (jsMod._uapmd_selection_region_note_group(sel, r) as Int).takeIf { it >= 0 },
                    (jsMod._uapmd_selection_region_note_channel(sel, r) as Int).takeIf { it >= 0 }
                )
            }
            else -> MidiAutomationSelectionRegion(
                MidiAutomationLane(
                    str(jsMod._uapmd_selection_region_clip_id(sel, r) as Int) ?: "",
                    str(jsMod._uapmd_selection_region_lane_note_id(sel, r) as Int),
                    jsMod._uapmd_selection_region_lane_group(sel, r) as Int,
                    jsMod._uapmd_selection_region_lane_channel(sel, r) as Int,
                    jsMod._uapmd_selection_region_lane_status(sel, r) as Int,
                    jsMod._uapmd_selection_region_lane_parameter(sel, r) as Int
                ),
                longOf(jsMod._uapmd_selection_region_start_tick(sel, r)),
                longOf(jsMod._uapmd_selection_region_end_tick(sel, r)),
                if (jsMod._uapmd_selection_region_has_values(sel, r) as Boolean)
                    (jsMod._uapmd_selection_region_value_min(sel, r) as Double)..
                        (jsMod._uapmd_selection_region_value_max(sel, r) as Double)
                else null
            )
        }
    }
    return ProjectSelection(objects, regions)
}

/** The caller destroys the returned handle. */
private fun buildSelection(selection: ProjectSelection): Int {
    val sel = jsMod._uapmd_selection_create() as Int
    selection.objects.forEach { id -> withJsCString(id) { p -> jsMod._uapmd_selection_add_object(sel, p) } }
    for (region in selection.regions) when (region) {
        is TimelineSelectionRegion -> {
            val r = jsMod._uapmd_selection_add_timeline_region(sel, region.startSeconds, region.endSeconds) as Int
            region.trackIds?.let { ids ->
                jsMod._uapmd_selection_region_restrict_tracks(sel, r)
                ids.forEach { id -> withJsCString(id) { p -> jsMod._uapmd_selection_region_add_track(sel, r, p) } }
            }
        }
        is MidiNoteSelectionRegion -> withJsCString(region.clipId) { c ->
            jsMod._uapmd_selection_add_midi_note_region(
                sel, c, bigInt(region.startTick), bigInt(region.endTick),
                region.pitches?.first ?: -1, region.pitches?.last ?: -1,
                region.group ?: -1, region.channel ?: -1
            )
        }
        is MidiAutomationSelectionRegion -> withJsCString(region.lane.clipId) { c ->
            withJsCString(region.lane.noteId) { n ->
                jsMod._uapmd_selection_add_midi_automation_region(
                    sel, c, n,
                    region.lane.group, region.lane.channel, region.lane.status, region.lane.parameter,
                    bigInt(region.startTick), bigInt(region.endTick),
                    region.values != null, region.values?.start ?: 0.0, region.values?.endInclusive ?: 0.0
                )
            }
        }
    }
    return sel
}
