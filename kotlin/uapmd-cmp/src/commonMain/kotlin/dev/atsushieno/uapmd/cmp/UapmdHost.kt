package dev.atsushieno.uapmd.cmp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import dev.atsushieno.uapmd.AddinManager
import dev.atsushieno.uapmd.AppModel
import dev.atsushieno.uapmd.AppProjectResult
import dev.atsushieno.uapmd.AudioIoDirection
import dev.atsushieno.uapmd.createAddinManager
import dev.atsushieno.uapmd.getAudioDeviceManager
import dev.atsushieno.uapmd.PluginUiHost
import dev.atsushieno.uapmd.PluginUiPresentation
import dev.atsushieno.uapmd.PluginUiPresentationRequest
import dev.atsushieno.uapmd.PluginUiPresentationRole
import dev.atsushieno.uapmd.RealtimeSequencer
import dev.atsushieno.uapmd.CatalogEntry
import dev.atsushieno.uapmd.ClipAddResult
import dev.atsushieno.uapmd.ClipData
import dev.atsushieno.uapmd.TimeReference
import dev.atsushieno.uapmd.TimeReferenceType
import dev.atsushieno.uapmd.TimelinePosition
import dev.atsushieno.uapmd.createAudioFileReader
import dev.atsushieno.uapmd.MidiNoteData
import dev.atsushieno.uapmd.OfflineRenderSettings
import dev.atsushieno.uapmd.PluginInstanceConfig
import dev.atsushieno.uapmd.PluginInstanceResult
import dev.atsushieno.uapmd.ScanMode
import dev.atsushieno.uapmd.TimelineState
import dev.atsushieno.uapmd.UndoState
import dev.atsushieno.uapmd.getAppModel
import dev.atsushieno.uapmd.instantiateAppModel
import dev.atsushieno.uapmd.prepareProjectLoad

/**
 * Owns the uapmd startup sequence and mirrors AppModel state into Compose.
 *
 * The sequence follows uapmd-app's own `main_common.cpp` / `web_main.cpp`
 * (docs/uapmd-cmp-plan.md §2.5), because uapmd-cmp replaces that entry point and
 * inherits everything it used to do:
 *
 *   platform event loop  ->  instantiate  ->  UI exists  ->  notifyUiReady
 *   ->  notifyPersistentStorageReady  ->  audio engine to its per-platform
 *   initial state  ...  engine off  ->  cleanup
 *
 * The event-loop step happens earlier, in each platform entry point, because it
 * has to run before AppModel exists at all.
 */
