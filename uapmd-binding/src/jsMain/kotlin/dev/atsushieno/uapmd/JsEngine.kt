package dev.atsushieno.uapmd

// ─── JsSequencerEngine ────────────────────────────────────────────────────────

class JsSequencerEngine internal constructor(
    internal val handle: Int
) : SequencerEngine {

    override val pluginHost: PluginHost
        get() = JsPluginHost(jsMod._uapmd_engine_plugin_host(handle) as Int)

    override fun getPluginInstance(instanceId: Int): PluginInstance? {
        val inst = jsMod._uapmd_engine_get_plugin_instance(handle, instanceId) as Int
        return if (inst == 0) null else JsPluginInstance(inst)
    }

    override val functionBlockManager: FunctionBlockManager
        get() = JsFunctionBlockManager(jsMod._uapmd_engine_function_block_manager(handle) as Int)

    override val trackCount: UInt
        get() = (jsMod._uapmd_engine_track_count(handle) as Int).toUInt()

    override fun getTrack(index: UInt): SequencerTrack =
        JsSequencerTrack(jsMod._uapmd_engine_get_track(handle, index.toInt()) as Int)

    override val masterTrack: SequencerTrack
        get() = JsSequencerTrack(jsMod._uapmd_engine_master_track(handle) as Int)

    override fun addEmptyTrack(): Int = jsMod._uapmd_engine_add_empty_track(handle) as Int

    override fun addPluginToTrack(
        trackIndex: Int, format: String, pluginId: String,
        callback: (instanceId: Int, trackIndex: Int, error: String?) -> Unit
    ) {
        val fnPtr = makeJsAddPluginCallback(callback)
        withJsTwoCStrings(format, pluginId) { fPtr, idPtr ->
            jsMod._uapmd_engine_add_plugin_to_track(handle, trackIndex, fPtr, idPtr, fnPtr, 0)
        }
    }

    override fun removePluginInstance(instanceId: Int): Boolean =
        jsMod._uapmd_engine_remove_plugin_instance(handle, instanceId) as Boolean

    override fun removeTrack(trackIndex: Int): Boolean =
        jsMod._uapmd_engine_remove_track(handle, trackIndex) as Boolean

    override fun cleanupEmptyTracks()       = jsMod._uapmd_engine_cleanup_empty_tracks(handle)
    override fun findTrackForInstance(instanceId: Int): Int = jsMod._uapmd_engine_find_track_for_instance(handle, instanceId) as Int
    override fun getInstanceGroup(instanceId: Int): UByte  = (jsMod._uapmd_engine_get_instance_group(handle, instanceId) as Int).toUByte()
    override fun setInstanceGroup(instanceId: Int, group: UByte): Boolean =
        jsMod._uapmd_engine_set_instance_group(handle, instanceId, group.toInt()) as Boolean

    override fun getTrackLatency(trackIndex: Int): UInt = (jsMod._uapmd_engine_track_latency(handle, trackIndex) as Int).toUInt()
    override val masterTrackLatency: UInt   get() = (jsMod._uapmd_engine_master_track_latency(handle) as Int).toUInt()
    override fun getTrackRenderLead(trackIndex: Int): UInt = (jsMod._uapmd_engine_track_render_lead(handle, trackIndex) as Int).toUInt()
    override val masterTrackRenderLead: UInt get() = (jsMod._uapmd_engine_master_track_render_lead(handle) as Int).toUInt()

    override fun setDefaultChannels(inputChannels: UInt, outputChannels: UInt) =
        jsMod._uapmd_engine_set_default_channels(handle, inputChannels.toInt(), outputChannels.toInt())

    override var sampleRate: Int
        get() = throw UnsupportedOperationException("sampleRate is write-only via the C API")
        set(value) { jsMod._uapmd_engine_set_sample_rate(handle, value) }

    override var offlineRendering: Boolean
        get() = jsMod._uapmd_engine_get_offline_rendering(handle) as Boolean
        set(value) { jsMod._uapmd_engine_set_offline_rendering(handle, value) }

    override fun setActive(active: Boolean)        = jsMod._uapmd_engine_set_active(handle, active)
    override fun setExternalPump(enabled: Boolean) = jsMod._uapmd_engine_set_external_pump(handle, enabled)

    override val isPlaybackActive: Boolean get() = jsMod._uapmd_engine_is_playback_active(handle) as Boolean

    override var playbackPosition: Long
        get() = (jsMod._uapmd_engine_get_playback_position(handle) as Double).toLong()
        set(value) { jsMod._uapmd_engine_set_playback_position(handle, value.toDouble()) }

    override val renderPlaybackPosition: Long
        get() = (jsMod._uapmd_engine_render_playback_position(handle) as Double).toLong()

    override fun startPlayback()  = jsMod._uapmd_engine_start_playback(handle)
    override fun stopPlayback()   = jsMod._uapmd_engine_stop_playback(handle)
    override fun pausePlayback()  = jsMod._uapmd_engine_pause_playback(handle)
    override fun resumePlayback() = jsMod._uapmd_engine_resume_playback(handle)

    override fun enqueueUmp(instanceId: Int, ump: UIntArray, timestamp: Long) {
        val ptr = jsMod._malloc(ump.size * 4) as Int
        try {
            ump.forEachIndexed { i, v -> jsSetI32(ptr + i * 4, v.toInt()) }
            jsMod._uapmd_engine_enqueue_ump(handle, instanceId, ptr, ump.size, timestamp.toDouble())
        } finally { jsMod._free(ptr) }
    }

    override fun sendNoteOn(instanceId: Int, note: Int)                = jsMod._uapmd_engine_send_note_on(handle, instanceId, note)
    override fun sendNoteOff(instanceId: Int, note: Int)               = jsMod._uapmd_engine_send_note_off(handle, instanceId, note)
    override fun sendPitchBend(instanceId: Int, value: Float)          = jsMod._uapmd_engine_send_pitch_bend(handle, instanceId, value.toDouble())
    override fun sendChannelPressure(instanceId: Int, pressure: Float) = jsMod._uapmd_engine_send_channel_pressure(handle, instanceId, pressure.toDouble())
    override fun setParameterValue(instanceId: Int, index: Int, value: Double) =
        jsMod._uapmd_engine_set_parameter_value(handle, instanceId, index, value)

    override fun getInputSpectrum(numBars: Int): FloatArray  = FloatArray(numBars)
    override fun getOutputSpectrum(numBars: Int): FloatArray = FloatArray(numBars)

    override val timeline: TimelineFacade
        get() = JsTimelineFacade(jsMod._uapmd_engine_timeline(handle) as Int)

    override fun renderOffline(
        settings: OfflineRenderSettings,
        progressCallback: ((OfflineRenderProgress) -> Unit)?,
        shouldCancel: (() -> Boolean)?
    ): OfflineRenderResult =
        throw UnsupportedOperationException("renderOffline not yet implemented for JS")
}

