package dev.atsushieno.uapmd

/**
 * JNI bridge object — every method corresponds to a C function in uapmd_jni.cpp.
 * Handles are jlong (opaque C pointer cast to uintptr_t).
 * Callback objects are plain Kotlin lambdas / SAM interfaces.
 */
object JniBridge {

    init {
        System.loadLibrary("uapmd-jni")
    }

    // ─── PluginInstance ───────────────────────────────────────────────────────

    @JvmStatic external fun uapmdInstanceDisplayName(h: Long): String
    @JvmStatic external fun uapmdInstanceFormatName(h: Long): String
    @JvmStatic external fun uapmdInstancePluginId(h: Long): String
    @JvmStatic external fun uapmdInstanceGetBypassed(h: Long): Boolean
    @JvmStatic external fun uapmdInstanceSetBypassed(h: Long, v: Boolean)
    @JvmStatic external fun uapmdInstanceStartProcessing(h: Long): Int
    @JvmStatic external fun uapmdInstanceStopProcessing(h: Long): Int
    @JvmStatic external fun uapmdInstanceLatencyInSamples(h: Long): Int
    @JvmStatic external fun uapmdInstanceTailLengthInSeconds(h: Long): Double
    @JvmStatic external fun uapmdInstanceRequiresReplacingProcess(h: Long): Boolean
    @JvmStatic external fun uapmdInstanceParameterCount(h: Long): Int
    @JvmStatic external fun uapmdInstanceGetParameterValue(h: Long, idx: Int): Double
    @JvmStatic external fun uapmdInstanceSetParameterValue(h: Long, idx: Int, v: Double)
    @JvmStatic external fun uapmdInstanceGetParameterValueString(h: Long, idx: Int, v: Double): String
    @JvmStatic external fun uapmdInstanceSetPerNoteControllerValue(h: Long, note: Byte, index: Byte, v: Double)
    @JvmStatic external fun uapmdInstanceGetPerNoteControllerValueString(h: Long, note: Byte, index: Byte, v: Double): String
    @JvmStatic external fun uapmdInstancePresetCount(h: Long): Int
    @JvmStatic external fun uapmdInstanceLoadPreset(h: Long, idx: Int)

    /**
     * Fills out-arrays and returns true on success.
     * outIndex[0]    = parameter index
     * outStrings[3]  = {stableId, name, path}
     * outDoubles[3]  = {defaultVal, minVal, maxVal}
     * outBools[3]    = {automatable, hidden, discrete}
     * outNamedCount[0] = number of named values (not filled here — caller must query separately)
     */
    @JvmStatic external fun uapmdInstanceGetParameterMetadata(
        h: Long, idx: Int,
        outIndex: IntArray,
        outStrings: Array<String?>,
        outDoubles: DoubleArray,
        outBools: BooleanArray,
        outNamedCount: IntArray
    ): Boolean

    @JvmStatic external fun uapmdInstanceGetPresetMetadata(
        h: Long, idx: Int,
        outBank: ByteArray,
        outIndex: IntArray,
        outStrings: Array<String?>   // {stableId, name, path}
    ): Boolean

    @JvmStatic external fun uapmdInstanceSaveStateSync(h: Long): ByteArray
    @JvmStatic external fun uapmdInstanceLoadStateSync(h: Long, data: ByteArray)

    /** cb: (state: ByteArray?, error: String?) -> Unit */
    @JvmStatic external fun uapmdInstanceRequestState(h: Long, ctx: Int, includeUi: Boolean, cb: Any)

    /** cb: (error: String?) -> Unit */
    @JvmStatic external fun uapmdInstanceLoadState(h: Long, data: ByteArray, ctx: Int, includeUi: Boolean, cb: Any)

