package dev.atsushieno.uapmd

import kotlinx.cinterop.*
import uapmd.*

class NativeAudioFileReader internal constructor(
    internal val handle: uapmd_audio_file_reader_t
) : AudioFileReader {

    override fun getProperties(): AudioFileProperties? = memScoped {
        val out = alloc<uapmd_audio_file_properties_t>()
        if (!uapmd_audio_file_reader_get_properties(handle, out.ptr)) return null
        AudioFileProperties(
            numFrames = out.num_frames.toLong(),
            numChannels = out.num_channels,
            sampleRate = out.sample_rate
        )
    }

    override fun readFrames(startFrame: Long, framesToRead: Long, dest: Array<FloatArray>) {
        val numChannels = dest.size
        if (numChannels == 0 || framesToRead <= 0) return
        // Build a C array of float* pointers into each pinned channel buffer.
        val pinnedChannels = dest.map { it.pin() }
        try {
            memScoped {
                val ptrArray = allocArray<CPointerVar<FloatVar>>(numChannels)
                pinnedChannels.forEachIndexed { i, pinned ->
                    ptrArray[i] = pinned.addressOf(0)
                }
                uapmd_audio_file_reader_read_frames(
                    handle,
                    startFrame.toULong(),
                    framesToRead.toULong(),
                    ptrArray,
                    numChannels.toUInt()
                )
            }
        } finally {
            pinnedChannels.forEach { it.unpin() }
        }
    }

    override fun close() = uapmd_audio_file_reader_destroy(handle)
}

// ---------------------------------------------------------------------------

class NativeTimelineTrack internal constructor(
    private val handle: uapmd_timeline_track_t
) : TimelineTrack {
    override fun getClips(): List<ClipData> = memScoped {
        val cm = uapmd_tt_clip_manager(handle) ?: return emptyList()
        val count = uapmd_cm_clip_count(cm).toInt()
        if (count == 0) return emptyList()
        val buf = allocArray<uapmd_clip_data_t>(count)
        val actual = uapmd_cm_get_all_clips(cm, buf, count.toUInt()).toInt()
        (0 until actual).map { i ->
            val c = (buf + i)!!.pointed
            ClipData(
                clipId              = c.clip_id,
                positionSamples     = c.position.samples,
                positionLegacyBeats = c.position.legacy_beats,
                durationSamples     = c.duration_samples,
                gain                = c.gain,
                muted               = c.muted,
                name                = c.name?.toKString() ?: "",
                filepath            = c.filepath?.toKString() ?: "",
                clipType            = ClipType.fromNative(c.clip_type.value.toInt())
            )
        }
    }
}

// ---------------------------------------------------------------------------

class NativeTimelineFacade internal constructor(
    private val handle: uapmd_timeline_facade_t
) : TimelineFacade {

    override fun getState(): TimelineState? = memScoped {
        val out = alloc<uapmd_timeline_state_t>()
        if (!uapmd_tl_get_state(handle, out.ptr)) return null
        out.toKotlin()
    }

    override fun setTempo(tempo: Double) = uapmd_tl_set_tempo(handle, tempo)

    override fun setTimeSignature(numerator: Int, denominator: Int) =
        uapmd_tl_set_time_signature(handle, numerator, denominator)

    override fun setLoop(enabled: Boolean, start: TimelinePosition, end: TimelinePosition) =
        uapmd_tl_set_loop(handle, enabled, start.toNativeCValue(), end.toNativeCValue())

    override val trackCount: UInt get() = uapmd_tl_track_count(handle)

    override fun getTrack(index: UInt): TimelineTrack =
        NativeTimelineTrack(uapmd_tl_get_track(handle, index)!!)

    override val masterTimelineTrack: TimelineTrack
        get() = NativeTimelineTrack(uapmd_tl_master_timeline_track(handle)!!)

    override fun addAudioClip(
        trackIndex: Int, position: TimelinePosition, reader: AudioFileReader, filepath: String
    ): ClipAddResult {
        val nativeReader = (reader as NativeAudioFileReader).handle
        return uapmd_tl_add_audio_clip(handle, trackIndex, position.toNativeCValue(), nativeReader, filepath)
            .useContents { ClipAddResult(clip_id, source_node_id, success, error?.toKString()) }
    }

    override fun addMidiClipFromFile(
        trackIndex: Int, position: TimelinePosition, filepath: String, nrpnToParameterMapping: Boolean
    ): ClipAddResult =
        uapmd_tl_add_midi_clip_from_file(handle, trackIndex, position.toNativeCValue(), filepath, nrpnToParameterMapping)
            .useContents { ClipAddResult(clip_id, source_node_id, success, error?.toKString()) }

    override fun removeClip(trackIndex: Int, clipId: Int): Boolean =
        uapmd_tl_remove_clip(handle, trackIndex, clipId)

    override fun loadProject(filePath: String): ProjectResult =
        uapmd_tl_load_project(handle, filePath)
            .useContents { ProjectResult(success, error?.toKString()) }

    override fun calculateContentBounds(): ContentBounds =
        uapmd_tl_calculate_content_bounds(handle).useContents {
            ContentBounds(has_content, first_sample, last_sample, first_seconds, last_seconds)
        }
}
