package dev.atsushieno.uapmd

/*
 * JNI callback wrappers.
 *
 * uapmd_jni_app.cpp resolves the completion method with GetMethodID(cls, "invoke", <sig>)
 * using the *specialized* descriptor, e.g. "(ILjava/lang/String;)V". A Kotlin lambda
 * compiles to kotlin.jvm.functions.FunctionN, whose only invoke is the erased
 * (Ljava/lang/Object;...)Ljava/lang/Object;, so that lookup fails, leaves a pending
 * NoSuchMethodError, and the next JNI call aborts the process. Wrapping the lambda in a
 * class that declares the exact parameter types gives the method the descriptor the
 * native side asks for. AndroidHistory.kt does the same for the history surface.
 */

private class AppTrackMutationCallback(private val callback: (Int, String?) -> Unit) {
    @Suppress("unused")
    fun invoke(trackIndex: Int, error: String?) = callback(trackIndex, error)
}

private class AppErrorCallback(private val callback: (String?) -> Unit) {
    @Suppress("unused")
    fun invoke(error: String?) = callback(error)
}

private class AppInstanceCreatedCallback(private val callback: (Int, String?, String?) -> Unit) {
    @Suppress("unused")
    fun invoke(instanceId: Int, pluginName: String?, error: String?) =
        callback(instanceId, pluginName, error)
}

private class AppMidiTracksImportCallback(private val callback: (Boolean, String?, Int) -> Unit) {
    @Suppress("unused")
    fun invoke(success: Boolean, error: String?, importedTrackCount: Int) =
        callback(success, error, importedTrackCount)
}

private class PluginStateCallback(private val callback: (Int, Boolean, String?, String?) -> Unit) {
    @Suppress("unused")
    fun invoke(instanceId: Int, success: Boolean, error: String?, filepath: String?) =
        callback(instanceId, success, error, filepath)
}

private class AppProjectSaveCallback(private val callback: (Boolean, String?) -> Unit) {
    @Suppress("unused")
    fun invoke(success: Boolean, error: String?) = callback(success, error)
}

class AndroidAppModel internal constructor(internal val handle: Long) : AppModel {

    override val sequencer: RealtimeSequencer
        // Borrowed: AppModel owns this; callers must not close it (see AppModel.sequencer).
        get() = AndroidRealtimeSequencer(JniBridge.uapmdAppSequencer(handle))

    override val transport: TransportController
        get() = AndroidTransportController(JniBridge.uapmdAppTransport(handle))

    override val sampleRate: Int get() = JniBridge.uapmdAppSampleRate(handle)
    override val trackCount: UInt get() = JniBridge.uapmdAppTrackCount(handle).toUInt()

    override val isScanning: Boolean get() = JniBridge.uapmdAppIsScanning(handle)

    override val isAudioEngineEnabled: Boolean get() = JniBridge.uapmdAppIsAudioEngineEnabled(handle)
    override fun setAudioEngineEnabled(enabled: Boolean) = JniBridge.uapmdAppSetAudioEngineEnabled(handle, enabled)
    override fun toggleAudioEngine() = JniBridge.uapmdAppToggleAudioEngine(handle)

    override var autoBufferSizeEnabled: Boolean
        get() = JniBridge.uapmdAppAutoBufferSizeEnabled(handle)
        set(value) { JniBridge.uapmdAppSetAutoBufferSizeEnabled(handle, value) }

    override fun updateAudioDeviceSettings(sampleRate: Int, bufferSize: UInt) =
        JniBridge.uapmdAppUpdateAudioDeviceSettings(handle, sampleRate, bufferSize.toInt())

    override fun notifyUiReady() = JniBridge.uapmdAppNotifyUiReady(handle)
    override fun notifyPersistentStorageReady() = JniBridge.uapmdAppNotifyPersistentStorageReady(handle)

    // ── Plugin scanning ─────────────────────────────────────────────────────

    override fun performPluginScanning(
        forceRescan: Boolean, mode: ScanMode, remoteTimeoutSeconds: Double, requireFastScanning: Boolean
    ) = JniBridge.uapmdAppPerformPluginScanning(
        handle, forceRescan, if (mode == ScanMode.Remote) 1 else 0, remoteTimeoutSeconds, requireFastScanning
    )

    override fun cancelPluginScanning() = JniBridge.uapmdAppCancelPluginScanning(handle)

    override val slowScanProgress: SlowScanProgress
        get() {
            val packed = JniBridge.uapmdAppSlowScanProgress(handle) ?: return SlowScanProgress()
            val counts = packed[0] as IntArray
            return SlowScanProgress(
                running = counts[0] != 0,
                processedBundles = counts[1].toUInt(),
                totalBundles = counts[2].toUInt(),
                currentBundle = (packed.getOrNull(1) as? String).orEmpty()
            )
        }

    override val lastPluginScanError: String?
        get() = JniBridge.uapmdAppLastPluginScanError(handle)?.ifEmpty { null }
    override fun generateScanReport(): String = JniBridge.uapmdAppGenerateScanReport(handle)
    override fun clearPluginBlocklist() = JniBridge.uapmdAppClearPluginBlocklist(handle)

    override val blocklist: List<BlocklistEntry>
        get() = (0 until JniBridge.uapmdAppBlocklistCount(handle)).mapNotNull { i ->
            JniBridge.uapmdAppGetBlocklistEntry(handle, i)?.let {
                BlocklistEntry(it[0], it[1], it[2], it[3])
            }
        }

    override fun unblockPlugin(entryId: String) = JniBridge.uapmdAppUnblockPlugin(handle, entryId)

    override fun refreshMasterTempoMap() = JniBridge.uapmdAppRefreshMasterTempoMap(handle)

