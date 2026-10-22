package dev.atsushieno.uapmd

/**
 * The project selection service (`uapmd-data` `ProjectSelection.hpp`).
 *
 * A selection is a list of selected objects (ordered by relevance; the first is primary) plus a
 * list of regions. Each editor works in its own selection *context*; the document also has a
 * [ProjectSelectionService.defaultContext], and at most one context at a time is the active one,
 * whose selection the rest of the application acts on.
 *
 * A model-thread service: call it from the thread that owns the project.
 */

/** Identifies one selection context. Positive for a real context. */
typealias SelectionContextId = Long

/** Object ids are the document's stable `ProjectObjectId` strings (tracks, clips, notes, ...). */
typealias ProjectObjectId = String

sealed interface SelectionRegion

/**
 * `TimelineSelectionRegion`: a half-open range of seconds across tracks. [trackIds] `null`
 * means all tracks; an empty list explicitly means none.
 */
data class TimelineSelectionRegion(
    val startSeconds: Double,
    val endSeconds: Double,
    val trackIds: List<ProjectObjectId>? = null
) : SelectionRegion

/**
 * `MidiNoteSelectionRegion`: a half-open tick range of a clip's content. [pitches] includes both
 * ends. A `null` constraint is absent.
 */
data class MidiNoteSelectionRegion(
    val clipId: ProjectObjectId,
    val startTick: Long,
    val endTick: Long,
    val pitches: IntRange? = null,
    val group: Int? = null,
    val channel: Int? = null
) : SelectionRegion

/** `MidiAutomationLaneAddress`. [status] is the MIDI channel status (default `0xB0`). */
data class MidiAutomationLane(
    val clipId: ProjectObjectId,
    val noteId: ProjectObjectId? = null,
    val group: Int = 0,
    val channel: Int = 0,
    val status: Int = 0xB0,
    val parameter: Int = 0
)

/**
 * `MidiAutomationSelectionRegion`: a half-open tick range on one automation lane. [values] is an
 * inclusive normalized range, or `null` for the whole range.
 */
data class MidiAutomationSelectionRegion(
    val lane: MidiAutomationLane,
    val startTick: Long,
    val endTick: Long,
    val values: ClosedFloatingPointRange<Double>? = null
) : SelectionRegion

/** `ProjectSelection`. The first object is primary. */
data class ProjectSelection(
    val objects: List<ProjectObjectId> = emptyList(),
    val regions: List<SelectionRegion> = emptyList()
) {
    val isEmpty: Boolean get() = objects.isEmpty() && regions.isEmpty()
}

/** `ProjectSelectionProjection`: a selection as consumers thinking in clips, tracks and time see it. */
data class ProjectSelectionProjection(
    val clipIds: List<ProjectObjectId>,
    val trackIds: List<ProjectObjectId>,
    val timeRange: ClosedFloatingPointRange<Double>?
)

/** `SelectionUpdateResult`. */
data class SelectionUpdateResult(val success: Boolean, val error: String = "")

/**
 * `ProjectSelectionListener`. Notifications arrive synchronously on the model thread, except
 * while a document batch is open or from inside another notification, when they are delivered
 * afterwards.
 */
interface ProjectSelectionListener {
    fun selectionChanged(context: SelectionContextId, previous: ProjectSelection, current: ProjectSelection) {}
    fun activeContextChanged(previous: SelectionContextId?, current: SelectionContextId?) {}
    fun selectionContextRemoved(context: SelectionContextId) {}
}

interface ProjectSelectionService {
    val defaultContext: SelectionContextId

    /** [ownerObjectId] is the clip or track the context belongs to; it is dropped with its owner. */
    fun createContext(ownerObjectId: ProjectObjectId? = null): SelectionContextId
    fun hasContext(context: SelectionContextId): Boolean
    fun removeContext(context: SelectionContextId)

    /** `null` for an unknown context. */
    fun snapshot(context: SelectionContextId): ProjectSelection?
    /** The selection of the active context. */
    fun activeSelection(): ProjectSelection

    /** Replaces a context's selection. Fails for an unknown context, an object not in the document, or an invalid region. */
    fun replace(context: SelectionContextId, selection: ProjectSelection): SelectionUpdateResult
    fun clear(context: SelectionContextId)

    /** `null` when no context is active. */
    fun activeContext(): SelectionContextId?
    /** `null` deactivates. False for an unknown context. */
    fun setActiveContext(context: SelectionContextId?): Boolean

    /** Returns a token for [removeListener]. */
    fun addListener(listener: ProjectSelectionListener): Long
    fun removeListener(token: Long)

    /** `projectSelection()`: what [selection] means for clips, tracks and a time range. */
    fun project(selection: ProjectSelection): ProjectSelectionProjection
}
