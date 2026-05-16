package dev.atsushieno.uapmd

// ─── WasmJsTimelineTrack ─────────────────────────────────────────────────────

class WasmJsTimelineTrack internal constructor(
    internal val handle: Int
) : TimelineTrack {
    override fun getClips(): List<ClipData> = emptyList()
}

// ─── WasmJsAudioFileReader ────────────────────────────────────────────────────

class WasmJsAudioFileReader internal constructor(
    internal val handle: Int
) : AudioFileReader {

    override fun getProperties(): AudioFileProperties? {
        val mod = wasmMod
        val ptr = mod.malloc(16) // sizeof uapmd_audio_file_properties_t
        return try {
            if (!mod.uapmdAudioFileReaderGetProperties(handle, ptr)) null
            else {
                // Layout: uint64_t num_frames (+0), uint32_t num_channels (+8), uint32_t sample_rate (+12)
                val numFramesLo = mod.getValue(ptr,     "i32").toInt().toLong() and 0xFFFFFFFFL
                val numFramesHi = mod.getValue(ptr + 4, "i32").toInt().toLong()
                val numFrames   = numFramesHi * 4294967296L + numFramesLo
                AudioFileProperties(
                    numFrames   = numFrames,
                    numChannels = mod.getValue(ptr + 8,  "i32").toInt().toUInt(),
                    sampleRate  = mod.getValue(ptr + 12, "i32").toInt().toUInt()
                )
            }
        } finally { mod.free(ptr) }
    }

    override fun readFrames(startFrame: Long, framesToRead: Long, dest: Array<FloatArray>) {
        val mod = wasmMod
        val channelCount = dest.size
        // Allocate an array of pointers (one per channel) + the channel buffers
        val ptrsPtr = mod.malloc(channelCount * 4)
        val channelPtrs = IntArray(channelCount)
        val frameSizeBytes = framesToRead.toInt() * 4 // float = 4 bytes
        try {
            for (i in 0 until channelCount) {
                val chPtr = mod.malloc(frameSizeBytes)
                channelPtrs[i] = chPtr
                mod.setValue(ptrsPtr + i * 4, chPtr.toDouble(), "i32")
            }
            mod.uapmdAudioFileReaderReadFrames(handle, startFrame.toDouble(), framesToRead.toDouble(), ptrsPtr, channelCount)
            // Copy back from Wasm memory to Kotlin arrays
            for (ch in 0 until channelCount) {
                val chPtr = channelPtrs[ch]
                for (f in 0 until framesToRead.toInt()) {
                    dest[ch][f] = mod.getValue(chPtr + f * 4, "float").toFloat()
                }
            }
        } finally {
            channelPtrs.forEach { mod.free(it) }
            mod.free(ptrsPtr)
        }
    }

    override fun close() = wasmMod.uapmdAudioFileReaderDestroy(handle)
}

// ─── WasmJsTimelineFacade ─────────────────────────────────────────────────────