    @JvmStatic external fun uapmdInstanceHasUiSupport(h: Long): Boolean
    /** resizeCb: (width: Int, height: Int) -> Boolean, nullable */
    @JvmStatic external fun uapmdInstanceCreateUi(h: Long, floating: Boolean, parent: Long, resizeCb: Any?): Boolean
    @JvmStatic external fun uapmdInstanceDestroyUi(h: Long)
    @JvmStatic external fun uapmdInstanceShowUi(h: Long): Boolean
    @JvmStatic external fun uapmdInstanceHideUi(h: Long)
    @JvmStatic external fun uapmdInstanceIsUiVisible(h: Long): Boolean
    @JvmStatic external fun uapmdInstanceSetUiSize(h: Long, w: Int, ht: Int): Boolean
    /** Returns int[2]{width, height} or null */
    @JvmStatic external fun uapmdInstanceGetUiSize(h: Long): IntArray?
    @JvmStatic external fun uapmdInstanceCanUiResize(h: Long): Boolean

    // ─── PluginHost ───────────────────────────────────────────────────────────

    @JvmStatic external fun uapmdPluginHostCreate(): Long
    @JvmStatic external fun uapmdPluginHostDestroy(h: Long)
    @JvmStatic external fun uapmdPluginHostCatalogEntryCount(h: Long): Int
    /** Returns String[3]{format, pluginId, displayName} or null */
    @JvmStatic external fun uapmdPluginHostGetCatalogEntry(h: Long, idx: Int): Array<String?>?
    @JvmStatic external fun uapmdPluginHostSaveCatalog(h: Long, path: String)
    @JvmStatic external fun uapmdPluginHostPerformScanning(h: Long, rescan: Boolean)
    @JvmStatic external fun uapmdPluginHostReloadCatalogFromCache(h: Long)
    /** cb: (instanceId: Int, error: String?) -> Unit */
    @JvmStatic external fun uapmdPluginHostCreateInstance(
        h: Long, sr: Int, bs: Int, inCh: Int, outCh: Int, offline: Boolean,
        format: String, pluginId: String, cb: Any
    )
    @JvmStatic external fun uapmdPluginHostDeleteInstance(h: Long, id: Int)
    @JvmStatic external fun uapmdPluginHostGetInstance(h: Long, id: Int): Long
    /** Returns int[] of instance IDs */
    @JvmStatic external fun uapmdPluginHostGetInstanceIds(h: Long): IntArray

    // ─── PluginNode ───────────────────────────────────────────────────────────

    @JvmStatic external fun uapmdNodeInstanceId(h: Long): Int
    @JvmStatic external fun uapmdNodeInstance(h: Long): Long
    @JvmStatic external fun uapmdNodeScheduleEvents(h: Long, ts: Long, data: ByteArray): Boolean
    @JvmStatic external fun uapmdNodeSendAllNotesOff(h: Long)

    // ─── PluginGraph ──────────────────────────────────────────────────────────

    @JvmStatic external fun uapmdGraphCreate(sz: Long): Long
    @JvmStatic external fun uapmdGraphDestroy(h: Long)
    /** deleteCb: () -> Unit, nullable */
    @JvmStatic external fun uapmdGraphAppendNode(g: Long, id: Int, inst: Long, deleteCb: Any?): Int
    @JvmStatic external fun uapmdGraphRemoveNode(h: Long, id: Int): Boolean
    @JvmStatic external fun uapmdGraphPluginCount(h: Long): Int
    @JvmStatic external fun uapmdGraphGetPluginNode(h: Long, id: Int): Long
    /** cb: (instanceId: Int, data: IntArray, sizeInBytes: Int) -> Unit, nullable */
    @JvmStatic external fun uapmdGraphSetEventOutputCallback(h: Long, cb: Any?)
    @JvmStatic external fun uapmdGraphOutputBusCount(h: Long): Int
    @JvmStatic external fun uapmdGraphOutputLatencyInSamples(h: Long, bus: Int): Int
    @JvmStatic external fun uapmdGraphOutputTailLengthInSeconds(h: Long, bus: Int): Double
    @JvmStatic external fun uapmdGraphRenderLeadInSamples(h: Long): Int
    @JvmStatic external fun uapmdGraphMainOutputLatencyInSamples(h: Long): Int
    @JvmStatic external fun uapmdGraphMainOutputTailLengthInSeconds(h: Long): Double

