package dev.atsushieno.uapmd.cmp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import dev.atsushieno.uapmd.AppModel
import dev.atsushieno.uapmd.AppProjectResult
import dev.atsushieno.uapmd.RealtimeSequencer
import dev.atsushieno.uapmd.CatalogEntry
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

    // ── Tracks / timeline ───────────────────────────────────────────────────

    var trackCount by mutableStateOf(0)
        private set
    var timeline by mutableStateOf<TimelineState?>(null)
        private set

    /** Per track, the plugin instances on it, in graph order. */
    var trackInstances by mutableStateOf<List<List<TrackInstance>>>(emptyList())
        private set

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
        if (catalog.isEmpty() && !isScanning) refreshCatalog()
    }

    fun shutdown() {
        model.setAudioEngineEnabled(false)
        cleanupUapmdAppModel()
    }

    companion object {
        fun start(): UapmdHost {
            instantiateAppModel()
            val host = UapmdHost(getAppModel())
            host.model.notifyUiReady()
            notifyPersistentStorageReadyForPlatform(host.model)
            host.enableAudioEngine(platformStartsWithAudioEngineEnabled)
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
        while (true) {
            host.refresh()
            kotlinx.coroutines.delay(100)
        }
    }
    return host
}
