package dev.atsushieno.uapmd_kmp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.atsushieno.uapmd.*
import dev.atsushieno.uapmd_kmp.nodegraph.BusType
import dev.atsushieno.uapmd_kmp.nodegraph.GraphLink
import dev.atsushieno.uapmd_kmp.nodegraph.GraphNode
import dev.atsushieno.uapmd_kmp.nodegraph.GraphPin
import dev.atsushieno.uapmd_kmp.timeline.TimelineClip
import dev.atsushieno.uapmd_kmp.timeline.TimelineTrack
import dev.atsushieno.uapmd_kmp.ui.AudioDeviceInfo as UiAudioDeviceInfo
import dev.atsushieno.uapmd_kmp.ui.BlocklistEntry as UiBlocklistEntry
import dev.atsushieno.uapmd_kmp.ui.ExportSettings
import dev.atsushieno.uapmd_kmp.ui.ExportRange
import dev.atsushieno.uapmd_kmp.ui.ParameterEntry
import dev.atsushieno.uapmd_kmp.ui.PluginEntry
import dev.atsushieno.uapmd_kmp.ui.PresetEntry
import dev.atsushieno.uapmd_kmp.ui.InstanceInfo
import dev.atsushieno.uapmd_kmp.ui.TrackInstanceEntry

/** Platform provides a ready-to-use [RealtimeSequencer]. */
expect fun createUapmdModel(): UapmdModel

class UapmdModel(val sequencer: RealtimeSequencer) {

    private val engine: SequencerEngine get() = sequencer.engine
    private val nativeUiPresentations = mutableMapOf<Int, PluginUiPresentation>()

    // ── Audio engine ───────────────────────────────────────────────────────

    var isAudioEngineRunning by mutableStateOf(true)
        private set

    fun toggleAudioEngine() {
        if (isAudioEngineRunning) {
            sequencer.stopAudio()
            isAudioEngineRunning = false
        } else {
            sequencer.startAudio()
            isAudioEngineRunning = true
        }
    }

    // ── Transport ──────────────────────────────────────────────────────────

    var isPlaying by mutableStateOf(false)
        private set
    var isPaused  by mutableStateOf(false)
        private set
    /** Playback position in milliseconds. */
    var playheadMs by mutableStateOf(0L)
        private set

    fun play() {
        if (isPlaying) {
            engine.stopPlayback()
            isPlaying = false
            isPaused  = false
        } else {
            engine.startPlayback()
            isPlaying = true
            isPaused  = false
        }
    }

    fun pause() {
        if (isPaused) {
            engine.resumePlayback()
            isPaused = false
        } else {
            engine.pausePlayback()
            isPaused = true
        }
    }

    // ── Tracks & instances ─────────────────────────────────────────────────

    var trackEntries by mutableStateOf<List<TrackInstanceEntry>>(emptyList())
        private set

    fun refreshTracks() {
        val list = mutableListOf<TrackInstanceEntry>()
        val count = engine.trackCount.toInt()
        for (ti in 0 until count) {
            val track = engine.getTrack(ti.toUInt())
            val ids = track.getOrderedInstanceIds()
            if (ids.isEmpty()) {
                list += TrackInstanceEntry(ti, "Track ${ti + 1}", -1, "", "", true)
            } else {
                for (id in ids) {
                    val inst = engine.getPluginInstance(id) ?: continue
                    list += TrackInstanceEntry(
                        trackIndex  = ti,
                        trackName   = "Track ${ti + 1}",
                        instanceId  = id,
                        pluginName  = inst.displayName,
                        deviceName  = "",
                        enabled     = !inst.bypassed
                    )
                }
            }
        }
        trackEntries = list
    }

    fun setInstanceEnabled(instanceId: Int, enabled: Boolean) {
        engine.getPluginInstance(instanceId)?.bypassed = !enabled
        refreshTracks()
    }

    fun removeInstance(instanceId: Int) {
        closePluginUi(instanceId)
        engine.removePluginInstance(instanceId)
        engine.cleanupEmptyTracks()
        refreshTracks()
        refreshTimeline()
        closeInstance(instanceId)
    }

    fun addEmptyTrack(): Int {
        val idx = engine.addEmptyTrack()
        refreshTracks()
        refreshTimeline()
        return idx
    }

    fun removeTrack(trackIndex: Int) {
        engine.removeTrack(trackIndex)
        refreshTracks()
        refreshTimeline()
    }

