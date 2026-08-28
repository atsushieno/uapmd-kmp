package dev.atsushieno.uapmd

/**
 * A project file made loadable. `.uapmdz` archives are unpacked to a temporary
 * directory; a plain `.uapmd` resolves to itself. Close it after loading to
 * remove any temporary files.
 */
interface PreparedProject : AutoCloseable {
    val success: Boolean
    /** The path to hand to [TimelineFacade.loadProject]. */
    val path: String
    val error: String
}

interface AudioFileReader : AutoCloseable {
    fun getProperties(): AudioFileProperties?
    /**
     * Read [framesToRead] interleaved frames starting at [startFrame] into [dest].
     * [dest] has one FloatArray per channel (planar layout matching the C API).
     */
    fun readFrames(startFrame: Long, framesToRead: Long, dest: Array<FloatArray>)
}

interface TimelineTrack {
    fun getClips(): List<ClipData>
}

interface TimelineFacade {
    fun getState(): TimelineState?
    fun setTempo(tempo: Double)
    fun setTimeSignature(numerator: Int, denominator: Int)
    fun setLoop(enabled: Boolean, start: TimelinePosition, end: TimelinePosition)

    val trackCount: UInt
    fun getTrack(index: UInt): TimelineTrack
    val masterTimelineTrack: TimelineTrack

    fun addAudioClip(
        trackIndex: Int,
        position: TimelinePosition,
        reader: AudioFileReader,
        filepath: String
    ): ClipAddResult

    fun addMidiClipFromFile(
        trackIndex: Int,
        position: TimelinePosition,
        filepath: String,
        nrpnToParameterMapping: Boolean = false
    ): ClipAddResult

    fun removeClip(trackIndex: Int, clipId: Int): Boolean
    fun loadProject(filePath: String): ProjectResult
    fun calculateContentBounds(): ContentBounds

    /** Returns MIDI notes for the given clip, or null if the track/clip is not found
     *  or is not a MIDI clip. Empty list means a valid MIDI clip with no notes. */
    fun getMidiClipNotes(trackIndex: Int, clipId: Int): List<MidiNoteData>?

    /** Registers a callback invoked after every clip add/remove and after loadProject().
     *  Pass null to unregister. */
    fun setTimelineChangedCallback(callback: (() -> Unit)?)

    // ─── Project history (uapmd 0.5.6) ──────────────────────────────────────

    val undoEngine: UndoEngine
    /** The undoable edits this project supports. */
    val commands: ProjectCommands
    /** Translation between persistent document identities and runtime indexes. */
    val addresses: ProjectAddressBook

    /**
     * Groups the document events produced by everything inside [block] into a
     * single batch. Use whenever one user-visible action performs several
     * mutations, or observers can see it half-applied. Calls nest.
     */
    fun <T> documentTransaction(block: () -> T): T

    fun removeClip(trackIndex: Int, clipId: Int, origin: MutationOrigin): Boolean
    /** Removes every clip on the track. True when at least one was removed. */
    fun clearClipsFromTrack(trackIndex: Int, origin: MutationOrigin = MutationOrigin.User): Boolean
    fun isClipEnabled(trackIndex: Int, clipId: Int): Boolean

    /** Replaces a MIDI clip's authored content, resizing the clip to match. */
    fun replaceMidiClipContent(
        trackIndex: Int,
        clipId: Int,
        umpEvents: UIntArray,
        tickTimestamps: LongArray,
        origin: MutationOrigin = MutationOrigin.User
    ): Boolean

    /**
     * Rebuilds an audio clip's source from its file with the given markers and
     * warp points. A non-empty [filepath] switches the clip to that file and
     * adopts its length; otherwise the clip keeps its length.
     */
    fun replaceAudioClipContent(
        trackIndex: Int,
        clipId: Int,
        filepath: String,
        markers: List<ClipMarkerData>,
        warps: List<AudioWarpPointData>,
        masterMarkers: List<ClipMarkerData>,
        origin: MutationOrigin = MutationOrigin.User
    ): Boolean

    /**
     * Capture is non-destructive; deleting is a separate [removeClip]. Must NOT
     * be called inside a [documentTransaction]. The returned fragment is owned
     * by the caller.
     */
    fun captureClipFragment(trackIndex: Int, clipId: Int): ClipFragment?
    fun attachClipFragment(trackIndex: Int, fragment: ClipFragment, idPolicy: ObjectIdPolicy): ClipAddResult

    /**
     * Both halves are asynchronous because a track owns live plug-in instances.
     * The callback runs exactly once, on the thread completing the last plug-in
     * operation. Capture must NOT be called inside a [documentTransaction].
     */
    fun captureTrackFragment(trackIndex: Int, callback: (TrackFragment?, String?) -> Unit)
    fun attachTrackFragment(
        fragment: TrackFragment,
        options: TrackAttachOptions,
        callback: (trackIndex: Int, error: String?) -> Unit
    )

    fun addEmptyTrack(origin: MutationOrigin = MutationOrigin.User, callback: (trackIndex: Int, error: String?) -> Unit)
    fun removeTrack(trackIndex: Int, origin: MutationOrigin = MutationOrigin.User, callback: (trackIndex: Int, error: String?) -> Unit)
    /** Records an already-published, fully constructed track as one addition. */
    fun recordTrackAddition(trackIndex: Int, origin: MutationOrigin = MutationOrigin.User, callback: (trackIndex: Int, error: String?) -> Unit)

    fun setPluginState(instanceId: Int, state: ByteArray, origin: MutationOrigin = MutationOrigin.User, completion: ((UndoResult) -> Unit)? = null)
    fun loadPluginPreset(instanceId: Int, presetIndex: Int, origin: MutationOrigin = MutationOrigin.User, completion: ((UndoResult) -> Unit)? = null)
    /** Records a plug-in that has already been instantiated. */
    fun recordPluginInstanceAddition(instanceId: Int, origin: MutationOrigin = MutationOrigin.User, completion: ((UndoResult) -> Unit)? = null)
    /** Captures state, removes the instance, and records the removal as one step. */
    fun removePluginInstance(instanceId: Int, origin: MutationOrigin = MutationOrigin.User, completion: ((UndoResult) -> Unit)? = null)
    val hasPendingPluginMutations: Boolean
}