    override val masterTempoPoints: List<TempoPoint>
        get() {
            val d = JniBridge.uapmdAppGetMasterTempoPoints(handle) ?: return emptyList()
            return (d.indices step 3).map { i -> TempoPoint(d[i], d[i + 1].toLong(), d[i + 2]) }
        }

    override val masterTimeSignaturePoints: List<TimeSignaturePoint>
        get() {
            val d = JniBridge.uapmdAppGetMasterTimeSignatures(handle) ?: return emptyList()
            return (d.indices step 4).map { i ->
                TimeSignaturePoint(d[i], d[i + 1].toLong(), d[i + 2].toInt(), d[i + 3].toInt())
            }
        }

    // ── Tracks ──────────────────────────────────────────────────────────────

    override fun isTrackMuted(trackIndex: Int) = JniBridge.uapmdAppIsTrackMuted(handle, trackIndex)
    override fun isTrackSolo(trackIndex: Int) = JniBridge.uapmdAppIsTrackSolo(handle, trackIndex)
    override fun setTrackMuted(trackIndex: Int, muted: Boolean) =
        JniBridge.uapmdAppSetTrackMuted(handle, trackIndex, muted)
    override fun setTrackSolo(trackIndex: Int, solo: Boolean) =
        JniBridge.uapmdAppSetTrackSolo(handle, trackIndex, solo)

    override fun addTrack(callback: (Int, String?) -> Unit) =
        JniBridge.uapmdAppAddTrack(handle, AppTrackMutationCallback(callback))

    override fun removeTrack(trackIndex: Int, callback: (Int, String?) -> Unit) =
        JniBridge.uapmdAppRemoveTrack(handle, trackIndex, AppTrackMutationCallback(callback))

    override fun removeAllTracks(callback: (String?) -> Unit) =
        JniBridge.uapmdAppRemoveAllTracks(handle, AppErrorCallback(callback))

    override val timelineTrackCount: UInt get() = JniBridge.uapmdAppTimelineTrackCount(handle).toUInt()

    override fun getTimelineTrack(index: UInt): TimelineTrack =
        AndroidTimelineTrack(JniBridge.uapmdAppGetTimelineTrack(handle, index.toInt()))

    override val masterTimelineTrack: TimelineTrack
        get() = AndroidTimelineTrack(JniBridge.uapmdAppMasterTimelineTrack(handle))

    override fun getTimelineState(): TimelineState? {
        val d = JniBridge.uapmdAppGetTimelineState(handle) ?: return null
        return TimelineState(
            playheadPosition = TimelinePosition(d[0].toLong(), d[1]),
            isPlaying = d[2] != 0.0,
            loopEnabled = d[3] != 0.0,
            loopStart = TimelinePosition(d[4].toLong(), d[5]),
            loopEnd = TimelinePosition(d[6].toLong(), d[7]),
            tempo = d[8],
            timeSignatureNumerator = d[9].toInt(),
            timeSignatureDenominator = d[10].toInt(),
            sampleRate = d[11].toInt()
        )
    }

    // ── History ─────────────────────────────────────────────────────────────

    override val historyState: UndoState
        get() = JniBridge.uapmdAppGetHistoryState(handle)?.toUndoState()
            ?: error("uapmdAppGetHistoryState returned null")

    override fun undo(callback: ((String?) -> Unit)?) = JniBridge.uapmdAppUndo(handle, callback?.let { AppErrorCallback(it) })
    override fun redo(callback: ((String?) -> Unit)?) = JniBridge.uapmdAppRedo(handle, callback?.let { AppErrorCallback(it) })

    // ── Plugin instances ────────────────────────────────────────────────────

    override fun createPluginInstance(
        format: String, pluginId: String, trackIndex: Int,
        config: PluginInstanceConfig, callback: (PluginInstanceResult) -> Unit
    ) = JniBridge.uapmdAppCreatePluginInstance(
        handle, format, pluginId, trackIndex,
        config.apiName, config.deviceName, config.manufacturer, config.version, config.stateFile,
        AppInstanceCreatedCallback { instanceId, pluginName, error ->
            callback(PluginInstanceResult(instanceId, pluginName ?: "", error))
        }
    )

    override fun removePluginInstance(instanceId: Int) = JniBridge.uapmdAppRemovePluginInstance(handle, instanceId)

    override fun getInstanceGroup(instanceId: Int): UByte =
        JniBridge.uapmdAppGetInstanceGroup(handle, instanceId).toUByte()

    override fun setInstanceGroup(instanceId: Int, group: UByte): Boolean =
        JniBridge.uapmdAppSetInstanceGroup(handle, instanceId, group.toInt())

    override fun enableUmpDevice(instanceId: Int, deviceName: String) =
        JniBridge.uapmdAppEnableUmpDevice(handle, instanceId, deviceName)

    override fun disableUmpDevice(instanceId: Int) = JniBridge.uapmdAppDisableUmpDevice(handle, instanceId)

    override fun requestShowInstanceDetails(instanceId: Int) =
        JniBridge.uapmdAppRequestShowInstanceDetails(handle, instanceId)

    override fun requestShowPluginUi(instanceId: Int) = JniBridge.uapmdAppRequestShowPluginUi(handle, instanceId)
    override fun hidePluginUi(instanceId: Int) = JniBridge.uapmdAppHidePluginUi(handle, instanceId)

    // ── Project I/O ─────────────────────────────────────────────────────────

    override fun loadProject(filePath: String): AppProjectResult =
        JniBridge.uapmdAppLoadProject(handle, filePath).toProjectResult()

    override fun saveProjectSync(filePath: String): AppProjectResult =
        JniBridge.uapmdAppSaveProjectSync(handle, filePath).toProjectResult()