    fun addPluginToTrack(trackIndex: Int, format: String, pluginId: String) {
        val existingCount = engine.trackCount.toInt()
        if (trackIndex >= existingCount) {
            repeat(trackIndex - existingCount + 1) { engine.addEmptyTrack() }
        }
        engine.addPluginToTrack(trackIndex, format, pluginId) { _, _, _ ->
            refreshTracks()
            refreshTimeline()
        }
    }

    // ── Timeline arrangement (clips) ───────────────────────────────────────
    // Clip data is not exposed through the current binding API, so clips are
    // empty lists.  Track count and names come from the real engine timeline.

    var timelineTracks by mutableStateOf<List<TimelineTrack>>(emptyList())
        private set

    fun refreshTimeline() {
        val tl = engine.timeline
        val tlCount  = tl.trackCount.toInt()
        // Show all engine tracks even if no clips have been added yet (tlCount may be < engCount)
        val engCount = engine.trackCount.toInt()
        val count = maxOf(tlCount, engCount)
        val sampleRate = sequencer.sampleRate.takeIf { it > 0 } ?: 48000
        fun clipDataToUi(clip: dev.atsushieno.uapmd.ClipData): TimelineClip {
            val startMs = clip.positionSamples * 1000L / sampleRate
            val endMs = (clip.positionSamples + clip.durationSamples) * 1000L / sampleRate
            val label = clip.name.ifEmpty { clip.filepath.substringAfterLast('/').substringAfterLast('\\') }
            return TimelineClip(
                id = clip.clipId,
                startMs = startMs,
                endMs = endMs.coerceAtLeast(startMs + 1L),
                label = label,
                previewData = dev.atsushieno.uapmd_kmp.timeline.ClipPreviewData.Loading
            )
        }
        timelineTracks = (0 until count).map { i ->
            // tl.getTrack() throws if index >= tlCount; use empty clips for engine-only tracks
            val clips = if (i < tlCount) tl.getTrack(i.toUInt()).getClips().map { clipDataToUi(it) }
                        else emptyList()
            TimelineTrack(index = i, name = "Track ${i + 1}", clips = clips)
        } + run {
            val master = tl.masterTimelineTrack
            TimelineTrack(index = count, name = "Master", clips = master.getClips().map { clipDataToUi(it) })
        }
    }

    // ── Plugin graph nodes ─────────────────────────────────────────────────
    // Connection topology is not exposed through the current binding API, so
    // links are empty.  Nodes are built from real plugin instances per track.

    var graphNodes by mutableStateOf<List<GraphNode>>(emptyList())
        private set
    var graphLinks by mutableStateOf<List<GraphLink>>(emptyList())
        private set
    var nextGraphLinkId by mutableStateOf(1)

    fun refreshGraph() {
        val nodes = mutableListOf<GraphNode>()
        var pinId = 0
        nodes += GraphNode(
            id = -1,
            label = "Graph Input",
            inputs = emptyList(),
            outputs = listOf(
                GraphPin(pinId++, "Audio", isInput = false, busType = BusType.Audio),
                GraphPin(pinId++, "Event", isInput = false, busType = BusType.Event),
            )
        )
        val count = engine.trackCount.toInt()
        for (ti in 0 until count) {
            val track = engine.getTrack(ti.toUInt())
            for (id in track.getOrderedInstanceIds()) {
                val inst = engine.getPluginInstance(id) ?: continue
                nodes += GraphNode(
                    id = id,
                    label = inst.displayName,
                    inputs = listOf(
                        GraphPin(pinId++, "Audio In", isInput = true,  busType = BusType.Audio),
                        GraphPin(pinId++, "Event In", isInput = true,  busType = BusType.Event),
                    ),
                    outputs = listOf(
                        GraphPin(pinId++, "Audio Out", isInput = false, busType = BusType.Audio),
                    )
                )
            }
        }
        nodes += GraphNode(
            id = Int.MAX_VALUE,
            label = "Graph Output",
            inputs = listOf(
                GraphPin(pinId++, "Audio L", isInput = true, busType = BusType.Audio),
                GraphPin(pinId++, "Audio R", isInput = true, busType = BusType.Audio),
            ),
            outputs = emptyList()
        )
        graphNodes = nodes
        // Connection topology is not available via the binding; links stay empty.
        graphLinks = emptyList()
        nextGraphLinkId = 1
    }