class UapmdHost private constructor(val model: AppModel) {

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
    )

    /** AppModel owns the underlying handle, so the app only ever holds a borrow. */
    val sequencer: RealtimeSequencer = BorrowedRealtimeSequencer(model.sequencer)

    var isAudioEngineEnabled by mutableStateOf(false)
        private set

    /** Engine control goes through AppModel, never `setActive` + `startAudio` (§2.1). */
    fun enableAudioEngine(enabled: Boolean) {
        model.setAudioEngineEnabled(enabled)
        refresh()
    }

    fun toggleAudioEngine() {
        model.toggleAudioEngine()
        refresh()
    }

    // ── Transport ───────────────────────────────────────────────────────────

    var isPlaying by mutableStateOf(false)
        private set
    var isPaused by mutableStateOf(false)
        private set
    var isRecording by mutableStateOf(false)
        private set

    fun playOrStop() {
        val t = model.transport
        if (t.isPlaying) t.stop() else t.play()
        refresh()
    }

    /** The clip the record button captures into, as uapmd-app's selected MIDI clip. */
    var selectedMidiClip by mutableStateOf<Pair<Int, Int>?>(null)

    /**
     * Records into the selected MIDI clip. uapmd-app targets the clip by its
     * *document* reference id, not the runtime index, so it survives edits.
     */
    fun toggleRecording(): String? {
        val recorder = model.sequencer.engine.midiRecorder
            ?: return "This build has no MIDI recorder extension."
        if (recorder.isRecording) {
            recorder.stop()
            model.transport.record()
            refresh()
            return null
        }
        val target = selectedMidiClip ?: return "Select a MIDI clip first."
        val (trackIndex, clipId) = target
        val trackRef = model.sequencer.engine.timeline.addresses.trackReferenceId(trackIndex)
            ?: return "Track $trackIndex has no document identity."
        return if (recorder.start(trackRef, clipId, model.sequencer.engine.playbackPosition)) {
            model.transport.record()
            refresh()
            null
        } else "The recorder rejected the target."
    }

    fun pauseOrResume() {
        val t = model.transport
        if (t.isPaused) t.resume() else t.pause()
        refresh()
    }

    // ── History ─────────────────────────────────────────────────────────────

    var history by mutableStateOf(model.historyState)
        private set

    fun undo() = model.undo { refresh() }
    fun redo() = model.redo { refresh() }

    // ── Scanning ────────────────────────────────────────────────────────────

    var isScanning by mutableStateOf(false)
        private set

    fun scanPlugins(forceRescan: Boolean = false, mode: ScanMode = ScanMode.InProcess) {
        model.performPluginScanning(forceRescan, mode)
        refresh()
    }

    fun cancelScan() {
        model.cancelPluginScanning()
        refresh()
    }

    // ── Addins ──────────────────────────────────────────────────────────────
    //
    // The engine publishes its extension points, then the manager loads what is
    // installed - the order uapmd-app uses.

    var addins: AddinManager? = null
        private set

    private fun initAddins() {
        runCatching {
            val manager = createAddinManager()
            model.sequencer.engine.registerAddinExtensionPoints(manager)
            manager.initialize()
            addins = manager
        }
    }

    // ── Audio devices ───────────────────────────────────────────────────────

    data class UiAudioDevice(val id: Int, val name: String, val isInput: Boolean)

    fun audioDevices(): List<UiAudioDevice> {
        val mgr = getAudioDeviceManager()
        val result = mutableListOf<UiAudioDevice>()
        for (i in 0 until mgr.deviceCount.toInt()) {
            val info = mgr.getDeviceInfo(i.toUInt()) ?: continue
            when (info.directions) {
                AudioIoDirection.Input -> result += UiAudioDevice(info.id, info.name, true)
                AudioIoDirection.Output -> result += UiAudioDevice(info.id, info.name, false)
                AudioIoDirection.Duplex -> {
                    result += UiAudioDevice(info.id, info.name, true)
                    result += UiAudioDevice(info.id, info.name, false)
                }
            }
        }
        return result
    }

    /** Returns a status line, or null when the change applied cleanly. */
    fun applyDeviceSettings(inputId: Int, outputId: Int, sampleRate: Int, bufferSize: Int): String? {
        // AppModel owns the UI-facing values; the sequencer owns the device.
        model.updateAudioDeviceSettings(sampleRate, bufferSize.toUInt())
        val ok = sequencer.reconfigureAudioDevice(inputId, outputId, sampleRate.toUInt(), bufferSize.toUInt())
        refresh()
        return if (ok) null else "Failed to reconfigure the audio device."
    }

    // ── Plugin catalog ──────────────────────────────────────────────────────

    var catalog by mutableStateOf<List<CatalogEntry>>(emptyList())
        private set
    var lastInstantiation by mutableStateOf<PluginInstanceResult?>(null)
        private set
    var isInstantiating by mutableStateOf(false)
        private set

    /**
     * Where the Plugin Selector will put the next instance: a track index, or
     * -1 for "new track". uapmd-app sets this when the selector is opened from
     * a track's Add Plugin button, so per-track adds land on that track.
     */
    var pluginDestinationTrack by mutableStateOf(-1)
        private set

    fun targetPluginDestination(trackIndex: Int) { pluginDestinationTrack = trackIndex }

    fun refreshCatalog() {
        val pluginHost = model.sequencer.engine.pluginHost
        catalog = (0 until pluginHost.catalogEntryCount.toInt())
            .mapNotNull { pluginHost.getCatalogEntry(it.toUInt()) }
    }

    /** [trackIndex] < 0 creates a new track, matching the C API. */
    fun instantiate(entry: CatalogEntry, trackIndex: Int, config: PluginInstanceConfig = PluginInstanceConfig()) {
        if (isInstantiating) return
        isInstantiating = true
        model.createPluginInstance(entry.format, entry.pluginId, trackIndex, config) { result ->
            lastInstantiation = result
            isInstantiating = false
            refresh()
        }
    }

    /** Plug-in state to/from a file, as uapmd-app's Save/Load State buttons. */
    fun savePluginState(instanceId: Int, path: String): String {
        val inst = model.sequencer.engine.getPluginInstance(instanceId)
            ?: return "Instance $instanceId is gone."
        return runCatching {
            writeBytesToFile(path, inst.saveStateSync())
            "Saved state to $path"
        }.getOrElse { "Failed to save state: ${it.message}" }
    }

    fun loadPluginState(instanceId: Int, path: String): String {
        val inst = model.sequencer.engine.getPluginInstance(instanceId)
            ?: return "Instance $instanceId is gone."
        return runCatching {
            val bytes = readBytesFromFile(path) ?: return "Could not read $path."
            // Route through ProjectCommands so the state change is undoable.
            model.sequencer.engine.timeline.setPluginState(instanceId, bytes)
            "Loaded state from $path"
        }.getOrElse { "Failed to load state: ${it.message}" }
    }

    fun setInstanceGroup(instanceId: Int, group: Int) {
        model.sequencer.engine.timeline.commands.setPluginGroup(instanceId, group.toUByte())
        refresh()
    }

    fun removeInstance(instanceId: Int) {
        model.removePluginInstance(instanceId)
        refresh()
    }

    // ── Project I/O ─────────────────────────────────────────────────────────

    var lastProjectResult by mutableStateOf<AppProjectResult?>(null)
        private set

    /**
     * Loading a project tears down every live plug-in, so it cannot run while
     * audio is going or while plug-in UIs are open — that is what crashed on
     * `.uapmdz`. It also has to be *unpacked* first: `.uapmdz` is an archive,
     * and handing its path straight to loadProject() is not valid.
     */
    fun loadProject(path: String) {
        val prepared = runCatching { prepareProjectLoad(path) }.getOrNull()
        if (prepared == null || !prepared.success) {
            lastProjectResult = AppProjectResult(false, prepared?.error?.ifEmpty { null }
                ?: "Could not open $path.")
            prepared?.close()
            return
        }

        val wasRunning = model.isAudioEngineEnabled
        // Close plug-in UIs first: the instances behind them are about to go.
        nativeUiPresentations.values.forEach { runCatching { it.close() } }
        nativeUiPresentations.clear()
        platformHostedUiInstanceIds = emptySet()
        selectedMidiClip = null
        if (wasRunning) model.setAudioEngineEnabled(false)

        lastProjectResult = try {
            model.loadProject(prepared.path)
        } finally {
            prepared.close()
            if (wasRunning) model.setAudioEngineEnabled(true)
        }
        noteCache.clear()
        refresh()
    }

    fun saveProject(path: String) {
        model.saveProject(path) { result ->
            lastProjectResult = result
            refresh()
        }
    }

    // ── Plugin UI ───────────────────────────────────────────────────────────
    //
    // Goes through PluginInstance.createUiPresentation(), the path composeApp
    // proved on desktop and Android — NOT AppModel's requestShowPluginUi(),
    // which only raises a request that uapmd-app's own MainWindow services.

    private val nativeUiPresentations = mutableMapOf<Int, PluginUiPresentation>()

    var platformHostedUiInstanceIds by mutableStateOf<Set<Int>>(emptySet())
        private set
    var pluginUiStatusMessage by mutableStateOf<String?>(null)
        private set

    fun reportPluginUiStatus(message: String?) { pluginUiStatusMessage = message }

    fun isPluginUiVisible(instanceId: Int): Boolean =
        nativeUiPresentations[instanceId]?.isVisible == true || instanceId in platformHostedUiInstanceIds

    fun showPluginUi(instanceId: Int) {
        val inst = model.sequencer.engine.getPluginInstance(instanceId) ?: return

        // Android AAP plugins are hosted by the platform's own view system.
        if (supportsPlatformHostedPluginUi(inst)) {
            platformHostedUiInstanceIds = platformHostedUiInstanceIds + instanceId
            pluginUiStatusMessage = null
            return
        }

        nativeUiPresentations[instanceId]?.let { existing ->
            pluginUiStatusMessage =
                if (!existing.show()) "Failed to show the UI for ${inst.displayName}." else null
            return
        }

        val caps = inst.uiCapabilities
        if (!caps.hasUiSupport) {
            pluginUiStatusMessage = "${inst.displayName} does not expose a UI."
            return
        }

        val target = defaultPluginUiPresentationTarget(instanceId)
        val request = when {
            target != null && caps.supportsEmbeddedPresentations ->
                PluginUiPresentationRequest(target.host, PluginUiPresentationRole.FULL)
            caps.supportsFloatingPresentations && supportsFloatingPluginUiPresentations() ->
                PluginUiPresentationRequest(PluginUiHost.FloatingWindow, PluginUiPresentationRole.FULL)
            target != null ->
                PluginUiPresentationRequest(target.host, PluginUiPresentationRole.FULL)
            else -> null
        }
        if (request == null) {
            pluginUiStatusMessage = unsupportedFloatingPluginUiMessage()
                ?: "No supported UI presentation target for ${inst.displayName}."
            return
        }

        val presentation = inst.createUiPresentation(request)
        if (presentation == null) {
            pluginUiStatusMessage = "Failed to create a UI presentation for ${inst.displayName}."
            return
        }
        nativeUiPresentations[instanceId] = presentation
        pluginUiStatusMessage = when {
            !presentation.show() -> "Created the UI for ${inst.displayName}, but show() failed."
            request.host is PluginUiHost.FloatingWindow -> null
            else -> "Attached ${inst.displayName} to the ${target?.description ?: "embedded surface"}."
        }
    }

    fun closePluginUi(instanceId: Int) {
        platformHostedUiInstanceIds = platformHostedUiInstanceIds - instanceId
        nativeUiPresentations.remove(instanceId)?.close()
    }

    // ── Offline render ──────────────────────────────────────────────────────

    var isRendering by mutableStateOf(false)
        private set
    var renderProgress by mutableStateOf(0.0)
        private set
    var renderStatus by mutableStateOf<String?>(null)
        private set
    private var renderCancelled = false

    fun startRender(
        outputPath: String,
        startSeconds: Double,
        endSeconds: Double?,
        tailSeconds: Double,
        enableSilenceStop: Boolean
    ) {
        if (isRendering) return
        isRendering = true
        renderCancelled = false
        renderProgress = 0.0
        renderStatus = null

        val bounds = model.sequencer.engine.timeline.calculateContentBounds()
        val settings = OfflineRenderSettings(
            outputPath = outputPath,
            startSeconds = startSeconds,
            endSeconds = endSeconds,
            useContentFallback = endSeconds == null,
            contentBoundsValid = bounds.hasContent,
            contentStartSeconds = bounds.firstSeconds,
            contentEndSeconds = bounds.lastSeconds,
            tailSeconds = tailSeconds,
            enableSilenceStop = enableSilenceStop,
            sampleRate = model.sampleRate.takeIf { it > 0 } ?: 48000
        )

        // renderOffline blocks, so keep it off the UI dispatcher.
        scope.launch(backgroundDispatcher()) {
            val result = model.sequencer.engine.renderOffline(
                settings,
                progressCallback = { p -> renderProgress = p.progress },
                shouldCancel = { renderCancelled }
            )
            isRendering = false
            renderStatus = when {
                result.canceled -> "Render cancelled."
                result.success -> "Rendered ${result.renderedSeconds}s to $outputPath"
                else -> result.errorMessage ?: "Render failed."
            }
        }
    }

    fun cancelRender() { renderCancelled = true }

    // ── Clip import ─────────────────────────────────────────────────────────

    var lastClipResult by mutableStateOf<ClipAddResult?>(null)
        private set

    /** SMF or .midi2, added at the start of [trackIndex]. */
    fun importMidiClip(trackIndex: Int, filePath: String) {
        lastClipResult = model.sequencer.engine.timeline
            .addMidiClipFromFile(trackIndex, TimelinePosition(0L, 0.0), filePath)
        invalidateClips()
        refresh()
    }

    fun importAudioClip(trackIndex: Int, filePath: String) {
        val reader = createAudioFileReader(filePath)
        lastClipResult = model.sequencer.engine.timeline
            .addAudioClip(trackIndex, TimelinePosition(0L, 0.0), reader, filePath)
        invalidateClips()
        refresh()
    }

    private fun invalidateClips() = noteCache.clear()

    /** Call after editing a clip's UMP stream so previews re-decode. */
    fun invalidateMidiCache() {
        noteCache.clear()
        refresh()
    }

    // ── Clip properties (all through ProjectCommands, so every edit is undoable) ──

    private val commands get() = model.sequencer.engine.timeline.commands

    fun setClipName(trackIndex: Int, clipId: Int, name: String) =
        commands.setClipName(trackIndex, clipId, name).also { invalidateMidiCache() }

    fun setClipGain(trackIndex: Int, clipId: Int, gain: Double) =
        commands.setClipGain(trackIndex, clipId, gain).also { invalidateMidiCache() }

    fun setClipMuted(trackIndex: Int, clipId: Int, muted: Boolean) =
        commands.setClipMuted(trackIndex, clipId, muted).also { invalidateMidiCache() }

    fun setClipEnabled(trackIndex: Int, clipId: Int, enabled: Boolean) =
        commands.setClipEnabled(trackIndex, clipId, enabled).also { invalidateMidiCache() }

    fun isClipEnabled(trackIndex: Int, clipId: Int) =
        model.sequencer.engine.timeline.isClipEnabled(trackIndex, clipId)

    fun resizeClip(trackIndex: Int, clipId: Int, durationSamples: Long) =
        commands.resizeClip(trackIndex, clipId, durationSamples).also { invalidateMidiCache() }

    fun setClipFilepath(trackIndex: Int, clipId: Int, path: String) =
        commands.setClipFilepath(trackIndex, clipId, path).also { invalidateMidiCache() }

    /** Moves a clip by rewriting its anchor offset, in seconds from the timeline origin. */
    fun moveClip(trackIndex: Int, clipId: Int, seconds: Double) =
        commands.setClipAnchor(
            trackIndex, clipId,
            TimeReference(TimeReferenceType.ContainerStart, "", seconds)
        ).also { invalidateMidiCache() }

    // ── Track mixer (read from the track, write through commands) ────────────

    private var gainGestureOpen = false

    /**
     * Opens an undo *gesture* on the first change of a drag so the whole drag
     * collapses into one history entry, as uapmd-app does around its slider.
     */
    fun setTrackGain(trackIndex: Int, gain: Double): Boolean {
        if (!gainGestureOpen) {
            model.sequencer.engine.timeline.undoEngine.beginGesture("Change track gain")
            gainGestureOpen = true
        }
        return commands.setTrackGain(trackIndex, gain).also { refresh() }
    }

    fun endTrackGainGesture() {
        if (gainGestureOpen) {
            model.sequencer.engine.timeline.undoEngine.endGesture()
            gainGestureOpen = false
            refresh()
        }
    }

    fun setTrackMuted(trackIndex: Int, muted: Boolean) =
        commands.setTrackMuted(trackIndex, muted).also { refresh() }

    /** Ctrl/Cmd-click is additive; otherwise soloing one track clears the others. */
    fun setTrackSolo(trackIndex: Int, solo: Boolean, additive: Boolean = false) {
        model.sequencer.engine.timeline.documentTransaction {
            if (solo && !additive) {
                (0 until model.sequencer.engine.trackCount.toInt()).forEach { i ->
                    if (i != trackIndex) commands.setTrackSolo(i, false)
                }
            }
            commands.setTrackSolo(trackIndex, solo)
        }
        refresh()
    }

    fun setTrackFreezePolicyEnabled(trackIndex: Int, enabled: Boolean) =
        commands.setTrackFreezePolicyEnabled(trackIndex, enabled).also { refresh() }

    fun setPluginBypassed(instanceId: Int, bypassed: Boolean) =
        commands.setPluginBypassed(instanceId, bypassed).also { refresh() }

    fun setPluginGroup(instanceId: Int, group: Int) =
        commands.setPluginGroup(instanceId, group.toUByte()).also { refresh() }

    fun clearClipsFromTrack(trackIndex: Int) {
        model.sequencer.engine.timeline.clearClipsFromTrack(trackIndex)
        invalidateMidiCache()
    }

    /** Empty MIDI 2.0 clip, as uapmd-app's "Add an Empty MIDI2 Clip". */
    fun addEmptyMidiClip(trackIndex: Int, positionSamples: Long = 0L): ClipAddResult {
        val r = model.createEmptyMidiClip(trackIndex, positionSamples, 480u, timeline?.tempo ?: 120.0)
        lastClipResult = r
        invalidateMidiCache()
        return r
    }

    fun removeClip(trackIndex: Int, clipId: Int): Boolean {
        val ok = model.removeClipFromTrack(trackIndex, clipId)
        invalidateMidiCache()
        return ok
    }

    // ── Tracks / timeline ───────────────────────────────────────────────────

    var trackCount by mutableStateOf(0)
        private set
    var timeline by mutableStateOf<TimelineState?>(null)
        private set

    /** Per track, the plugin instances on it, in graph order. */
    var trackInstances by mutableStateOf<List<List<TrackInstance>>>(emptyList())
        private set

    /** Clips on the master track, which sits above the regular tracks. */
    var masterClips by mutableStateOf<List<ClipData>>(emptyList())
        private set
    var masterInstances by mutableStateOf<List<TrackInstance>>(emptyList())
        private set

    /** Per timeline track, its clips. Index matches [trackInstances]. */
    var trackClips by mutableStateOf<List<List<ClipData>>>(emptyList())
        private set

    var playheadSeconds by mutableStateOf(0.0)
        private set

    var inputSpectrum by mutableStateOf(FloatArray(24))
        private set
    var outputSpectrum by mutableStateOf(FloatArray(24))
        private set

    /** MIDI note previews, cached per (track, clip) because decoding is not free. */
    private val noteCache = mutableMapOf<Pair<Int, Int>, List<MidiNoteData>>()

    fun midiNotes(trackIndex: Int, clipId: Int): List<MidiNoteData> =
        noteCache.getOrPut(trackIndex to clipId) {
            model.sequencer.engine.timeline.getMidiClipNotes(trackIndex, clipId) ?: emptyList()
        }

    fun setTrackBypassed(trackIndex: Int, bypassed: Boolean) {
        model.sequencer.engine.timeline.commands.setTrackBypassed(trackIndex, bypassed)
        refresh()
    }

    fun addTrack() = model.addTrack { _, _ -> refresh() }
    fun removeTrack(trackIndex: Int) = model.removeTrack(trackIndex) { _, _ -> refresh() }

    /**
     * Read state back rather than assuming a request took effect — engine
     * transitions in particular are asynchronous.
     */
    fun refresh() {
        isAudioEngineEnabled = model.isAudioEngineEnabled
        isScanning = model.isScanning
        val t = model.transport
        isPlaying = t.isPlaying
        isPaused = t.isPaused
        isRecording = t.isRecording || model.sequencer.engine.midiRecorder?.isRecording == true
        history = model.historyState
        trackCount = model.timelineTrackCount.toInt()
        timeline = model.getTimelineState()

        val engine = model.sequencer.engine
        val count = engine.trackCount.toInt()
        trackInstances = (0 until count).map { ti ->
            engine.getTrack(ti.toUInt()).getOrderedInstanceIds().mapNotNull { id ->
                engine.getPluginInstance(id)?.let { inst ->
                    TrackInstance(id, inst.displayName, inst.formatName)
                }
            }
        }
        trackClips = (0 until model.timelineTrackCount.toInt()).map { ti ->
            model.getTimelineTrack(ti.toUInt()).getClips()
        }

        masterClips = runCatching { model.masterTimelineTrack.getClips() }.getOrDefault(emptyList())
        masterInstances = runCatching {
            engine.masterTrack.getOrderedInstanceIds().mapNotNull { id ->
                engine.getPluginInstance(id)?.let { TrackInstance(id, it.displayName, it.formatName) }
            }
        }.getOrDefault(emptyList())

        val sr = model.sampleRate.takeIf { it > 0 } ?: 48000
        playheadSeconds = engine.playbackPosition.toDouble() / sr

        inputSpectrum = runCatching { engine.getInputSpectrum(24) }.getOrDefault(inputSpectrum)
        outputSpectrum = runCatching { engine.getOutputSpectrum(24) }.getOrDefault(outputSpectrum)

        if (catalog.isEmpty() && !isScanning) refreshCatalog()
    }

    fun shutdown() {
        scope.cancel()
        addins?.shutdown()
        addins?.close()
        nativeUiPresentations.values.forEach { it.close() }
        nativeUiPresentations.clear()
        model.setAudioEngineEnabled(false)
        cleanupUapmdAppModel()
    }

    companion object {
        fun start(): UapmdHost {
            // Ordering is load-bearing: the event loop must exist first (§2.3).
            initPlatformEventLoop()
            instantiateAppModel()
            val host = UapmdHost(getAppModel())
            host.initAddins()
            host.model.notifyUiReady()
            notifyPersistentStorageReadyForPlatform(host.model)
            host.enableAudioEngine(platformStartsWithAudioEngineEnabled)
            startupImportPath()?.let { host.importMidiClip(0, it) }
            return host
        }
    }
}