    override fun saveProject(filePath: String, callback: (AppProjectResult) -> Unit) =
        JniBridge.uapmdAppSaveProject(handle, filePath, AppProjectSaveCallback { success, error ->
            callback(AppProjectResult(success, error))
        })

    override fun loadProjectFromHandleToken(token: String): AppProjectResult =
        JniBridge.uapmdAppLoadProjectFromHandleToken(handle, token).toProjectResult()

    override fun newProject(): AppProjectResult =
        JniBridge.uapmdAppNewProject(handle).toProjectResult()

    // ── Timeline clip selection and clipboard ───────────────────────────────

    override fun isTimelineClipSelected(trackIndex: Int, clipId: Int): Boolean =
        JniBridge.uapmdAppIsTimelineClipSelected(handle, trackIndex, clipId)

    override val selectedTimelineClips: List<TimelineClipTarget>
        get() = JniBridge.uapmdAppSelectedTimelineClips(handle).toClipTargets()

    override fun selectTimelineClips(clips: List<TimelineClipTarget>, additive: Boolean, toggle: Boolean) =
        JniBridge.uapmdAppSelectTimelineClips(handle, clips.toFlatPairs(), additive, toggle)

    override fun clearTimelineClipSelection() = JniBridge.uapmdAppClearTimelineClipSelection(handle)

    override fun selectTimelineMidiClip(trackIndex: Int, clipId: Int): Boolean =
        JniBridge.uapmdAppSelectTimelineMidiClip(handle, trackIndex, clipId)

    override val selectedTimelineMidiClip: TimelineClipTarget?
        get() = JniBridge.uapmdAppSelectedTimelineMidiClip(handle)
            ?.let { TimelineClipTarget(it[0], it[1]) }

    override val timelineClipboardCount: Int
        get() = JniBridge.uapmdAppTimelineClipboardCount(handle)

    override fun clearTimelineClipboard() = JniBridge.uapmdAppClearTimelineClipboard(handle)

    override fun copySelectedTimelineClips(): Boolean =
        JniBridge.uapmdAppCopySelectedTimelineClips(handle)

    override fun deleteSelectedTimelineClips(cut: Boolean): TimelineClipDeleteResult {
        // One call only: this both deletes and reports. A track can lose several
        // clips but appears once, so the selection size bounds the changed-track
        // list — measured before the call, which clears the selection.
        val capacity = selectedTimelineClips.size
        val tracks = IntArray(capacity)
        val count = JniBridge.uapmdAppDeleteSelectedTimelineClips(handle, cut, tracks)
        val error = lastTimelineClipError
        return TimelineClipDeleteResult(
            success = count >= 0,
            changedTracks = if (count > 0) tracks.take(minOf(capacity, count)) else emptyList(),
            error = error.ifEmpty { null }
        )
    }

    override fun timelinePasteDestinations(trackIndex: Int, originalTracks: Boolean): List<Int> =
        JniBridge.uapmdAppTimelinePasteDestinations(handle, trackIndex, originalTracks).toList()

    override fun pasteTimelineClips(
        trackIndex: Int,
        positionSeconds: Double,
        originalTracks: Boolean
    ): TimelinePasteResult {
        val flat = JniBridge.uapmdAppPasteTimelineClips(handle, trackIndex, positionSeconds, originalTracks)
        if (flat.isEmpty())
            return TimelinePasteResult(false, emptyList(), lastTimelineClipError.ifEmpty { null })
        val pasted = flat.drop(1).toIntArray().toClipTargets()
        return TimelinePasteResult(flat[0] != 0, pasted, lastTimelineClipError.ifEmpty { null })
    }

    override val lastTimelineClipError: String
        get() = JniBridge.uapmdAppLastTimelineClipError()

    // ── Piano roll editing session ──────────────────────────────────────────

    override fun pianoRollClipSnapshot(
        trackIndex: Int,
        clipId: Int,
        fallbackDurationSeconds: Double
    ): PianoRollSnapshot? =
        JniBridge.uapmdAppPianoRollClipSnapshot(handle, trackIndex, clipId, fallbackDurationSeconds)
            .takeIf { it != 0L }?.let { AndroidPianoRollSnapshot(it) }

    override fun openPianoRollSession(trackIndex: Int, clipId: Int): PianoRollSession? =
        JniBridge.uapmdAppOpenPianoRollSession(handle, trackIndex, clipId)
            .takeIf { it != 0L }?.let { AndroidPianoRollSession(it) }

    override fun findPianoRollSession(trackIndex: Int, clipId: Int): PianoRollSession? =
        JniBridge.uapmdAppFindPianoRollSession(handle, trackIndex, clipId)
            .takeIf { it != 0L }?.let { AndroidPianoRollSession(it) }

    override fun closePianoRollSession(trackIndex: Int, clipId: Int) =
        JniBridge.uapmdAppClosePianoRollSession(handle, trackIndex, clipId)

    override fun recordPianoRollCommitSource(trackIndex: Int, clipId: Int) =
        JniBridge.uapmdAppRecordPianoRollCommitSource(handle, trackIndex, clipId)

    override fun pianoRollSourceMatchesLastEdit(): Boolean =
        JniBridge.uapmdAppPianoRollSourceMatchesLastEdit(handle)

    override fun clearPianoRollCommitSource() = JniBridge.uapmdAppClearPianoRollCommitSource(handle)

    // ── Assorted accessors ──────────────────────────────────────────────────

    override val midiInputPorts: List<MidiPortInfo>
        get() = JniBridge.uapmdAppGetMidiInputPorts(handle).toMidiPorts()

    override val midiOutputPorts: List<MidiPortInfo>
        get() = JniBridge.uapmdAppGetMidiOutputPorts(handle).toMidiPorts()

