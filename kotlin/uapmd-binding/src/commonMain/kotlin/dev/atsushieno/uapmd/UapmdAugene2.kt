package dev.atsushieno.uapmd

/** `uapmd_augene2::IntegrationSource`: an MML resource of the project. */
data class Augene2IntegrationSource(
    /** Project-relative resource path. */
    val path: String,
    /** Linked file; empty for a bundled copy. */
    val externalPath: String,
    /** Compiled on its own, rather than only `#include`d. */
    val compile: Boolean
)

/** `uapmd_augene2::IntegrationTrackMapping`: where one MML track's clips live. */
data class Augene2TrackMapping(
    val key: String,
    /**
     * A timeline track index, [ProjectAddressBook.MASTER_TRACK_INDEX], or -1
     * when the track is no longer available.
     */
    val trackIndex: Int
)

/**
 * `uapmd_augene2::Integration`: the Augene2 project service and everything its
 * panel presents.
 *
 * Every member runs on the model thread. Requests complete asynchronously:
 * picking, compilation and applying the result to the timeline advance in
 * [PanelRegistry.update]. Imports pick files through
 * [AppModel.documentProvider], which the host must keep ticking.
 *
 * This object does not keep the service alive; once the application releases
 * it ([PanelRegistry.clearRetainedPanels]) every member reads empty and every
 * request is ignored. [close] releases only this reference.
 */
interface Augene2Integration : AutoCloseable {
    /** Panel visibility. The addin command opens it; disabling the addin closes it. */
    var isOpen: Boolean
    /** A document pick, an apply, or another history operation is in progress; edits are ignored. */
    val busy: Boolean
    /** Sources are being checked or compiled. */
    val compiling: Boolean
    val sources: List<Augene2IntegrationSource>
    val trackMappings: List<Augene2TrackMapping>
    val status: String
    val diagnostics: List<String>
    /** Optional project-relative folder imported resources are placed under. */
    var resourceFolder: String

    /** Picks MML files. Inputs ([compile] = true) are compiled; the others are only available to `#include`. */
    fun importSources(compile: Boolean)
    /** Picks a replacement file for the resource at [path]. */
    fun relinkSource(path: String)
    fun removeSource(path: String)
    /** Refreshes linked files, compiles, and applies the result as one undo step. */
    fun compile()
}

/** The free functions of `uapmd-augene2/uapmd-augene2.hpp`. */
object Augene2 {
    /** False when this build does not include uapmd-augene2. */
    val isAvailable: Boolean get() = augene2Available()

    /**
     * `uapmd_augene2::registerProjectService()`: project persistence that works
     * even while the addin's UI is disabled. Register it once, before
     * [AddinManager.initialize], and release it with
     * [PanelRegistry.clearRetainedPanels] before the timeline goes away.
     */
    fun registerProjectService(timeline: TimelineFacade, panels: PanelRegistry) =
        augene2RegisterProjectService(timeline, panels)

    /** `uapmd_augene2::integration()`, or null when there is none. */
    fun integration(): Augene2Integration? = augene2Integration()
}

internal expect fun augene2Available(): Boolean
internal expect fun augene2RegisterProjectService(timeline: TimelineFacade, panels: PanelRegistry)
internal expect fun augene2Integration(): Augene2Integration?
