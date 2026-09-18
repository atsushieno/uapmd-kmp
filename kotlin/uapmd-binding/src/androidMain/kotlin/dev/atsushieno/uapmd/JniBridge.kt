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

    @JvmStatic external fun uapmdDocumentProviderInit(activity: Any)
    @JvmStatic external fun uapmdDocumentProviderOnActivityResult(requestCode: Int, resultCode: Int, intent: Any?)
    @JvmStatic external fun uapmdDocumentProviderCreate(): Long
    @JvmStatic external fun uapmdDocumentProviderDestroy(h: Long)
    @JvmStatic external fun uapmdDocumentProviderTick(h: Long)
    /** cb: (success: Boolean, path: String?, error: String?) -> Unit */
    @JvmStatic external fun uapmdDocumentProviderPickOpenPath(h: Long, kind: Int, cb: Any)
    /** cb.onResult(success: Boolean, path: String?, error: String?) */
    @JvmStatic external fun uapmdDocumentProviderPickSavePath(h: Long, kind: Int, defaultName: String, cb: Any)
    @JvmStatic external fun uapmdPrepareProjectLoad(filePath: String): Long
    @JvmStatic external fun uapmdPreparedProjectSuccess(h: Long): Boolean
    @JvmStatic external fun uapmdPreparedProjectPath(h: Long): String
    @JvmStatic external fun uapmdPreparedProjectError(h: Long): String
    @JvmStatic external fun uapmdPreparedProjectDestroy(h: Long)

    // ─── PluginInstance ───────────────────────────────────────────────────────

    @JvmStatic external fun uapmdInstanceDisplayName(h: Long): String
    @JvmStatic external fun uapmdInstanceFormatName(h: Long): String
    @JvmStatic external fun uapmdInstancePluginId(h: Long): String
    @JvmStatic external fun uapmdInstanceGetAapUiHostDetails(h: Long): Array<String?>?
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
    @JvmStatic external fun uapmdInstanceGetUiCapabilities(h: Long): BooleanArray
    /** resizeCb: (width: Int, height: Int) -> Boolean, nullable */
    @JvmStatic external fun uapmdInstanceCreateUi(h: Long, floating: Boolean, parent: Long, resizeCb: Any?): Boolean
    @JvmStatic external fun uapmdInstanceCreateUiPresentation(
        h: Long,
        hostKind: Int,
        role: Int,
        parent: Long,
        webContainerId: String?,
        resizeCb: Any?
    ): Long
    @JvmStatic external fun uapmdInstanceDestroyUi(h: Long)
    @JvmStatic external fun uapmdInstanceShowUi(h: Long): Boolean
    @JvmStatic external fun uapmdInstanceHideUi(h: Long)
    @JvmStatic external fun uapmdInstanceIsUiVisible(h: Long): Boolean
    @JvmStatic external fun uapmdInstanceSetUiSize(h: Long, w: Int, ht: Int): Boolean
    /** Returns int[2]{width, height} or null */
    @JvmStatic external fun uapmdInstanceGetUiSize(h: Long): IntArray?
    @JvmStatic external fun uapmdInstanceCanUiResize(h: Long): Boolean
    @JvmStatic external fun uapmdUiPresentationDestroy(h: Long)
    @JvmStatic external fun uapmdUiPresentationShow(h: Long): Boolean
    @JvmStatic external fun uapmdUiPresentationHide(h: Long)
    @JvmStatic external fun uapmdUiPresentationIsVisible(h: Long): Boolean
    @JvmStatic external fun uapmdUiPresentationSetUiSize(h: Long, w: Int, ht: Int): Boolean
    @JvmStatic external fun uapmdUiPresentationGetUiSize(h: Long): IntArray?
    @JvmStatic external fun uapmdUiPresentationCanUiResize(h: Long): Boolean

    // ─── PluginHost ───────────────────────────────────────────────────────────

    @JvmStatic external fun uapmdPluginHostCreate(): Long
    @JvmStatic external fun uapmdPluginHostDestroy(h: Long)
    @JvmStatic external fun uapmdPluginHostCatalogEntryCount(h: Long): Int
    /** Returns String[3]{format, pluginId, displayName} or null */
    @JvmStatic external fun uapmdPluginHostGetCatalogEntry(h: Long, idx: Int): Array<String?>?
    @JvmStatic external fun uapmdPluginHostFormatCount(h: Long): Int
    @JvmStatic external fun uapmdPluginHostGetFormatName(h: Long, idx: Int): String
    @JvmStatic external fun uapmdApplicationDataDirectorySet(path: String)
    @JvmStatic external fun uapmdApplicationDataDirectoryGet(): String
    @JvmStatic external fun uapmdScanToolGetSearchPathSettingsFile(h: Long): String
    @JvmStatic external fun uapmdScanToolLoadSearchPathSettings(h: Long)
    @JvmStatic external fun uapmdScanToolSaveSearchPathSettings(h: Long)
    @JvmStatic external fun uapmdScanToolFormatUsesSearchPaths(h: Long, fi: Int): Boolean
    @JvmStatic external fun uapmdScanToolFormatDefaultSearchPathCount(h: Long, fi: Int): Int
    @JvmStatic external fun uapmdScanToolFormatGetDefaultSearchPath(h: Long, fi: Int, pi: Int): String
    @JvmStatic external fun uapmdScanToolFormatSearchPathCount(h: Long, fi: Int): Int
    @JvmStatic external fun uapmdScanToolFormatGetSearchPath(h: Long, fi: Int, pi: Int): String
    @JvmStatic external fun uapmdScanToolFormatAddSearchPath(h: Long, fi: Int, path: String)
    @JvmStatic external fun uapmdScanToolFormatSetSearchPaths(h: Long, fi: Int, paths: Array<String>)
    @JvmStatic external fun uapmdScanToolFormatGetUseDefaultSearchPaths(h: Long, fi: Int): Boolean
    @JvmStatic external fun uapmdScanToolFormatSetUseDefaultSearchPaths(h: Long, fi: Int, value: Boolean)
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
    @JvmStatic external fun uapmdTrackUnresolvedGraphType(h: Long): String
    @JvmStatic external fun uapmdTrackUnresolvedGraphPayload(h: Long): ByteArray
    @JvmStatic external fun uapmdTrackLatencyInSamples(h: Long): Int
    @JvmStatic external fun uapmdTrackRenderLeadInSamples(h: Long): Int
    @JvmStatic external fun uapmdTrackTailLengthInSeconds(h: Long): Double
    @JvmStatic external fun uapmdEngineMidiRecorder(engine: Long): Long
    @JvmStatic external fun uapmdMidiRecorderStart(rec: Long, trackReferenceId: String, clipId: Int, startSample: Long): Boolean
    @JvmStatic external fun uapmdMidiRecorderStop(rec: Long)
    @JvmStatic external fun uapmdMidiRecorderCancel(rec: Long)
    @JvmStatic external fun uapmdMidiRecorderIsRecording(rec: Long): Boolean
    @JvmStatic external fun uapmdTrackGetGain(h: Long): Double
    @JvmStatic external fun uapmdTrackGetMuted(h: Long): Boolean
    @JvmStatic external fun uapmdTrackGetSolo(h: Long): Boolean
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
    @JvmStatic external fun uapmdTlNewProject(h: Long): Array<String?>

    // ══ Master tempo map ════════════════════════════════════════════════════

    @JvmStatic external fun uapmdTlMasterTempoMap(h: Long): Long
    @JvmStatic external fun uapmdTempoMapHasTempoData(h: Long): Boolean
    @JvmStatic external fun uapmdTempoMapIsEmpty(h: Long): Boolean
    @JvmStatic external fun uapmdTempoMapSecondsToBeats(h: Long, seconds: Double): Double
    @JvmStatic external fun uapmdTempoMapBeatsToSeconds(h: Long, beats: Double): Double
    @JvmStatic external fun uapmdTempoMapEffectiveSignatureCount(h: Long): Int
    /** double[4] {startBeat, endBeat, numerator, denominator}, or null. */
    @JvmStatic external fun uapmdTempoMapGetEffectiveSignature(h: Long, index: Int): DoubleArray?
    @JvmStatic external fun uapmdTempoMapBarLengthBeats(numerator: Int, denominator: Int): Double
    /** Returns double[5]{hasContent, firstSample, lastSample, firstSecs, lastSecs} */
    @JvmStatic external fun uapmdTlCalculateContentBounds(h: Long): DoubleArray
    @JvmStatic external fun uapmdTlGetClipMidiNotes(h: Long, trackIdx: Int, clipId: Int): DoubleArray?
    @JvmStatic external fun uapmdTlSetTimelineChangedCallback(h: Long, callback: Runnable?)

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

    /** cb.invoke(success: Boolean, error: String?, importedTrackCount: Int) */
    @JvmStatic external fun uapmdAppImportMidiTracksFromFile(app: Long, filepath: String, cb: Any)
    @JvmStatic external fun uapmdEngineTrackFreezePolicy(engine: Long, trackIndex: Int): Int
    @JvmStatic external fun uapmdEngineTrackFreezeState(engine: Long, trackIndex: Int): Int
    /** [progress, renderedSeconds, totalSeconds, renderedFrames, totalFrames], or null when idle. */
    @JvmStatic external fun uapmdEngineTrackFreezeRenderProgress(engine: Long, trackIndex: Int): DoubleArray?
    @JvmStatic external fun uapmdEngineIsTrackBusy(engine: Long, trackIndex: Int): Boolean
    @JvmStatic external fun uapmdTtChannelCount(tt: Long): Int
    @JvmStatic external fun uapmdAudioFileReaderCreate(path: String): Long
    @JvmStatic external fun uapmdAudioFileReaderCreateSilent(numFrames: Long, numChannels: Int, sampleRate: Int): Long
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

    // ══ Project history: ProjectCommandManager ══════════════════════════════
    //

    @JvmStatic external fun uapmdCommandManagerGetState(h: Long): Array<Any>?
    @JvmStatic external fun uapmdCommandManagerUndo(h: Long, cb: Any?)
    @JvmStatic external fun uapmdCommandManagerRedo(h: Long, cb: Any?)
    @JvmStatic external fun uapmdCommandManagerBeginStep(h: Long, description: String, origin: Int, batching: Int): Array<Any>?
    @JvmStatic external fun uapmdCommandManagerEndStep(h: Long, cb: Any?)
    @JvmStatic external fun uapmdCommandManagerCancelStep(h: Long, cb: Any?)
    @JvmStatic external fun uapmdCommandManagerBeginGesture(h: Long, description: String, origin: Int, batching: Int): Array<Any>?
    @JvmStatic external fun uapmdCommandManagerEndGesture(h: Long, cb: Any?)
    @JvmStatic external fun uapmdCommandManagerCancelGesture(h: Long, cb: Any?)
    @JvmStatic external fun uapmdCommandManagerMarkSaved(h: Long): Boolean
    @JvmStatic external fun uapmdCommandManagerMarkStateSaved(h: Long, stateId: Long): Boolean
    @JvmStatic external fun uapmdCommandManagerClear(h: Long, markSaved: Boolean): Boolean
    @JvmStatic external fun uapmdCommandManagerSetMaximumHistorySize(h: Long, bytes: Long): Boolean
    @JvmStatic external fun uapmdCommandManagerShutdown(h: Long)

    // ══ Project history: ProjectCommands ════════════════════════════════════
    //
    // Marker lists travel as parallel arrays: strings[i*4] = {markerId,
    // referenceClipId, referenceMarkerId, name}, numbers[i] = offset,
    // refTypes[i] = referenceType. Warps use strings[i*2] / numbers[i*2].

    @JvmStatic external fun uapmdCommandsHistory(h: Long): Long
    @JvmStatic external fun uapmdCommandsSetClipEnabled(h: Long, t: Int, c: Int, v: Boolean, o: Int): Boolean
    @JvmStatic external fun uapmdCommandsSetClipAnchor(h: Long, t: Int, c: Int, type: Int, refId: String, offset: Double, o: Int): Boolean
    @JvmStatic external fun uapmdCommandsSetClipGain(h: Long, t: Int, c: Int, v: Double, o: Int): Boolean
    @JvmStatic external fun uapmdCommandsSetClipMuted(h: Long, t: Int, c: Int, v: Boolean, o: Int): Boolean
    @JvmStatic external fun uapmdCommandsResizeClip(h: Long, t: Int, c: Int, v: Long, o: Int): Boolean
    @JvmStatic external fun uapmdCommandsSetClipName(h: Long, t: Int, c: Int, v: String, o: Int): Boolean
    @JvmStatic external fun uapmdCommandsSetClipFilepath(h: Long, t: Int, c: Int, v: String, o: Int): Boolean
    @JvmStatic external fun uapmdCommandsSetClipNeedsFileSave(h: Long, t: Int, c: Int, v: Boolean, o: Int): Boolean
    @JvmStatic external fun uapmdCommandsSetClipMarkers(h: Long, t: Int, c: Int, strings: Array<String?>, numbers: DoubleArray, refTypes: IntArray, o: Int): Boolean
    @JvmStatic external fun uapmdCommandsSetClipAudioWarps(h: Long, t: Int, c: Int, strings: Array<String?>, numbers: DoubleArray, refTypes: IntArray, o: Int): Boolean
    @JvmStatic external fun uapmdCommandsSetTrackGain(h: Long, t: Int, v: Double, o: Int): Boolean
    @JvmStatic external fun uapmdCommandsSetTrackMuted(h: Long, t: Int, v: Boolean, o: Int): Boolean
    @JvmStatic external fun uapmdCommandsSetTrackSolo(h: Long, t: Int, v: Boolean, o: Int): Boolean
    @JvmStatic external fun uapmdCommandsSetTrackBypassed(h: Long, t: Int, v: Boolean, o: Int): Boolean
    @JvmStatic external fun uapmdCommandsSetTrackFreezePolicyEnabled(h: Long, t: Int, v: Boolean, o: Int): Boolean
    @JvmStatic external fun uapmdCommandsSetPluginBypassed(h: Long, id: Int, v: Boolean, o: Int): Boolean
    @JvmStatic external fun uapmdCommandsSetPluginParameterValue(h: Long, id: Int, idx: Int, v: Double, o: Int): Boolean
    @JvmStatic external fun uapmdCommandsSetPluginPerNoteControllerValue(
        h: Long, id: Int, contextType: Int, note: Int, channel: Int, group: Int, extra: Int, idx: Int, v: Double, o: Int
    ): Boolean
    @JvmStatic external fun uapmdCommandsSetPluginGroup(h: Long, id: Int, g: Byte, o: Int): Boolean
    @JvmStatic external fun uapmdCommandsSetMasterTrackMarkers(h: Long, strings: Array<String?>, numbers: DoubleArray, refTypes: IntArray, o: Int): Boolean
    @JvmStatic external fun uapmdCommandsAddDeviceInputToTrack(h: Long, t: Int, nodeId: Int, channels: IntArray, o: Int): Boolean
    @JvmStatic external fun uapmdCommandsSetDeviceInputChannels(h: Long, t: Int, nodeId: Int, channels: IntArray, o: Int): Boolean
    @JvmStatic external fun uapmdCommandsRemoveDeviceInputFromTrack(h: Long, t: Int, nodeId: Int, o: Int): Boolean
    /** `ints` = {busType, srcType, srcInstanceId, srcBusIndex, dstType, dstInstanceId, dstBusIndex}; `strings` = {srcNodeId, dstNodeId}. */
    @JvmStatic external fun uapmdCommandsConnectTrackGraph(h: Long, t: Int, connectionId: Long, ints: IntArray, strings: Array<String?>, o: Int): Boolean
    @JvmStatic external fun uapmdCommandsDisconnectTrackGraphConnection(h: Long, t: Int, connectionId: Long, o: Int): Boolean
    @JvmStatic external fun uapmdCommandsLastGraphError(): String
    @JvmStatic external fun uapmdCommandsReplaceTrackGraphType(h: Long, t: Int, graphTypeId: String, bufferSize: Long, o: Int): Boolean
    /** `ints` = {playbackCompensationMode, inputMonitoringPolicy}; `strings` = {implementationId, key, value, ...}. */
    @JvmStatic external fun uapmdCommandsSetLatencyCompensationSettings(h: Long, ints: IntArray, monitored: IntArray, armed: IntArray, strings: Array<String?>, o: Int): Boolean

    // ══ Project history: ProjectAddressBook ═════════════════════════════════

    @JvmStatic external fun uapmdAddressesTimelineTrack(h: Long, refId: String): Long
    @JvmStatic external fun uapmdAddressesSequencerTrack(h: Long, refId: String): Long
    @JvmStatic external fun uapmdAddressesTrackIndex(h: Long, refId: String): Int
    @JvmStatic external fun uapmdAddressesClipId(h: Long, trackRef: String, clipRef: String): Int
    @JvmStatic external fun uapmdAddressesPluginInstanceId(h: Long, trackRef: String, nodeId: String): Int
    @JvmStatic external fun uapmdAddressesTrackReferenceId(h: Long, trackIndex: Int): String?
    @JvmStatic external fun uapmdAddressesClipAddress(h: Long, trackIndex: Int, clipId: Int): Array<String>?
    @JvmStatic external fun uapmdAddressesPluginAddress(h: Long, instanceId: Int): Array<String>?

    // ══ Fragments ═══════════════════════════════════════════════════════════

    @JvmStatic external fun uapmdClipFragmentDestroy(h: Long)
    @JvmStatic external fun uapmdClipFragmentIsMidi(h: Long): Boolean
    /** Fills outStrings[0..1] = {name, filepath}; returns the 7 numeric clip fields. */
    @JvmStatic external fun uapmdClipFragmentGetClip(h: Long, outStrings: Array<String?>): DoubleArray?
    @JvmStatic external fun uapmdClipFragmentGetUmpEvents(h: Long): IntArray
    @JvmStatic external fun uapmdClipFragmentGetUmpTickTimestamps(h: Long): LongArray
    @JvmStatic external fun uapmdClipFragmentExtensionStateCount(h: Long): Int
    @JvmStatic external fun uapmdClipFragmentExtensionStateKey(h: Long, index: Int): String
    @JvmStatic external fun uapmdClipFragmentExtensionStateData(h: Long, index: Int): ByteArray

    @JvmStatic external fun uapmdTrackFragmentDestroy(h: Long)
    @JvmStatic external fun uapmdTrackFragmentReferenceId(h: Long): String
    @JvmStatic external fun uapmdTrackFragmentVolume(h: Long): Double
    @JvmStatic external fun uapmdTrackFragmentMuted(h: Long): Boolean
    @JvmStatic external fun uapmdTrackFragmentSolo(h: Long): Boolean
    @JvmStatic external fun uapmdTrackFragmentGraphType(h: Long): String
    @JvmStatic external fun uapmdTrackFragmentGraphBytes(h: Long): ByteArray
    @JvmStatic external fun uapmdTrackFragmentClipCount(h: Long): Int
    @JvmStatic external fun uapmdTrackFragmentGetClip(h: Long, index: Int): Long
    @JvmStatic external fun uapmdTrackFragmentPluginCount(h: Long): Int
    /** Fills outStrings[0..3] = {nodeId, pluginId, format, displayName}; returns the plug-in state. */
    @JvmStatic external fun uapmdTrackFragmentGetPlugin(h: Long, index: Int, outStrings: Array<String?>): ByteArray?
    @JvmStatic external fun uapmdTrackFragmentPluginGroupIndex(h: Long, index: Int): Int

    // ══ TimelineFacade history accessors and undoable mutations ═════════════

    @JvmStatic external fun uapmdTlCommands(h: Long): Long
    @JvmStatic external fun uapmdTlHistory(h: Long): Long
    @JvmStatic external fun uapmdTlAddresses(h: Long): Long
    @JvmStatic external fun uapmdTlBeginDocumentTransaction(h: Long)
    @JvmStatic external fun uapmdTlEndDocumentTransaction(h: Long)
    @JvmStatic external fun uapmdTlRemoveClipWithOrigin(h: Long, t: Int, c: Int, o: Int): Boolean
    @JvmStatic external fun uapmdTlClearClipsFromTrack(h: Long, t: Int, o: Int): Boolean
    @JvmStatic external fun uapmdTlClipEnabled(h: Long, t: Int, c: Int): Boolean
    @JvmStatic external fun uapmdTlReplaceMidiClipContent(h: Long, t: Int, c: Int, umpEvents: IntArray?, tickTimestamps: LongArray?, o: Int): Boolean
    @JvmStatic external fun uapmdTlReplaceAudioClipContent(
        h: Long, t: Int, c: Int, filepath: String,
        markerStrings: Array<String?>, markerNumbers: DoubleArray, markerTypes: IntArray,
        warpStrings: Array<String?>, warpNumbers: DoubleArray, warpTypes: IntArray,
        masterStrings: Array<String?>, masterNumbers: DoubleArray, masterTypes: IntArray, o: Int
    ): Boolean
    @JvmStatic external fun uapmdTlCaptureClipFragment(h: Long, t: Int, c: Int): Long
    /** Fills outStrings[0] with the error; returns {clipId, sourceNodeId, success}. */
    @JvmStatic external fun uapmdTlAttachClipFragment(h: Long, t: Int, fragment: Long, idPolicy: Int, outStrings: Array<String?>): IntArray
    @JvmStatic external fun uapmdTlPasteClipFragment(h: Long, t: Int, fragment: Long, outStrings: Array<String?>): IntArray
    /** cb: (fragmentHandle: Long, error: String?) -> Unit */
    @JvmStatic external fun uapmdTlCaptureTrackFragment(h: Long, t: Int, cb: Any)
    /** cb: (trackIndex: Int, error: String?) -> Unit */
    @JvmStatic external fun uapmdTlAttachTrackFragment(
        h: Long, fragment: Long, idPolicy: Int, insertionIndex: Int,
        includePlugins: Boolean, includePluginState: Boolean, includeClips: Boolean, cb: Any
    )
    @JvmStatic external fun uapmdTlAddEmptyTrackUndoable(h: Long, o: Int, cb: Any)
    @JvmStatic external fun uapmdTlRemoveTrackUndoable(h: Long, t: Int, o: Int, cb: Any)
    @JvmStatic external fun uapmdTlRecordTrackAddition(h: Long, t: Int, o: Int, cb: Any)
    @JvmStatic external fun uapmdTlSetPluginState(h: Long, id: Int, state: ByteArray?, o: Int, cb: Any?)
    @JvmStatic external fun uapmdTlLoadPluginPreset(h: Long, id: Int, presetIndex: Int, o: Int, cb: Any?)
    @JvmStatic external fun uapmdTlRecordPluginInstanceAddition(h: Long, id: Int, o: Int, cb: Any?)
    @JvmStatic external fun uapmdTlRemovePluginInstanceUndoable(h: Long, id: Int, o: Int, cb: Any?)
    @JvmStatic external fun uapmdTlHasPendingPluginMutations(h: Long): Boolean

    // ══ Engine dirty state, master markers, addin extension points ══════════

    @JvmStatic external fun uapmdEngineIsProjectDirty(h: Long): Boolean
    @JvmStatic external fun uapmdEngineIsTrackDirty(h: Long, t: Int): Boolean
    @JvmStatic external fun uapmdEngineMarkTrackDirty(h: Long, t: Int, dirty: Boolean)
    @JvmStatic external fun uapmdEngineClearTrackDirtyState(h: Long)
    @JvmStatic external fun uapmdEngineMasterMarkerCount(h: Long): Int
    @JvmStatic external fun uapmdEngineGetMasterMarkers(h: Long, outStrings: Array<String?>, outTypes: IntArray): DoubleArray
    @JvmStatic external fun uapmdEngineSetMasterMarkers(h: Long, strings: Array<String?>, numbers: DoubleArray, refTypes: IntArray)
    @JvmStatic external fun uapmdEngineRegisterAddinExtensionPoints(engine: Long, mgr: Long)

    // ══ AddinManager ════════════════════════════════════════════════════════

    @JvmStatic external fun uapmdAddinManagerCreate(): Long
    @JvmStatic external fun uapmdAddinManagerDestroy(h: Long)
    @JvmStatic external fun uapmdAddinManagerInitialize(h: Long)
    @JvmStatic external fun uapmdAddinManagerSetEnabled(h: Long, packageId: String, addinId: String, enabled: Boolean): Boolean
    @JvmStatic external fun uapmdAddinManagerShutdown(h: Long)
    @JvmStatic external fun uapmdAddinManagerDirectoryCount(h: Long): Int
    @JvmStatic external fun uapmdAddinManagerGetDirectory(h: Long, index: Int): String
    @JvmStatic external fun uapmdAddinManagerAddinCount(h: Long): Int
    /** Fills outStrings[0..5] = {packageId, addinId, name, path, libraryPath, message}; returns {builtIn, state}. */
    @JvmStatic external fun uapmdAddinManagerGetAddin(h: Long, index: Int, outStrings: Array<String?>): IntArray?
    @JvmStatic external fun uapmdAddinManagerLastError(h: Long): String
    @JvmStatic external fun uapmdAddinSupportsDynamicLoading(): Boolean

    // ══ Addin host registries ═══════════════════════════════════════════════
    //
    // Command info comes back as int[2] {order, enabled} with the caller's
    // String[2] filled with {id, title}, matching the addin-info getter.

    @JvmStatic external fun uapmdCommandRegistryCreate(): Long
    @JvmStatic external fun uapmdCommandRegistryDestroy(h: Long)
    @JvmStatic external fun uapmdAddinManagerRegisterCommandRegistry(mgr: Long, reg: Long)
    @JvmStatic external fun uapmdCommandRegistryCount(h: Long): Int
    @JvmStatic external fun uapmdCommandRegistryGet(h: Long, index: Int, outStrings: Array<String?>): IntArray?
    @JvmStatic external fun uapmdCommandRegistryInvoke(h: Long, index: Int): Boolean
    @JvmStatic external fun uapmdCommandRegistryInvokeById(h: Long, id: String): Boolean

    @JvmStatic external fun uapmdClipCommandRegistryCreate(): Long
    @JvmStatic external fun uapmdClipCommandRegistryDestroy(h: Long)
    @JvmStatic external fun uapmdAddinManagerRegisterClipCommandRegistry(mgr: Long, reg: Long)
    @JvmStatic external fun uapmdClipCommandRegistryCount(h: Long): Int
    @JvmStatic external fun uapmdClipCommandRegistryGet(h: Long, index: Int, outStrings: Array<String?>): IntArray?
    @JvmStatic external fun uapmdClipCommandRegistryAppliesTo(h: Long, index: Int, t: Int, clipId: Int, midi: Boolean, master: Boolean): Boolean
    @JvmStatic external fun uapmdClipCommandRegistryEnabled(h: Long, index: Int, t: Int, clipId: Int, midi: Boolean, master: Boolean): Boolean
    @JvmStatic external fun uapmdClipCommandRegistryInvoke(h: Long, index: Int, t: Int, clipId: Int, midi: Boolean, master: Boolean): Boolean

    @JvmStatic external fun uapmdClipEditorRegistryCreate(): Long
    @JvmStatic external fun uapmdClipEditorRegistryDestroy(h: Long)
    @JvmStatic external fun uapmdAddinManagerRegisterClipEditorRegistry(mgr: Long, reg: Long)
    @JvmStatic external fun uapmdClipEditorRegistryCount(h: Long): Int
    @JvmStatic external fun uapmdClipEditorRegistryGet(h: Long, index: Int, outStrings: Array<String?>): Boolean

    @JvmStatic external fun uapmdStemSeparatorRegistryCreate(): Long
    @JvmStatic external fun uapmdStemSeparatorRegistryDestroy(h: Long)
    @JvmStatic external fun uapmdAddinManagerRegisterStemSeparatorRegistry(mgr: Long, reg: Long)
    @JvmStatic external fun uapmdStemSeparatorRegistryCount(h: Long): Int
    /** Fills outStrings with {id, name, modelFileLabel}; returns the extension count, or -1. */
    @JvmStatic external fun uapmdStemSeparatorRegistryGet(h: Long, index: Int, outStrings: Array<String?>): Int
    @JvmStatic external fun uapmdStemSeparatorRegistryGetModelExtension(h: Long, index: Int, extensionIndex: Int): String

    /**
     * Returns int[2] {success, canceled}, fills outCounts with
     * {warningCount, stemCount}, and lays outStrings out as
     * {error, warnings..., (stemName, path, displayName)...}. Call once with a
     * short array to learn the counts, then again with one sized to them.
     */
    @JvmStatic external fun uapmdImportAudioFile(
        h: Long, separatorId: String, filepath: String, outputDirectory: String, modelPath: String?,
        progress: Any?, outStrings: Array<String?>?, outCounts: IntArray
    ): IntArray

    // ── AppModel / TransportController ──────────────────────────────────────

    @JvmStatic external fun uapmdAppInstantiate()
    @JvmStatic external fun uapmdAppInstance(): Long
    @JvmStatic external fun uapmdAppCleanup()

    @JvmStatic external fun uapmdAppSequencer(app: Long): Long
    @JvmStatic external fun uapmdAppTransport(app: Long): Long
    @JvmStatic external fun uapmdAppSampleRate(app: Long): Int
    @JvmStatic external fun uapmdAppTrackCount(app: Long): Int

    @JvmStatic external fun uapmdAppIsScanning(app: Long): Boolean
    @JvmStatic external fun uapmdAppIsAudioEngineEnabled(app: Long): Boolean
    @JvmStatic external fun uapmdAppSetAudioEngineEnabled(app: Long, enabled: Boolean)
    @JvmStatic external fun uapmdAppToggleAudioEngine(app: Long)
    @JvmStatic external fun uapmdAppUpdateAudioDeviceSettings(app: Long, sampleRate: Int, bufferSize: Int)
    @JvmStatic external fun uapmdAppSetAutoBufferSizeEnabled(app: Long, enabled: Boolean)
    @JvmStatic external fun uapmdAppAutoBufferSizeEnabled(app: Long): Boolean

    @JvmStatic external fun uapmdAppNotifyUiReady(app: Long)
    @JvmStatic external fun uapmdAppNotifyPersistentStorageReady(app: Long)

    @JvmStatic external fun uapmdTransportIsPlaying(tc: Long): Boolean
    @JvmStatic external fun uapmdTransportIsPaused(tc: Long): Boolean
    @JvmStatic external fun uapmdTransportIsRecording(tc: Long): Boolean
    @JvmStatic external fun uapmdTransportGetVolume(tc: Long): Float
    @JvmStatic external fun uapmdTransportSetVolume(tc: Long, volume: Float)
    @JvmStatic external fun uapmdTransportPlay(tc: Long)
    @JvmStatic external fun uapmdTransportStop(tc: Long)
    @JvmStatic external fun uapmdTransportPause(tc: Long)
    @JvmStatic external fun uapmdTransportResume(tc: Long)
    @JvmStatic external fun uapmdTransportRecord(tc: Long)

    @JvmStatic external fun uapmdAppPerformPluginScanning(app: Long, forceRescan: Boolean, request: Int, remoteTimeoutSeconds: Double, requireFastScanning: Boolean)
    @JvmStatic external fun uapmdAppCancelPluginScanning(app: Long)
    /** Returns Object[]{ int[3]{running, processed, total}, String currentBundle }. */
    @JvmStatic external fun uapmdAppSlowScanProgress(app: Long): Array<Any>?
    @JvmStatic external fun uapmdAppLastPluginScanError(app: Long): String?
    @JvmStatic external fun uapmdAppGenerateScanReport(app: Long): String
    @JvmStatic external fun uapmdAppClearPluginBlocklist(app: Long)
    @JvmStatic external fun uapmdAppBlocklistCount(app: Long): Int
    @JvmStatic external fun uapmdAppRefreshMasterTempoMap(app: Long): Double
    /** Packed [seconds, tick, bpm] per point. */
    @JvmStatic external fun uapmdAppGetMasterTempoPoints(app: Long): DoubleArray?
    /** Packed [seconds, tick, numerator, denominator] per point. */
    @JvmStatic external fun uapmdAppGetMasterTimeSignatures(app: Long): DoubleArray?
    /** Returns [id, format, pluginId, reason] or null. */
    @JvmStatic external fun uapmdAppGetBlocklistEntry(app: Long, index: Int): Array<String>?
    @JvmStatic external fun uapmdAppUnblockPlugin(app: Long, entryId: String): Boolean

    /** cb: (trackIndex: Int, error: String?) -> Unit */
    @JvmStatic external fun uapmdAppIsTrackMuted(app: Long, trackIndex: Int): Boolean
    @JvmStatic external fun uapmdAppIsTrackSolo(app: Long, trackIndex: Int): Boolean
    @JvmStatic external fun uapmdAppSetTrackMuted(app: Long, trackIndex: Int, muted: Boolean): Boolean
    @JvmStatic external fun uapmdAppSetTrackSolo(app: Long, trackIndex: Int, solo: Boolean): Boolean
    // ══ JS runtime and MCP ══════════════════════════════════════════════════
    //
    // evaluate() answers Object[]{ boolean[1] success, String? json, String? error }.
    // The resolver is an object with invoke(String): String?, as the other
    // callbacks here are, so the descriptor the native side looks up is exact.

    @JvmStatic external fun uapmdJsRuntimeCreate(): Long
    @JvmStatic external fun uapmdJsRuntimeDestroy(rt: Long)
    @JvmStatic external fun uapmdJsRuntimeEnsureApiBootstrapped(rt: Long): Boolean
    @JvmStatic external fun uapmdJsRuntimeReinitialize(rt: Long)
    @JvmStatic external fun uapmdJsRuntimeEvaluate(rt: Long, code: String, resolver: Any?): Array<Any?>
    @JvmStatic external fun uapmdJsRuntimeRegisterParameterListener(rt: Long, instanceId: Int)
    @JvmStatic external fun uapmdJsRuntimeUnregisterParameterListener(rt: Long, instanceId: Int)
    @JvmStatic external fun uapmdJsRuntimeRegisterAllParameterListeners(rt: Long)
    @JvmStatic external fun uapmdJsRuntimeUnregisterAllParameterListeners(rt: Long)
    @JvmStatic external fun uapmdJsRuntimeRegisterMetadataListener(rt: Long, instanceId: Int)
    @JvmStatic external fun uapmdJsRuntimeUnregisterMetadataListener(rt: Long, instanceId: Int)
    @JvmStatic external fun uapmdJsRuntimeRegisterAllMetadataListeners(rt: Long)
    @JvmStatic external fun uapmdJsRuntimeUnregisterAllMetadataListeners(rt: Long)

    @JvmStatic external fun uapmdMcpIsSupported(): Boolean
    @JvmStatic external fun uapmdMcpHasHttpServer(): Boolean
    @JvmStatic external fun uapmdMcpServerCreate(port: Int): Long
    @JvmStatic external fun uapmdMcpClientCreate(relayUrl: String, autoReconnect: Boolean): Long
    @JvmStatic external fun uapmdMcpServerDestroy(mcp: Long)
    @JvmStatic external fun uapmdMcpServerStart(mcp: Long)
    @JvmStatic external fun uapmdMcpServerStop(mcp: Long)
    @JvmStatic external fun uapmdMcpServerMode(mcp: Long): Int
    @JvmStatic external fun uapmdMcpServerConnectionState(mcp: Long): Int
    @JvmStatic external fun uapmdMcpServerPort(mcp: Long): Int
    @JvmStatic external fun uapmdMcpServerStatusMessage(mcp: Long): String
    @JvmStatic external fun uapmdMcpServerProcessMainThreadQueue(mcp: Long)

    @JvmStatic external fun uapmdAppAddTrack(app: Long, cb: Any)
    /** cb: (trackIndex: Int, error: String?) -> Unit */
    @JvmStatic external fun uapmdAppRemoveTrack(app: Long, trackIndex: Int, cb: Any)
    /** cb: (error: String?) -> Unit */
    @JvmStatic external fun uapmdAppRemoveAllTracks(app: Long, cb: Any)

    @JvmStatic external fun uapmdAppTimelineTrackCount(app: Long): Int
    @JvmStatic external fun uapmdAppGetTimelineTrack(app: Long, index: Int): Long
    @JvmStatic external fun uapmdAppMasterTimelineTrack(app: Long): Long
    @JvmStatic external fun uapmdAppGetTimelineState(app: Long): DoubleArray?

    @JvmStatic external fun uapmdAppGetHistoryState(app: Long): Array<Any>?
    /** cb: (error: String?) -> Unit */
    @JvmStatic external fun uapmdAppUndo(app: Long, cb: Any?)
    /** cb: (error: String?) -> Unit */
    @JvmStatic external fun uapmdAppRedo(app: Long, cb: Any?)

    /** cb: (instanceId: Int, pluginName: String?, error: String?) -> Unit */
    @JvmStatic external fun uapmdAppCreatePluginInstance(
        app: Long, format: String, pluginId: String, trackIndex: Int,
        apiName: String, deviceName: String, manufacturer: String, version: String, stateFile: String,
        cb: Any
    )
    @JvmStatic external fun uapmdAppRemovePluginInstance(app: Long, instanceId: Int)
    @JvmStatic external fun uapmdAppGetInstanceGroup(app: Long, instanceId: Int): Int
    @JvmStatic external fun uapmdAppSetInstanceGroup(app: Long, instanceId: Int, group: Int): Boolean
    @JvmStatic external fun uapmdAppEnableUmpDevice(app: Long, instanceId: Int, deviceName: String)
    @JvmStatic external fun uapmdAppDisableUmpDevice(app: Long, instanceId: Int)
    @JvmStatic external fun uapmdAppRequestShowInstanceDetails(app: Long, instanceId: Int)
    @JvmStatic external fun uapmdAppRequestShowPluginUi(app: Long, instanceId: Int)
    @JvmStatic external fun uapmdAppHidePluginUi(app: Long, instanceId: Int)

    @JvmStatic external fun uapmdAppLoadProject(app: Long, filePath: String): Array<Any>?
    @JvmStatic external fun uapmdAppSaveProjectSync(app: Long, filePath: String): Array<Any>?
    /** cb: (success: Boolean, error: String?) -> Unit */
    @JvmStatic external fun uapmdAppSaveProject(app: Long, filePath: String, cb: Any)
    @JvmStatic external fun uapmdAppLoadProjectFromHandleToken(app: Long, token: String): Array<Any>?
    @JvmStatic external fun uapmdAppNewProject(app: Long): Array<Any>?
    @JvmStatic external fun uapmdAppMasterTempoMap(app: Long): Long

    // ══ Timeline clip selection and clipboard ═══════════════════════════════
    //
    // Targets cross as a flat int[] of {trackIndex, clipId} pairs.

    @JvmStatic external fun uapmdAppIsTimelineClipSelected(app: Long, t: Int, c: Int): Boolean
    @JvmStatic external fun uapmdAppSelectedTimelineClips(app: Long): IntArray
    @JvmStatic external fun uapmdAppSelectTimelineClips(app: Long, pairs: IntArray, additive: Boolean, toggle: Boolean)
    @JvmStatic external fun uapmdAppClearTimelineClipSelection(app: Long)
    @JvmStatic external fun uapmdAppSelectTimelineMidiClip(app: Long, t: Int, c: Int): Boolean
    @JvmStatic external fun uapmdAppSelectedTimelineMidiClip(app: Long): IntArray?
    @JvmStatic external fun uapmdAppTimelineClipboardCount(app: Long): Int
    @JvmStatic external fun uapmdAppClearTimelineClipboard(app: Long)
    @JvmStatic external fun uapmdAppCopySelectedTimelineClips(app: Long): Boolean
    /** Deletes and reports in one call; returns the changed-track count, or -1 on failure. */
    @JvmStatic external fun uapmdAppDeleteSelectedTimelineClips(app: Long, cut: Boolean, outTracks: IntArray): Int
    @JvmStatic external fun uapmdAppTimelinePasteDestinations(app: Long, t: Int, originalTracks: Boolean): IntArray
    /** int[1 + 2n]: success flag, then {trackIndex, clipId} per pasted clip. */
    @JvmStatic external fun uapmdAppPasteTimelineClips(app: Long, t: Int, positionSeconds: Double, originalTracks: Boolean): IntArray
    @JvmStatic external fun uapmdAppLastTimelineClipError(): String

    // ══ Piano roll editing session ══════════════════════════════════════════
    //
    // A note crosses as double[3] {startSeconds, durationSeconds, velocity} plus
    // long[9] {note, channel, deleted, editId, umpGroup, releaseVelocity,
    // attributeType, attributeValue, automationEventCount}.

    @JvmStatic external fun uapmdAppPianoRollClipSnapshot(app: Long, t: Int, c: Int, fallbackDurationSeconds: Double): Long
    @JvmStatic external fun uapmdPianoRollSnapshotDestroy(h: Long)
    @JvmStatic external fun uapmdPianoRollSnapshotReady(h: Long): Boolean
    @JvmStatic external fun uapmdPianoRollSnapshotError(h: Long): String
    @JvmStatic external fun uapmdPianoRollSnapshotDurationSeconds(h: Long): Double
    @JvmStatic external fun uapmdPianoRollSnapshotMinNote(h: Long): Int
    @JvmStatic external fun uapmdPianoRollSnapshotMaxNote(h: Long): Int
    @JvmStatic external fun uapmdPianoRollSnapshotNoteCount(h: Long): Int
    @JvmStatic external fun uapmdPianoRollSnapshotGetNote(h: Long, index: Int, outDoubles: DoubleArray, outLongs: LongArray): Boolean

    @JvmStatic external fun uapmdAppOpenPianoRollSession(app: Long, t: Int, c: Int): Long
    @JvmStatic external fun uapmdAppFindPianoRollSession(app: Long, t: Int, c: Int): Long
    @JvmStatic external fun uapmdAppClosePianoRollSession(app: Long, t: Int, c: Int)

    @JvmStatic external fun uapmdPianoRollSessionMatchesSource(session: Long, snapshot: Long): Boolean
    @JvmStatic external fun uapmdPianoRollSessionLoadNotes(session: Long, snapshot: Long)
    @JvmStatic external fun uapmdPianoRollSessionNoteCount(h: Long): Int
    @JvmStatic external fun uapmdPianoRollSessionGetNote(h: Long, index: Int, outDoubles: DoubleArray, outLongs: LongArray): Boolean
    @JvmStatic external fun uapmdPianoRollSessionIsNoteSelected(h: Long, index: Int): Boolean
    @JvmStatic external fun uapmdPianoRollSessionSelectedNoteCount(h: Long): Int
    @JvmStatic external fun uapmdPianoRollSessionFocusedNote(h: Long): Int
    @JvmStatic external fun uapmdPianoRollSessionSetFocusedNote(h: Long, index: Int)
    @JvmStatic external fun uapmdPianoRollSessionDurationSeconds(h: Long): Double
    @JvmStatic external fun uapmdPianoRollSessionMinNote(h: Long): Int
    @JvmStatic external fun uapmdPianoRollSessionMaxNote(h: Long): Int
    @JvmStatic external fun uapmdPianoRollSessionClipboardCount(h: Long): Int
    @JvmStatic external fun uapmdPianoRollSessionDirty(h: Long): Boolean
    @JvmStatic external fun uapmdPianoRollSessionError(h: Long): String
    @JvmStatic external fun uapmdPianoRollSessionSelectNote(h: Long, index: Int, additive: Boolean, toggle: Boolean)
    @JvmStatic external fun uapmdPianoRollSessionPerformAction(h: Long, action: Int, pasteSeconds: Double)
    @JvmStatic external fun uapmdPianoRollSessionCreateNote(h: Long, start: Double, duration: Double, note: Int, velocity: Float)
    @JvmStatic external fun uapmdPianoRollSessionDeleteNote(h: Long, index: Int)
    @JvmStatic external fun uapmdPianoRollSessionResizeNote(h: Long, index: Int, start: Double, duration: Double, note: Int)
    @JvmStatic external fun uapmdPianoRollSessionBeginDrag(h: Long)
    @JvmStatic external fun uapmdPianoRollSessionMoveSelection(h: Long, timeDelta: Double, pitchDelta: Int)
    @JvmStatic external fun uapmdPianoRollSessionCancelDrag(h: Long)
    @JvmStatic external fun uapmdPianoRollSessionFinishDrag(h: Long, index: Int, origStart: Double, origEnd: Double, origNote: Int)
    @JvmStatic external fun uapmdPianoRollSessionCommit(h: Long, app: Long): Boolean
    @JvmStatic external fun uapmdAppRecordPianoRollCommitSource(app: Long, t: Int, c: Int)
    @JvmStatic external fun uapmdAppPianoRollSourceMatchesLastEdit(app: Long): Boolean
    @JvmStatic external fun uapmdAppClearPianoRollCommitSource(app: Long)
    @JvmStatic external fun uapmdTransportJump(tc: Long, positionSeconds: Double)

    // ══ Assorted AppModel accessors ═════════════════════════════════════════
    //
    // Ports cross as String[2n] {id, displayName}; devices as String[3n]
    // {label, apiName, statusMessage} plus int[4n] {id, running, instantiating,
    // hasError}; a state result as int[2] {instanceId, success} plus
    // String[2] {error, filepath}.

    @JvmStatic external fun uapmdAppGetMidiInputPorts(app: Long): Array<String?>
    @JvmStatic external fun uapmdAppGetMidiOutputPorts(app: Long): Array<String?>
    @JvmStatic external fun uapmdAppIsTrackHidden(app: Long, t: Int): Boolean
    @JvmStatic external fun uapmdAppTimelineContentBounds(app: Long): DoubleArray
    @JvmStatic external fun uapmdAppGetDeviceCount(app: Long): Int
    @JvmStatic external fun uapmdAppGetDevices(app: Long, outInts: IntArray): Array<String?>
    @JvmStatic external fun uapmdAppGetDeviceForInstance(app: Long, instanceId: Int, outInts: IntArray): Array<String?>?
    @JvmStatic external fun uapmdAppUpdateDeviceLabel(app: Long, instanceId: Int, label: String)
    @JvmStatic external fun uapmdAppLoadPluginState(app: Long, instanceId: Int, filepath: String, cb: Any)
    @JvmStatic external fun uapmdAppSavePluginState(app: Long, instanceId: Int, filepath: String, cb: Any)
    @JvmStatic external fun uapmdAppLoadPluginStateSync(app: Long, instanceId: Int, filepath: String, outStrings: Array<String?>): IntArray
    @JvmStatic external fun uapmdAppSavePluginStateSync(app: Long, instanceId: Int, filepath: String, outStrings: Array<String?>): IntArray
    @JvmStatic external fun uapmdAppMarkPluginInstanceTrackDirty(app: Long, instanceId: Int)

    // ══ Clip adding, master markers, render-to-file ═════════════════════════
    //
    // Positions cross as (samples, legacyBeats). Clip-add results come back as
    // Object[]{ long[3] clipId/sourceNodeId/success, String? error }.

    @JvmStatic external fun uapmdAppAddClipToTrack(app: Long, t: Int, posSamples: Long, posBeats: Double, reader: Long, filepath: String): Array<Any?>?
    @JvmStatic external fun uapmdAppAddMidiClipToTrack(app: Long, t: Int, posSamples: Long, posBeats: Double, filepath: String): Array<Any?>?
    @JvmStatic external fun uapmdAppAddMidiClipFromData(
        app: Long, t: Int, posSamples: Long, posBeats: Double,
        umpEvents: IntArray?, tickTimestamps: LongArray?,
        tickResolution: Int, clipTempo: Double,
        tempoNums: DoubleArray?, sigTicks: LongArray?, sigNums: IntArray?,
        clipName: String, needsFileSave: Boolean
    ): Array<Any?>?
    @JvmStatic external fun uapmdAppAddDeviceInputToTrack(app: Long, t: Int, channels: IntArray?): Int
    @JvmStatic external fun uapmdAppMasterMarkerCount(app: Long): Int
    @JvmStatic external fun uapmdAppGetMasterMarkers(app: Long, outOffsets: DoubleArray, outRefTypes: IntArray): Array<String?>
    @JvmStatic external fun uapmdAppSetMasterTrackMarkersWithValidation(app: Long, mStr: Array<String>, mNum: DoubleArray, mRef: IntArray): Array<Any>
    @JvmStatic external fun uapmdAppStartRenderToFile(app: Long, outputPath: String, nums: DoubleArray, flags: BooleanArray): Boolean
    @JvmStatic external fun uapmdAppCancelRenderToFile(app: Long)
    @JvmStatic external fun uapmdAppGetRenderToFileStatus(app: Long): Array<Any?>
    @JvmStatic external fun uapmdAppClearCompletedRenderStatus(app: Long)
    @JvmStatic external fun uapmdAppRequestShowTrackGraph(app: Long, trackIndex: Int)

    /** Object[]{ long[1] success, String? error, long[] ticks, int[][] words } */
    @JvmStatic external fun uapmdAppGetMidiClipUmpEvents(app: Long, trackIndex: Int, clipId: Int): Array<Any>?
    @JvmStatic external fun uapmdAppAddUmpEventToClip(app: Long, trackIndex: Int, clipId: Int, tick: Long, words: IntArray): Boolean
    @JvmStatic external fun uapmdAppRemoveUmpEventFromClip(app: Long, trackIndex: Int, clipId: Int, eventIndex: Int): Boolean
    @JvmStatic external fun uapmdAppRemoveClipFromTrack(app: Long, trackIndex: Int, clipId: Int): Boolean
    /** Object[]{ long[3] clipId/sourceNodeId/success, String? error } */
    @JvmStatic external fun uapmdAppCreateEmptyMidiClip(app: Long, trackIndex: Int, positionSamples: Long, tickResolution: Int, bpm: Double): Array<Any>?

    @JvmStatic external fun uapmdAppEnsureTrackUsesEditorGraph(app: Long, trackIndex: Int): Boolean
    @JvmStatic external fun uapmdAppRevertTrackToSimpleGraph(app: Long, trackIndex: Int): Boolean
    /** Object[]{ long[1] success, String? error, long[] ids, int[] flat(7 per connection) } */
    @JvmStatic external fun uapmdAppGetTrackGraphConnections(app: Long, trackIndex: Int): Array<Any>?
    @JvmStatic external fun uapmdAppGetTrackGraphNodes(app: Long, trackIndex: Int): Array<Any>?
    @JvmStatic external fun uapmdAppConnectTrackGraph(
        app: Long, trackIndex: Int, id: Long, busType: Int,
        srcType: Int, srcNodeId: String, srcInstance: Int, srcBus: Int,
        tgtType: Int, tgtNodeId: String, tgtInstance: Int, tgtBus: Int
    ): Array<Any>?
    @JvmStatic external fun uapmdAppDisconnectTrackGraphConnection(app: Long, trackIndex: Int, connectionId: Long): Array<Any>?

    @JvmStatic external fun uapmdAppGetClipAudioEvents(app: Long, trackIndex: Int, clipId: Int): Array<Any>?
    @JvmStatic external fun uapmdAppSetClipAudioEvents(
        app: Long, trackIndex: Int, clipId: Int,
        markerStrings: Array<String>, markerOffsets: DoubleArray, markerRefTypes: IntArray,
        warpNums: DoubleArray, warpRefTypes: IntArray, warpStrings: Array<String>
    ): Array<Any>?
}