    // ── Instance details (multiple open panels) ────────────────────────────

    var instanceInfos by mutableStateOf<Map<Int, InstanceInfo>>(emptyMap())
        private set
    var selectedInstanceId by mutableStateOf<Int?>(null)
        private set
    var pluginUiStatusMessage by mutableStateOf<String?>(null)
        private set

    val selectedInstanceInfo: InstanceInfo?
        get() = selectedInstanceId?.let { instanceInfos[it] }

    fun openInstance(instanceId: Int) {
        val info = buildInstanceInfo(instanceId) ?: return
        instanceInfos = instanceInfos + (instanceId to info)
        selectedInstanceId = instanceId
    }

    fun closeInstance(instanceId: Int) {
        instanceInfos = instanceInfos - instanceId
        if (selectedInstanceId == instanceId)
            selectedInstanceId = null
    }

    fun selectInstance(instanceId: Int?) {
        selectedInstanceId = instanceId
        if (instanceId != null && instanceId !in instanceInfos)
            openInstance(instanceId)
    }

    private fun refreshOpenInstance(instanceId: Int) {
        if (instanceId !in instanceInfos) return
        val info = buildInstanceInfo(instanceId) ?: return
        instanceInfos = instanceInfos + (instanceId to info)
    }

    fun sendNoteOn(instanceId: Int, note: Int) = engine.sendNoteOn(instanceId, note)
    fun sendNoteOff(instanceId: Int, note: Int) = engine.sendNoteOff(instanceId, note)

    private fun buildInstanceInfo(instanceId: Int): InstanceInfo? {
        val inst = engine.getPluginInstance(instanceId) ?: return null
        val params = (0 until inst.parameterCount.toInt()).mapNotNull { i ->
            val meta = inst.getParameterMetadata(i.toUInt()) ?: return@mapNotNull null
            val value = inst.getParameterValue(i).toFloat()
            ParameterEntry(
                index        = i,
                name         = meta.name,
                value        = ((value - meta.minPlainValue) / (meta.maxPlainValue - meta.minPlainValue)).toFloat().coerceIn(0f, 1f),
                displayValue = inst.getParameterValueString(i, value.toDouble()),
                isAutomatable = meta.automatable,
                isDiscrete   = meta.discrete,
                namedValues  = meta.namedValues.map { nv -> nv.value.toFloat() to nv.name }
            )
        }
        val presets = (0 until inst.presetCount.toInt()).mapNotNull { i ->
            val meta = inst.getPresetMetadata(i.toUInt()) ?: return@mapNotNull null
            PresetEntry(meta.bank.toInt(), meta.index.toInt(), meta.name)
        }
        val trackIdx = engine.findTrackForInstance(instanceId)
        val groupIndex = engine.getInstanceGroup(instanceId).toInt()
        val groupCount = if (trackIdx >= 0) {
            val track = engine.getTrack(trackIdx.toUInt())
            track.findAvailableGroup().toInt() + 1
        } else 1
        val uiCapabilities = inst.uiCapabilities

        return InstanceInfo(
            instanceId   = instanceId,
            displayName  = inst.displayName,
            isEnabled    = !inst.bypassed,
            groupIndex   = groupIndex,
            groupCount   = groupCount,
            hasUiSupport = uiCapabilities.hasUiSupport,
            nativeUiVisible = nativeUiPresentations[instanceId]?.isVisible == true,
            parameters   = params,
            presets      = presets
        )
    }