    // ─── MidiIO ───────────────────────────────────────────────────────────────

    /** receiver: (ump: IntArray, timestamp: Long) -> Unit; returns handler ID */
    @JvmStatic external fun uapmdMidiIoAddInputHandler(io: Long, receiver: Any): Long
    @JvmStatic external fun uapmdMidiIoRemoveInputHandler(io: Long, handlerId: Long)
    @JvmStatic external fun uapmdMidiIoSend(io: Long, msgs: IntArray, ts: Long)

    // ─── FunctionBlock ────────────────────────────────────────────────────────

    @JvmStatic external fun uapmdFbMidiIo(h: Long): Long
    @JvmStatic external fun uapmdFbInstanceId(h: Long): Int
    @JvmStatic external fun uapmdFbGetGroup(h: Long): Byte
    @JvmStatic external fun uapmdFbSetGroup(h: Long, g: Byte)
    @JvmStatic external fun uapmdFbDetachOutputMapper(h: Long)
    @JvmStatic external fun uapmdFbInitialize(h: Long)

    // ─── FunctionBlockManager ─────────────────────────────────────────────────

    @JvmStatic external fun uapmdFbmCount(h: Long): Long
    @JvmStatic external fun uapmdFbmCreateDevice(h: Long): Long
    @JvmStatic external fun uapmdFbmGetDeviceByIndex(h: Long, i: Int): Long
    @JvmStatic external fun uapmdFbmGetDeviceForInstance(h: Long, id: Int): Long
    @JvmStatic external fun uapmdFbmDeleteEmptyDevices(h: Long)
    @JvmStatic external fun uapmdFbmDetachAllOutputMappers(h: Long)
    @JvmStatic external fun uapmdFbmClearAllDevices(h: Long)

    // ─── UmpMapper ────────────────────────────────────────────────────────────

    @JvmStatic external fun uapmdUmpInSetParameterValue(h: Long, idx: Int, v: Double)
    @JvmStatic external fun uapmdUmpInGetParameterValue(h: Long, idx: Int): Double
    @JvmStatic external fun uapmdUmpInSetPerNoteControllerValue(h: Long, note: Byte, idx: Byte, v: Double)
    @JvmStatic external fun uapmdUmpInLoadPreset(h: Long, idx: Int)
    @JvmStatic external fun uapmdUmpOutSendParameterValue(h: Long, idx: Int, v: Double)
    @JvmStatic external fun uapmdUmpOutSendPerNoteControllerValue(h: Long, note: Byte, idx: Byte, v: Double)
    @JvmStatic external fun uapmdUmpOutSendPresetIndexChange(h: Long, idx: Int)

    // ─── SequencerEngine ──────────────────────────────────────────────────────

