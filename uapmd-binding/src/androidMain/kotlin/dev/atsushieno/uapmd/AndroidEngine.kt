package dev.atsushieno.uapmd

// ─── AndroidSequencerEngine ──────────────────────────────────────────────────

class AndroidSequencerEngine internal constructor(
    internal val handle: Long
) : SequencerEngine {

    override fun enqueueUmp(instanceId: Int, ump: UIntArray, timestamp: Long) =
        JniBridge.uapmdEngineEnqueueUmp(handle, instanceId, IntArray(ump.size) { ump[it].toInt() }, timestamp)

    override val pluginHost: PluginHost
        get() = AndroidPluginHost(JniBridge.uapmdEnginePluginHost(handle))

    override fun getPluginInstance(instanceId: Int): PluginInstance? {
        val h = JniBridge.uapmdEngineGetPluginInstance(handle, instanceId)
        return if (h == 0L) null else AndroidPluginInstance(h)
    }

    override val functionBlockManager: FunctionBlockManager
        get() = AndroidFunctionBlockManager(JniBridge.uapmdEngineFunctionBlockManager(handle))

    override val trackCount: UInt get() = JniBridge.uapmdEngineTrackCount(handle).toUInt()

    override fun getTrack(index: UInt): SequencerTrack =
        AndroidSequencerTrack(JniBridge.uapmdEngineGetTrack(handle, index.toInt()))

    override val masterTrack: SequencerTrack
        get() = AndroidSequencerTrack(JniBridge.uapmdEngineMasterTrack(handle))

    override fun addEmptyTrack(): Int = JniBridge.uapmdEngineAddEmptyTrack(handle)

    override fun addPluginToTrack(
        trackIndex: Int, format: String, pluginId: String,
        callback: (Int, Int, String?) -> Unit
    ) {
        val cb = object : Any() {
            @Suppress("unused")
            fun invoke(instId: Int, tIdx: Int, error: String?) = callback(instId, tIdx, error)
        }
        JniBridge.uapmdEngineAddPluginToTrack(handle, trackIndex, format, pluginId, cb)
    }

    override fun removePluginInstance(instanceId: Int): Boolean =
        JniBridge.uapmdEngineRemovePluginInstance(handle, instanceId)

    override fun removeTrack(trackIndex: Int): Boolean =
        JniBridge.uapmdEngineRemoveTrack(handle, trackIndex)

    override fun cleanupEmptyTracks() = JniBridge.uapmdEngineCleanupEmptyTracks(handle)

    override fun findTrackForInstance(instanceId: Int): Int =
        JniBridge.uapmdEngineFindTrackForInstance(handle, instanceId)

    override fun getInstanceGroup(instanceId: Int): UByte =
        JniBridge.uapmdEngineGetInstanceGroup(handle, instanceId).toUByte()

    override fun setInstanceGroup(instanceId: Int, group: UByte): Boolean =
        JniBridge.uapmdEngineSetInstanceGroup(handle, instanceId, group.toByte())

    override fun getTrackLatency(trackIndex: Int): UInt =
        JniBridge.uapmdEngineTrackLatency(handle, trackIndex).toUInt()

    override val masterTrackLatency: UInt get() = JniBridge.uapmdEngineMasterTrackLatency(handle).toUInt()

    override fun getTrackRenderLead(trackIndex: Int): UInt =
        JniBridge.uapmdEngineTrackRenderLead(handle, trackIndex).toUInt()

    override val masterTrackRenderLead: UInt get() = JniBridge.uapmdEngineMasterTrackRenderLead(handle).toUInt()

    override fun setDefaultChannels(inputChannels: UInt, outputChannels: UInt) =
        JniBridge.uapmdEngineSetDefaultChannels(handle, inputChannels.toInt(), outputChannels.toInt())

    override var sampleRate: Int
        get() = error("sampleRate getter not available via C API")
        set(value) { JniBridge.uapmdEngineSetSampleRate(handle, value) }

    override var offlineRendering: Boolean
        get() = JniBridge.uapmdEngineGetOfflineRendering(handle)
        set(value) { JniBridge.uapmdEngineSetOfflineRendering(handle, value) }

    override fun setActive(active: Boolean) = JniBridge.uapmdEngineSetActive(handle, active)
    override fun setExternalPump(enabled: Boolean) = JniBridge.uapmdEngineSetExternalPump(handle, enabled)

    override val isPlaybackActive: Boolean get() = JniBridge.uapmdEngineIsPlaybackActive(handle)

    override var playbackPosition: Long
        get() = JniBridge.uapmdEngineGetPlaybackPosition(handle)
        set(value) { JniBridge.uapmdEngineSetPlaybackPosition(handle, value) }

    override val renderPlaybackPosition: Long get() = JniBridge.uapmdEngineRenderPlaybackPosition(handle)

    override fun startPlayback() = JniBridge.uapmdEngineStartPlayback(handle)
    override fun stopPlayback() = JniBridge.uapmdEngineStopPlayback(handle)
    override fun pausePlayback() = JniBridge.uapmdEnginePausePlayback(handle)
    override fun resumePlayback() = JniBridge.uapmdEngineResumePlayback(handle)

    override fun sendNoteOn(instanceId: Int, note: Int) = JniBridge.uapmdEngineSendNoteOn(handle, instanceId, note)
    override fun sendNoteOff(instanceId: Int, note: Int) = JniBridge.uapmdEngineSendNoteOff(handle, instanceId, note)
    override fun sendPitchBend(instanceId: Int, value: Float) = JniBridge.uapmdEngineSendPitchBend(handle, instanceId, value)
    override fun sendChannelPressure(instanceId: Int, pressure: Float) = JniBridge.uapmdEngineSendChannelPressure(handle, instanceId, pressure)
    override fun setParameterValue(instanceId: Int, index: Int, value: Double) =
        JniBridge.uapmdEngineSetParameterValue(handle, instanceId, index, value)

    override fun getInputSpectrum(numBars: Int): FloatArray = JniBridge.uapmdEngineGetInputSpectrum(handle, numBars)
    override fun getOutputSpectrum(numBars: Int): FloatArray = JniBridge.uapmdEngineGetOutputSpectrum(handle, numBars)

    override val timeline: TimelineFacade get() = AndroidTimelineFacade(JniBridge.uapmdEngineTimeline(handle))

    override fun renderOffline(
        settings: OfflineRenderSettings,
        progressCallback: ((OfflineRenderProgress) -> Unit)?,
        shouldCancel: (() -> Boolean)?
    ): OfflineRenderResult {
        val progressCb = progressCallback?.let { fn ->
            object : Any() {
                @Suppress("unused")
                fun invoke(progress: Double, renderedSecs: Double, totalSecs: Double, renderedFrames: Long, totalFrames: Long) =
                    fn(OfflineRenderProgress(progress, renderedSecs, totalSecs, renderedFrames, totalFrames))
            }
        }
        val cancelCb = shouldCancel?.let { fn ->
            object : Any() {
                @Suppress("unused")
                fun invoke(): Boolean = fn()
            }
        }
        val arr = JniBridge.uapmdRenderOffline(
            handle,
            settings.outputPath,
            settings.startSeconds,
            settings.endSeconds ?: 0.0,
            settings.endSeconds != null,
            settings.useContentFallback,
            settings.contentBoundsValid,
            settings.contentStartSeconds,
            settings.contentEndSeconds,
            settings.tailSeconds,
            settings.enableSilenceStop,
            settings.silenceDurationSeconds,
            settings.silenceThresholdDb,
            settings.sampleRate,
            settings.bufferSize.toInt(),
            settings.outputChannels.toInt(),
            settings.umpBufferSize.toInt(),
            progressCb, cancelCb
        )
        return OfflineRenderResult(
            success = arr[0] == "1",
            canceled = arr[1] == "1",
            renderedSeconds = arr[2]?.toDoubleOrNull() ?: 0.0,
            errorMessage = arr[3]
        )
    }
}