/**
 * Desktop and mobile start with the engine running; the web build starts with it
 * off, matching uapmd-app's `web_main.cpp` (browsers also require a user gesture
 * before audio can begin).
 */
expect val platformStartsWithAudioEngineEnabled: Boolean

/**
 * Desktop/mobile call `notifyPersistentStorageReady()` directly. On web the
 * binding's `initUapmdWasm()` has already mounted IDBFS before this point.
 */
expect fun notifyPersistentStorageReadyForPlatform(model: AppModel)

expect fun cleanupUapmdAppModel()

/** One plugin instance on a track, flattened for the UI. */
data class TrackInstance(val instanceId: Int, val displayName: String, val formatName: String)

@Composable
fun rememberUapmdHost(): UapmdHost {
    val host = remember { UapmdHost.start() }
    // uapmd state lives in C++ and changes without notifying Compose (async
    // engine transitions, scan completion, history commits), so poll it.
    LaunchedEffect(host) {
        // Dev hook: AppModel scans plugins asynchronously at startup, so the
        // catalog is empty when start() returns. Wait for it, then try
        // candidates until one instantiates - many plugins fail for their own
        // reasons, so the first entry is not a reliable choice.
        var pendingFormat = startupInstantiateFormat()
        while (true) {
            host.refresh()
            if (pendingFormat != null && !host.isScanning && host.catalog.isNotEmpty()) {
                val format = pendingFormat
                pendingFormat = null
                for (entry in host.catalog.filter { it.format == format }.take(12)) {
                    host.instantiate(entry, 0)
                    while (host.isInstantiating) kotlinx.coroutines.delay(50)
                    if (host.lastInstantiation?.error == null) break
                }
            }
            kotlinx.coroutines.delay(100)
        }
    }
    return host
}