    override fun isTrackHidden(trackIndex: Int) = JniBridge.uapmdAppIsTrackHidden(handle, trackIndex)

    override val timelineContentBounds: TimelineContentBounds
        get() = JniBridge.uapmdAppTimelineContentBounds(handle).let {
            TimelineContentBounds(it[0] != 0.0, it[1], it[2], it[3])
        }

    override val devices: List<DeviceEntry>
        get() {
            val count = JniBridge.uapmdAppGetDeviceCount(handle)
            if (count <= 0) return emptyList()
            val ints = IntArray(count * 4)
            val strings = JniBridge.uapmdAppGetDevices(handle, ints)
            return (0 until count).map { i -> decodeDevice(strings, ints, i) }
        }

    override fun deviceForInstance(instanceId: Int): DeviceEntry? {
        val ints = IntArray(4)
        val strings = JniBridge.uapmdAppGetDeviceForInstance(handle, instanceId, ints) ?: return null
        return decodeDevice(strings, ints, 0)
    }

    override fun updateDeviceLabel(instanceId: Int, label: String) =
        JniBridge.uapmdAppUpdateDeviceLabel(handle, instanceId, label)

    override fun loadPluginState(instanceId: Int, filepath: String, callback: (PluginStateResult) -> Unit) =
        JniBridge.uapmdAppLoadPluginState(handle, instanceId, filepath,
            PluginStateCallback { id, ok, error, path ->
                callback(PluginStateResult(id, ok, error ?: "", path ?: ""))
            })

    override fun savePluginState(instanceId: Int, filepath: String, callback: (PluginStateResult) -> Unit) =
        JniBridge.uapmdAppSavePluginState(handle, instanceId, filepath,
            PluginStateCallback { id, ok, error, path ->
                callback(PluginStateResult(id, ok, error ?: "", path ?: ""))
            })

    override fun loadPluginStateSync(instanceId: Int, filepath: String): PluginStateResult {
        val strings = arrayOfNulls<String>(2)
        val ints = JniBridge.uapmdAppLoadPluginStateSync(handle, instanceId, filepath, strings)
        return PluginStateResult(ints[0], ints[1] != 0, strings[0] ?: "", strings[1] ?: "")
    }

    override fun savePluginStateSync(instanceId: Int, filepath: String): PluginStateResult {
        val strings = arrayOfNulls<String>(2)
        val ints = JniBridge.uapmdAppSavePluginStateSync(handle, instanceId, filepath, strings)
        return PluginStateResult(ints[0], ints[1] != 0, strings[0] ?: "", strings[1] ?: "")
    }

    override fun markPluginInstanceTrackDirty(instanceId: Int) =
        JniBridge.uapmdAppMarkPluginInstanceTrackDirty(handle, instanceId)

    override val masterTempoMap: TempoMap
        get() = AndroidTempoMap(JniBridge.uapmdAppMasterTempoMap(handle))

    // ── MIDI clip UMP events ────────────────────────────────────────────────

    override fun getMidiClipUmpEvents(trackIndex: Int, clipId: Int): UmpEventsResult {
        val packed = JniBridge.uapmdAppGetMidiClipUmpEvents(handle, trackIndex, clipId)
            ?: return UmpEventsResult(false, "native call returned null", emptyList())
        // long[2] { success, tickResolution }, String? error, long[] ticks,
        // int[][] words, double[1] clipTempo.
        val head = packed[0] as LongArray
        val ok = head[0] != 0L
        val tickRes = head[1].toUInt()
        val tempo = (packed.getOrNull(4) as? DoubleArray)?.getOrNull(0) ?: 0.0
        val error = packed.getOrNull(1) as? String
        val ticks = packed[2] as LongArray
        @Suppress("UNCHECKED_CAST")
        val words = packed[3] as Array<IntArray>
        return UmpEventsResult(ok, error, ticks.indices.map { i ->
            UmpEvent(ticks[i], UIntArray(words[i].size) { w -> words[i][w].toUInt() })
        }, tickRes, tempo)
    }

    override fun addUmpEventToClip(trackIndex: Int, clipId: Int, tick: Long, words: UIntArray): Boolean =
        JniBridge.uapmdAppAddUmpEventToClip(
            handle, trackIndex, clipId, tick, IntArray(words.size) { words[it].toInt() }
        )

    override fun removeUmpEventFromClip(trackIndex: Int, clipId: Int, eventIndex: Int): Boolean =
        JniBridge.uapmdAppRemoveUmpEventFromClip(handle, trackIndex, clipId, eventIndex)

    override fun removeClipFromTrack(trackIndex: Int, clipId: Int): Boolean =
        JniBridge.uapmdAppRemoveClipFromTrack(handle, trackIndex, clipId)

    override fun importMidiTracksFromFile(filepath: String, callback: (Boolean, String?, Int) -> Unit) =
        JniBridge.uapmdAppImportMidiTracksFromFile(
            handle, filepath, AppMidiTracksImportCallback(callback)
        )

    override fun createEmptyMidiClip(
        trackIndex: Int, positionSamples: Long, tickResolution: UInt, bpm: Double
    ): ClipAddResult {
        val packed = JniBridge.uapmdAppCreateEmptyMidiClip(handle, trackIndex, positionSamples, tickResolution.toInt(), bpm)
            ?: return ClipAddResult(-1, -1, false, "native call returned null")
        val nums = packed[0] as LongArray
        return ClipAddResult(nums[0].toInt(), nums[1].toInt(), nums[2] != 0L, packed.getOrNull(1) as? String)
    }