// ─── AndroidSequencerTrack ───────────────────────────────────────────────────

class AndroidSequencerTrack internal constructor(
    private val handle: Long
) : SequencerTrack {

    override val graph: PluginGraph get() = AndroidPluginGraph(JniBridge.uapmdTrackGraph(handle))
    override val latencyInSamples: UInt get() = JniBridge.uapmdTrackLatencyInSamples(handle).toUInt()
    override val renderLeadInSamples: UInt get() = JniBridge.uapmdTrackRenderLeadInSamples(handle).toUInt()
    override val tailLengthInSeconds: Double get() = JniBridge.uapmdTrackTailLengthInSeconds(handle)

    override var bypassed: Boolean
        get() = JniBridge.uapmdTrackGetBypassed(handle)
        set(value) { JniBridge.uapmdTrackSetBypassed(handle, value) }

    override var frozen: Boolean
        get() = JniBridge.uapmdTrackGetFrozen(handle)
        set(value) { JniBridge.uapmdTrackSetFrozen(handle, value) }

    override fun getOrderedInstanceIds(): List<Int> = JniBridge.uapmdTrackGetOrderedInstanceIds(handle).toList()

    override fun setInstanceGroup(instanceId: Int, group: UByte) =
        JniBridge.uapmdTrackSetInstanceGroup(handle, instanceId, group.toByte())

    override fun getInstanceGroup(instanceId: Int): UByte =
        JniBridge.uapmdTrackGetInstanceGroup(handle, instanceId).toUByte()

    override fun findAvailableGroup(): UByte =
        JniBridge.uapmdTrackFindAvailableGroup(handle).toUByte()

    override fun removeInstance(instanceId: Int) =
        JniBridge.uapmdTrackRemoveInstance(handle, instanceId)
}