    @JvmStatic external fun uapmdEngineCreate(sr: Int, abs: Int, ubs: Int): Long
    @JvmStatic external fun uapmdEngineDestroy(h: Long)
    @JvmStatic external fun uapmdEngineEnqueueUmp(h: Long, instId: Int, ump: IntArray, ts: Long)
    @JvmStatic external fun uapmdEnginePluginHost(h: Long): Long
    @JvmStatic external fun uapmdEngineGetPluginInstance(h: Long, id: Int): Long
    @JvmStatic external fun uapmdEngineFunctionBlockManager(h: Long): Long
    @JvmStatic external fun uapmdEngineTrackCount(h: Long): Int
    @JvmStatic external fun uapmdEngineGetTrack(h: Long, idx: Int): Long
    @JvmStatic external fun uapmdEngineMasterTrack(h: Long): Long
    @JvmStatic external fun uapmdEngineAddEmptyTrack(h: Long): Int
    /** cb: (instId: Int, trackIndex: Int, error: String?) -> Unit */
    @JvmStatic external fun uapmdEngineAddPluginToTrack(h: Long, trackIdx: Int, format: String, pluginId: String, cb: Any)
    @JvmStatic external fun uapmdEngineRemovePluginInstance(h: Long, id: Int): Boolean
    @JvmStatic external fun uapmdEngineRemoveTrack(h: Long, idx: Int): Boolean
    @JvmStatic external fun uapmdEngineCleanupEmptyTracks(h: Long)
    @JvmStatic external fun uapmdEngineFindTrackForInstance(h: Long, id: Int): Int
    @JvmStatic external fun uapmdEngineGetInstanceGroup(h: Long, id: Int): Byte
    @JvmStatic external fun uapmdEngineSetInstanceGroup(h: Long, id: Int, g: Byte): Boolean
    @JvmStatic external fun uapmdEngineTrackLatency(h: Long, idx: Int): Int
    @JvmStatic external fun uapmdEngineMasterTrackLatency(h: Long): Int
    @JvmStatic external fun uapmdEngineTrackRenderLead(h: Long, idx: Int): Int
    @JvmStatic external fun uapmdEngineMasterTrackRenderLead(h: Long): Int
    @JvmStatic external fun uapmdEngineSetDefaultChannels(h: Long, inCh: Int, outCh: Int)
    @JvmStatic external fun uapmdEngineSetSampleRate(h: Long, sr: Int)
    @JvmStatic external fun uapmdEngineGetOfflineRendering(h: Long): Boolean
    @JvmStatic external fun uapmdEngineSetOfflineRendering(h: Long, v: Boolean)
    @JvmStatic external fun uapmdEngineSetActive(h: Long, v: Boolean)
    @JvmStatic external fun uapmdEngineSetExternalPump(h: Long, v: Boolean)
    @JvmStatic external fun uapmdEngineIsPlaybackActive(h: Long): Boolean
    @JvmStatic external fun uapmdEngineGetPlaybackPosition(h: Long): Long
    @JvmStatic external fun uapmdEngineSetPlaybackPosition(h: Long, v: Long)
    @JvmStatic external fun uapmdEngineRenderPlaybackPosition(h: Long): Long
    @JvmStatic external fun uapmdEngineStartPlayback(h: Long)
    @JvmStatic external fun uapmdEngineStopPlayback(h: Long)
    @JvmStatic external fun uapmdEnginePausePlayback(h: Long)
    @JvmStatic external fun uapmdEngineResumePlayback(h: Long)
    @JvmStatic external fun uapmdEngineSendNoteOn(h: Long, id: Int, note: Int)
    @JvmStatic external fun uapmdEngineSendNoteOff(h: Long, id: Int, note: Int)
    @JvmStatic external fun uapmdEngineSendPitchBend(h: Long, id: Int, v: Float)
    @JvmStatic external fun uapmdEngineSendChannelPressure(h: Long, id: Int, v: Float)
    @JvmStatic external fun uapmdEngineSetParameterValue(h: Long, id: Int, idx: Int, v: Double)
    @JvmStatic external fun uapmdEngineGetInputSpectrum(h: Long, bars: Int): FloatArray
    @JvmStatic external fun uapmdEngineGetOutputSpectrum(h: Long, bars: Int): FloatArray
    @JvmStatic external fun uapmdEngineTimeline(h: Long): Long

    /**
     * Returns String[4]: {success("1"/"0"), canceled("1"/"0"), renderedSeconds, error?}
     * progressCb: (progress, renderedSecs, totalSecs, renderedFrames, totalFrames) -> Unit (nullable)
     * cancelCb:   () -> Boolean (nullable)
     */
    @JvmStatic external fun uapmdRenderOffline(
        h: Long,
        outputPath: String,
        startSecs: Double, endSecs: Double, hasEndSecs: Boolean,
        useContentFallback: Boolean, contentBoundsValid: Boolean,
        contentStartSecs: Double, contentEndSecs: Double,
        tailSecs: Double, enableSilenceStop: Boolean,
        silenceDurSecs: Double, silenceThreshDb: Double,
        sampleRate: Int, bufferSize: Int, outputChannels: Int, umpBufSize: Int,
        progressCb: Any?, cancelCb: Any?
    ): Array<String?>

