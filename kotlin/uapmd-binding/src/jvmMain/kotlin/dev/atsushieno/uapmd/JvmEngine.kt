package dev.atsushieno.uapmd

import com.sun.jna.Pointer
import dev.atsushieno.uapmd.jna.*

// ─── JvmSequencerEngine ──────────────────────────────────────────────────────

class JvmSequencerEngine internal constructor(
    internal val handle: Pointer
) : SequencerEngine {

    override fun enqueueUmp(instanceId: Int, ump: UIntArray, timestamp: Long) {
        val arr = IntArray(ump.size) { ump[it].toInt() }
        lib.uapmd_engine_enqueue_ump(handle, instanceId, arr, (ump.size * 4L), timestamp)
    }

    override val pluginHost: PluginHost
        get() = JvmPluginHost(lib.uapmd_engine_plugin_host(handle) ?: error("uapmd_engine_plugin_host returned null"))

    override fun getPluginInstance(instanceId: Int): PluginInstance? =
        lib.uapmd_engine_get_plugin_instance(handle, instanceId)?.let { JvmPluginInstance(it) }

    override val functionBlockManager: FunctionBlockManager
        get() = JvmFunctionBlockManager(lib.uapmd_engine_function_block_manager(handle) ?: error("uapmd_engine_function_block_manager returned null"))

    override val trackCount: UInt get() = lib.uapmd_engine_track_count(handle).toUInt()

    override fun getTrack(index: UInt): SequencerTrack =
        JvmSequencerTrack(lib.uapmd_engine_get_track(handle, index.toInt()) ?: error("track $index not found"))

    override val masterTrack: SequencerTrack
        get() = JvmSequencerTrack(lib.uapmd_engine_master_track(handle) ?: error("master track not found"))

    override fun addEmptyTrack(): Int = lib.uapmd_engine_add_empty_track(handle)

    override fun addPluginToTrack(
        trackIndex: Int, format: String, pluginId: String,
        callback: (Int, Int, String?) -> Unit
    ) {
        debugJvmThread("JvmSequencerEngine.addPluginToTrack.request format=$format pluginId=$pluginId")
        val cb = object : AddPluginCb {
            override fun invoke(instanceId: Int, trackIdx: Int, error: String?, userData: Pointer?) =
                callback(instanceId, trackIdx, error).also {
                    debugJvmThread("JvmSequencerEngine.addPluginToTrack.callback format=$format pluginId=$pluginId instanceId=$instanceId error=${error ?: ""}")
                }
        }
        lib.uapmd_engine_add_plugin_to_track(handle, trackIndex, format, pluginId, null, cb)
    }

    override fun removePluginInstance(instanceId: Int): Boolean =
        lib.uapmd_engine_remove_plugin_instance(handle, instanceId)

    override fun removeTrack(trackIndex: Int): Boolean =
        lib.uapmd_engine_remove_track(handle, trackIndex)

    override fun cleanupEmptyTracks() = lib.uapmd_engine_cleanup_empty_tracks(handle)

    override fun findTrackForInstance(instanceId: Int): Int =
        lib.uapmd_engine_find_track_for_instance(handle, instanceId)

    override fun getInstanceGroup(instanceId: Int): UByte =
        lib.uapmd_engine_get_instance_group(handle, instanceId).toUByte()

    override fun setInstanceGroup(instanceId: Int, group: UByte): Boolean =
        lib.uapmd_engine_set_instance_group(handle, instanceId, group.toByte())

    override fun getTrackLatency(trackIndex: Int): UInt =
        lib.uapmd_engine_track_latency(handle, trackIndex).toUInt()

    override val masterTrackLatency: UInt get() = lib.uapmd_engine_master_track_latency(handle).toUInt()

    override fun getTrackRenderLead(trackIndex: Int): UInt =
        lib.uapmd_engine_track_render_lead(handle, trackIndex).toUInt()

    override val masterTrackRenderLead: UInt get() = lib.uapmd_engine_master_track_render_lead(handle).toUInt()

    override fun setDefaultChannels(inputChannels: UInt, outputChannels: UInt) =
        lib.uapmd_engine_set_default_channels(handle, inputChannels.toInt(), outputChannels.toInt())

    override var sampleRate: Int
        get() = TODO("no getter in C API; track via setters")
        set(value) { lib.uapmd_engine_set_sample_rate(handle, value) }

    override var offlineRendering: Boolean
        get() = lib.uapmd_engine_get_offline_rendering(handle)
        set(value) { lib.uapmd_engine_set_offline_rendering(handle, value) }

    override fun setActive(active: Boolean) = lib.uapmd_engine_set_active(handle, active)
    override fun setExternalPump(enabled: Boolean) = lib.uapmd_engine_set_external_pump(handle, enabled)

    override val isPlaybackActive: Boolean get() = lib.uapmd_engine_is_playback_active(handle)

    override var playbackPosition: Long
        get() = lib.uapmd_engine_get_playback_position(handle)
        set(value) { lib.uapmd_engine_set_playback_position(handle, value) }

    override val renderPlaybackPosition: Long get() = lib.uapmd_engine_render_playback_position(handle)

    override fun startPlayback() = lib.uapmd_engine_start_playback(handle)
    override fun stopPlayback() = lib.uapmd_engine_stop_playback(handle)
    override fun pausePlayback() = lib.uapmd_engine_pause_playback(handle)
    override fun resumePlayback() = lib.uapmd_engine_resume_playback(handle)

    override fun sendNoteOn(instanceId: Int, note: Int) = lib.uapmd_engine_send_note_on(handle, instanceId, note)
    override fun sendNoteOff(instanceId: Int, note: Int) = lib.uapmd_engine_send_note_off(handle, instanceId, note)
    override fun sendPitchBend(instanceId: Int, value: Float) = lib.uapmd_engine_send_pitch_bend(handle, instanceId, value)
    override fun sendChannelPressure(instanceId: Int, pressure: Float) = lib.uapmd_engine_send_channel_pressure(handle, instanceId, pressure)
    override fun setParameterValue(instanceId: Int, index: Int, value: Double) =
        lib.uapmd_engine_set_parameter_value(handle, instanceId, index, value)

    override fun getInputSpectrum(numBars: Int): FloatArray =
        FloatArray(numBars).also { lib.uapmd_engine_get_input_spectrum(handle, it, numBars) }

    override fun getOutputSpectrum(numBars: Int): FloatArray =
        FloatArray(numBars).also { lib.uapmd_engine_get_output_spectrum(handle, it, numBars) }

    override val timeline: TimelineFacade
        get() = JvmTimelineFacade(lib.uapmd_engine_timeline(handle) ?: error("uapmd_engine_timeline returned null"))

    override fun renderOffline(
        settings: OfflineRenderSettings,
        progressCallback: ((OfflineRenderProgress) -> Unit)?,
        shouldCancel: (() -> Boolean)?
    ): OfflineRenderResult {
        val cs = UapmdOfflineRenderSettings()
        cs.output_path = settings.outputPath
        cs.start_seconds = settings.startSeconds
        cs.end_seconds = settings.endSeconds ?: 0.0
        cs.has_end_seconds = if (settings.endSeconds != null) 1 else 0
        cs.use_content_fallback = if (settings.useContentFallback) 1 else 0
        cs.content_bounds_valid = if (settings.contentBoundsValid) 1 else 0
        cs.content_start_seconds = settings.contentStartSeconds
        cs.content_end_seconds = settings.contentEndSeconds
        cs.tail_seconds = settings.tailSeconds
        cs.enable_silence_stop = if (settings.enableSilenceStop) 1 else 0
        cs.silence_duration_seconds = settings.silenceDurationSeconds
        cs.silence_threshold_db = settings.silenceThresholdDb
        cs.sample_rate = settings.sampleRate
        cs.buffer_size = settings.bufferSize.toInt()
        cs.output_channels = settings.outputChannels.toInt()
        cs.ump_buffer_size = settings.umpBufferSize.toInt()

        val progressCb: RenderProgressCb? = progressCallback?.let { cb ->
            object : RenderProgressCb {
                override fun invoke(progress: UapmdOfflineRenderProgress?, userData: Pointer?) {
                    if (progress == null) return
                    cb(OfflineRenderProgress(
                        progress.progress,
                        progress.rendered_seconds,
                        progress.total_seconds,
                        progress.rendered_frames,
                        progress.total_frames
                    ))
                }
            }
        }
        val cancelCb: RenderShouldCancelCb? = shouldCancel?.let { fn ->
            object : RenderShouldCancelCb {
                override fun invoke(userData: Pointer?): Boolean = fn()
            }
        }

        val result = lib.uapmd_render_offline(handle, cs, null, progressCb, cancelCb)
        return OfflineRenderResult(
            success = result.success != 0.toByte(),
            canceled = result.canceled != 0.toByte(),
            renderedSeconds = result.rendered_seconds,
            errorMessage = result.error_message
        )
    }
}

