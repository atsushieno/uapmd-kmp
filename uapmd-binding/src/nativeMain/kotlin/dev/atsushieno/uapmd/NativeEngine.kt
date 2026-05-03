package dev.atsushieno.uapmd

import kotlinx.cinterop.*
import uapmd.*

class NativeSequencerEngine internal constructor(
    internal val handle: uapmd_sequencer_engine_t
) : SequencerEngine {

    override fun enqueueUmp(instanceId: Int, ump: UIntArray, timestamp: Long) {
        ump.usePinned { pinned ->
            uapmd_engine_enqueue_ump(handle, instanceId, pinned.addressOf(0), (ump.size * 4).toULong(), timestamp)
        }
    }

    override val pluginHost: PluginHost
        get() = NativePluginHost(uapmd_engine_plugin_host(handle)!!)

    override fun getPluginInstance(instanceId: Int): PluginInstance? =
        uapmd_engine_get_plugin_instance(handle, instanceId)?.let { NativePluginInstance(it) }

    override val functionBlockManager: FunctionBlockManager
        get() = NativeFunctionBlockManager(uapmd_engine_function_block_manager(handle)!!)

    override val trackCount: UInt get() = uapmd_engine_track_count(handle)

    override fun getTrack(index: UInt): SequencerTrack =
        NativeSequencerTrack(uapmd_engine_get_track(handle, index)!!)

    override val masterTrack: SequencerTrack
        get() = NativeSequencerTrack(uapmd_engine_master_track(handle)!!)

    override fun addEmptyTrack(): Int = uapmd_engine_add_empty_track(handle)

    override fun addPluginToTrack(
        trackIndex: Int, format: String, pluginId: String,
        callback: (Int, Int, String?) -> Unit
    ) {
        val ref = StableRef.create(callback)
        uapmd_engine_add_plugin_to_track(
            handle, trackIndex, format, pluginId, ref.asCPointer(),
            staticCFunction { instanceId, trackIdx, error, userData ->
                if (userData == null) return@staticCFunction
                val cb = userData.asStableRef<(Int, Int, String?) -> Unit>()
                cb.get()(instanceId, trackIdx, error?.toKString())
                cb.dispose()
            }
        )
    }

    override fun removePluginInstance(instanceId: Int): Boolean =
        uapmd_engine_remove_plugin_instance(handle, instanceId)

    override fun removeTrack(trackIndex: Int): Boolean =
        uapmd_engine_remove_track(handle, trackIndex)

    override fun cleanupEmptyTracks() = uapmd_engine_cleanup_empty_tracks(handle)

    override fun findTrackForInstance(instanceId: Int): Int =
        uapmd_engine_find_track_for_instance(handle, instanceId)

    override fun getInstanceGroup(instanceId: Int): UByte =
        uapmd_engine_get_instance_group(handle, instanceId)

    override fun setInstanceGroup(instanceId: Int, group: UByte): Boolean =
        uapmd_engine_set_instance_group(handle, instanceId, group)

    override fun getTrackLatency(trackIndex: Int): UInt =
        uapmd_engine_track_latency(handle, trackIndex)

    override val masterTrackLatency: UInt get() = uapmd_engine_master_track_latency(handle)

    override fun getTrackRenderLead(trackIndex: Int): UInt =
        uapmd_engine_track_render_lead(handle, trackIndex)

    override val masterTrackRenderLead: UInt get() = uapmd_engine_master_track_render_lead(handle)

    override fun setDefaultChannels(inputChannels: UInt, outputChannels: UInt) =
        uapmd_engine_set_default_channels(handle, inputChannels, outputChannels)

    override var sampleRate: Int
        get() = TODO("no getter in C API; track via setters")
        set(value) { uapmd_engine_set_sample_rate(handle, value) }

    override var offlineRendering: Boolean
        get() = uapmd_engine_get_offline_rendering(handle)
        set(value) { uapmd_engine_set_offline_rendering(handle, value) }

    override fun setActive(active: Boolean) = uapmd_engine_set_active(handle, active)
    override fun setExternalPump(enabled: Boolean) = uapmd_engine_set_external_pump(handle, enabled)

    override val isPlaybackActive: Boolean get() = uapmd_engine_is_playback_active(handle)

    override var playbackPosition: Long
        get() = uapmd_engine_get_playback_position(handle)
        set(value) { uapmd_engine_set_playback_position(handle, value) }

    override val renderPlaybackPosition: Long get() = uapmd_engine_render_playback_position(handle)

    override fun startPlayback() = uapmd_engine_start_playback(handle)
    override fun stopPlayback() = uapmd_engine_stop_playback(handle)
    override fun pausePlayback() = uapmd_engine_pause_playback(handle)
    override fun resumePlayback() = uapmd_engine_resume_playback(handle)

    override fun sendNoteOn(instanceId: Int, note: Int) = uapmd_engine_send_note_on(handle, instanceId, note)
    override fun sendNoteOff(instanceId: Int, note: Int) = uapmd_engine_send_note_off(handle, instanceId, note)
    override fun sendPitchBend(instanceId: Int, value: Float) = uapmd_engine_send_pitch_bend(handle, instanceId, value)
    override fun sendChannelPressure(instanceId: Int, pressure: Float) = uapmd_engine_send_channel_pressure(handle, instanceId, pressure)
    override fun setParameterValue(instanceId: Int, index: Int, value: Double) =
        uapmd_engine_set_parameter_value(handle, instanceId, index, value)

    override fun getInputSpectrum(numBars: Int): FloatArray = FloatArray(numBars).also { arr ->
        arr.usePinned { uapmd_engine_get_input_spectrum(handle, it.addressOf(0), numBars) }
    }

    override fun getOutputSpectrum(numBars: Int): FloatArray = FloatArray(numBars).also { arr ->
        arr.usePinned { uapmd_engine_get_output_spectrum(handle, it.addressOf(0), numBars) }
    }

    override val timeline: TimelineFacade
        get() = NativeTimelineFacade(uapmd_engine_timeline(handle)!!)

    override fun renderOffline(
        settings: OfflineRenderSettings,
        progressCallback: ((OfflineRenderProgress) -> Unit)?,
        shouldCancel: (() -> Boolean)?
    ): OfflineRenderResult = memScoped {
        val cs = alloc<uapmd_offline_render_settings_t>()
        cs.output_path = settings.outputPath.cstr.getPointer(this)
        cs.start_seconds = settings.startSeconds
        cs.end_seconds = settings.endSeconds ?: 0.0
        cs.has_end_seconds = settings.endSeconds != null
        cs.use_content_fallback = settings.useContentFallback
        cs.content_bounds_valid = settings.contentBoundsValid
        cs.content_start_seconds = settings.contentStartSeconds
        cs.content_end_seconds = settings.contentEndSeconds
        cs.tail_seconds = settings.tailSeconds
        cs.enable_silence_stop = settings.enableSilenceStop
        cs.silence_duration_seconds = settings.silenceDurationSeconds
        cs.silence_threshold_db = settings.silenceThresholdDb
        cs.sample_rate = settings.sampleRate
        cs.buffer_size = settings.bufferSize
        cs.output_channels = settings.outputChannels
        cs.ump_buffer_size = settings.umpBufferSize

        // Use Pair to avoid local-class capture in staticCFunction lambdas.
        val ctx = (progressCallback != null || shouldCancel != null)
            .takeIf { it }
            ?.let { StableRef.create(Pair(progressCallback, shouldCancel)) }

        val result = uapmd_render_offline(
            handle, cs.ptr, ctx?.asCPointer(),
            if (progressCallback != null) staticCFunction { progress, userData ->
                if (userData == null || progress == null) return@staticCFunction
                val pair = userData!!.asStableRef<Pair<((OfflineRenderProgress) -> Unit)?, (() -> Boolean)?>>().get()
                val p = progress.pointed
                pair?.first?.invoke(
                    OfflineRenderProgress(p.progress, p.rendered_seconds, p.total_seconds, p.rendered_frames, p.total_frames)
                )
            } else null,
            if (shouldCancel != null) staticCFunction { userData ->
                userData!!.asStableRef<Pair<((OfflineRenderProgress) -> Unit)?, (() -> Boolean)?>>()
                    .get().second?.invoke() ?: false
            } else null
        )
        ctx?.dispose()

        result.useContents {
            OfflineRenderResult(success, canceled, rendered_seconds, error_message?.toKString())
        }
    }
}

