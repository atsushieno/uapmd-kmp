package dev.atsushieno.uapmd

// ─── WasmJsSequencerEngine ────────────────────────────────────────────────────

class WasmJsSequencerEngine internal constructor(
    internal val handle: Int
) : SequencerEngine {

    override val pluginHost: PluginHost
        get() = WasmJsPluginHost(wasmMod.uapmdEnginePluginHost(handle))

    override fun getPluginInstance(instanceId: Int): PluginInstance? {
        val inst = wasmMod.uapmdEngineGetPluginInstance(handle, instanceId)
        return if (inst == 0) null else WasmJsPluginInstance(inst)
    }

    override val functionBlockManager: FunctionBlockManager
        get() = WasmJsFunctionBlockManager(wasmMod.uapmdEngineFunctionBlockManager(handle))

    override val trackCount: UInt
        get() = wasmMod.uapmdEngineTrackCount(handle).toUInt()

    override fun getTrack(index: UInt): SequencerTrack =
        WasmJsSequencerTrack(wasmMod.uapmdEngineGetTrack(handle, index.toInt()))

    override val masterTrack: SequencerTrack
        get() = WasmJsSequencerTrack(wasmMod.uapmdEngineMasterTrack(handle))

    override fun addEmptyTrack(): Int = wasmMod.uapmdEngineAddEmptyTrack(handle)

    override fun addPluginToTrack(
        trackIndex: Int,
        format: String,
        pluginId: String,
        callback: (instanceId: Int, trackIndex: Int, error: String?) -> Unit
    ) {
        val cbId = nextCallbackId()
        pendingAddPluginCallbacks[cbId] = callback
        val fnPtr = makeCFunctionPtr(cbId, "uapmdDispatchAddPlugin", "viii")
        withTwoCStringsKt(format, pluginId) { fmtPtr, idPtr ->
            wasmMod.uapmdEngineAddPluginToTrack(handle, trackIndex, fmtPtr, idPtr, fnPtr, 0)
        }
    }

    override fun removePluginInstance(instanceId: Int): Boolean =
        wasmMod.uapmdEngineRemovePluginInstance(handle, instanceId)

    override fun removeTrack(trackIndex: Int): Boolean =
        wasmMod.uapmdEngineRemoveTrack(handle, trackIndex)

    override fun cleanupEmptyTracks() = wasmMod.uapmdEngineCleanupEmptyTracks(handle)

    override fun findTrackForInstance(instanceId: Int): Int =
        wasmMod.uapmdEngineFindTrackForInstance(handle, instanceId)

    override fun getInstanceGroup(instanceId: Int): UByte =
        wasmMod.uapmdEngineGetInstanceGroup(handle, instanceId).toUByte()

    override fun setInstanceGroup(instanceId: Int, group: UByte): Boolean =
        wasmMod.uapmdEngineSetInstanceGroup(handle, instanceId, group.toInt())

    override fun getTrackLatency(trackIndex: Int): UInt =
        wasmMod.uapmdEngineTrackLatency(handle, trackIndex).toUInt()

    override val masterTrackLatency: UInt
        get() = wasmMod.uapmdEngineMasterTrackLatency(handle).toUInt()

    override fun getTrackRenderLead(trackIndex: Int): UInt =
        wasmMod.uapmdEngineTrackRenderLead(handle, trackIndex).toUInt()

    override val masterTrackRenderLead: UInt
        get() = wasmMod.uapmdEngineMasterTrackRenderLead(handle).toUInt()

    override fun setDefaultChannels(inputChannels: UInt, outputChannels: UInt) =
        wasmMod.uapmdEngineSetDefaultChannels(handle, inputChannels.toInt(), outputChannels.toInt())

    override var sampleRate: Int
        get() = throw UnsupportedOperationException("sampleRate is write-only via the C API")
        set(value) { wasmMod.uapmdEngineSetSampleRate(handle, value) }

    override var offlineRendering: Boolean
        get() = wasmMod.uapmdEngineGetOfflineRendering(handle)
        set(value) { wasmMod.uapmdEngineSetOfflineRendering(handle, value) }

    override fun setActive(active: Boolean) = wasmMod.uapmdEngineSetActive(handle, active)
    override fun setExternalPump(enabled: Boolean) = wasmMod.uapmdEngineSetExternalPump(handle, enabled)

    override val isPlaybackActive: Boolean
        get() = wasmMod.uapmdEngineIsPlaybackActive(handle)

    override var playbackPosition: Long
        get() = wasmMod.uapmdEngineGetPlaybackPosition(handle)
        set(value) { wasmMod.uapmdEngineSetPlaybackPosition(handle, value) }

    override val renderPlaybackPosition: Long
        get() = wasmMod.uapmdEngineRenderPlaybackPosition(handle)

    override fun startPlayback()  = wasmMod.uapmdEngineStartPlayback(handle)
    override fun stopPlayback()   = wasmMod.uapmdEngineStopPlayback(handle)
    override fun pausePlayback()  = wasmMod.uapmdEnginePausePlayback(handle)
    override fun resumePlayback() = wasmMod.uapmdEngineResumePlayback(handle)

    override fun enqueueUmp(instanceId: Int, ump: UIntArray, timestamp: Long) {
        val mod = wasmMod
        val ptr = mod.malloc(ump.size * 4)
        try {
            ump.forEachIndexed { i, v -> mod.setValue(ptr + i * 4, v.toDouble(), "i32") }
            mod.uapmdEngineEnqueueUmp(handle, instanceId, ptr, ump.size, timestamp.toDouble())
        } finally { mod.free(ptr) }
    }

    override fun sendNoteOn(instanceId: Int, note: Int)                  = wasmMod.uapmdEngineSendNoteOn(handle, instanceId, note)
    override fun sendNoteOff(instanceId: Int, note: Int)                 = wasmMod.uapmdEngineSendNoteOff(handle, instanceId, note)
    override fun sendPitchBend(instanceId: Int, value: Float)            = wasmMod.uapmdEngineSendPitchBend(handle, instanceId, value.toDouble())
    override fun sendChannelPressure(instanceId: Int, pressure: Float)   = wasmMod.uapmdEngineSendChannelPressure(handle, instanceId, pressure.toDouble())
    override fun setParameterValue(instanceId: Int, index: Int, value: Double) =
        wasmMod.uapmdEngineSetParameterValue(handle, instanceId, index, value)

    override fun getInputSpectrum(numBars: Int): FloatArray  = FloatArray(numBars) // C API exists; stub for now
    override fun getOutputSpectrum(numBars: Int): FloatArray = FloatArray(numBars)

    override val timeline: TimelineFacade
        get() = WasmJsTimelineFacade(wasmMod.uapmdEngineTimeline(handle))

    override fun renderOffline(
        settings: OfflineRenderSettings,
        progressCallback: ((OfflineRenderProgress) -> Unit)?,
        shouldCancel: (() -> Boolean)?
    ): OfflineRenderResult {
        // Offline rendering requires passing a settings struct and callbacks.
        // For now, return a not-implemented result.
        // TODO: implement uapmd_render_offline via struct allocation
        throw UnsupportedOperationException("renderOffline not yet implemented for WasmJs")
    }
}

