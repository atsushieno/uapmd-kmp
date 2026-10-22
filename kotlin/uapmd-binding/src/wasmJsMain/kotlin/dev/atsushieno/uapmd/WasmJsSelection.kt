package dev.atsushieno.uapmd

// The module is linked with -sWASM_BIGINT=1, so int64_t parameters, results and callback
// arguments are BigInt. Contexts, ticks and listener tokens cross here as decimal strings,
// which keeps them exact beyond 2^53 and avoids Long in the JS interop.

@JsFun("(mod, svc) => mod._uapmd_selection_service_default_context(svc).toString()")
private external fun wasmSelDefaultContext(mod: UapmdCApiModule, svc: Int): String

@JsFun("(mod, svc, owner) => mod._uapmd_selection_service_create_context(svc, owner).toString()")
private external fun wasmSelCreateContext(mod: UapmdCApiModule, svc: Int, ownerPtr: Int): String

@JsFun("(mod, svc, ctx) => mod._uapmd_selection_service_has_context(svc, BigInt(ctx))")
private external fun wasmSelHasContext(mod: UapmdCApiModule, svc: Int, ctx: String): Boolean

@JsFun("(mod, svc, ctx) => { mod._uapmd_selection_service_remove_context(svc, BigInt(ctx)); }")
private external fun wasmSelRemoveContext(mod: UapmdCApiModule, svc: Int, ctx: String)

@JsFun("(mod, svc, ctx) => mod._uapmd_selection_service_snapshot(svc, BigInt(ctx))")
private external fun wasmSelSnapshot(mod: UapmdCApiModule, svc: Int, ctx: String): Int

@JsFun("(mod, svc) => mod._uapmd_selection_service_active_selection(svc)")
private external fun wasmSelActiveSelection(mod: UapmdCApiModule, svc: Int): Int

@JsFun("(mod, svc, ctx, sel) => mod._uapmd_selection_service_replace(svc, BigInt(ctx), sel)")
private external fun wasmSelReplace(mod: UapmdCApiModule, svc: Int, ctx: String, sel: Int): Boolean

@JsFun("(mod, svc, ctx) => { mod._uapmd_selection_service_clear(svc, BigInt(ctx)); }")
private external fun wasmSelClear(mod: UapmdCApiModule, svc: Int, ctx: String)

@JsFun("(mod, svc) => mod._uapmd_selection_service_active_context(svc).toString()")
private external fun wasmSelActiveContext(mod: UapmdCApiModule, svc: Int): String

@JsFun("(mod, svc, ctx) => mod._uapmd_selection_service_set_active_context(svc, BigInt(ctx))")
private external fun wasmSelSetActiveContext(mod: UapmdCApiModule, svc: Int, ctx: String): Boolean

@JsFun("(mod, svc, changed, active, removed) => mod._uapmd_selection_service_add_listener(svc, 0, changed, active, removed).toString()")
private external fun wasmSelAddListener(mod: UapmdCApiModule, svc: Int, changed: Int, active: Int, removed: Int): String

@JsFun("(mod, svc, token) => { mod._uapmd_selection_service_remove_listener(svc, BigInt(token)); }")
private external fun wasmSelRemoveListener(mod: UapmdCApiModule, svc: Int, token: String)

@JsFun("(mod, sel, r) => mod._uapmd_selection_region_start_tick(sel, r).toString()")
private external fun wasmSelRegionStartTick(mod: UapmdCApiModule, sel: Int, r: Int): String

@JsFun("(mod, sel, r) => mod._uapmd_selection_region_end_tick(sel, r).toString()")
private external fun wasmSelRegionEndTick(mod: UapmdCApiModule, sel: Int, r: Int): String

@JsFun("(mod, sel, clip, start, end, pMin, pMax, group, channel) => mod._uapmd_selection_add_midi_note_region(sel, clip, BigInt(start), BigInt(end), pMin, pMax, group, channel)")
private external fun wasmSelAddMidiNoteRegion(
    mod: UapmdCApiModule, sel: Int, clipPtr: Int, startTick: String, endTick: String,
    pitchMin: Int, pitchMax: Int, group: Int, channel: Int
): Int

@JsFun("(mod, sel, clip, note, group, channel, status, parameter, start, end, hasValues, vMin, vMax) => mod._uapmd_selection_add_midi_automation_region(sel, clip, note, group, channel, status, parameter, BigInt(start), BigInt(end), hasValues, vMin, vMax)")
private external fun wasmSelAddMidiAutomationRegion(
    mod: UapmdCApiModule, sel: Int, clipPtr: Int, notePtr: Int, group: Int, channel: Int,
    status: Int, parameter: Int, startTick: String, endTick: String,
    hasValues: Boolean, valueMin: Double, valueMax: Double
): Int

// ─── Listener dispatch ───────────────────────────────────────────────────────

internal val selectionListeners = mutableMapOf<Int, ProjectSelectionListener>()