// ─── AndroidAudioDeviceManager ───────────────────────────────────────────────

class AndroidAudioDeviceManager internal constructor(
    private val handle: Long
) : AudioDeviceManager {

    override val deviceCount: UInt get() = JniBridge.uapmdAudioDeviceMgrDeviceCount(handle).toUInt()

    override fun getDeviceInfo(index: UInt): AudioDeviceInfo? {
        val outInts = IntArray(4)
        val outName = arrayOfNulls<String>(1)
        if (!JniBridge.uapmdAudioDeviceMgrGetDeviceInfo(handle, index.toInt(), outInts, outName))
            return null
        return AudioDeviceInfo(
            directions = AudioIoDirection.fromNative(outInts[0]),
            id = outInts[1],
            name = outName[0] ?: "",
            sampleRate = outInts[2].toUInt(),
            channels = outInts[3].toUInt()
        )
    }

    override fun open(inputDeviceIndex: Int, outputDeviceIndex: Int, sampleRate: UInt, bufferSize: UInt): AudioIODevice =
        AndroidAudioIODevice(JniBridge.uapmdAudioDeviceMgrOpen(handle, inputDeviceIndex, outputDeviceIndex, sampleRate.toInt(), bufferSize.toInt()))
}

// ─── AndroidAudioIODevice ────────────────────────────────────────────────────

class AndroidAudioIODevice internal constructor(
    private val handle: Long
) : AudioIODevice {

    override val sampleRate: Double get() = JniBridge.uapmdAudioDeviceSampleRate(handle)
    override val channels: UInt get() = JniBridge.uapmdAudioDeviceChannels(handle).toUInt()
    override val inputChannels: UInt get() = JniBridge.uapmdAudioDeviceInputChannels(handle).toUInt()
    override val outputChannels: UInt get() = JniBridge.uapmdAudioDeviceOutputChannels(handle).toUInt()
    override fun start(): Int = JniBridge.uapmdAudioDeviceStart(handle)
    override fun stop(): Int = JniBridge.uapmdAudioDeviceStop(handle)
    override val isPlaying: Boolean get() = JniBridge.uapmdAudioDeviceIsPlaying(handle)
}

// ─── AndroidMidiIODevice ─────────────────────────────────────────────────────

class AndroidMidiIODevice internal constructor(
    @Suppress("unused") val handle: Long
) : MidiIODevice

// ─── AndroidDeviceIODispatcher ───────────────────────────────────────────────

class AndroidDeviceIODispatcher internal constructor(
    internal val handle: Long
) : DeviceIODispatcher {

    override fun start(): Int = JniBridge.uapmdDispatcherStart(handle)
    override fun stop(): Int = JniBridge.uapmdDispatcherStop(handle)
    override val isPlaying: Boolean get() = JniBridge.uapmdDispatcherIsPlaying(handle)
    override fun clearOutputBuffers() = JniBridge.uapmdDispatcherClearOutputBuffers(handle)
}

// ─── AndroidRealtimeSequencer ────────────────────────────────────────────────

class AndroidRealtimeSequencer internal constructor(
    private val handle: Long
) : RealtimeSequencer {

    override val engine: SequencerEngine
        get() = AndroidSequencerEngine(JniBridge.uapmdRtSequencerEngine(handle))

    override fun startAudio(): Int = JniBridge.uapmdRtSequencerStartAudio(handle)
    override fun stopAudio(): Int = JniBridge.uapmdRtSequencerStopAudio(handle)
    override fun isAudioPlaying(): Int = JniBridge.uapmdRtSequencerIsAudioPlaying(handle)
    override fun clearOutputBuffers() = JniBridge.uapmdRtSequencerClearOutputBuffers(handle)

    override var sampleRate: Int
        get() = JniBridge.uapmdRtSequencerSampleRate(handle)
        set(value) { JniBridge.uapmdRtSequencerSetSampleRate(handle, value) }

    override fun reconfigureAudioDevice(
        inputDeviceIndex: Int, outputDeviceIndex: Int, sampleRate: UInt, bufferSize: UInt
    ): Boolean = JniBridge.uapmdRtSequencerReconfigureAudioDevice(
        handle, inputDeviceIndex, outputDeviceIndex, sampleRate.toInt(), bufferSize.toInt()
    )

    override fun close() = JniBridge.uapmdRtSequencerDestroy(handle)
}