// ─── WasmJsSequencerTrack ─────────────────────────────────────────────────────

class WasmJsSequencerTrack internal constructor(
    private val handle: Int
) : SequencerTrack {

    override val graph: PluginGraph
        get() = WasmJsPluginGraph(wasmMod.uapmdTrackGraph(handle))

    override val latencyInSamples: UInt
        get() = wasmMod.uapmdTrackLatencyInSamples(handle).toUInt()

    override val renderLeadInSamples: UInt
        get() = wasmMod.uapmdTrackRenderLeadInSamples(handle).toUInt()

    override val tailLengthInSeconds: Double
        get() = wasmMod.uapmdTrackTailLengthInSeconds(handle)

    override var bypassed: Boolean
        get() = wasmMod.uapmdTrackGetBypassed(handle)
        set(v) { wasmMod.uapmdTrackSetBypassed(handle, v) }

    override var frozen: Boolean
        get() = wasmMod.uapmdTrackGetFrozen(handle)
        set(v) { wasmMod.uapmdTrackSetFrozen(handle, v) }

    override fun getOrderedInstanceIds(): List<Int> {
        val mod = wasmMod
        val count = mod.uapmdTrackOrderedInstanceIdCount(handle)
        if (count <= 0) return emptyList()
        val buf = mod.malloc(count * 4)
        return try {
            mod.uapmdTrackGetOrderedInstanceIds(handle, buf, count)
            List(count) { i -> mod.getValue(buf + i * 4, "i32").toInt() }
        } finally { mod.free(buf) }
    }

    override fun setInstanceGroup(instanceId: Int, group: UByte) =
        wasmMod.uapmdTrackSetInstanceGroup(handle, instanceId, group.toInt())

    override fun getInstanceGroup(instanceId: Int): UByte =
        wasmMod.uapmdTrackGetInstanceGroup(handle, instanceId).toUByte()

    override fun findAvailableGroup(): UByte =
        wasmMod.uapmdTrackFindAvailableGroup(handle).toUByte()

    override fun removeInstance(instanceId: Int) =
        wasmMod.uapmdTrackRemoveInstance(handle, instanceId)
}

// ─── WasmJsDeviceIODispatcher ─────────────────────────────────────────────────