// ─── JsSequencerTrack ─────────────────────────────────────────────────────────

class JsSequencerTrack internal constructor(
    private val handle: Int
) : SequencerTrack {

    override val graph: PluginGraph get() = JsPluginGraph(jsMod._uapmd_track_graph(handle) as Int)
    override val latencyInSamples: UInt   get() = (jsMod._uapmd_track_latency_in_samples(handle) as Int).toUInt()
    override val renderLeadInSamples: UInt get() = (jsMod._uapmd_track_render_lead_in_samples(handle) as Int).toUInt()
    override val tailLengthInSeconds: Double get() = jsMod._uapmd_track_tail_length_in_seconds(handle) as Double

    override var bypassed: Boolean
        get() = jsMod._uapmd_track_get_bypassed(handle) as Boolean
        set(v) { jsMod._uapmd_track_set_bypassed(handle, v) }

    override var frozen: Boolean
        get() = jsMod._uapmd_track_get_frozen(handle) as Boolean
        set(v) { jsMod._uapmd_track_set_frozen(handle, v) }

    override fun getOrderedInstanceIds(): List<Int> {
        val count = jsMod._uapmd_track_ordered_instance_id_count(handle) as Int
        if (count <= 0) return emptyList()
        return withWasmMem(count * 4) { buf ->
            jsMod._uapmd_track_get_ordered_instance_ids(handle, buf, count)
            List(count) { i -> jsGetI32(buf + i * 4) }
        }
    }

    override fun setInstanceGroup(instanceId: Int, group: UByte) = jsMod._uapmd_track_set_instance_group(handle, instanceId, group.toInt())
    override fun getInstanceGroup(instanceId: Int): UByte = (jsMod._uapmd_track_get_instance_group(handle, instanceId) as Int).toUByte()
    override fun findAvailableGroup(): UByte = (jsMod._uapmd_track_find_available_group(handle) as Int).toUByte()
    override fun removeInstance(instanceId: Int) = jsMod._uapmd_track_remove_instance(handle, instanceId)
}