// C: void(int64 context, uapmd_selection_t previous, uapmd_selection_t current, void*)
@JsExport
fun uapmdDispatchSelectionChanged(cbId: Int, context: String, previous: Int, current: Int, userData: Int) {
    val l = selectionListeners[cbId] ?: return
    l.selectionChanged(
        context.toLong(),
        if (previous != 0) readSelection(previous) else ProjectSelection(),
        if (current != 0) readSelection(current) else ProjectSelection()
    )
}

// C: void(int64 previous, int64 current, void*)
@JsExport
fun uapmdDispatchSelectionActiveContextChanged(cbId: Int, previous: String, current: String, userData: Int) {
    selectionListeners[cbId]?.activeContextChanged(previous.toLong().takeIf { it >= 0 }, current.toLong().takeIf { it >= 0 })
}

// C: void(int64 context, void*)
@JsExport
fun uapmdDispatchSelectionContextRemoved(cbId: Int, context: String, userData: Int) {
    selectionListeners[cbId]?.selectionContextRemoved(context.toLong())
}

// ─── WasmJsProjectSelectionService ───────────────────────────────────────────

internal class WasmJsProjectSelectionService(
    private val timeline: Int,
    private val handle: Int
) : ProjectSelectionService {

    override val defaultContext: SelectionContextId get() = wasmSelDefaultContext(wasmMod, handle).toLong()

    override fun createContext(ownerObjectId: ProjectObjectId?): SelectionContextId =
        withCStringKt(ownerObjectId) { p -> wasmSelCreateContext(wasmMod, handle, p).toLong() }

    override fun hasContext(context: SelectionContextId): Boolean =
        wasmSelHasContext(wasmMod, handle, context.toString())

    override fun removeContext(context: SelectionContextId) =
        wasmSelRemoveContext(wasmMod, handle, context.toString())

    override fun snapshot(context: SelectionContextId): ProjectSelection? {
        val sel = wasmSelSnapshot(wasmMod, handle, context.toString())
        if (sel == 0) return null
        return try { readSelection(sel) } finally { wasmMod.uapmdSelectionDestroy(sel) }
    }

    override fun activeSelection(): ProjectSelection {
        val sel = wasmSelActiveSelection(wasmMod, handle)
        if (sel == 0) return ProjectSelection()
        return try { readSelection(sel) } finally { wasmMod.uapmdSelectionDestroy(sel) }
    }

    override fun replace(context: SelectionContextId, selection: ProjectSelection): SelectionUpdateResult {
        val sel = buildSelection(selection)
        return try {
            if (wasmSelReplace(wasmMod, handle, context.toString(), sel)) SelectionUpdateResult(true)
            else SelectionUpdateResult(false, str(wasmMod.uapmdSelectionServiceLastError()) ?: "")
        } finally { wasmMod.uapmdSelectionDestroy(sel) }
    }

    override fun clear(context: SelectionContextId) = wasmSelClear(wasmMod, handle, context.toString())

    override fun activeContext(): SelectionContextId? =
        wasmSelActiveContext(wasmMod, handle).toLong().takeIf { it >= 0 }

    override fun setActiveContext(context: SelectionContextId?): Boolean =
        wasmSelSetActiveContext(wasmMod, handle, (context ?: -1L).toString())

    override fun addListener(listener: ProjectSelectionListener): Long {
        val cbId = nextCallbackId()
        selectionListeners[cbId] = listener
        val changed = makeCFunctionPtr(cbId, "uapmdDispatchSelectionChanged", "vjiii")
        val active = makeCFunctionPtr(cbId, "uapmdDispatchSelectionActiveContextChanged", "vjji")
        val removed = makeCFunctionPtr(cbId, "uapmdDispatchSelectionContextRemoved", "vji")
        val token = wasmSelAddListener(wasmMod, handle, changed, active, removed).toLong()
        if (token > 0) wasmSelectionListenerRegs["$handle:$token"] = SelectionListenerReg(cbId, intArrayOf(changed, active, removed))
        else {
            selectionListeners.remove(cbId)
            intArrayOf(changed, active, removed).forEach { removeCFunctionPtr(it) }
        }
        return token
    }

    override fun removeListener(token: Long) {
        wasmSelRemoveListener(wasmMod, handle, token.toString())
        wasmSelectionListenerRegs.remove("$handle:$token")?.let { reg ->
            selectionListeners.remove(reg.cbId)
            reg.tablePtrs.forEach { removeCFunctionPtr(it) }
        }
    }

    override fun project(selection: ProjectSelection): ProjectSelectionProjection {
        val sel = buildSelection(selection)
        val prj = try { wasmMod.uapmdSelectionProject(timeline, sel) } finally { wasmMod.uapmdSelectionDestroy(sel) }
        if (prj == 0) return ProjectSelectionProjection(emptyList(), emptyList(), null)
        try {
            return ProjectSelectionProjection(
                clipIds = List(wasmMod.uapmdSelectionProjectionClipCount(prj)) {
                    str(wasmMod.uapmdSelectionProjectionClipAt(prj, it)) ?: ""
                },
                trackIds = List(wasmMod.uapmdSelectionProjectionTrackCount(prj)) {
                    str(wasmMod.uapmdSelectionProjectionTrackAt(prj, it)) ?: ""
                },
                timeRange = if (wasmMod.uapmdSelectionProjectionHasTimeRange(prj))
                    wasmMod.uapmdSelectionProjectionRangeStartSeconds(prj)..
                        wasmMod.uapmdSelectionProjectionRangeEndSeconds(prj)
                else null
            )
        } finally { wasmMod.uapmdSelectionProjectionDestroy(prj) }
    }
}