// ─── JvmSequencerTrack ───────────────────────────────────────────────────────

class JvmSequencerTrack internal constructor(
    private val handle: Pointer
) : SequencerTrack {

    override val graph: PluginGraph get() = JvmPluginGraph(lib.uapmd_track_graph(handle) ?: error("uapmd_track_graph returned null"))
    override val latencyInSamples: UInt get() = lib.uapmd_track_latency_in_samples(handle).toUInt()
    override val renderLeadInSamples: UInt get() = lib.uapmd_track_render_lead_in_samples(handle).toUInt()
    override val tailLengthInSeconds: Double get() = lib.uapmd_track_tail_length_in_seconds(handle)

    override var bypassed: Boolean
        get() = lib.uapmd_track_get_bypassed(handle)
        set(value) { lib.uapmd_track_set_bypassed(handle, value) }

    override var frozen: Boolean
        get() = lib.uapmd_track_get_frozen(handle)
        set(value) { lib.uapmd_track_set_frozen(handle, value) }

    override fun getOrderedInstanceIds(): List<Int> {
        val count = lib.uapmd_track_ordered_instance_id_count(handle)
        if (count == 0) return emptyList()
        val arr = IntArray(count)
        lib.uapmd_track_get_ordered_instance_ids(handle, arr, count)
        return arr.toList()
    }

    override fun setInstanceGroup(instanceId: Int, group: UByte) =
        lib.uapmd_track_set_instance_group(handle, instanceId, group.toByte())

    override fun getInstanceGroup(instanceId: Int): UByte =
        lib.uapmd_track_get_instance_group(handle, instanceId).toUByte()

    override fun findAvailableGroup(): UByte = lib.uapmd_track_find_available_group(handle).toUByte()
    override fun removeInstance(instanceId: Int) = lib.uapmd_track_remove_instance(handle, instanceId)
}

