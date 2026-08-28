package dev.atsushieno.uapmd

// ─── AndroidAudioFileReader ──────────────────────────────────────────────────

class AndroidAudioFileReader internal constructor(
    internal val handle: Long
) : AudioFileReader {

    override fun getProperties(): AudioFileProperties? {
        val arr = JniBridge.uapmdAudioFileReaderGetProperties(handle) ?: return null
        return AudioFileProperties(
            numFrames = arr[0],
            numChannels = arr[1].toUInt(),
            sampleRate = arr[2].toUInt()
        )
    }

    override fun readFrames(startFrame: Long, framesToRead: Long, dest: Array<FloatArray>) =
        JniBridge.uapmdAudioFileReaderReadFrames(handle, startFrame, framesToRead, dest)

    override fun close() = JniBridge.uapmdAudioFileReaderDestroy(handle)
}

// ─── AndroidTimelineTrack ────────────────────────────────────────────────────

class AndroidTimelineTrack internal constructor(
    private val handle: Long
) : TimelineTrack {
    override val channelCount: Int get() = JniBridge.uapmdTtChannelCount(handle)

    override fun getClips(): List<ClipData> {
        val count = JniBridge.uapmdTtClipCount(handle)
        if (count == 0) return emptyList()
        // 4 strings and 9 numerics per clip; see uapmdTtGetAllClips in uapmd_jni.cpp.
        val outStrings = arrayOfNulls<String>(count * 4)
        val numerics = JniBridge.uapmdTtGetAllClips(handle, outStrings) ?: return emptyList()
        return (0 until count).map { i ->
            val base = i * 9
            ClipData(
                clipId               = numerics[base + 0].toInt(),
                positionSamples      = numerics[base + 1].toLong(),
                positionLegacyBeats  = numerics[base + 2],
                durationSamples      = numerics[base + 3].toLong(),
                gain                 = numerics[base + 4],
                muted                = numerics[base + 5] != 0.0,
                name                 = outStrings[i * 4] ?: "",
                filepath             = outStrings[i * 4 + 1] ?: "",
                clipType             = ClipType.fromNative(numerics[base + 6].toInt()),
                referenceId          = outStrings[i * 4 + 2] ?: "",
                anchorReferenceId    = outStrings[i * 4 + 3] ?: "",
                anchorOrigin         = AnchorOrigin.fromNative(numerics[base + 7].toInt()),
                anchorOffsetSamples  = numerics[base + 8].toLong()
            )
        }
    }
}

// ─── AndroidTimelineFacade ───────────────────────────────────────────────────

