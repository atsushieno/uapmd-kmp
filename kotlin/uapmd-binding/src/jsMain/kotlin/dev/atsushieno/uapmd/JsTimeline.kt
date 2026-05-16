package dev.atsushieno.uapmd

// ─── JsTimelineTrack ─────────────────────────────────────────────────────────

class JsTimelineTrack internal constructor(
    internal val handle: Int
) : TimelineTrack {
    override fun getClips(): List<ClipData> = emptyList()
}

// ─── JsAudioFileReader ────────────────────────────────────────────────────────

class JsAudioFileReader internal constructor(
    internal val handle: Int
) : AudioFileReader {

    override fun getProperties(): AudioFileProperties? =
        withWasmMem(16) { ptr ->
            if (!(jsMod._uapmd_audio_file_reader_get_properties(handle, ptr) as Boolean)) null
            else AudioFileProperties(
                numFrames   = jsGetI64(ptr),
                numChannels = jsGetU32(ptr + 8).toUInt(),
                sampleRate  = jsGetU32(ptr + 12).toUInt()
            )
        }

    override fun readFrames(startFrame: Long, framesToRead: Long, dest: Array<FloatArray>) {
        val channelCount = dest.size
        val ptrsPtr = jsMod._malloc(channelCount * 4) as Int
        val channelPtrs = IntArray(channelCount)
        val frameSizeBytes = framesToRead.toInt() * 4
        try {
            for (i in 0 until channelCount) {
                val chPtr = jsMod._malloc(frameSizeBytes) as Int
                channelPtrs[i] = chPtr
                jsSetI32(ptrsPtr + i * 4, chPtr)
            }
            jsMod._uapmd_audio_file_reader_read_frames(handle, startFrame.toDouble(), framesToRead.toDouble(), ptrsPtr, channelCount)
            for (ch in 0 until channelCount) {
                val chPtr = channelPtrs[ch]
                for (f in 0 until framesToRead.toInt()) {
                    dest[ch][f] = (jsMod.getValue(chPtr + f * 4, "float") as Double).toFloat()
                }
            }
        } finally {
            channelPtrs.forEach { jsMod._free(it) }
            jsMod._free(ptrsPtr)
        }
    }

    override fun close() = jsMod._uapmd_audio_file_reader_destroy(handle)
}

// ─── JsTimelineFacade ─────────────────────────────────────────────────────────

class JsTimelineFacade internal constructor(
    internal val handle: Int
) : TimelineFacade {

    override fun getState(): TimelineState? =
        withWasmMem(80) { ptr ->
            if (!(jsMod._uapmd_tl_get_state(handle, ptr) as Boolean)) null
            else jsDecodeTimelineState(ptr)
        }

    override fun setTempo(tempo: Double)                        = jsMod._uapmd_tl_set_tempo(handle, tempo)
    override fun setTimeSignature(numerator: Int, denominator: Int) = jsMod._uapmd_tl_set_time_signature(handle, numerator, denominator)

    override fun setLoop(enabled: Boolean, start: TimelinePosition, end: TimelinePosition) =
        jsMod._uapmd_tl_set_loop(handle, enabled, start.samples.toDouble(), start.legacyBeats, end.samples.toDouble(), end.legacyBeats)

    override val trackCount: UInt   get() = (jsMod._uapmd_tl_track_count(handle) as Int).toUInt()
    override fun getTrack(index: UInt): TimelineTrack = JsTimelineTrack(jsMod._uapmd_tl_get_track(handle, index.toInt()) as Int)
    override val masterTimelineTrack: TimelineTrack   get() = JsTimelineTrack(jsMod._uapmd_tl_master_timeline_track(handle) as Int)

    override fun addAudioClip(
        trackIndex: Int, position: TimelinePosition,
        reader: AudioFileReader, filepath: String
    ): ClipAddResult =
        withWasmMem(16) { outPtr ->
            withJsCString(filepath) { fpPtr ->
                jsMod._uapmd_tl_add_audio_clip(
                    handle, trackIndex,
                    position.samples.toDouble(), position.legacyBeats,
                    (reader as JsAudioFileReader).handle, fpPtr, outPtr
                )
            }
            jsDecodeClipAddResult(outPtr)
        }

    override fun addMidiClipFromFile(
        trackIndex: Int, position: TimelinePosition,
        filepath: String, nrpnToParameterMapping: Boolean
    ): ClipAddResult =
        withWasmMem(16) { outPtr ->
            withJsCString(filepath) { fpPtr ->
                jsMod._uapmd_tl_add_midi_clip_from_file(
                    handle, trackIndex,
                    position.samples.toDouble(), position.legacyBeats,
                    fpPtr, nrpnToParameterMapping, outPtr
                )
            }
            jsDecodeClipAddResult(outPtr)
        }

    override fun removeClip(trackIndex: Int, clipId: Int): Boolean =
        jsMod._uapmd_tl_remove_clip(handle, trackIndex, clipId) as Boolean

    override fun loadProject(filePath: String): ProjectResult {
        val errBufSize = 512
        val errBuf  = jsMod._malloc(errBufSize) as Int
        val sucPtr  = jsMod._malloc(4) as Int
        return try {
            withJsCString(filePath) { fpPtr ->
                jsMod._uapmd_tl_load_project(handle, fpPtr, sucPtr, errBuf, errBufSize)
            }
            val ok = jsGetBool(sucPtr)
            ProjectResult(ok, if (!ok) jsMod.UTF8ToString(errBuf) as String else null)
        } finally { jsMod._free(errBuf); jsMod._free(sucPtr) }
    }

    override fun calculateContentBounds(): ContentBounds =
        withWasmMem(48) { ptr ->
            jsMod._uapmd_tl_calculate_content_bounds(handle, ptr)
            ContentBounds(
                hasContent   = jsGetBool(ptr),
                firstSample  = jsGetI64(ptr + 8),
                lastSample   = jsGetI64(ptr + 16),
                firstSeconds = jsGetF64(ptr + 24),
                lastSeconds  = jsGetF64(ptr + 32)
            )
        }

    override fun getMidiClipNotes(trackIndex: Int, clipId: Int): List<MidiNoteData>? = null
    override fun setTimelineChangedCallback(callback: (() -> Unit)?) {}
}
