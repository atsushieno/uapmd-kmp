package dev.atsushieno.uapmd.cmp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
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
import dev.atsushieno.uapmd.TimelinePosition
import dev.atsushieno.uapmd.createAudioFileReader
import dev.atsushieno.uapmd.MidiNoteData
import dev.atsushieno.uapmd.PluginInstanceConfig
import dev.atsushieno.uapmd.PluginInstanceResult
import dev.atsushieno.uapmd.ScanMode
import dev.atsushieno.uapmd.TimelineState
import dev.atsushieno.uapmd.UndoState
import dev.atsushieno.uapmd.getAppModel
import dev.atsushieno.uapmd.instantiateAppModel

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

    fun removeInstance(instanceId: Int) {
        model.removePluginInstance(instanceId)
        refresh()
    }

    // ── Project I/O ─────────────────────────────────────────────────────────

    var lastProjectResult by mutableStateOf<AppProjectResult?>(null)
        private set

    fun loadProject(path: String) {
        lastProjectResult = model.loadProject(path)
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

    /** Per timeline track, its clips. Index matches [trackInstances]. */
    var trackClips by mutableStateOf<List<List<ClipData>>>(emptyList())
        private set

    var playheadSeconds by mutableStateOf(0.0)
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
        isRecording = t.isRecording
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

        val sr = model.sampleRate.takeIf { it > 0 } ?: 48000
        playheadSeconds = engine.playbackPosition.toDouble() / sr

        if (catalog.isEmpty() && !isScanning) refreshCatalog()
    }

    fun shutdown() {
        addins?.shutdown()
        addins?.close()
        nativeUiPresentations.values.forEach { it.close() }
        nativeUiPresentations.clear()
        model.setAudioEngineEnabled(false)
        cleanupUapmdAppModel()
    }

    companion object {
        fun start(): UapmdHost {
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