    // ─── SequencerTrack ───────────────────────────────────────────────────────

    @JvmStatic external fun uapmdTrackGraph(h: Long): Long
    @JvmStatic external fun uapmdTrackLatencyInSamples(h: Long): Int
    @JvmStatic external fun uapmdTrackRenderLeadInSamples(h: Long): Int
    @JvmStatic external fun uapmdTrackTailLengthInSeconds(h: Long): Double
    @JvmStatic external fun uapmdTrackGetBypassed(h: Long): Boolean
    @JvmStatic external fun uapmdTrackGetFrozen(h: Long): Boolean
    @JvmStatic external fun uapmdTrackSetBypassed(h: Long, v: Boolean)
    @JvmStatic external fun uapmdTrackSetFrozen(h: Long, v: Boolean)
    @JvmStatic external fun uapmdTrackGetOrderedInstanceIds(h: Long): IntArray
    @JvmStatic external fun uapmdTrackSetInstanceGroup(h: Long, id: Int, g: Byte)
    @JvmStatic external fun uapmdTrackGetInstanceGroup(h: Long, id: Int): Byte
    @JvmStatic external fun uapmdTrackFindAvailableGroup(h: Long): Byte
    @JvmStatic external fun uapmdTrackRemoveInstance(h: Long, id: Int)

    // ─── TimelineFacade ───────────────────────────────────────────────────────

    /**
     * Returns double[12] or null:
     * [0]=playhead.samples [1]=playhead.beats [2]=isPlaying [3]=loopEnabled
     * [4]=loopStart.samples [5]=loopStart.beats [6]=loopEnd.samples [7]=loopEnd.beats
     * [8]=tempo [9]=timeNum [10]=timeDen [11]=sampleRate
     */
    @JvmStatic external fun uapmdTlGetState(h: Long): DoubleArray?
    @JvmStatic external fun uapmdTlSetTempo(h: Long, t: Double)
    @JvmStatic external fun uapmdTlSetTimeSignature(h: Long, n: Int, d: Int)
    @JvmStatic external fun uapmdTlSetLoop(h: Long, en: Boolean, startSamples: Long, startBeats: Double, endSamples: Long, endBeats: Double)
    @JvmStatic external fun uapmdTlTrackCount(h: Long): Int
    @JvmStatic external fun uapmdTlGetTrack(h: Long, idx: Int): Long
    @JvmStatic external fun uapmdTlMasterTimelineTrack(h: Long): Long
    /** Returns int[3]{clipId, sourceNodeId, success} or null */
    @JvmStatic external fun uapmdTlAddAudioClip(h: Long, trackIdx: Int, posSamples: Long, posBeats: Double, reader: Long, filepath: String): IntArray
    @JvmStatic external fun uapmdTlAddMidiClipFromFile(h: Long, trackIdx: Int, posSamples: Long, posBeats: Double, filepath: String, nrpn: Boolean): IntArray
    @JvmStatic external fun uapmdTlRemoveClip(h: Long, tIdx: Int, cId: Int): Boolean
    /** Returns String[2]{success, error?} */
    @JvmStatic external fun uapmdTlLoadProject(h: Long, path: String): Array<String?>
    /** Returns double[5]{hasContent, firstSample, lastSample, firstSecs, lastSecs} */
    @JvmStatic external fun uapmdTlCalculateContentBounds(h: Long): DoubleArray

    // ─── TimelineTrack (clip data) ────────────────────────────────────────────

    /** Returns the number of clips on this timeline track. */
    @JvmStatic external fun uapmdTtClipCount(h: Long): Int