// ---------------------------------------------------------------------------

class NativeSequencerTrack internal constructor(
    private val handle: uapmd_sequencer_track_t
) : SequencerTrack {

    override val graph: PluginGraph get() = NativePluginGraph(uapmd_track_graph(handle)!!)
    override val latencyInSamples: UInt get() = uapmd_track_latency_in_samples(handle)
    override val renderLeadInSamples: UInt get() = uapmd_track_render_lead_in_samples(handle)
    override val tailLengthInSeconds: Double get() = uapmd_track_tail_length_in_seconds(handle)

    override var bypassed: Boolean
        get() = uapmd_track_get_bypassed(handle)
        set(value) { uapmd_track_set_bypassed(handle, value) }

    override var frozen: Boolean
        get() = uapmd_track_get_frozen(handle)
        set(value) { uapmd_track_set_frozen(handle, value) }

    override fun getOrderedInstanceIds(): List<Int> = memScoped {
        val count = uapmd_track_ordered_instance_id_count(handle)
        if (count == 0u) return emptyList()
        val arr = allocArray<IntVar>(count.toInt())
        uapmd_track_get_ordered_instance_ids(handle, arr, count)
        (0 until count.toInt()).map { arr[it] }
    }

    override fun setInstanceGroup(instanceId: Int, group: UByte) =
        uapmd_track_set_instance_group(handle, instanceId, group)

    override fun getInstanceGroup(instanceId: Int): UByte =
        uapmd_track_get_instance_group(handle, instanceId)

    override fun findAvailableGroup(): UByte = uapmd_track_find_available_group(handle)
    override fun removeInstance(instanceId: Int) = uapmd_track_remove_instance(handle, instanceId)
}