class AndroidTimelineFacade internal constructor(
    private val handle: Long
) : TimelineFacade {

    private val history = AndroidTimelineHistory(handle)

    override fun getState(): TimelineState? {
        val d = JniBridge.uapmdTlGetState(handle) ?: return null
        return TimelineState(
            playheadPosition = TimelinePosition(d[0].toLong(), d[1]),
            isPlaying = d[2] != 0.0,
            loopEnabled = d[3] != 0.0,
            loopStart = TimelinePosition(d[4].toLong(), d[5]),
            loopEnd = TimelinePosition(d[6].toLong(), d[7]),
            tempo = d[8],
            timeSignatureNumerator = d[9].toInt(),
            timeSignatureDenominator = d[10].toInt(),
            sampleRate = d[11].toInt()
        )
    }

    override fun setTempo(tempo: Double) = JniBridge.uapmdTlSetTempo(handle, tempo)

    override fun setTimeSignature(numerator: Int, denominator: Int) =
        JniBridge.uapmdTlSetTimeSignature(handle, numerator, denominator)

    override fun setLoop(enabled: Boolean, start: TimelinePosition, end: TimelinePosition) =
        JniBridge.uapmdTlSetLoop(handle, enabled, start.samples, start.legacyBeats, end.samples, end.legacyBeats)

    override val trackCount: UInt get() = JniBridge.uapmdTlTrackCount(handle).toUInt()

    override fun getTrack(index: UInt): TimelineTrack =
        AndroidTimelineTrack(JniBridge.uapmdTlGetTrack(handle, index.toInt()))

    override val masterTimelineTrack: TimelineTrack
        get() = AndroidTimelineTrack(JniBridge.uapmdTlMasterTimelineTrack(handle))

    override fun addAudioClip(
        trackIndex: Int,
        position: TimelinePosition,
        reader: AudioFileReader,
        filepath: String
    ): ClipAddResult {
        val arr = JniBridge.uapmdTlAddAudioClip(
            handle, trackIndex,
            position.samples, position.legacyBeats,
            (reader as AndroidAudioFileReader).handle,
            filepath
        )
        return ClipAddResult(arr[0], arr[1], arr[2] != 0, null)
    }

    override fun addMidiClipFromFile(
        trackIndex: Int,
        position: TimelinePosition,
        filepath: String,
        nrpnToParameterMapping: Boolean
    ): ClipAddResult {
        val arr = JniBridge.uapmdTlAddMidiClipFromFile(
            handle, trackIndex,
            position.samples, position.legacyBeats,
            filepath, nrpnToParameterMapping
        )
        return ClipAddResult(arr[0], arr[1], arr[2] != 0, null)
    }

    override fun removeClip(trackIndex: Int, clipId: Int): Boolean =
        JniBridge.uapmdTlRemoveClip(handle, trackIndex, clipId)

    override fun loadProject(filePath: String): ProjectResult {
        val arr = JniBridge.uapmdTlLoadProject(handle, filePath)
        return ProjectResult(arr[0] == "1", arr[1])
    }

    override fun calculateContentBounds(): ContentBounds {
        val d = JniBridge.uapmdTlCalculateContentBounds(handle)
        return ContentBounds(
            hasContent = d[0] != 0.0,
            firstSample = d[1].toLong(),
            lastSample = d[2].toLong(),
            firstSeconds = d[3],
            lastSeconds = d[4]
        )
    }

    override fun getMidiClipNotes(trackIndex: Int, clipId: Int): List<MidiNoteData>? {
        val flat = JniBridge.uapmdTlGetClipMidiNotes(handle, trackIndex, clipId) ?: return null
        val count = flat.size / 4
        return (0 until count).map { i ->
            MidiNoteData(flat[i*4], flat[i*4+1], flat[i*4+3].toInt(), flat[i*4+2].toFloat())
        }
    }

    override fun setTimelineChangedCallback(callback: (() -> Unit)?) {
        JniBridge.uapmdTlSetTimelineChangedCallback(handle, callback?.let { Runnable { it() } })
    }

    // ─── Project history (uapmd 0.5.6) ──────────────────────────────────────

    override val undoEngine get() = history.undoEngine
    override val commands get() = history.commands
    override val addresses get() = history.addresses

    override fun <T> documentTransaction(block: () -> T): T = history.documentTransaction(block)

    override fun removeClip(trackIndex: Int, clipId: Int, origin: MutationOrigin) =
        history.removeClip(trackIndex, clipId, origin)

    override fun clearClipsFromTrack(trackIndex: Int, origin: MutationOrigin) =
        history.clearClipsFromTrack(trackIndex, origin)

    override fun isClipEnabled(trackIndex: Int, clipId: Int) = history.isClipEnabled(trackIndex, clipId)

    override fun replaceMidiClipContent(
        trackIndex: Int, clipId: Int, umpEvents: UIntArray, tickTimestamps: LongArray, origin: MutationOrigin
    ) = history.replaceMidiClipContent(trackIndex, clipId, umpEvents, tickTimestamps, origin)

    override fun replaceAudioClipContent(
        trackIndex: Int, clipId: Int, filepath: String,
        markers: List<ClipMarkerData>, warps: List<AudioWarpPointData>,
        masterMarkers: List<ClipMarkerData>, origin: MutationOrigin
    ) = history.replaceAudioClipContent(trackIndex, clipId, filepath, markers, warps, masterMarkers, origin)

    override fun captureClipFragment(trackIndex: Int, clipId: Int) = history.captureClipFragment(trackIndex, clipId)

    override fun attachClipFragment(trackIndex: Int, fragment: ClipFragment, idPolicy: ObjectIdPolicy) =
        history.attachClipFragment(trackIndex, fragment, idPolicy)

    override fun captureTrackFragment(trackIndex: Int, callback: (TrackFragment?, String?) -> Unit) =
        history.captureTrackFragment(trackIndex, callback)

    override fun attachTrackFragment(
        fragment: TrackFragment, options: TrackAttachOptions, callback: (Int, String?) -> Unit
    ) = history.attachTrackFragment(fragment, options, callback)

    override fun addEmptyTrack(origin: MutationOrigin, callback: (Int, String?) -> Unit) =
        history.addEmptyTrack(origin, callback)

    override fun removeTrack(trackIndex: Int, origin: MutationOrigin, callback: (Int, String?) -> Unit) =
        history.removeTrack(trackIndex, origin, callback)

    override fun recordTrackAddition(trackIndex: Int, origin: MutationOrigin, callback: (Int, String?) -> Unit) =
        history.recordTrackAddition(trackIndex, origin, callback)

    override fun setPluginState(instanceId: Int, state: ByteArray, origin: MutationOrigin, completion: ((UndoResult) -> Unit)?) =
        history.setPluginState(instanceId, state, origin, completion)

    override fun loadPluginPreset(instanceId: Int, presetIndex: Int, origin: MutationOrigin, completion: ((UndoResult) -> Unit)?) =
        history.loadPluginPreset(instanceId, presetIndex, origin, completion)

    override fun recordPluginInstanceAddition(instanceId: Int, origin: MutationOrigin, completion: ((UndoResult) -> Unit)?) =
        history.recordPluginInstanceAddition(instanceId, origin, completion)

    override fun removePluginInstance(instanceId: Int, origin: MutationOrigin, completion: ((UndoResult) -> Unit)?) =
        history.removePluginInstance(instanceId, origin, completion)

    override val hasPendingPluginMutations get() = history.hasPendingPluginMutations
}