    override fun addClipToTrack(
        trackIndex: Int, position: TimelinePosition, reader: AudioFileReader, filepath: String
    ): ClipAddResult = JniBridge.uapmdAppAddClipToTrack(
        handle, trackIndex, position.samples, position.legacyBeats,
        (reader as AndroidAudioFileReader).handle, filepath
    ).toClipAddResult()

    override fun addMidiClipToTrack(trackIndex: Int, position: TimelinePosition, filepath: String): ClipAddResult =
        JniBridge.uapmdAppAddMidiClipToTrack(
            handle, trackIndex, position.samples, position.legacyBeats, filepath
        ).toClipAddResult()

    override fun addMidiClipFromData(
        trackIndex: Int, position: TimelinePosition,
        umpEvents: List<UInt>, tickTimestamps: List<ULong>,
        tickResolution: UInt, clipTempo: Double,
        tempoChanges: List<MidiTempoChange>, timeSignatureChanges: List<MidiTimeSignatureChange>,
        clipName: String, needsFileSave: Boolean
    ): ClipAddResult = JniBridge.uapmdAppAddMidiClipFromData(
        handle, trackIndex, position.samples, position.legacyBeats,
        umpEvents.takeIf { it.isNotEmpty() }?.let { l -> IntArray(l.size) { l[it].toInt() } },
        tickTimestamps.takeIf { it.isNotEmpty() }?.let { l -> LongArray(l.size) { l[it].toLong() } },
        tickResolution.toInt(), clipTempo,
        tempoChanges.takeIf { it.isNotEmpty() }?.let { l ->
            DoubleArray(l.size * 2) { i ->
                if (i % 2 == 0) l[i / 2].tickPosition.toDouble() else l[i / 2].bpm
            }
        },
        timeSignatureChanges.takeIf { it.isNotEmpty() }?.let { l -> LongArray(l.size) { l[it].tickPosition.toLong() } },
        timeSignatureChanges.takeIf { it.isNotEmpty() }?.let { l ->
            IntArray(l.size * 4) { i ->
                val c = l[i / 4]
                when (i % 4) {
                    0 -> c.numerator.toInt()
                    1 -> c.denominator.toInt()
                    2 -> c.clocksPerClick.toInt()
                    else -> c.thirtySecondsPerQuarter.toInt()
                }
            }
        },
        clipName, needsFileSave
    ).toClipAddResult()

    override fun addDeviceInputToTrack(trackIndex: Int, channelIndices: List<UInt>): Int =
        JniBridge.uapmdAppAddDeviceInputToTrack(
            handle, trackIndex,
            channelIndices.takeIf { it.isNotEmpty() }?.let { l -> IntArray(l.size) { l[it].toInt() } }
        )

    // ── Master track markers ────────────────────────────────────────────────

    override val masterMarkers: List<ClipMarkerData>
        get() {
            val n = JniBridge.uapmdAppMasterMarkerCount(handle)
            if (n == 0) return emptyList()
            val offsets = DoubleArray(n)
            val refTypes = IntArray(n)
            val strings = JniBridge.uapmdAppGetMasterMarkers(handle, offsets, refTypes)
            return (0 until n).map { i ->
                ClipMarkerData(
                    markerId = strings.getOrNull(i * 4) ?: "",
                    clipPositionOffset = offsets[i],
                    referenceType = WarpReferenceType.fromNative(refTypes[i]),
                    referenceClipId = strings.getOrNull(i * 4 + 1) ?: "",
                    referenceMarkerId = strings.getOrNull(i * 4 + 2) ?: "",
                    name = strings.getOrNull(i * 4 + 3) ?: ""
                )
            }
        }

    override fun setMasterTrackMarkersWithValidation(markers: List<ClipMarkerData>): OpResult =
        JniBridge.uapmdAppSetMasterTrackMarkersWithValidation(
            handle,
            Array(markers.size * 4) { i ->
                val m = markers[i / 4]
                when (i % 4) {
                    0 -> m.markerId; 1 -> m.referenceClipId; 2 -> m.referenceMarkerId; else -> m.name
                }
            },
            DoubleArray(markers.size) { markers[it].clipPositionOffset },
            IntArray(markers.size) { markers[it].referenceType.nativeValue }
        ).toOpResult()

    // ── Offline render to file ──────────────────────────────────────────────

    override fun startRenderToFile(settings: RenderToFileSettings): Boolean =
        JniBridge.uapmdAppStartRenderToFile(
            handle, settings.outputPath,
            doubleArrayOf(
                settings.startSeconds, settings.endSeconds,
                settings.contentStartSeconds, settings.contentEndSeconds,
                settings.tailSeconds, settings.silenceDurationSeconds,
                settings.silenceThresholdDb, 0.0
            ),
            booleanArrayOf(
                settings.hasEndSeconds, settings.useContentFallback,
                settings.contentBoundsValid, settings.enableSilenceStop
            )
        )

    override fun cancelRenderToFile() = JniBridge.uapmdAppCancelRenderToFile(handle)

    override val renderToFileStatus: RenderToFileStatus
        get() {
            val packed = JniBridge.uapmdAppGetRenderToFileStatus(handle)
            val nums = packed[0] as DoubleArray
            val flags = packed[1] as BooleanArray
            return RenderToFileStatus(
                running = flags[0], completed = flags[1], success = flags[2],
                progress = nums[0], renderedSeconds = nums[1],
                message = packed.getOrNull(2) as? String ?: "",
                outputPath = packed.getOrNull(3) as? String ?: ""
            )
        }

    override fun clearCompletedRenderStatus() = JniBridge.uapmdAppClearCompletedRenderStatus(handle)

    override fun requestShowTrackGraph(trackIndex: Int) =
        JniBridge.uapmdAppRequestShowTrackGraph(handle, trackIndex)