    /**
     * Fills [outStrings] (size = count*2) with [name, filepath] per clip.
     * Returns double[count*7] where each clip occupies 7 doubles:
     * [clipId, positionSamples, positionBeats, durationSamples, gain, muted, clipType]
     * or null on failure.
     */
    @JvmStatic external fun uapmdTtGetAllClips(h: Long, outStrings: Array<String?>): DoubleArray?

    // ─── AudioDeviceManager ───────────────────────────────────────────────────

    @JvmStatic external fun uapmdAudioDeviceMgrInstance(driver: String?): Long
    @JvmStatic external fun uapmdAudioDeviceMgrDeviceCount(h: Long): Int
    /**
     * outInts[4]={directions, id, sampleRate, channels}
     * outName[1]={name}
     */
    @JvmStatic external fun uapmdAudioDeviceMgrGetDeviceInfo(h: Long, idx: Int, outInts: IntArray, outName: Array<String?>): Boolean
    @JvmStatic external fun uapmdAudioDeviceMgrOpen(h: Long, inIdx: Int, outIdx: Int, sr: Int, bs: Int): Long

    // ─── AudioIODevice ────────────────────────────────────────────────────────

    @JvmStatic external fun uapmdAudioDeviceSampleRate(h: Long): Double
    @JvmStatic external fun uapmdAudioDeviceChannels(h: Long): Int
    @JvmStatic external fun uapmdAudioDeviceInputChannels(h: Long): Int
    @JvmStatic external fun uapmdAudioDeviceOutputChannels(h: Long): Int
    @JvmStatic external fun uapmdAudioDeviceStart(h: Long): Int
    @JvmStatic external fun uapmdAudioDeviceStop(h: Long): Int
    @JvmStatic external fun uapmdAudioDeviceIsPlaying(h: Long): Boolean

    // ─── MidiIODevice ─────────────────────────────────────────────────────────

    @JvmStatic external fun uapmdMidiDeviceInstance(driver: String?): Long

    // ─── DeviceIODispatcher ───────────────────────────────────────────────────

    @JvmStatic external fun uapmdDefaultDeviceIoDispatcher(): Long
    @JvmStatic external fun uapmdDispatcherStart(h: Long): Int
    @JvmStatic external fun uapmdDispatcherStop(h: Long): Int
    @JvmStatic external fun uapmdDispatcherIsPlaying(h: Long): Boolean
    @JvmStatic external fun uapmdDispatcherClearOutputBuffers(h: Long)

    // ─── RealtimeSequencer ────────────────────────────────────────────────────

    @JvmStatic external fun uapmdRtSequencerCreate(bs: Int, ubs: Int, sr: Int, disp: Long): Long
    @JvmStatic external fun uapmdRtSequencerDestroy(h: Long)
    @JvmStatic external fun uapmdRtSequencerEngine(h: Long): Long
    @JvmStatic external fun uapmdRtSequencerStartAudio(h: Long): Int
    @JvmStatic external fun uapmdRtSequencerStopAudio(h: Long): Int
    @JvmStatic external fun uapmdRtSequencerIsAudioPlaying(h: Long): Int
    @JvmStatic external fun uapmdRtSequencerClearOutputBuffers(h: Long)
    @JvmStatic external fun uapmdRtSequencerSampleRate(h: Long): Int
    @JvmStatic external fun uapmdRtSequencerSetSampleRate(h: Long, sr: Int): Boolean
    @JvmStatic external fun uapmdRtSequencerReconfigureAudioDevice(h: Long, inIdx: Int, outIdx: Int, sr: Int, bs: Int): Boolean

    // ─── AudioFileReader ──────────────────────────────────────────────────────