class WasmJsDeviceIODispatcher internal constructor(
    internal val handle: Int
) : DeviceIODispatcher {
    override fun start(): Int        = wasmMod.uapmdDispatcherStart(handle)
    override fun stop(): Int         = wasmMod.uapmdDispatcherStop(handle)
    override val isPlaying: Boolean  get() = wasmMod.uapmdDispatcherIsPlaying(handle)
    override fun clearOutputBuffers() = wasmMod.uapmdDispatcherClearOutputBuffers(handle)
}

// ─── WasmJsRealtimeSequencer ──────────────────────────────────────────────────

class WasmJsRealtimeSequencer internal constructor(
    private val handle: Int
) : RealtimeSequencer {

    override val engine: SequencerEngine
        get() = WasmJsSequencerEngine(wasmMod.uapmdRtSequencerEngine(handle))

    override fun startAudio(): Int  = wasmMod.uapmdRtSequencerStartAudio(handle)
    override fun stopAudio(): Int   = wasmMod.uapmdRtSequencerStopAudio(handle)
    override fun isAudioPlaying(): Int = wasmMod.uapmdRtSequencerIsAudioPlaying(handle)
    override fun clearOutputBuffers() = wasmMod.uapmdRtSequencerClearOutputBuffers(handle)

    override var sampleRate: Int
        get() = wasmMod.uapmdRtSequencerSampleRate(handle)
        set(v) { wasmMod.uapmdRtSequencerSetSampleRate(handle, v) }

    override fun reconfigureAudioDevice(
        inputDeviceIndex: Int, outputDeviceIndex: Int,
        sampleRate: UInt, bufferSize: UInt
    ): Boolean = wasmMod.uapmdRtSequencerReconfigureAudioDevice(
        handle, inputDeviceIndex, outputDeviceIndex, sampleRate.toInt(), bufferSize.toInt()
    )

    override fun close() = wasmMod.uapmdRtSequencerDestroy(handle)
}

// ─── WasmJsAudioDeviceManager ─────────────────────────────────────────────────

class WasmJsAudioDeviceManager internal constructor(
    private val handle: Int
) : AudioDeviceManager {

    override val deviceCount: UInt
        get() = wasmMod.uapmdAudioDeviceMgrDeviceCount(handle).toUInt()

    override fun getDeviceInfo(index: UInt): AudioDeviceInfo? {
        val mod = wasmMod
        val ptr = mod.malloc(24) // sizeof uapmd_audio_device_info_t
        return try {
            if (!mod.uapmdAudioDeviceMgrGetDeviceInfo(handle, index.toInt(), ptr)) null
            else {
                val namePtr = mod.getValue(ptr + 8, "i32").toInt()
                AudioDeviceInfo(
                    directions = AudioIoDirection.fromNative(mod.getValue(ptr, "i32").toInt()),
                    id         = mod.getValue(ptr + 4, "i32").toInt(),
                    name       = if (namePtr != 0) mod.utf8ToString(namePtr) else "",
                    sampleRate = mod.getValue(ptr + 12, "i32").toInt().toUInt(),
                    channels   = mod.getValue(ptr + 16, "i32").toInt().toUInt()
                )
            }
        } finally { mod.free(ptr) }
    }

    override fun open(
        inputDeviceIndex: Int,
        outputDeviceIndex: Int,
        sampleRate: UInt,
        bufferSize: UInt
    ): AudioIODevice {
        val dev = wasmMod.uapmdAudioDeviceMgrOpen(handle, inputDeviceIndex, outputDeviceIndex, sampleRate.toInt(), bufferSize.toInt())
        return WasmJsAudioIODevice(dev)
    }
}

// ─── WasmJsAudioIODevice ─────────────────────────────────────────────────────

class WasmJsAudioIODevice internal constructor(
    private val handle: Int
) : AudioIODevice {
    override val sampleRate: Double    get() = wasmMod.uapmdAudioDeviceSampleRate(handle)
    override val channels: UInt        get() = wasmMod.uapmdAudioDeviceChannels(handle).toUInt()
    override val inputChannels: UInt   get() = wasmMod.uapmdAudioDeviceInputChannels(handle).toUInt()
    override val outputChannels: UInt  get() = wasmMod.uapmdAudioDeviceOutputChannels(handle).toUInt()
    override fun start(): Int          = wasmMod.uapmdAudioDeviceStart(handle)
    override fun stop(): Int           = wasmMod.uapmdAudioDeviceStop(handle)
    override val isPlaying: Boolean    get() = wasmMod.uapmdAudioDeviceIsPlaying(handle)
}

// ─── WasmJsMidiIODevice ───────────────────────────────────────────────────────

class WasmJsMidiIODevice internal constructor(
    internal val handle: Int
) : MidiIODevice