    // ── Track graph ─────────────────────────────────────────────────────────

    override fun ensureTrackUsesEditorGraph(trackIndex: Int): Boolean =
        JniBridge.uapmdAppEnsureTrackUsesEditorGraph(handle, trackIndex)

    override fun revertTrackToSimpleGraph(trackIndex: Int): Boolean =
        JniBridge.uapmdAppRevertTrackToSimpleGraph(handle, trackIndex)

    override fun getTrackGraphConnections(trackIndex: Int): GraphConnectionsResult {
        val packed = JniBridge.uapmdAppGetTrackGraphConnections(handle, trackIndex)
            ?: return GraphConnectionsResult(false, "native call returned null", emptyList())
        val ok = (packed[0] as LongArray)[0] != 0L
        val error = packed.getOrNull(1) as? String
        val ids = packed[2] as LongArray
        val flat = packed[3] as IntArray
        @Suppress("UNCHECKED_CAST")
        val nodeIds = packed[4] as Array<String?>
        return GraphConnectionsResult(ok, error, ids.indices.map { i ->
            val b = i * 7
            GraphConnection(
                id = ids[i],
                busType = GraphBusType.fromNative(flat[b]),
                source = GraphEndpoint(
                    GraphEndpointType.fromNative(flat[b + 1]),
                    nodeIds.getOrNull(i * 2).orEmpty(), flat[b + 2], flat[b + 3].toUInt()
                ),
                target = GraphEndpoint(
                    GraphEndpointType.fromNative(flat[b + 4]),
                    nodeIds.getOrNull(i * 2 + 1).orEmpty(), flat[b + 5], flat[b + 6].toUInt()
                )
            )
        })
    }

    override fun getTrackGraphNodes(trackIndex: Int): GraphNodesResult {
        val packed = JniBridge.uapmdAppGetTrackGraphNodes(handle, trackIndex)
            ?: return GraphNodesResult.failure("native call returned null")
        val ok = (packed[0] as LongArray)[0] != 0L
        val error = packed.getOrNull(1) as? String
        if (!ok) return GraphNodesResult.failure(error)
        @Suppress("UNCHECKED_CAST")
        val nodeStrings = packed[2] as Array<String?>
        val nodeInts = packed[3] as IntArray
        val tails = packed[4] as DoubleArray
        val mainBuses = packed[5] as IntArray
        @Suppress("UNCHECKED_CAST")
        val busStrings = packed[6] as Array<String?>
        val busInts = packed[7] as IntArray
        val graph = packed[8] as IntArray

        val buses = (0 until busInts.size / 3).map { i ->
            val b = i * 3
            GraphAudioBus(
                name = busStrings.getOrNull(i * 2).orEmpty(),
                role = AudioBusRole.fromNative(busInts[b]),
                enabled = busInts[b + 1] != 0,
                channelLayoutName = busStrings.getOrNull(i * 2 + 1).orEmpty(),
                channelCount = busInts[b + 2].toUInt()
            )
        }
        val nodes = (0 until nodeInts.size / 9).map { i ->
            val v = i * 9
            val from = nodeInts[v + 6]
            val inCount = nodeInts[v + 7]
            GraphNode(
                nodeId = nodeStrings.getOrNull(i * 3).orEmpty(),
                nodeType = nodeStrings.getOrNull(i * 3 + 1).orEmpty(),
                displayName = nodeStrings.getOrNull(i * 3 + 2).orEmpty(),
                instanceId = nodeInts[v],
                bypassed = nodeInts[v + 1] != 0,
                latencyInSamples = nodeInts[v + 2].toUInt(),
                tailLengthInSeconds = tails.getOrElse(i) { 0.0 },
                hasAudioBuses = nodeInts[v + 3] != 0,
                hasEventInputs = nodeInts[v + 4] != 0,
                hasEventOutputs = nodeInts[v + 5] != 0,
                audioInputBuses = buses.busRange(from, inCount),
                audioOutputBuses = buses.busRange(from + inCount, nodeInts[v + 8]),
                mainInputBusIndex = mainBuses.getOrElse(i * 2) { -1 },
                mainOutputBusIndex = mainBuses.getOrElse(i * 2 + 1) { -1 }
            )
        }
        return GraphNodesResult(
            true, error, nodes,
            graph[0].toUInt(), graph[1].toUInt(), graph[2].toUInt(), graph[3].toUInt()
        )
    }

    override fun connectTrackGraph(trackIndex: Int, connection: GraphConnection): OpResult =
        JniBridge.uapmdAppConnectTrackGraph(
            handle, trackIndex, connection.id, connection.busType.nativeValue,
            connection.source.type.nativeValue, connection.source.nodeId,
            connection.source.instanceId, connection.source.busIndex.toInt(),
            connection.target.type.nativeValue, connection.target.nodeId,
            connection.target.instanceId, connection.target.busIndex.toInt()
        ).toOpResult()

    override fun disconnectTrackGraphConnection(trackIndex: Int, connectionId: Long): OpResult =
        JniBridge.uapmdAppDisconnectTrackGraphConnection(handle, trackIndex, connectionId).toOpResult()

    // ── Clip audio events ───────────────────────────────────────────────────