// ---------------------------------------------------------------------------

class NativeAudioDeviceManager internal constructor(
    private val handle: uapmd_audio_io_device_mgr_t
) : AudioDeviceManager {

    override val deviceCount: UInt get() = uapmd_audio_device_mgr_device_count(handle)

    override fun getDeviceInfo(index: UInt): AudioDeviceInfo? = memScoped {
        val out = alloc<uapmd_audio_device_info_t>()
        if (!uapmd_audio_device_mgr_get_device_info(handle, index, out.ptr)) return null
        AudioDeviceInfo(
            directions = AudioIoDirection.fromNative(out.directions.toInt()),
            id = out.id,
            name = out.name?.toKString() ?: "",
            sampleRate = out.sample_rate,
            channels = out.channels
        )
    }

    override fun open(
        inputDeviceIndex: Int, outputDeviceIndex: Int,
        sampleRate: UInt, bufferSize: UInt
    ): AudioIODevice = NativeAudioIODevice(
        uapmd_audio_device_mgr_open(handle, inputDeviceIndex, outputDeviceIndex, sampleRate, bufferSize)!!
    )
}

// ---------------------------------------------------------------------------

class NativeAudioIODevice internal constructor(
    private val handle: uapmd_audio_io_device_t
) : AudioIODevice {
    override val sampleRate: Double get() = uapmd_audio_device_sample_rate(handle)
    override val channels: UInt get() = uapmd_audio_device_channels(handle)
    override val inputChannels: UInt get() = uapmd_audio_device_input_channels(handle)
    override val outputChannels: UInt get() = uapmd_audio_device_output_channels(handle)
    override fun start(): Int = uapmd_audio_device_start(handle)
    override fun stop(): Int = uapmd_audio_device_stop(handle)
    override val isPlaying: Boolean get() = uapmd_audio_device_is_playing(handle)
}

// ---------------------------------------------------------------------------

class NativeMidiIODevice internal constructor(
    @Suppress("unused") private val handle: uapmd_midi_io_device_t
) : MidiIODevice

// ---------------------------------------------------------------------------

class NativeDeviceIODispatcher internal constructor(
    internal val handle: uapmd_device_io_dispatcher_t
) : DeviceIODispatcher {
    override fun start(): Int = uapmd_dispatcher_start(handle)
    override fun stop(): Int = uapmd_dispatcher_stop(handle)
    override val isPlaying: Boolean get() = uapmd_dispatcher_is_playing(handle)
    override fun clearOutputBuffers() = uapmd_dispatcher_clear_output_buffers(handle)
}

// ---------------------------------------------------------------------------

class NativeRealtimeSequencer internal constructor(
    private val handle: uapmd_realtime_sequencer_t
) : RealtimeSequencer {

    override val engine: SequencerEngine
        get() = NativeSequencerEngine(uapmd_rt_sequencer_engine(handle)!!)

    override fun startAudio(): Int = uapmd_rt_sequencer_start_audio(handle)
    override fun stopAudio(): Int = uapmd_rt_sequencer_stop_audio(handle)
    override fun isAudioPlaying(): Int = uapmd_rt_sequencer_is_audio_playing(handle)
    override fun clearOutputBuffers() = uapmd_rt_sequencer_clear_output_buffers(handle)

    override var sampleRate: Int
        get() = uapmd_rt_sequencer_sample_rate(handle)
        set(value) { uapmd_rt_sequencer_set_sample_rate(handle, value) }

    override fun reconfigureAudioDevice(
        inputDeviceIndex: Int, outputDeviceIndex: Int, sampleRate: UInt, bufferSize: UInt
    ): Boolean = uapmd_rt_sequencer_reconfigure_audio_device(
        handle, inputDeviceIndex, outputDeviceIndex, sampleRate, bufferSize
    )

    override fun close() = uapmd_rt_sequencer_destroy(handle)
}