    @JvmStatic external fun uapmdAudioFileReaderCreate(path: String): Long
    @JvmStatic external fun uapmdAudioFileReaderDestroy(h: Long)
    /** Returns long[3]{numFrames, numChannels, sampleRate} or null */
    @JvmStatic external fun uapmdAudioFileReaderGetProperties(h: Long): LongArray?
    @JvmStatic external fun uapmdAudioFileReaderReadFrames(h: Long, startFrame: Long, nFrames: Long, dest: Array<FloatArray>)

    // ─── ScanTool ─────────────────────────────────────────────────────────────

    @JvmStatic external fun uapmdScanToolCreate(): Long
    @JvmStatic external fun uapmdScanToolDestroy(h: Long)
    @JvmStatic external fun uapmdScanToolCatalogEntryCount(h: Long): Int
    @JvmStatic external fun uapmdScanToolFormatCount(h: Long): Int
    @JvmStatic external fun uapmdScanToolGetFormatName(h: Long, idx: Int): String
    @JvmStatic external fun uapmdScanToolGetCacheFile(h: Long): String
    @JvmStatic external fun uapmdScanToolSetCacheFile(h: Long, path: String)
    @JvmStatic external fun uapmdScanToolSaveCache(h: Long)
    @JvmStatic external fun uapmdScanToolSaveCacheTo(h: Long, path: String)
    /**
     * All callback args are nullable Any? with SAM-compatible invoke signatures:
     *   slowStartCb:      (total: Int) -> Unit
     *   bundleStartCb:    (path: String?) -> Unit
     *   bundleCompleteCb: (path: String?) -> Unit
     *   slowCompleteCb:   () -> Unit
     *   errorCb:          (msg: String?) -> Unit
     *   cancelCb:         () -> Boolean
     */
    @JvmStatic external fun uapmdScanToolPerformScanning(
        h: Long, fast: Boolean,
        slowStartCb: Any?, bundleStartCb: Any?, bundleCompleteCb: Any?,
        slowCompleteCb: Any?, errorCb: Any?, cancelCb: Any?
    )
    @JvmStatic external fun uapmdScanToolBlocklistCount(h: Long): Int
    /** Returns String[4]{id, format, pluginId, reason} or null */
    @JvmStatic external fun uapmdScanToolGetBlocklistEntry(h: Long, idx: Int): Array<String?>?
    @JvmStatic external fun uapmdScanToolFlushBlocklist(h: Long)
    @JvmStatic external fun uapmdScanToolUnblockBundle(h: Long, id: String): Boolean
    @JvmStatic external fun uapmdScanToolClearBlocklist(h: Long)
    @JvmStatic external fun uapmdScanToolAddToBlocklist(h: Long, fmt: String, pid: String, reason: String)
    @JvmStatic external fun uapmdScanToolLastScanError(h: Long): String

    // ─── PluginInstancing ─────────────────────────────────────────────────────

    @JvmStatic external fun uapmdInstancingCreate(tool: Long, format: String, pluginId: String): Long
    @JvmStatic external fun uapmdInstancingDestroy(h: Long)
    /** cb: (error: String?) -> Unit */
    @JvmStatic external fun uapmdInstancingMakeAlive(h: Long, cb: Any)
    @JvmStatic external fun uapmdInstancingState(h: Long): Int

    // ─── FormatManager ────────────────────────────────────────────────────────

    @JvmStatic external fun uapmdFormatManagerCreate(): Long
    @JvmStatic external fun uapmdFormatManagerDestroy(h: Long)
    @JvmStatic external fun uapmdFormatManagerFormatCount(h: Long): Int
    @JvmStatic external fun uapmdFormatManagerGetFormatName(h: Long, idx: Int): String

    // ─── Android EventLoop ────────────────────────────────────────────────────
    // Must be called once from the Android main thread before any engine is
    // created.  dispatcher.dispatchTask(token) is called to post a task to
    // the main looper; uapmdRunEventLoopTask(token) then executes it.

    @JvmStatic external fun uapmdSetupAndroidEventLoop(dispatcher: Any)
    @JvmStatic external fun uapmdRunEventLoopTask(token: Long)
}