    override fun getClipAudioEvents(trackIndex: Int, clipId: Int): ClipAudioEventsResult {
        val packed = JniBridge.uapmdAppGetClipAudioEvents(handle, trackIndex, clipId)
            ?: return ClipAudioEventsResult(false, "native call returned null", emptyList(), emptyList())
        val ok = (packed[0] as LongArray)[0] != 0L
        val error = packed.getOrNull(1) as? String
        @Suppress("UNCHECKED_CAST") val mStr = packed[2] as Array<String>
        val mNum = packed[3] as DoubleArray
        val mRef = packed[4] as IntArray
        val wNum = packed[5] as DoubleArray
        val wRef = packed[6] as IntArray
        @Suppress("UNCHECKED_CAST") val wStr = packed[7] as Array<String>
        return ClipAudioEventsResult(
            ok, error,
            mNum.indices.map { i ->
                ClipMarkerData(
                    markerId = mStr[i * 4],
                    clipPositionOffset = mNum[i],
                    referenceType = WarpReferenceType.fromNative(mRef[i]),
                    referenceClipId = mStr[i * 4 + 1],
                    referenceMarkerId = mStr[i * 4 + 2],
                    name = mStr[i * 4 + 3]
                )
            },
            wRef.indices.map { i ->
                AudioWarpPointData(
                    clipPositionOffset = wNum[i * 2],
                    speedRatio = wNum[i * 2 + 1],
                    referenceType = WarpReferenceType.fromNative(wRef[i]),
                    referenceClipId = wStr[i * 2],
                    referenceMarkerId = wStr[i * 2 + 1]
                )
            }
        )
    }

    override fun setClipAudioEvents(
        trackIndex: Int, clipId: Int,
        markers: List<ClipMarkerData>, warps: List<AudioWarpPointData>
    ): OpResult = JniBridge.uapmdAppSetClipAudioEvents(
        handle, trackIndex, clipId,
        Array(markers.size * 4) { i ->
            val m = markers[i / 4]
            when (i % 4) {
                0 -> m.markerId; 1 -> m.referenceClipId; 2 -> m.referenceMarkerId; else -> m.name
            }
        },
        DoubleArray(markers.size) { markers[it].clipPositionOffset },
        IntArray(markers.size) { markers[it].referenceType.nativeValue },
        DoubleArray(warps.size * 2) { i -> if (i % 2 == 0) warps[i / 2].clipPositionOffset else warps[i / 2].speedRatio },
        IntArray(warps.size) { warps[it].referenceType.nativeValue },
        Array(warps.size * 2) { i ->
            val w = warps[i / 2]
            if (i % 2 == 0) w.referenceClipId else w.referenceMarkerId
        }
    ).toOpResult()
}

private fun Array<Any>?.toOpResult(): OpResult {
    if (this == null) return OpResult(false, "native call returned null")
    return OpResult((this[0] as LongArray)[0] != 0L, this.getOrNull(1) as? String)
}

private fun Array<Any>?.toProjectResult(): AppProjectResult {
    if (this == null) return AppProjectResult(false, "native call returned null")
    val nums = this[0] as LongArray
    return AppProjectResult(nums[0] != 0L, this.getOrNull(1) as? String)
}

class AndroidTransportController internal constructor(internal val handle: Long) : TransportController {
    override val isPlaying: Boolean get() = JniBridge.uapmdTransportIsPlaying(handle)
    override val isPaused: Boolean get() = JniBridge.uapmdTransportIsPaused(handle)
    override val isRecording: Boolean get() = JniBridge.uapmdTransportIsRecording(handle)

    override var volume: Float
        get() = JniBridge.uapmdTransportGetVolume(handle)
        set(value) { JniBridge.uapmdTransportSetVolume(handle, value) }

    override fun play() = JniBridge.uapmdTransportPlay(handle)
    override fun stop() = JniBridge.uapmdTransportStop(handle)
    override fun pause() = JniBridge.uapmdTransportPause(handle)
    override fun resume() = JniBridge.uapmdTransportResume(handle)
    override fun record() = JniBridge.uapmdTransportRecord(handle)

    override fun jump(positionSeconds: Double) = JniBridge.uapmdTransportJump(handle, positionSeconds)
}

actual fun instantiateAppModel() = JniBridge.uapmdAppInstantiate()

actual fun getAppModel(): AppModel {
    val h = JniBridge.uapmdAppInstance()
    if (h == 0L) error("uapmd_app_instance returned null; call instantiateAppModel() first")
    return AndroidAppModel(h)
}

actual fun cleanupAppModel() = JniBridge.uapmdAppCleanup()

/** The flat {trackIndex, clipId} pair encoding the JNI layer uses. */
private fun IntArray.toClipTargets(): List<TimelineClipTarget> =
    (0 until size / 2).map { TimelineClipTarget(this[it * 2], this[it * 2 + 1]) }

private fun List<TimelineClipTarget>.toFlatPairs(): IntArray {
    val out = IntArray(size * 2)
    forEachIndexed { i, t ->
        out[i * 2] = t.trackIndex
        out[i * 2 + 1] = t.clipId
    }
    return out
}

/** The double[3] / long[9] note encoding the JNI layer uses. */
private fun decodePianoRollNote(d: DoubleArray, l: LongArray) = PianoRollNote(
    startSeconds = d[0],
    durationSeconds = d[1],
    velocity = d[2].toFloat(),
    note = l[0].toInt(),
    channel = l[1].toInt(),
    deleted = l[2] != 0L,
    editId = l[3],
    umpGroup = l[4].toInt(),
    releaseVelocity = l[5].toInt(),
    attributeType = l[6].toInt(),
    attributeValue = l[7].toInt(),
    automationEventCount = l[8].toInt()
)

class AndroidPianoRollSnapshot internal constructor(internal val handle: Long) : PianoRollSnapshot {
    override val isReady: Boolean get() = JniBridge.uapmdPianoRollSnapshotReady(handle)
    override val error: String get() = JniBridge.uapmdPianoRollSnapshotError(handle)
    override val durationSeconds: Double get() = JniBridge.uapmdPianoRollSnapshotDurationSeconds(handle)
    override val minNote: Int get() = JniBridge.uapmdPianoRollSnapshotMinNote(handle)
    override val maxNote: Int get() = JniBridge.uapmdPianoRollSnapshotMaxNote(handle)