// ─── JsDeviceIODispatcher ─────────────────────────────────────────────────────

class JsDeviceIODispatcher internal constructor(
    internal val handle: Int
) : DeviceIODispatcher {
    override fun start(): Int         = jsMod._uapmd_dispatcher_start(handle) as Int
    override fun stop(): Int          = jsMod._uapmd_dispatcher_stop(handle) as Int
    override val isPlaying: Boolean   get() = jsMod._uapmd_dispatcher_is_playing(handle) as Boolean
    override fun clearOutputBuffers() = jsMod._uapmd_dispatcher_clear_output_buffers(handle)
}

// ─── JsRealtimeSequencer ──────────────────────────────────────────────────────

class JsRealtimeSequencer internal constructor(
    private val handle: Int
) : RealtimeSequencer {

    override val engine: SequencerEngine get() = JsSequencerEngine(jsMod._uapmd_rt_sequencer_engine(handle) as Int)
    override fun startAudio(): Int      = jsMod._uapmd_rt_sequencer_start_audio(handle) as Int
    override fun stopAudio(): Int       = jsMod._uapmd_rt_sequencer_stop_audio(handle) as Int
    override fun isAudioPlaying(): Int  = jsMod._uapmd_rt_sequencer_is_audio_playing(handle) as Int
    override fun clearOutputBuffers()   = jsMod._uapmd_rt_sequencer_clear_output_buffers(handle)

    override var sampleRate: Int
        get() = jsMod._uapmd_rt_sequencer_sample_rate(handle) as Int
        set(v) { jsMod._uapmd_rt_sequencer_set_sample_rate(handle, v) }

    override fun reconfigureAudioDevice(
        inputDeviceIndex: Int, outputDeviceIndex: Int,
        sampleRate: UInt, bufferSize: UInt
    ): Boolean = jsMod._uapmd_rt_sequencer_reconfigure_audio_device(
        handle, inputDeviceIndex, outputDeviceIndex, sampleRate.toInt(), bufferSize.toInt()
    ) as Boolean

    override fun close() = jsMod._uapmd_rt_sequencer_destroy(handle)
}

// ─── JsAudioDeviceManager ─────────────────────────────────────────────────────

class JsAudioDeviceManager internal constructor(
    private val handle: Int
) : AudioDeviceManager {
    override val deviceCount: UInt get() = (jsMod._uapmd_audio_device_mgr_device_count(handle) as Int).toUInt()

    override fun getDeviceInfo(index: UInt): AudioDeviceInfo? =
        withWasmMem(24) { ptr ->
            if (!(jsMod._uapmd_audio_device_mgr_get_device_info(handle, index.toInt(), ptr) as Boolean)) null
            else jsDecodeAudioDeviceInfo(ptr)
        }

    override fun open(inputDeviceIndex: Int, outputDeviceIndex: Int, sampleRate: UInt, bufferSize: UInt): AudioIODevice {
        val dev = jsMod._uapmd_audio_device_mgr_open(handle, inputDeviceIndex, outputDeviceIndex, sampleRate.toInt(), bufferSize.toInt()) as Int
        return JsAudioIODevice(dev)
    }
}

// ─── JsAudioIODevice ──────────────────────────────────────────────────────────

class JsAudioIODevice internal constructor(private val handle: Int) : AudioIODevice {
    override val sampleRate: Double   get() = jsMod._uapmd_audio_device_sample_rate(handle) as Double
    override val channels: UInt       get() = (jsMod._uapmd_audio_device_channels(handle) as Int).toUInt()
    override val inputChannels: UInt  get() = (jsMod._uapmd_audio_device_input_channels(handle) as Int).toUInt()
    override val outputChannels: UInt get() = (jsMod._uapmd_audio_device_output_channels(handle) as Int).toUInt()
    override fun start(): Int         = jsMod._uapmd_audio_device_start(handle) as Int
    override fun stop(): Int          = jsMod._uapmd_audio_device_stop(handle) as Int
    override val isPlaying: Boolean   get() = jsMod._uapmd_audio_device_is_playing(handle) as Boolean
}

// ─── JsMidiIODevice ───────────────────────────────────────────────────────────

class JsMidiIODevice internal constructor(internal val handle: Int) : MidiIODevice