    fun showPluginUi(instanceId: Int) {
        val inst = engine.getPluginInstance(instanceId) ?: return
        val existing = nativeUiPresentations[instanceId]
        if (existing != null) {
            if (!existing.show())
                pluginUiStatusMessage = "Failed to show native UI for ${inst.displayName}."
            refreshOpenInstance(instanceId)
            return
        }
        val uiCapabilities = inst.uiCapabilities
        if (!uiCapabilities.hasUiSupport) {
            pluginUiStatusMessage = "${inst.displayName} does not expose a native UI."
            refreshOpenInstance(instanceId)
            return
        }
        val preferredTarget = defaultPluginUiPresentationTarget(instanceId)
        val request = when {
            preferredTarget != null && uiCapabilities.supportsEmbeddedPresentations ->
                PluginUiPresentationRequest(
                    host = preferredTarget.host,
                    role = PluginUiPresentationRole.FULL
                )
            uiCapabilities.supportsFloatingPresentations && supportsFloatingPluginUiPresentations() ->
                PluginUiPresentationRequest(
                    host = PluginUiHost.FloatingWindow,
                    role = PluginUiPresentationRole.FULL
                )
            preferredTarget != null ->
                PluginUiPresentationRequest(
                    host = preferredTarget.host,
                    role = PluginUiPresentationRole.FULL
                )
            else -> null
        }
        if (request == null) {
            pluginUiStatusMessage =
                unsupportedFloatingPluginUiMessage()
                    ?: "No supported native UI presentation target is available for ${inst.displayName}."
            refreshOpenInstance(instanceId)
            return
        }
        val presentation = inst.createUiPresentation(
            request
        )
        if (presentation == null) {
            pluginUiStatusMessage = "Failed to create a native UI presentation for ${inst.displayName}."
            refreshOpenInstance(instanceId)
            return
        }
        nativeUiPresentations[instanceId] = presentation
        if (!presentation.show())
            pluginUiStatusMessage = "Created the native UI presentation for ${inst.displayName}, but show() failed."
        else
            pluginUiStatusMessage = when (request.host) {
                PluginUiHost.FloatingWindow -> null
                else -> "Attached ${inst.displayName} to the ${preferredTarget?.description ?: "embedded editor surface"}."
            }
        refreshOpenInstance(instanceId)
    }

    fun closePluginUi(instanceId: Int) {
        nativeUiPresentations.remove(instanceId)?.close()
        refreshOpenInstance(instanceId)
    }

    fun clearPluginUiStatus() {
        pluginUiStatusMessage = null
    }

    fun setParameterValue(instanceId: Int, paramIndex: Int, normalizedValue: Float) {
        val inst = engine.getPluginInstance(instanceId) ?: return
        val meta = inst.getParameterMetadata(paramIndex.toUInt()) ?: return
        val plain = meta.minPlainValue + normalizedValue * (meta.maxPlainValue - meta.minPlainValue)
        inst.setParameterValue(paramIndex, plain)
        refreshOpenInstance(instanceId)
    }

    fun loadPreset(instanceId: Int, preset: PresetEntry) {
        engine.getPluginInstance(instanceId)?.loadPreset(preset.index)
        refreshOpenInstance(instanceId)
    }

    fun setInstanceGroup(instanceId: Int, group: Int) {
        engine.setInstanceGroup(instanceId, group.toUByte())
        refreshOpenInstance(instanceId)
    }

    // ── Plugin catalog & scanning ──────────────────────────────────────────

    var catalogEntries by mutableStateOf<List<PluginEntry>>(emptyList())
        private set
    var isScanning by mutableStateOf(false)
        private set
    var scanProgress by mutableStateOf(0f)
        private set
    var scanReport by mutableStateOf("")
        private set
    var blocklist by mutableStateOf<List<UiBlocklistEntry>>(emptyList())
        private set

    private val scanTool: ScanTool = createScanTool()

    fun refreshCatalog() {
        val host = engine.pluginHost
        host.reloadCatalogFromCache()
        catalogEntries = (0 until host.catalogEntryCount.toInt()).mapNotNull { i ->
            host.getCatalogEntry(i.toUInt())?.let { e ->
                PluginEntry(e.format, e.displayName, e.vendor, e.pluginId)
            }
        }
        blocklist = (0 until scanTool.blocklistCount.toInt()).mapNotNull { i ->
            scanTool.getBlocklistEntry(i.toUInt())?.let { b ->
                UiBlocklistEntry(b.id, b.format, b.pluginId, b.reason)
            }
        }
    }

    fun scanPlugins() {
        isScanning = true
        scanProgress = 0f
        scanReport = ""
        val observer = object : ScanObserver {
            private var total = 0u
            private var done  = 0
            override fun onSlowScanStarted(totalBundles: UInt) { total = totalBundles }
            override fun onBundleScanCompleted(bundlePath: String) {
                done++
                scanProgress = if (total > 0u) done.toFloat() / total.toFloat() else 0f
            }
            override fun onSlowScanCompleted() {
                isScanning = false
                scanProgress = 1f
                refreshCatalog()
            }
            override fun onErrorOccurred(message: String) { scanReport += "$message\n" }
        }
        // Scanning is synchronous in C; run on a background thread via coroutines in the caller.
        scanTool.performScanning(requireFastScanning = false, observer = observer)
        engine.pluginHost.reloadCatalogFromCache()
        refreshCatalog()
        isScanning = false
    }