    override val notes: List<PianoRollNote>
        get() {
            val d = DoubleArray(3)
            val l = LongArray(9)
            return (0 until JniBridge.uapmdPianoRollSnapshotNoteCount(handle)).mapNotNull { i ->
                if (!JniBridge.uapmdPianoRollSnapshotGetNote(handle, i, d, l)) null
                else decodePianoRollNote(d, l)
            }
        }

    override fun close() = JniBridge.uapmdPianoRollSnapshotDestroy(handle)
}

class AndroidPianoRollSession internal constructor(private val handle: Long) : PianoRollSession {
    override val notes: List<PianoRollNote>
        get() {
            val d = DoubleArray(3)
            val l = LongArray(9)
            return (0 until JniBridge.uapmdPianoRollSessionNoteCount(handle)).mapNotNull { i ->
                if (!JniBridge.uapmdPianoRollSessionGetNote(handle, i, d, l)) null
                else decodePianoRollNote(d, l)
            }
        }

    override fun isNoteSelected(index: Int) = JniBridge.uapmdPianoRollSessionIsNoteSelected(handle, index)
    override val selectedNoteCount: Int get() = JniBridge.uapmdPianoRollSessionSelectedNoteCount(handle)

    override var focusedNote: Int
        get() = JniBridge.uapmdPianoRollSessionFocusedNote(handle)
        set(value) { JniBridge.uapmdPianoRollSessionSetFocusedNote(handle, value) }

    override val durationSeconds: Double get() = JniBridge.uapmdPianoRollSessionDurationSeconds(handle)
    override val minNote: Int get() = JniBridge.uapmdPianoRollSessionMinNote(handle)
    override val maxNote: Int get() = JniBridge.uapmdPianoRollSessionMaxNote(handle)
    override val clipboardCount: Int get() = JniBridge.uapmdPianoRollSessionClipboardCount(handle)
    override val isDirty: Boolean get() = JniBridge.uapmdPianoRollSessionDirty(handle)
    override val error: String get() = JniBridge.uapmdPianoRollSessionError(handle)

    override fun matchesSource(snapshot: PianoRollSnapshot) =
        JniBridge.uapmdPianoRollSessionMatchesSource(handle, (snapshot as AndroidPianoRollSnapshot).handle)

    override fun loadNotes(snapshot: PianoRollSnapshot?) =
        JniBridge.uapmdPianoRollSessionLoadNotes(handle, (snapshot as AndroidPianoRollSnapshot?)?.handle ?: 0L)

    override fun selectNote(index: Int, additive: Boolean, toggle: Boolean) =
        JniBridge.uapmdPianoRollSessionSelectNote(handle, index, additive, toggle)

    override fun performAction(action: PianoRollAction, pasteSeconds: Double) =
        JniBridge.uapmdPianoRollSessionPerformAction(handle, action.nativeValue, pasteSeconds)

    override fun createNote(startSeconds: Double, durationSeconds: Double, note: Int, velocity: Float) =
        JniBridge.uapmdPianoRollSessionCreateNote(handle, startSeconds, durationSeconds, note, velocity)

    override fun deleteNote(index: Int) = JniBridge.uapmdPianoRollSessionDeleteNote(handle, index)

    override fun resizeNote(index: Int, startSeconds: Double, durationSeconds: Double, note: Int) =
        JniBridge.uapmdPianoRollSessionResizeNote(handle, index, startSeconds, durationSeconds, note)

    override fun beginDrag() = JniBridge.uapmdPianoRollSessionBeginDrag(handle)
    override fun moveSelection(timeDeltaSeconds: Double, pitchDelta: Int) =
        JniBridge.uapmdPianoRollSessionMoveSelection(handle, timeDeltaSeconds, pitchDelta)
    override fun cancelDrag() = JniBridge.uapmdPianoRollSessionCancelDrag(handle)
    override fun finishDrag(index: Int, originalStart: Double, originalEnd: Double, originalNote: Int) =
        JniBridge.uapmdPianoRollSessionFinishDrag(handle, index, originalStart, originalEnd, originalNote)

    override fun commit(app: AppModel) =
        JniBridge.uapmdPianoRollSessionCommit(handle, (app as AndroidAppModel).handle)
}

/** The flat {id, displayName} pair encoding the JNI layer uses for ports. */
private fun Array<String?>.toMidiPorts(): List<MidiPortInfo> =
    (0 until size / 2).map { MidiPortInfo(this[it * 2] ?: "", this[it * 2 + 1] ?: "") }

/** String[3n] {label, apiName, statusMessage} + int[4n] {id, flags...}. */
private fun decodeDevice(strings: Array<String?>, ints: IntArray, i: Int) = DeviceEntry(
    id = ints[i * 4],
    label = strings.getOrNull(i * 3) ?: "",
    apiName = strings.getOrNull(i * 3 + 1) ?: "",
    statusMessage = strings.getOrNull(i * 3 + 2) ?: "",
    running = ints[i * 4 + 1] != 0,
    instantiating = ints[i * 4 + 2] != 0,
    hasError = ints[i * 4 + 3] != 0
)

/** Object[]{ long[3] clipId/sourceNodeId/success, String? error }. */
private fun Array<Any?>?.toClipAddResult(): ClipAddResult {
    val packed = this ?: return ClipAddResult(-1, -1, false, "native call returned null")
    val nums = packed[0] as LongArray
    return ClipAddResult(nums[0].toInt(), nums[1].toInt(), nums[2] != 0L, packed.getOrNull(1) as? String)
}