// Listener registrations, kept until the listener is removed: the dispatch id and the three table entries.
private class SelectionListenerReg(val cbId: Int, val tablePtrs: IntArray)
private val wasmSelectionListenerRegs = mutableMapOf<String, SelectionListenerReg>()

private fun str(ptr: Int): String? = if (ptr == 0) null else wasmMod.utf8ToString(ptr)

// ─── Selection <-> native snapshot ───────────────────────────────────────────

private fun readSelection(sel: Int): ProjectSelection {
    val mod = wasmMod
    val objects = List(mod.uapmdSelectionObjectCount(sel)) { str(mod.uapmdSelectionObjectAt(sel, it)) ?: "" }
    val regions = List(mod.uapmdSelectionRegionCount(sel)) { r ->
        when (mod.uapmdSelectionRegionKind(sel, r)) {
            0 -> TimelineSelectionRegion(
                mod.uapmdSelectionRegionStartSeconds(sel, r),
                mod.uapmdSelectionRegionEndSeconds(sel, r),
                if (mod.uapmdSelectionRegionTracksRestricted(sel, r))
                    List(mod.uapmdSelectionRegionTrackCount(sel, r)) {
                        str(mod.uapmdSelectionRegionTrackAt(sel, r, it)) ?: ""
                    }
                else null
            )
            1 -> {
                val pMin = mod.uapmdSelectionRegionPitchMin(sel, r)
                val pMax = mod.uapmdSelectionRegionPitchMax(sel, r)
                MidiNoteSelectionRegion(
                    str(mod.uapmdSelectionRegionClipId(sel, r)) ?: "",
                    wasmSelRegionStartTick(mod, sel, r).toLong(),
                    wasmSelRegionEndTick(mod, sel, r).toLong(),
                    if (pMin >= 0 && pMax >= 0) pMin..pMax else null,
                    mod.uapmdSelectionRegionNoteGroup(sel, r).takeIf { it >= 0 },
                    mod.uapmdSelectionRegionNoteChannel(sel, r).takeIf { it >= 0 }
                )
            }
            else -> MidiAutomationSelectionRegion(
                MidiAutomationLane(
                    str(mod.uapmdSelectionRegionClipId(sel, r)) ?: "",
                    str(mod.uapmdSelectionRegionLaneNoteId(sel, r)),
                    mod.uapmdSelectionRegionLaneGroup(sel, r),
                    mod.uapmdSelectionRegionLaneChannel(sel, r),
                    mod.uapmdSelectionRegionLaneStatus(sel, r),
                    mod.uapmdSelectionRegionLaneParameter(sel, r)
                ),
                wasmSelRegionStartTick(mod, sel, r).toLong(),
                wasmSelRegionEndTick(mod, sel, r).toLong(),
                if (mod.uapmdSelectionRegionHasValues(sel, r))
                    mod.uapmdSelectionRegionValueMin(sel, r)..mod.uapmdSelectionRegionValueMax(sel, r)
                else null
            )
        }
    }
    return ProjectSelection(objects, regions)
}

/** The caller destroys the returned handle. */
private fun buildSelection(selection: ProjectSelection): Int {
    val mod = wasmMod
    val sel = mod.uapmdSelectionCreate()
    selection.objects.forEach { id -> withCStringKt(id) { p -> mod.uapmdSelectionAddObject(sel, p) } }
    for (region in selection.regions) when (region) {
        is TimelineSelectionRegion -> {
            val r = mod.uapmdSelectionAddTimelineRegion(sel, region.startSeconds, region.endSeconds)
            region.trackIds?.let { ids ->
                mod.uapmdSelectionRegionRestrictTracks(sel, r)
                ids.forEach { id -> withCStringKt(id) { p -> mod.uapmdSelectionRegionAddTrack(sel, r, p) } }
            }
        }
        is MidiNoteSelectionRegion -> withCStringKt(region.clipId) { c ->
            wasmSelAddMidiNoteRegion(
                mod, sel, c, region.startTick.toString(), region.endTick.toString(),
                region.pitches?.first ?: -1, region.pitches?.last ?: -1,
                region.group ?: -1, region.channel ?: -1
            )
        }
        is MidiAutomationSelectionRegion -> withCStringKt(region.lane.clipId) { c ->
            withCStringKt(region.lane.noteId) { n ->
                wasmSelAddMidiAutomationRegion(
                    mod, sel, c, n,
                    region.lane.group, region.lane.channel, region.lane.status, region.lane.parameter,
                    region.startTick.toString(), region.endTick.toString(),
                    region.values != null, region.values?.start ?: 0.0, region.values?.endInclusive ?: 0.0
                )
            }
        }
    }
    return sel
}
