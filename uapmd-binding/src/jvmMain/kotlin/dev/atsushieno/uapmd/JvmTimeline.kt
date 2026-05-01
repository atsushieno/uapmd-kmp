package dev.atsushieno.uapmd

import com.sun.jna.Memory
import com.sun.jna.Pointer
import dev.atsushieno.uapmd.jna.UapmdAudioFileProperties
import dev.atsushieno.uapmd.jna.UapmdTimelineState

// ─── JvmAudioFileReader ──────────────────────────────────────────────────────

class JvmAudioFileReader internal constructor(
    internal val handle: Pointer
) : AudioFileReader {

    override fun getProperties(): AudioFileProperties? {
        val out = UapmdAudioFileProperties()
        if (!lib.uapmd_audio_file_reader_get_properties(handle, out)) return null
        return AudioFileProperties(
            numFrames = out.num_frames,   // Long (uint64_t bit-pattern)
            numChannels = out.num_channels.toUInt(),
            sampleRate = out.sample_rate.toUInt()
        )
    }

    /**
     * Reads [framesToRead] frames starting at [startFrame] into the caller-allocated [dest] arrays.
     * The C API takes `float* const* dest` — an array of per-channel float pointers.
     * We pin each FloatArray in [dest] via JNA Memory, call the C function, then copy back.
     */
    override fun readFrames(startFrame: Long, framesToRead: Long, dest: Array<FloatArray>) {
        val nCh = dest.size
        val channelMems = Array(nCh) { ch ->
            Memory(dest[ch].size.toLong() * 4L).also { mem ->
                mem.write(0, dest[ch], 0, dest[ch].size)
            }
        }
        val destPointers = Array<Pointer?>(nCh) { channelMems[it] }
        lib.uapmd_audio_file_reader_read_frames(handle, startFrame, framesToRead, destPointers, nCh)
        for (ch in 0 until nCh) {
            channelMems[ch].read(0, dest[ch], 0, dest[ch].size)
        }
    }

    override fun close() = lib.uapmd_audio_file_reader_destroy(handle)
}

// ─── JvmTimelineFacade ───────────────────────────────────────────────────────

class JvmTimelineFacade internal constructor(
    private val handle: Pointer
) : TimelineFacade {

    override fun getState(): TimelineState? {
        val out = UapmdTimelineState()
        if (!lib.uapmd_tl_get_state(handle, out)) return null
        return TimelineState(
            playheadPosition = out.playhead_position.toKotlin(),
            isPlaying = out.is_playing != 0.toByte(),
            loopEnabled = out.loop_enabled != 0.toByte(),
            loopStart = out.loop_start.toKotlin(),
            loopEnd = out.loop_end.toKotlin(),
            tempo = out.tempo,
            timeSignatureNumerator = out.time_signature_numerator,
            timeSignatureDenominator = out.time_signature_denominator,
            sampleRate = out.sample_rate
        )
    }

    override fun setTempo(tempo: Double) = lib.uapmd_tl_set_tempo(handle, tempo)

    override fun setTimeSignature(numerator: Int, denominator: Int) =
        lib.uapmd_tl_set_time_signature(handle, numerator, denominator)

    override fun setLoop(enabled: Boolean, start: TimelinePosition, end: TimelinePosition) =
        lib.uapmd_tl_set_loop(handle, enabled, start.toJvmByVal(), end.toJvmByVal())

    override val trackCount: UInt get() = lib.uapmd_tl_track_count(handle).toUInt()

    override fun getTrack(index: UInt): TimelineTrack =
        JvmTimelineTrack(lib.uapmd_tl_get_track(handle, index.toInt()) ?: error("timeline track $index not found"))

    override val masterTimelineTrack: TimelineTrack
        get() = JvmTimelineTrack(lib.uapmd_tl_master_timeline_track(handle) ?: error("master timeline track not found"))

    override fun addAudioClip(
        trackIndex: Int,
        position: TimelinePosition,
        reader: AudioFileReader,
        filepath: String
    ): ClipAddResult {
        val r = lib.uapmd_tl_add_audio_clip(
            handle, trackIndex, position.toJvmByVal(),
            (reader as JvmAudioFileReader).handle, filepath
        )
        return ClipAddResult(r.clip_id, r.source_node_id, r.success != 0.toByte(), r.error)
    }

    override fun addMidiClipFromFile(
        trackIndex: Int,
        position: TimelinePosition,
        filepath: String,
        nrpnToParameterMapping: Boolean
    ): ClipAddResult {
        val r = lib.uapmd_tl_add_midi_clip_from_file(
            handle, trackIndex, position.toJvmByVal(), filepath, nrpnToParameterMapping
        )
        return ClipAddResult(r.clip_id, r.source_node_id, r.success != 0.toByte(), r.error)
    }

    override fun removeClip(trackIndex: Int, clipId: Int): Boolean =
        lib.uapmd_tl_remove_clip(handle, trackIndex, clipId)

    override fun loadProject(filePath: String): ProjectResult {
        val r = lib.uapmd_tl_load_project(handle, filePath)
        return ProjectResult(r.success != 0.toByte(), r.error)
    }

    override fun calculateContentBounds(): ContentBounds {
        val r = lib.uapmd_tl_calculate_content_bounds(handle)
        return ContentBounds(
            hasContent = r.has_content != 0.toByte(),
            firstSample = r.first_sample,
            lastSample = r.last_sample,
            firstSeconds = r.first_seconds,
            lastSeconds = r.last_seconds
        )
    }
}

// ─── JvmTimelineTrack (marker wrapper) ───────────────────────────────────────

class JvmTimelineTrack internal constructor(
    @Suppress("unused") private val handle: Pointer
) : TimelineTrack