// ─── JvmAudioDeviceManager ───────────────────────────────────────────────────

class JvmAudioDeviceManager internal constructor(
    private val handle: Pointer
) : AudioDeviceManager {

    override val deviceCount: UInt get() = lib.uapmd_audio_device_mgr_device_count(handle).toUInt()

    override fun getDeviceInfo(index: UInt): AudioDeviceInfo? {
        val out = UapmdAudioDeviceInfo()
        if (!lib.uapmd_audio_device_mgr_get_device_info(handle, index.toInt(), out)) return null
        return AudioDeviceInfo(
            directions = AudioIoDirection.fromNative(out.directions),
            id = out.id,
            name = out.name ?: "",
            sampleRate = out.sample_rate.toUInt(),
            channels = out.channels.toUInt()
        )
    }

    override fun open(
        inputDeviceIndex: Int, outputDeviceIndex: Int,
        sampleRate: UInt, bufferSize: UInt
    ): AudioIODevice = JvmAudioIODevice(
        lib.uapmd_audio_device_mgr_open(handle, inputDeviceIndex, outputDeviceIndex, sampleRate.toInt(), bufferSize.toInt())
            ?: error("uapmd_audio_device_mgr_open failed")
    )
}

// ─── JvmAudioIODevice ────────────────────────────────────────────────────────

class JvmAudioIODevice internal constructor(
    private val handle: Pointer
) : AudioIODevice {
    override val sampleRate: Double get() = lib.uapmd_audio_device_sample_rate(handle)
    override val channels: UInt get() = lib.uapmd_audio_device_channels(handle).toUInt()
    override val inputChannels: UInt get() = lib.uapmd_audio_device_input_channels(handle).toUInt()
    override val outputChannels: UInt get() = lib.uapmd_audio_device_output_channels(handle).toUInt()
    override fun start(): Int = lib.uapmd_audio_device_start(handle)
    override fun stop(): Int = lib.uapmd_audio_device_stop(handle)
    override val isPlaying: Boolean get() = lib.uapmd_audio_device_is_playing(handle)
}

// ─── JvmMidiIODevice ─────────────────────────────────────────────────────────

class JvmMidiIODevice internal constructor(
    @Suppress("unused") private val handle: Pointer
) : MidiIODevice

// ─── JvmDeviceIODispatcher ───────────────────────────────────────────────────

class JvmDeviceIODispatcher internal constructor(
    internal val handle: Pointer
) : DeviceIODispatcher {
    override fun start(): Int = lib.uapmd_dispatcher_start(handle)
    override fun stop(): Int = lib.uapmd_dispatcher_stop(handle)
    override val isPlaying: Boolean get() = lib.uapmd_dispatcher_is_playing(handle)
    override fun clearOutputBuffers() = lib.uapmd_dispatcher_clear_output_buffers(handle)
}

// ─── JvmRealtimeSequencer ────────────────────────────────────────────────────

class JvmRealtimeSequencer internal constructor(
    private val handle: Pointer
) : RealtimeSequencer {

    override val engine: SequencerEngine
        get() = JvmSequencerEngine(lib.uapmd_rt_sequencer_engine(handle) ?: error("uapmd_rt_sequencer_engine returned null"))

    override fun startAudio(): Int = lib.uapmd_rt_sequencer_start_audio(handle)
    override fun stopAudio(): Int = lib.uapmd_rt_sequencer_stop_audio(handle)
    override fun isAudioPlaying(): Int = lib.uapmd_rt_sequencer_is_audio_playing(handle)
    override fun clearOutputBuffers() = lib.uapmd_rt_sequencer_clear_output_buffers(handle)

    override var sampleRate: Int
        get() = lib.uapmd_rt_sequencer_sample_rate(handle)
        set(value) { lib.uapmd_rt_sequencer_set_sample_rate(handle, value) }

    override fun reconfigureAudioDevice(
        inputDeviceIndex: Int, outputDeviceIndex: Int, sampleRate: UInt, bufferSize: UInt
    ): Boolean = lib.uapmd_rt_sequencer_reconfigure_audio_device(
        handle, inputDeviceIndex, outputDeviceIndex, sampleRate.toInt(), bufferSize.toInt()
    )

    override fun close() = lib.uapmd_rt_sequencer_destroy(handle)
}