class WasmJsTimelineFacade internal constructor(
    internal val handle: Int
) : TimelineFacade {

    override fun getState(): TimelineState? {
        val mod = wasmMod
        val ptr = mod.malloc(80) // sizeof uapmd_timeline_state_t
        return try {
            if (!mod.uapmdTlGetState(handle, ptr)) null
            else decodeTimelineState(mod, ptr)
        } finally { mod.free(ptr) }
    }

    private fun decodeTimelineState(mod: UapmdCApiModule, ptr: Int): TimelineState {
        fun getI32(o: Int) = mod.getValue(ptr + o, "i32").toInt()
        fun getF64(o: Int) = mod.getValue(ptr + o, "double")
        fun getBool(o: Int) = mod.getValue(ptr + o, "i8").toInt() != 0
        fun getI64(o: Int): Long {
            val lo = mod.getValue(ptr + o, "i32").toInt().toLong() and 0xFFFFFFFFL
            val hi = mod.getValue(ptr + o + 4, "i32").toInt().toLong()
            return hi * 4294967296L + lo
        }
        fun readPos(o: Int) = TimelinePosition(samples = getI64(o), legacyBeats = getF64(o + 8))

        return TimelineState(
            playheadPosition           = readPos(0),
            isPlaying                  = getBool(16),
            loopEnabled                = getBool(17),
            loopStart                  = readPos(24),
            loopEnd                    = readPos(40),
            tempo                      = getF64(56),
            timeSignatureNumerator     = getI32(64),
            timeSignatureDenominator   = getI32(68),
            sampleRate                 = getI32(72)
        )
    }

    override fun setTempo(tempo: Double) = wasmMod.uapmdTlSetTempo(handle, tempo)

    override fun setTimeSignature(numerator: Int, denominator: Int) =
        wasmMod.uapmdTlSetTimeSignature(handle, numerator, denominator)

    override fun setLoop(enabled: Boolean, start: TimelinePosition, end: TimelinePosition) =
        wasmMod.uapmdTlSetLoop(
            handle, enabled,
            start.samples.toDouble(), start.legacyBeats,
            end.samples.toDouble(), end.legacyBeats
        )

    override val trackCount: UInt
        get() = wasmMod.uapmdTlTrackCount(handle).toUInt()

    override fun getTrack(index: UInt): TimelineTrack =
        WasmJsTimelineTrack(wasmMod.uapmdTlGetTrack(handle, index.toInt()))

    override val masterTimelineTrack: TimelineTrack
        get() = WasmJsTimelineTrack(wasmMod.uapmdTlMasterTimelineTrack(handle))

    override fun addAudioClip(
        trackIndex: Int,
        position: TimelinePosition,
        reader: AudioFileReader,
        filepath: String
    ): ClipAddResult {
        val mod = wasmMod
        val outPtr = mod.malloc(16) // sizeof uapmd_clip_add_result_t
        return try {
            withCStringKt(filepath) { fpPtr ->
                val readerHandle = (reader as WasmJsAudioFileReader).handle
                mod.uapmdTlAddAudioClip(
                    handle, trackIndex,
                    position.samples.toDouble(), position.legacyBeats,
                    readerHandle, fpPtr, outPtr
                )
            }
            decodeClipAddResult(mod, outPtr)
        } finally { mod.free(outPtr) }
    }

    override fun addMidiClipFromFile(
        trackIndex: Int,
        position: TimelinePosition,
        filepath: String,
        nrpnToParameterMapping: Boolean
    ): ClipAddResult {
        val mod = wasmMod
        val outPtr = mod.malloc(16)
        return try {
            withCStringKt(filepath) { fpPtr ->
                mod.uapmdTlAddMidiClipFromFile(
                    handle, trackIndex,
                    position.samples.toDouble(), position.legacyBeats,
                    fpPtr, nrpnToParameterMapping, outPtr
                )
            }
            decodeClipAddResult(mod, outPtr)
        } finally { mod.free(outPtr) }
    }

    override fun removeClip(trackIndex: Int, clipId: Int): Boolean =
        wasmMod.uapmdTlRemoveClip(handle, trackIndex, clipId)

    override fun loadProject(filePath: String): ProjectResult {
        val mod = wasmMod
        val errBufSize = 512
        val errBuf = mod.malloc(errBufSize)
        val successPtr = mod.malloc(4)
        return try {
            withCStringKt(filePath) { fpPtr ->
                mod.uapmdTlLoadProject(handle, fpPtr, successPtr, errBuf, errBufSize)
            }
            val success = mod.getValue(successPtr, "i8").toInt() != 0
            val error = if (!success) mod.utf8ToString(errBuf) else null
            ProjectResult(success, error)
        } finally { mod.free(errBuf); mod.free(successPtr) }
    }

    override fun calculateContentBounds(): ContentBounds {
        val mod = wasmMod
        // uapmd_content_bounds_t layout (approximate; check actual C struct)
        val ptr = mod.malloc(48)
        return try {
            mod.uapmdTlCalculateContentBounds(handle, ptr)
            // bool has_content (+0), i64 first_sample (+8), i64 last_sample (+16), f64 first_sec (+24), f64 last_sec (+32)
            fun getBool(o: Int) = mod.getValue(ptr + o, "i8").toInt() != 0
            fun getI64(o: Int): Long {
                val lo = mod.getValue(ptr + o, "i32").toInt().toLong() and 0xFFFFFFFFL
                val hi = mod.getValue(ptr + o + 4, "i32").toInt().toLong()
                return hi * 4294967296L + lo
            }
            fun getF64(o: Int) = mod.getValue(ptr + o, "double")
            ContentBounds(
                hasContent   = getBool(0),
                firstSample  = getI64(8),
                lastSample   = getI64(16),
                firstSeconds = getF64(24),
                lastSeconds  = getF64(32)
            )
        } finally { mod.free(ptr) }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun decodeClipAddResult(mod: UapmdCApiModule, ptr: Int): ClipAddResult {
        // uapmd_clip_add_result_t: { int clip_id; int source_node_id; bool success; const char* error }
        val clipId       = mod.getValue(ptr,     "i32").toInt()
        val sourceNodeId = mod.getValue(ptr + 4, "i32").toInt()
        val success      = mod.getValue(ptr + 8, "i8").toInt() != 0
        val errPtr       = mod.getValue(ptr + 12, "i32").toInt()
        val error        = if (errPtr != 0) mod.utf8ToString(errPtr) else null
        return ClipAddResult(clipId, sourceNodeId, success, error)
    }

    override fun getMidiClipNotes(trackIndex: Int, clipId: Int): List<MidiNoteData>? = null
    override fun setTimelineChangedCallback(callback: (() -> Unit)?) {}
}