    fun clearBlocklist() {
        scanTool.clearBlocklist()
        blocklist = emptyList()
    }

    // ── Audio devices ──────────────────────────────────────────────────────

    var inputDevices  by mutableStateOf<List<UiAudioDeviceInfo>>(emptyList())
        private set
    var outputDevices by mutableStateOf<List<UiAudioDeviceInfo>>(emptyList())
        private set
    var selectedInputId  by mutableStateOf<Int?>(null)
    var selectedOutputId by mutableStateOf<Int?>(null)
    var selectedSampleRate by mutableStateOf(48000)
    var selectedBufferSize by mutableStateOf(512)

    fun refreshDevices() {
        val mgr = getAudioDeviceManager()
        val inputs  = mutableListOf<UiAudioDeviceInfo>()
        val outputs = mutableListOf<UiAudioDeviceInfo>()
        for (i in 0 until mgr.deviceCount.toInt()) {
            val info = mgr.getDeviceInfo(i.toUInt()) ?: continue
            val uiInfo = UiAudioDeviceInfo(id = info.id, name = info.name,
                isInput = info.directions != AudioIoDirection.Output)
            when (info.directions) {
                AudioIoDirection.Input  -> inputs  += uiInfo
                AudioIoDirection.Output -> outputs += uiInfo
                AudioIoDirection.Duplex -> { inputs += uiInfo; outputs += uiInfo }
            }
        }
        inputDevices  = inputs
        outputDevices = outputs
        if (selectedInputId  == null) selectedInputId  = inputs.firstOrNull()?.id
        if (selectedOutputId == null) selectedOutputId = outputs.firstOrNull()?.id
    }

    fun applyDeviceSettings() {
        val inId  = selectedInputId  ?: -1
        val outId = selectedOutputId ?: -1
        sequencer.reconfigureAudioDevice(inId, outId, selectedSampleRate.toUInt(), selectedBufferSize.toUInt())
    }

    // ── Spectrum ───────────────────────────────────────────────────────────

    var inputSpectrum  by mutableStateOf(FloatArray(32))
        private set
    var outputSpectrum by mutableStateOf(FloatArray(32))
        private set

    fun refreshSpectrum() {
        inputSpectrum  = engine.getInputSpectrum(32)
        outputSpectrum = engine.getOutputSpectrum(32)
    }

    // ── Offline render ─────────────────────────────────────────────────────

    fun renderOffline(
        settings: ExportSettings,
        onProgress: (Float) -> Unit,
        onDone: (success: Boolean, error: String?) -> Unit
    ) {
        val bounds = engine.timeline.calculateContentBounds()
        val renderSettings = OfflineRenderSettings(
            outputPath           = settings.outputPath,
            startSeconds         = when (settings.range) {
                ExportRange.EntireProject -> 0.0
                ExportRange.LoopRegion    -> 0.0
                ExportRange.Custom        -> settings.customStartSeconds
            },
            endSeconds           = when (settings.range) {
                ExportRange.Custom -> settings.customEndSeconds
                else               -> null
            },
            useContentFallback   = settings.range == ExportRange.EntireProject,
            contentBoundsValid   = bounds.hasContent,
            contentStartSeconds  = bounds.firstSeconds,
            contentEndSeconds    = bounds.lastSeconds,
            tailSeconds          = settings.tailSeconds,
            enableSilenceStop    = settings.enableSilenceStop,
            silenceDurationSeconds = settings.silenceDurationSeconds,
            silenceThresholdDb   = settings.silenceThresholdDb,
            sampleRate           = settings.sampleRate,
        )
        val result = engine.renderOffline(
            settings         = renderSettings,
            progressCallback = { p -> onProgress(p.progress.toFloat()) }
        )
        onDone(result.success, result.errorMessage)
    }

    // ── Polling tick (call from LaunchedEffect every ~16ms) ────────────────

    fun tick() {
        isPlaying  = engine.isPlaybackActive
        val sampleRate = sequencer.sampleRate.takeIf { it > 0 } ?: 48000
        playheadMs = engine.playbackPosition * 1000L / sampleRate
    }
}
