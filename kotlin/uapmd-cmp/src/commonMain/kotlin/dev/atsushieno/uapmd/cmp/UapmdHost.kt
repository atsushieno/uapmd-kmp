package dev.atsushieno.uapmd.cmp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.atsushieno.uapmd.AddinCommandInfo
import dev.atsushieno.uapmd.AudioImportResult
import dev.atsushieno.uapmd.StemSeparatorInfo
import dev.atsushieno.uapmd.TimelineClipTarget
import dev.atsushieno.uapmd.AddinManager
import dev.atsushieno.uapmd.ClipCommandRegistry
import dev.atsushieno.uapmd.ClipCommandTarget
import dev.atsushieno.uapmd.ClipEditorRegistry
import dev.atsushieno.uapmd.CommandRegistry
import dev.atsushieno.uapmd.StemSeparatorRegistry
import dev.atsushieno.uapmd.AppModel
import dev.atsushieno.uapmd.AppProjectResult
import dev.atsushieno.uapmd.AudioIoDirection
import dev.atsushieno.uapmd.BlocklistEntry
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
import dev.atsushieno.uapmd.FreezePolicy
import dev.atsushieno.uapmd.OfflineRenderProgress
import dev.atsushieno.uapmd.FreezeRuntimeState
import dev.atsushieno.uapmd.createAudioFileReader
import dev.atsushieno.uapmd.createSilentAudioFileReader
import dev.atsushieno.uapmd.MidiNoteData
import dev.atsushieno.uapmd.OfflineRenderSettings
import dev.atsushieno.uapmd.PluginInstanceConfig
import dev.atsushieno.uapmd.PluginInstanceResult
import dev.atsushieno.uapmd.ScanMode
import dev.atsushieno.uapmd.SlowScanProgress
import dev.atsushieno.uapmd.TimelineState
import dev.atsushieno.uapmd.UndoState
import dev.atsushieno.uapmd.getAppModel
import dev.atsushieno.uapmd.instantiateAppModel
import dev.atsushieno.uapmd.PreparedProject
import dev.atsushieno.uapmd.prepareProjectLoad

/**
 * Owns the uapmd startup sequence and mirrors AppModel state into Compose.
 *
 * The sequence follows uapmd-app's own `main_common.cpp` / `web_main.cpp`
 * (docs/uapmd-cmp-plan.md §2.5), because uapmd-cmp replaces that entry point and
 * inherits everything it does:
 *
 *   platform event loop  ->  instantiate  ->  UI exists  ->  notifyUiReady
 *   ->  notifyPersistentStorageReady  ->  audio engine to its per-platform
 *   initial state  ...  engine off  ->  cleanup
 *
 * The event-loop step happens earlier, in each platform entry point, because it
 * has to run before AppModel exists at all.
 */
/** The block size uapmd-app runs at on Android; see applyDefaultAudioBufferSize. */
private const val DefaultAudioBufferFrames = 512

class UapmdHost private constructor(val model: AppModel) {

    private val uiDispatcher = kotlinx.coroutines.Dispatchers.Main

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + uiDispatcher
    )

    /**
     * Async engine completions land on whatever thread finished the operation,
     * not the UI thread (verified: `AWT-EventQueue-0` while the caller was
     * `main`). Compose state must only change on the UI thread, so every
     * callback routes its state update through here.
     */
    private fun onUiThread(block: () -> Unit) {
        scope.launch { block() }
    }

    /**
     * Native calls that can block must leave the UI thread. On Android this is
     * not merely a responsiveness matter: instantiating an AAP plugin binds to
     * another process and the bind is completed on the main looper, so issuing
     * it from the main thread deadlocks and the completion never arrives.
     * See [backgroundDispatcher].
     */
    private fun offUiThread(block: suspend () -> Unit) {
        scope.launch(backgroundDispatcher()) { block() }
    }

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

    fun undo() = model.undo { onUiThread { refresh() } }
    fun redo() = model.redo { onUiThread { refresh() } }

    // ── Scanning ────────────────────────────────────────────────────────────

    var isScanning by mutableStateOf(false)
        private set

    /** Previous poll's scanning flag, so a scan's completion can be noticed. */
    private var wasScanning = false

    /** Progress of the running slow scan, as uapmd-app's selector shows it. */
    var scanProgress by mutableStateOf(SlowScanProgress())
        private set

    /** The last scanning error, surfaced rather than swallowed. */
    var scanError by mutableStateOf<String?>(null)
        private set

    /**
     * Drops the scan blocklist. A plug-in that crashed a previous scan stays out
     * of the catalog, which is indistinguishable from "not installed" when a
     * project fails to resolve it.
     */
    fun clearPluginBlocklist() = model.clearPluginBlocklist().also { refreshBlocklist() }

    /**
     * The master track's tempo map, for the beats view. Rebuilt when the project
     * changes rather than per frame — `buildMasterTrackSnapshot()` walks the
     * master clip.
     */
    var tempoMap by mutableStateOf(TempoMap.Empty)
        private set

    fun refreshTempoMap() {
        runCatching {
            model.refreshMasterTempoMap()
            TempoMap.build(model.masterTempoPoints, model.masterTimeSignaturePoints)
        }.onSuccess { tempoMap = it }
    }

    /** AppModel's blocked bundles, as the Plugin Selector lists them. */
    var blocklist by mutableStateOf<List<BlocklistEntry>>(emptyList())
        private set

    fun refreshBlocklist() {
        blocklist = runCatching { model.blocklist }.getOrDefault(emptyList())
    }

    fun unblockPlugin(entryId: String) =
        runCatching { model.unblockPlugin(entryId) }.getOrDefault(false).also { refreshBlocklist() }

    /**
     * [mode] defaults to whatever the platform can do: scanning in a separate
     * process keeps a crashing plug-in from taking the app with it, which an
     * in-process scan cannot, so it is the desktop default as it is in uapmd-app.
     */
    fun scanPlugins(
        forceRescan: Boolean = true,
        mode: ScanMode = if (platformSupportsRemoteScanner) ScanMode.Remote else ScanMode.InProcess,
        remoteTimeoutSeconds: Double = 20.0
    ) {
        offUiThread {
            model.performPluginScanning(
                forceRescan, mode, remoteTimeoutSeconds,
                requireFastScanning = !platformNeedsSlowScan
            )
        }
        refresh()
    }

    fun cancelScan() {
        model.cancelPluginScanning()
        refresh()
    }

    // ── Addins ──────────────────────────────────────────────────────────────
    //
    // The engine publishes its extension points, the host publishes its own,
    // then the manager loads what is installed - the order uapmd-app uses in
    // `MainWindow::MainWindow` (MainWindow.cpp:47-72).
    //
    // Every extension point has to be up before initialize(): an addin attaches
    // to exactly one path and fails to load when the host never published it.
    // Leaving one out is how the MIR, Basic Pitch and DrumScript commands
    // silently fail to appear.

    var addins: AddinManager? = null
        private set

    /** Application-wide commands addins contributed, for the Command menu. */
    var commandRegistry: CommandRegistry? = null
        private set

    /** Clip-scoped commands, offered from a clip's context menu. */
    var clipCommandRegistry: ClipCommandRegistry? = null
        private set

    /**
     * Held only so that addins asking for the clip-editor extension point still
     * load. Their editors draw through an immediate-mode UI loop that Compose
     * does not have, so uapmd-cmp presents none of them.
     */
    var clipEditorRegistry: ClipEditorRegistry? = null
        private set

    /** Stem separation backends, for the split audio import. */
    var stemSeparatorRegistry: StemSeparatorRegistry? = null
        private set

    private fun initAddins() {
        runCatching {
            val manager = createAddinManager()
            model.sequencer.engine.registerAddinExtensionPoints(manager)

            val commands = CommandRegistry.create()
            val clipCommands = ClipCommandRegistry.create()
            val clipEditors = ClipEditorRegistry.create()
            val separators = StemSeparatorRegistry.create()
            manager.registerCommandRegistry(commands)
            manager.registerClipCommandRegistry(clipCommands)
            manager.registerClipEditorRegistry(clipEditors)
            manager.registerStemSeparatorRegistry(separators)

            manager.initialize()

            addins = manager
            commandRegistry = commands
            clipCommandRegistry = clipCommands
            clipEditorRegistry = clipEditors
            stemSeparatorRegistry = separators
        }
    }

    /**
     * Bumped whenever the addin set changes, so anything showing addin-supplied
     * commands or separators recomposes. Enabling an addin adds its entries to
     * the registries and disabling one withdraws them, and neither is something
     * the registries can announce on their own.
     */
    var addinRevision by mutableStateOf(0)
        private set

    fun notifyAddinsChanged() {
        addinRevision++
    }

    /**
     * Commands addins contributed. Re-read rather than cached: enabling or
     * disabling an addin changes the list, and an index is only good until then.
     */
    fun addinCommands(): List<AddinCommandInfo> = commandRegistry?.commands.orEmpty()

    fun invokeAddinCommand(id: String) {
        commandRegistry?.invokeById(id)
        refresh()
    }

    /** The clip commands that apply to this clip, with their live indexes. */
    fun clipCommandsFor(target: ClipCommandTarget): List<Pair<Int, AddinCommandInfo>> {
        val registry = clipCommandRegistry ?: return emptyList()
        return registry.commands.withIndex()
            .filter { (index, _) -> registry.appliesTo(index, target) }
            .map { (index, info) -> index to info.copy(enabled = registry.isEnabled(index, target)) }
    }

    fun invokeClipCommand(index: Int, target: ClipCommandTarget) {
        clipCommandRegistry?.invoke(index, target)
        refresh()
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

    /**
     * Configures the audio device with an explicit block size at startup.
     *
     * Leaving it unset makes the Oboe device come up with
     * `internalCapacity=1024 stabilizedBlock=1024`, and on that configuration the
     * engine cannot sustain real time with a six-plug-in project: measured
     * repeatedly at 87-90% of real time (the playhead advances ~10.7s per 12s of
     * wall clock), which is heard as continuous stuttering. The same project on
     * the same device at 512 measures 99.95-99.98%.
     *
     * The engine's automatic buffer sizing is what chooses 1024 here, so it is
     * turned off: uapmd-app runs at `internalCapacity=512 stabilizedBlock=512`,
     * and this matches it rather than inventing a value. Device Settings still
     * lets the user re-enable auto sizing or pick another size.
     */
    fun applyDefaultAudioBufferSize() {
        model.autoBufferSizeEnabled = false
        val sampleRate = model.sampleRate.takeIf { it > 0 } ?: 48000
        val ok = runCatching {
            model.updateAudioDeviceSettings(sampleRate, DefaultAudioBufferFrames.toUInt())
            sequencer.reconfigureAudioDevice(-1, -1, sampleRate.toUInt(), DefaultAudioBufferFrames.toUInt())
        }.getOrDefault(false)
        println("uapmd.cmp: default audio buffer ${DefaultAudioBufferFrames} applied=$ok")
    }

    /** [trackIndex] < 0 creates a new track, matching the C API. */
    fun instantiate(entry: CatalogEntry, trackIndex: Int, config: PluginInstanceConfig = PluginInstanceConfig()) {
        if (isInstantiating) return
        isInstantiating = true
        offUiThread {
            model.createPluginInstance(entry.format, entry.pluginId, trackIndex, config) { result ->
                onUiThread {
                    lastInstantiation = result
                    isInstantiating = false
                    refresh()
                }
            }
        }
    }

    /**
     * Plug-in state to/from a file, as uapmd-app's Save/Load State buttons.
     * Suspending because both talk to the plug-in and to the filesystem; on
     * Android the plug-in lives in another process. See [backgroundDispatcher].
     */
    suspend fun savePluginState(instanceId: Int, path: String): String =
        withContext(backgroundDispatcher()) {
            val inst = model.sequencer.engine.getPluginInstance(instanceId)
                ?: return@withContext "Instance $instanceId is gone."
            runCatching {
                writeBytesToFile(path, inst.saveStateSync())
                "Saved state to $path"
            }.getOrElse { "Failed to save state: ${it.message}" }
        }

    suspend fun loadPluginState(instanceId: Int, path: String): String =
        withContext(backgroundDispatcher()) {
            val inst = model.sequencer.engine.getPluginInstance(instanceId)
                ?: return@withContext "Instance $instanceId is gone."
            runCatching {
                val bytes = readBytesFromFile(path)
                    ?: return@runCatching "Could not read $path."
                // Route through ProjectCommands so the state change is undoable.
                model.sequencer.engine.timeline.setPluginState(instanceId, bytes)
                "Loaded state from $path"
            }.getOrElse { "Failed to load state: ${it.message}" }
        }

    fun setInstanceGroup(instanceId: Int, group: Int) {
        model.sequencer.engine.timeline.commands.setPluginGroup(instanceId, group.toUByte())
        refresh()
    }

    /** Tears down an out-of-process plug-in on Android, so not on the UI thread. */
    fun removeInstance(instanceId: Int) {
        // The presentation holds the plug-in's UI; letting it outlive the
        // instance leaves a window on screen that nothing can reach any more.
        destroyPluginUi(instanceId)
        offUiThread {
            model.removePluginInstance(instanceId)
            onUiThread { refresh() }
        }
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
    /**
     * Runs off the UI thread - unpacking, engine stop/start and plug-in teardown
     * all block, and on Android tearing down an out-of-process plug-in from the
     * main thread deadlocks the same way instancing does. The steps still happen
     * in the original order; only the thread each runs on differs, so the
     * teardown below still cannot run before a successful prepare.
     */
    /**
     * True while a project load is in flight.
     *
     * The load runs off the UI thread, but the 100 ms poll keeps calling
     * `refresh()`, which reads track and clip state the load is busy replacing.
     * Those reads block on the engine's own locks, so the UI thread stalls for
     * the whole load — the freeze seen opening a `.uapmdz`. The poll skips its
     * refresh while this is set.
     */
    var isLoadingProject by mutableStateOf(false)
        private set

    /**
     * Bumped whenever the engine's tracks are replaced wholesale. UI state
     * derived from track values keys on this so a load re-reads them; native
     * handles are never cached across it.
     */
    var projectRevision by mutableStateOf(0)
        private set

    /**
     * Loads a project, following `composeApp`'s sequence exactly
     * (`UapmdModel.loadProject`), which is the one proven on Android.
     *
     * Three things I had wrong before, all mine, none upstream:
     *  - it went through `AppModel::loadProject`, whose instantiation chain is
     *    marshalled onto the remidy event loop — the Android main thread — so the
     *    second plug-in's service bind was issued from the main looper and blocked
     *    it waiting for its own callback. `TimelineFacade.loadProject` is the
     *    engine-level load composeApp uses, and it does not marshal that way.
     *  - it hopped to the UI thread to close plug-in UIs. Nothing in a load
     *    belongs on the main thread.
     *  - it closed the prepared archive in a `finally`, deleting the unpacked
     *    directory the freshly loaded audio clips still point at. The temp
     *    directory has to outlive the load and is only retired on the next one.
     */
    fun loadProject(path: String) {
        if (isLoadingProject)
            return
        // Set synchronously: posting it through onUiThread left a window where
        // the poll saw "not loading" and refreshed straight into the load.
        isLoadingProject = true
        loadProjectInternal(path)
    }

    /** Kept alive while its project is loaded; the clips reference files inside it. */
    private var activePreparedProject: PreparedProject? = null

    private fun loadProjectInternal(path: String) = offUiThread {
        val prepared = runCatching { prepareProjectLoad(path) }.getOrNull()
        if (prepared == null || !prepared.success) {
            val message = prepared?.error?.ifEmpty { null } ?: "Could not open $path."
            prepared?.close()
            onUiThread {
                lastProjectResult = AppProjectResult(false, message)
                isLoadingProject = false
            }
            return@offUiThread
        }

        // Stop the engine the way composeApp does, at the engine level.
        // AppModel.setAudioEngineEnabled() runs its shutdown through the remidy
        // event loop, which on Android is the main looper — the same thread the
        // load then needs for plug-in service binds.
        val engine = model.sequencer.engine
        val wasRunning = model.isAudioEngineEnabled
        if (wasRunning) {
            engine.setActive(false)
            sequencer.stopAudio()
        }

        // Project replacement destroys the old plugin instances as part of
        // timeline reset. Detach Compose-owned state on its dispatcher, then
        // perform native teardown on this background load coroutine.
        val presentationsToClose = withContext(uiDispatcher) {
            nativeUiVisibleInstanceIds = emptySet()
            nativeUiPresentations.values.toList().also {
                nativeUiPresentations.clear()
            }
        }
        presentationsToClose.forEach { runCatching { it.close() } }

        val result = try {
            engine.timeline.loadProject(prepared.path)
        } finally {
            if (wasRunning) {
                engine.setActive(true)
                sequencer.startAudio()
            }
        }

        if (result.success) {
            // The previous project's unpacked files are only safe to drop once a
            // new project has taken over.
            activePreparedProject?.let { runCatching { it.close() } }
            activePreparedProject = prepared
        } else {
            prepared.close()
        }

        onUiThread {
            if (result.success) {
                platformHostedUiInstanceIds = emptySet()
                selectedMidiClip = null
            }
            lastProjectResult = AppProjectResult(result.success, result.error)
            noteCache.clear()
            isLoadingProject = false
            projectRevision++
            refreshTempoMap()
            refresh()
        }
    }

    /**
     * "New Project", as uapmd-app does it (MainWindow.cpp:750).
     *
     * Goes through the same stop/teardown/restart dance as a load, and for the
     * same reason: replacing the project destroys every live plug-in instance,
     * which must not happen with audio running or with plug-in UIs open.
     *
     * Whether unsaved changes may be discarded is the caller's decision — the
     * engine asks nothing and always replaces — so the toolbar confirms first
     * when the project is dirty.
     */
    fun newProject() {
        if (isLoadingProject)
            return
        isLoadingProject = true
        newProjectInternal()
    }

    private fun newProjectInternal() = offUiThread {
        val engine = model.sequencer.engine
        val wasRunning = model.isAudioEngineEnabled
        if (wasRunning) {
            engine.setActive(false)
            sequencer.stopAudio()
        }

        val presentationsToClose = withContext(uiDispatcher) {
            nativeUiVisibleInstanceIds = emptySet()
            nativeUiPresentations.values.toList().also {
                nativeUiPresentations.clear()
            }
        }
        presentationsToClose.forEach { runCatching { it.close() } }

        val result = try {
            model.newProject()
        } finally {
            if (wasRunning) {
                engine.setActive(true)
                sequencer.startAudio()
            }
        }

        // The outgoing project's unpacked archive is only safe to drop once the
        // timeline no longer references the files inside it.
        if (result.success) {
            activePreparedProject?.let { runCatching { it.close() } }
            activePreparedProject = null
        }

        onUiThread {
            if (result.success) {
                platformHostedUiInstanceIds = emptySet()
                selectedMidiClip = null
            }
            lastProjectResult = result
            noteCache.clear()
            isLoadingProject = false
            projectRevision++
            refreshTempoMap()
            refresh()
        }
    }

    fun saveProject(path: String) = offUiThread {
        model.saveProject(path) { result ->
            onUiThread {
                lastProjectResult = result
                refresh()
            }
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

    /**
     * Which native plug-in UIs are on screen.
     *
     * A snapshot rather than a live `presentation.isVisible` read, because the
     * presentations live in a plain map: Compose has nothing to subscribe to
     * there, so the Show/Hide labels on the track menu and in Instance Details
     * never changed after a toggle. [refresh] re-reads it so a plug-in window
     * closed by its own title bar is noticed too.
     */
    private var nativeUiVisibleInstanceIds by mutableStateOf<Set<Int>>(emptySet())

    fun reportPluginUiStatus(message: String?) { pluginUiStatusMessage = message }

    fun isPluginUiVisible(instanceId: Int): Boolean =
        instanceId in nativeUiVisibleInstanceIds || instanceId in platformHostedUiInstanceIds

    /**
     * Re-reads visibility from the presentations themselves. Called from
     * [refresh] on the structural cadence.
     *
     * Only the UIs believed to be on screen are read: a UI never appears
     * without [showPluginUi], so the one transition this has to catch is a
     * plug-in window closed from its own title bar — and each read is a
     * blocking hop to the native UI thread, so an idle project costs nothing.
     */
    private fun syncNativeUiVisibility() {
        if (nativeUiVisibleInstanceIds.isEmpty())
            return
        val stillVisible = nativeUiVisibleInstanceIds.filterTo(mutableSetOf()) { id ->
            nativeUiPresentations[id]?.let { runCatching { it.isVisible }.getOrDefault(false) } == true
        }
        if (stillVisible != nativeUiVisibleInstanceIds) nativeUiVisibleInstanceIds = stillVisible
    }

    private fun markNativeUiVisible(instanceId: Int, visible: Boolean) {
        nativeUiVisibleInstanceIds =
            if (visible) nativeUiVisibleInstanceIds + instanceId
            else nativeUiVisibleInstanceIds - instanceId
    }

    fun showPluginUi(instanceId: Int) {
        if (isLoadingProject) {
            pluginUiStatusMessage = "Wait for the project to finish loading before opening a plug-in UI."
            return
        }
        val inst = model.sequencer.engine.getPluginInstance(instanceId) ?: return

        // Android AAP plugins are hosted by the platform's own view system.
        if (supportsPlatformHostedPluginUi(inst)) {
            platformHostedUiInstanceIds = platformHostedUiInstanceIds + instanceId
            pluginUiStatusMessage = null
            return
        }

        // An existing presentation is shown again, never rebuilt: uapmd-app
        // keeps the plug-in's UI and its container alive across Hide/Show
        // (MainWindow::handleHideUI), and destroying it here is what threw the
        // plug-in's own UI state away on every toggle.
        nativeUiPresentations[instanceId]?.let { existing ->
            val shown = existing.show()
            markNativeUiVisible(instanceId, shown)
            pluginUiStatusMessage =
                if (!shown) "Failed to show the UI for ${inst.displayName}." else null
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
        val shown = presentation.show()
        markNativeUiVisible(instanceId, shown)
        pluginUiStatusMessage = when {
            !shown -> "Created the UI for ${inst.displayName}, but show() failed."
            request.host is PluginUiHost.FloatingWindow -> null
            else -> "Attached ${inst.displayName} to the ${target?.description ?: "embedded surface"}."
        }
    }

    /**
     * Hides the UI, keeping it instantiated — the counterpart of [showPluginUi]
     * and what uapmd-app's single Show UI / Hide UI button does. Use
     * [destroyPluginUi] to actually tear the UI down.
     */
    fun hidePluginUi(instanceId: Int) {
        platformHostedUiInstanceIds = platformHostedUiInstanceIds - instanceId
        nativeUiPresentations[instanceId]?.hide()
        markNativeUiVisible(instanceId, false)
    }

    /** Destroys the UI. For teardown — removing the instance, replacing the project. */
    fun destroyPluginUi(instanceId: Int) {
        platformHostedUiInstanceIds = platformHostedUiInstanceIds - instanceId
        nativeUiPresentations.remove(instanceId)?.let { runCatching { it.close() } }
        markNativeUiVisible(instanceId, false)
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

    private fun samplesAt(seconds: Double): Long {
        val sampleRate = (model.sampleRate.takeIf { it > 0 } ?: 48000).toDouble()
        return (seconds.coerceAtLeast(0.0) * sampleRate).toLong()
    }

    /** Last result of the multi-track SMF import, for the toolbar to report. */
    var lastImportStatus by mutableStateOf<String?>(null)
        private set

    /**
     * uapmd-app's Import ▸ MIDI Tracks: one new track per SMF track, with a
     * master-track clip for any source track carrying tempo data.
     */
    fun importMidiTracks(filePath: String) = offUiThread {
        model.importMidiTracksFromFile(filePath) { success, error, count ->
            onUiThread {
                lastImportStatus =
                    if (success) "Imported $count track(s) from $filePath."
                    else "Import failed: ${error ?: "unknown error"}"
                invalidateClips()
                refresh()
            }
        }
    }

    // ── Timeline clip selection and clipboard ───────────────────────────────
    //
    // The selection and the clipboard live in AppModel, not here:
    // upstream moved them out of its GUI precisely so every host behaves the
    // same. This is a thin surface over that, plus a revision counter so the
    // lanes recompose when the selection changes — the model cannot announce it.

    var selectionRevision by mutableStateOf(0)
        private set

    /** Last clipboard/selection failure, shown in the toolbar's status line. */
    var lastSelectionError by mutableStateOf<String?>(null)
        private set

    private fun selectionChanged() {
        selectionRevision++
        refresh()
    }

    fun isClipSelected(trackIndex: Int, clipId: Int): Boolean =
        runCatching { model.isTimelineClipSelected(trackIndex, clipId) }.getOrDefault(false)

    fun selectedClips(): List<TimelineClipTarget> =
        runCatching { model.selectedTimelineClips }.getOrDefault(emptyList())

    /**
     * [additive] extends the selection (shift-click, shift-marquee), [toggle]
     * flips each clip within it (ctrl/cmd-click). Both false replaces it, and an
     * empty list with both false clears it — the three outcomes uapmd-app's
     * marquee produces (`TimelineClipSelection.hpp`).
     */
    fun selectClips(clips: List<TimelineClipTarget>, additive: Boolean = false, toggle: Boolean = false) {
        runCatching { model.selectTimelineClips(clips, additive, toggle) }
        selectionChanged()
    }

    fun clearClipSelection() {
        runCatching { model.clearTimelineClipSelection() }
        selectionChanged()
    }

    val clipboardCount: Int
        get() = runCatching { model.timelineClipboardCount }.getOrDefault(0)

    fun copySelectedClips() {
        val ok = runCatching { model.copySelectedTimelineClips() }.getOrDefault(false)
        lastSelectionError = if (ok) null else model.lastTimelineClipError.ifEmpty { null }
        selectionChanged()
    }

    /** [cut] copies the selection before removing it. */
    fun deleteSelectedClips(cut: Boolean = false) {
        val result = runCatching { model.deleteSelectedTimelineClips(cut) }.getOrNull()
        lastSelectionError = result?.error
        invalidateClips()
        selectionChanged()
    }

    /**
     * Pastes at [positionSeconds]. [originalTracks] puts each clip back on the
     * track it came from; otherwise they land relative to [trackIndex].
     */
    fun pasteClips(trackIndex: Int, positionSeconds: Double, originalTracks: Boolean = false) {
        val result = runCatching {
            model.pasteTimelineClips(trackIndex, positionSeconds, originalTracks)
        }.getOrNull()
        lastSelectionError = result?.error
        // A successful paste selects what it created, so the next action acts on
        // the new clips rather than on the ones they came from.
        if (result?.success == true && result.pasted.isNotEmpty())
            runCatching { model.selectTimelineClips(result.pasted, additive = false, toggle = false) }
        invalidateClips()
        selectionChanged()
    }

    /** Whether a paste onto this track would succeed, for enabling the menu item. */
    fun canPasteOnto(trackIndex: Int, originalTracks: Boolean = false): Boolean =
        clipboardCount > 0 &&
            runCatching { model.timelinePasteDestinations(trackIndex, originalTracks) }
                .getOrDefault(emptyList()).isNotEmpty()

    // ── Split audio import (stem separation) ────────────────────────────────
    //
    // uapmd-app's AudioImportWindow: pick an audio file (and a model file, for
    // separators that need one), run the separation on a worker, then turn each
    // stem into its own track and clip inside one history step.

    /** Live progress of a running import, for the import dialog. */
    data class StemImportStatus(
        val running: Boolean = false,
        val completed: Boolean = false,
        val success: Boolean = false,
        val canceled: Boolean = false,
        val progress: Float = 0f,
        val message: String = "",
        val error: String? = null
    )

    var stemImportStatus by mutableStateOf(StemImportStatus())
        private set

    /** Backends addins contributed; empty means no separator addin is enabled. */
    fun stemSeparators(): List<StemSeparatorInfo> = stemSeparatorRegistry?.separators.orEmpty()

    /**
     * Set while an import runs; the dialog's Cancel flips it. Read from the
     * worker through the progress callback, which is the separator's only
     * cancellation point.
     */
    private var cancelStemImport = false

    fun cancelStemImport() {
        cancelStemImport = true
        stemImportStatus = stemImportStatus.copy(message = "Cancelling…")
    }

    fun resetStemImportStatus() {
        stemImportStatus = StemImportStatus()
    }

    /**
     * Separates [audioFile] into stems and adds each as a new track.
     *
     * The separation is a neural model over the whole file, so it runs off the
     * UI thread; [outputDirectory] is where the stem files are written, and
     * [modelPath] is the model file the separator asked for (null when it needs
     * none).
     */
    fun importSplitAudioTracks(
        separatorId: String,
        audioFile: String,
        outputDirectory: String,
        modelPath: String?
    ) {
        val registry = stemSeparatorRegistry ?: return
        if (stemImportStatus.running)
            return
        cancelStemImport = false
        stemImportStatus = StemImportStatus(running = true, message = "Starting…")

        offUiThread {
            val result = runCatching {
                registry.importAudioFile(separatorId, audioFile, outputDirectory, modelPath) { progress, message ->
                    onUiThread {
                        stemImportStatus = stemImportStatus.copy(
                            progress = progress.coerceIn(0f, 1f),
                            message = message
                        )
                    }
                    !cancelStemImport
                }
            }.getOrElse { throwable ->
                AudioImportResult(false, false, throwable.message ?: "Stem separation failed", emptyList(), emptyList())
            }

            onUiThread {
                stemImportStatus = StemImportStatus(
                    running = false,
                    completed = true,
                    success = result.success,
                    canceled = result.canceled,
                    progress = if (result.success) 1f else stemImportStatus.progress,
                    message = when {
                        result.canceled -> "Import cancelled."
                        result.success -> "Import complete."
                        else -> result.error ?: "Import failed."
                    },
                    error = result.error
                )
                if (result.success && !result.canceled)
                    applyStemImportResult(result)
            }
        }
    }

    /**
     * One track and one clip per stem, recorded as a single history step so the
     * whole import undoes in one go (TimelineEditor::applyAudioImportResult).
     *
     * addTrack() is asynchronous — a track owns live plug-in instances — so the
     * stems are applied one at a time, each from the previous one's callback,
     * rather than in a loop.
     */
    private fun applyStemImportResult(result: AudioImportResult) {
        if (result.stems.isEmpty()) {
            lastImportStatus = "No stems were imported."
            return
        }
        val warnings = result.warnings.toMutableList()
        val history = model.sequencer.engine.timeline.commands.history
        val ownsStep = !history.state.compoundOpen
        if (ownsStep) {
            val opened = history.beginStep("Import audio stems")
            if (!opened.succeeded) {
                lastImportStatus = "Import failed: ${opened.error ?: "could not open a history step"}"
                return
            }
        }

        fun finish(imported: Int) {
            if (ownsStep)
                history.endStep()
            lastImportStatus = when {
                imported == 0 -> "No stems were imported." + warningSuffix(warnings)
                else -> "Imported $imported stem(s)." + warningSuffix(warnings)
            }
            invalidateClips()
            refresh()
        }

        fun applyNext(index: Int, imported: Int) {
            if (index >= result.stems.size) {
                finish(imported)
                return
            }
            val stem = result.stems[index]
            model.addTrack { trackIndex, error ->
                onUiThread {
                    if (trackIndex < 0 || !error.isNullOrEmpty()) {
                        warnings += "${stem.clipDisplayName}: ${error ?: "Failed to create track"}"
                        applyNext(index + 1, imported)
                        return@onUiThread
                    }
                    val reader = runCatching { createAudioFileReader(stem.filepath) }.getOrNull()
                    if (reader == null) {
                        warnings += "${stem.clipDisplayName}: Failed to open stem audio"
                        applyNext(index + 1, imported)
                        return@onUiThread
                    }
                    val timeline = model.sequencer.engine.timeline
                    val clip = timeline.addAudioClip(
                        trackIndex, TimelinePosition(0L, 0.0), reader, stem.filepath
                    )
                    if (!clip.success) {
                        warnings += "${stem.clipDisplayName}: ${clip.error ?: "Failed to add clip"}"
                        applyNext(index + 1, imported)
                        return@onUiThread
                    }
                    timeline.commands.setClipName(trackIndex, clip.clipId, stem.clipDisplayName)
                    timeline.commands.setClipNeedsFileSave(trackIndex, clip.clipId, true)
                    applyNext(index + 1, imported + 1)
                }
            }
        }

        applyNext(0, 0)
    }

    private fun warningSuffix(warnings: List<String>): String =
        if (warnings.isEmpty()) "" else " Warnings: " + warnings.joinToString("; ")

    /** SMF or .midi2, added at [positionSeconds] on [trackIndex]. */
    fun importMidiClip(trackIndex: Int, filePath: String, positionSeconds: Double = 0.0) {
        lastClipResult = model.sequencer.engine.timeline
            .addMidiClipFromFile(trackIndex, TimelinePosition(samplesAt(positionSeconds), 0.0), filePath)
        invalidateClips()
        refresh()
    }

    fun importAudioClip(trackIndex: Int, filePath: String, positionSeconds: Double = 0.0) {
        val reader = createAudioFileReader(filePath)
        lastClipResult = model.sequencer.engine.timeline
            .addAudioClip(trackIndex, TimelinePosition(samplesAt(positionSeconds), 0.0), reader, filePath)
        invalidateClips()
        refresh()
    }

    /**
     * uapmd-app's "Add Empty Audio Clip": a clip backed by a silent reader
     * sized to the range, with no source file
     * (`TimelineEditor::addEmptyAudioClipInRange`). The master track takes only
     * MIDI clips, as it does there.
     */
    fun addEmptyAudioClip(trackIndex: Int, startSeconds: Double, endSeconds: Double) {
        // uapmd's kMasterTrackIndex; the master track takes only MIDI clips.
        if (trackIndex == Int.MIN_VALUE) {
            lastClipResult = ClipAddResult(-1, -1, false, "The master track only accepts MIDI/SMF clips.")
            return
        }
        val sampleRate = model.sampleRate.takeIf { it > 0 } ?: 48000
        val frames = ((endSeconds - startSeconds).coerceAtLeast(0.0) * sampleRate).toLong().coerceAtLeast(1L)
        val channels = runCatching { model.getTimelineTrack(trackIndex.toUInt()).channelCount }
            .getOrDefault(2).coerceAtLeast(1)
        val reader = createSilentAudioFileReader(frames, channels, sampleRate)
        lastClipResult = model.sequencer.engine.timeline
            .addAudioClip(trackIndex, TimelinePosition(samplesAt(startSeconds), 0.0), reader, "")
        invalidateClips()
        refresh()
    }

    /** The one-second default uapmd-app uses when there is no dragged range. */
    fun addEmptyAudioClip(trackIndex: Int, positionSeconds: Double) =
        addEmptyAudioClip(trackIndex, positionSeconds, positionSeconds + 1.0)

    private fun invalidateClips() = noteCache.clear()

    /** Call after editing a clip's UMP stream so previews re-decode. */
    fun invalidateMidiCache() {
        noteCache.clear()
        refresh()
    }

    // ── Step sequencer ───────────────────────────────────────────────────────

    /**
     * The pattern a MIDI clip was baked from, or null when the clip carries no
     * step metadata — which is how uapmd-app tells "reopen this pattern" apart
     * from "this clip came from somewhere else and opening it would overwrite
     * whatever is in it".
     */
    fun readStepPattern(trackIndex: Int, clipId: Int): StepSequencerModel.Pattern? {
        val result = model.getMidiClipUmpEvents(trackIndex, clipId)
        if (!result.success) return null
        val (words, ticks) = result.flatten()
        return StepSequencerModel.readPattern(words, ticks, result.tickResolutionOrDefault)
    }

    /** An empty pattern on the clip's own tick grid, for a clip with no metadata. */
    fun newStepPattern(trackIndex: Int, clipId: Int): StepSequencerModel.Pattern =
        StepSequencerModel.emptyPattern(
            model.getMidiClipUmpEvents(trackIndex, clipId).tickResolutionOrDefault
        )

    /** The clip's ticks per quarter note, as the engine reports it. */
    fun clipTickResolution(trackIndex: Int, clipId: Int): Int =
        model.getMidiClipUmpEvents(trackIndex, clipId).tickResolutionOrDefault

    /** The clip's own tempo, which the piano roll's beats-in-view zoom scales by. */
    fun clipTempo(trackIndex: Int, clipId: Int): Double =
        model.getMidiClipUmpEvents(trackIndex, clipId).clipTempo.takeIf { it > 0.0 } ?: 120.0

    /**
     * Bakes [pattern] into the clip, keeping every event the editor does not own.
     *
     * Goes through the timeline facade's `replaceMidiClipContent`, which is the
     * same call uapmd-app's `applyStepSequencerEdits` makes, so the edit lands on
     * the history like any other.
     */
    fun applyStepPattern(
        trackIndex: Int, clipId: Int, pattern: StepSequencerModel.Pattern
    ): String? {
        val existing = model.getMidiClipUmpEvents(trackIndex, clipId)
        if (!existing.success)
            return existing.error ?: "Could not read the clip's events."
        val (words, ticks) = existing.flatten()
        val baked = StepSequencerModel.bake(pattern, words, ticks)
        // replaceMidiClipContent wants one tick entry per UMP *word*, not per
        // event — see the note in UmpNotes.kt.
        val newWords = baked.flatMap { it.words.toList() }.toUIntArray()
        val newTicks = baked.flatMap { e -> List(e.words.size) { e.tick } }.toLongArray()
        val ok = model.sequencer.engine.timeline
            .replaceMidiClipContent(trackIndex, clipId, newWords, newTicks)
        if (!ok) return "Failed to replace MIDI clip data."
        invalidateMidiCache()
        projectRevision++
        return null
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
    /**
     * Anchor, origin and offset in one command, as the Sequence Editor's
     * Anchor / Origin / Position columns edit them together.
     */
    fun setClipAnchor(trackIndex: Int, clipId: Int, anchor: TimeReference) =
        commands.setClipAnchor(trackIndex, clipId, anchor).also { invalidateMidiCache() }

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
            model.sequencer.engine.timeline.commands.history.beginGesture("Change track gain")
            gainGestureOpen = true
        }
        return commands.setTrackGain(trackIndex, gain).also { refresh() }
    }

    fun endTrackGainGesture() {
        if (gainGestureOpen) {
            model.sequencer.engine.timeline.commands.history.endGesture()
            gainGestureOpen = false
            refresh()
        }
    }

    fun setTrackMuted(trackIndex: Int, muted: Boolean) {
        model.setTrackMuted(trackIndex, muted)
        refresh()
    }

    fun setTrackSolo(trackIndex: Int, solo: Boolean) {
        model.setTrackSolo(trackIndex, solo)
        refresh()
    }

    fun setTrackFreezePolicyEnabled(trackIndex: Int, enabled: Boolean) =
        commands.setTrackFreezePolicyEnabled(trackIndex, enabled).also { refresh() }

    /**
     * Track state, looked up fresh on every read.
     *
     * A `SequencerTrack` handle must never be cached across a project load:
     * loading frees the engine's tracks and builds new ones, so a composable
     * holding the old pointer is holding freed memory. The legend used to
     * `remember` it keyed on the track count, which does not change when the
     * same project is loaded twice — the second load then crashed in
     * `uapmd_track_get_muted` on a dangling pointer.
     */
    private fun engineTrackAt(trackIndex: Int) = runCatching {
        if (trackIndex == Int.MIN_VALUE) model.sequencer.engine.masterTrack
        else model.sequencer.engine.getTrack(trackIndex.toUInt())
    }.getOrNull()

    fun trackExists(trackIndex: Int) = engineTrackAt(trackIndex) != null
    fun trackGain(trackIndex: Int) = engineTrackAt(trackIndex)?.gain ?: 1.0
    fun trackBypassed(trackIndex: Int) = engineTrackAt(trackIndex)?.bypassed ?: false

    /*
     * Mute and solo are mirrored into Compose state for the same reason freeze
     * is: a native read is not observable, so a legend that reads the engine
     * directly never recomposes when the value changes.
     *
     * For a *toggle* that is not cosmetic but fatal: the button sends
     * `!currentValue`, so a row that never recomposes reads the value it was
     * first composed with and every click sends the same command — solo
     * switches on and never off.
     */
    var trackMutedFlags by mutableStateOf<List<Boolean>>(emptyList())
        private set
    var trackSoloFlags by mutableStateOf<List<Boolean>>(emptyList())
        private set

    fun trackMuted(trackIndex: Int) =
        trackMutedFlags.getOrNull(trackIndex) ?: model.isTrackMuted(trackIndex)

    fun trackSolo(trackIndex: Int) =
        trackSoloFlags.getOrNull(trackIndex) ?: model.isTrackSolo(trackIndex)

    /*
     * Freeze state is mirrored into Compose state by the poll, like every other
     * engine value the UI shows.
     *
     * Reading the engine directly from a composable does not work: nothing about
     * a native read is observable, so a freeze that starts, progresses and
     * finishes never triggers recomposition. The symptom is that the freeze
     * button appears dead and the state only appears later, when some unrelated
     * edit — moving a clip — invalidates the composition for its own reasons.
     */
    var trackFreezePolicies by mutableStateOf<List<FreezePolicy>>(emptyList())
        private set
    var trackFreezeStates by mutableStateOf<List<FreezeRuntimeState>>(emptyList())
        private set
    var trackBusyFlags by mutableStateOf<List<Boolean>>(emptyList())
        private set

    /** Track number (1-based) and progress of the running freeze render, if any. */
    var freezeRender by mutableStateOf<Pair<Int, OfflineRenderProgress>?>(null)
        private set

    /** What uapmd-app's freeze button renders: the policy, and whether it is busy. */
    fun trackFreezePolicy(trackIndex: Int) =
        trackFreezePolicies.getOrNull(trackIndex) ?: FreezePolicy.Off

    fun trackFreezeState(trackIndex: Int) =
        trackFreezeStates.getOrNull(trackIndex) ?: FreezeRuntimeState.Live

    fun isTrackBusy(trackIndex: Int) = trackBusyFlags.getOrNull(trackIndex) ?: false

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

    /** Counts completions actually delivered by the engine; the dev hook reports it. */
    internal var addTrackCompletions = 0
        private set

    fun addTrack() = offUiThread {
        model.addTrack { _, _ -> addTrackCompletions++; onUiThread { refresh() } }
    }

    fun removeTrack(trackIndex: Int) = offUiThread {
        model.removeTrack(trackIndex) { _, _ -> onUiThread { refresh() } }
    }

    /**
     * Read state back rather than assuming a request took effect — engine
     * transitions in particular are asynchronous.
     */
    /**
     * Mirrors engine state into Compose state.
     *
     * Every per-track lookup is guarded. Track mutations are asynchronous, so
     * the track *count* can update before the track itself is retrievable, and
     * both `getTrack()` and `getTimelineTrack()` throw on a miss — with this
     * polling every 100 ms, that window was reliably hit and crashed the app on
     * "+ Add Track".
     *
     * Both lists are also built from **one** count. Reading `engine.trackCount`
     * for one and `timelineTrackCount` for the other let the legend and the
     * lanes disagree mid-mutation.
     */
    /**
     * [structural] re-reads the things that only change when the project does:
     * every track's plug-in list (each one costing two JNI string reads), every
     * track's clips, and the history state.
     *
     * The poll runs at 10 Hz, and doing all of that every tick measurably stole
     * CPU from the audio callback - with the poll on, the engine reported 132%
     * of its buffer budget against 108% with it off, i.e. the UI was taking
     * roughly a quarter of the time the audio thread needed. Transport and
     * meters still update every tick; structure is re-read on a slower cadence
     * and whenever something actually edits the project.
     */
    fun refresh(structural: Boolean = true) {
        isAudioEngineEnabled = model.isAudioEngineEnabled
        isScanning = model.isScanning
        scanProgress = runCatching { model.slowScanProgress }.getOrDefault(SlowScanProgress())
        scanError = runCatching { model.lastPluginScanError }.getOrNull()
        val t = model.transport
        isPlaying = t.isPlaying
        isPaused = t.isPaused
        isRecording = t.isRecording || model.sequencer.engine.midiRecorder?.isRecording == true
        timeline = model.getTimelineState()
        if (structural) history = model.historyState

        val engine = model.sequencer.engine
        val count = minOf(engine.trackCount.toInt(), model.timelineTrackCount.toInt())
        trackCount = count

        // Freeze: policy, runtime state, busy flag and the one running render.
        // Every tick, not only structural ones — progress is what makes the wait
        // legible, and it moves far faster than the structural cadence.
        trackFreezePolicies = (0 until count).map {
            runCatching { engine.trackFreezePolicy(it) }.getOrDefault(FreezePolicy.Off)
        }
        trackFreezeStates = (0 until count).map {
            runCatching { engine.trackFreezeState(it) }.getOrDefault(FreezeRuntimeState.Live)
        }
        trackBusyFlags = (0 until count).map {
            runCatching { engine.isTrackBusy(it) }.getOrDefault(false)
        }
        // Every tick, not only structural ones: these back the M and S buttons,
        // and a stale value there makes the toggle send the wrong command.
        trackMutedFlags = (0 until count).map {
            runCatching { model.isTrackMuted(it) }.getOrDefault(false)
        }
        trackSoloFlags = (0 until count).map {
            runCatching { model.isTrackSolo(it) }.getOrDefault(false)
        }
        // Only one track renders at a time, so stop at the first that reports.
        freezeRender = (0 until count).firstNotNullOfOrNull { i ->
            runCatching { engine.trackFreezeRenderProgress(i) }.getOrNull()?.let { (i + 1) to it }
        }

        if (structural) trackInstances = (0 until count).map { ti ->
            runCatching {
                engine.getTrack(ti.toUInt()).getOrderedInstanceIds().mapNotNull { id ->
                    engine.getPluginInstance(id)?.let { inst ->
                        TrackInstance(id, inst.displayName, inst.formatName)
                    }
                }
            }.getOrDefault(emptyList())
        }
        if (structural) syncNativeUiVisibility()
        if (structural) trackClips = (0 until count).map { ti ->
            runCatching { model.getTimelineTrack(ti.toUInt()).getClips() }.getOrDefault(emptyList())
        }

        if (structural) masterClips =
            runCatching { model.masterTimelineTrack.getClips() }.getOrDefault(emptyList())
        if (structural) masterInstances = runCatching {
            engine.masterTrack.getOrderedInstanceIds().mapNotNull { id ->
                engine.getPluginInstance(id)?.let { TrackInstance(id, it.displayName, it.formatName) }
            }
        }.getOrDefault(emptyList())

        val sr = model.sampleRate.takeIf { it > 0 } ?: 48000
        playheadSeconds = engine.playbackPosition.toDouble() / sr

        inputSpectrum = runCatching { engine.getInputSpectrum(24) }.getOrDefault(inputSpectrum)
        outputSpectrum = runCatching { engine.getOutputSpectrum(24) }.getOrDefault(outputSpectrum)

        // A finished scan is the moment the catalog changed, so refresh on the
        // falling edge of `isScanning`. Refreshing only while the catalog is
        // empty would mean a rescan never reaches the list: press Scan, watch
        // nothing happen, conclude scanning is broken.
        if (wasScanning && !isScanning) refreshCatalog()
        wasScanning = isScanning
        if (catalog.isEmpty() && !isScanning) refreshCatalog()
    }

    fun shutdown() {
        scope.cancel()
        val presentationsToClose = nativeUiPresentations.values.toList()
        nativeUiPresentations.clear()
        nativeUiVisibleInstanceIds = emptySet()
        presentationsToClose.forEach { runCatching { it.close() } }
        addins?.shutdown()
        addins?.close()
        model.setAudioEngineEnabled(false)
        cleanupUapmdAppModel()
    }

    companion object {
        /**
         * Wraps an AppModel that is already running, for the headless UI
         * snapshot tool — it drives the bootstrap itself and only needs a host
         * to render against.
         */
        internal fun attach(model: AppModel) = UapmdHost(model)

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
/**
 * Whether this platform can scan plug-ins in a separate process.
 *
 * Desktop only, matching uapmd-app's `kRemoteScannerSupported`
 * (`PluginSelector.cpp:15`): Android, iOS and the browser have no way to launch a
 * scanner process. It matters because an in-process scan runs every plug-in's entry
 * code inside the app, so one bad plug-in takes the whole app down mid-scan — which
 * is why uapmd-app defaults to the remote scanner wherever it exists.
 */
/**
 * Whether this platform's formats need the slow, bundle-by-bundle scan at all.
 *
 * AAP does not: `PluginScanningAAP::getAllFastScannablePlugins` returns every
 * installed plug-in from the package manager, and its `startSlowPluginScan` is a
 * no-op that reports completion immediately. Desktop formats and WebCLAP do - a
 * VST3/AU/LV2/CLAP bundle has to be opened to be described, and WebCLAP's fast list
 * is empty by construction.
 *
 * The fast list is collected either way (`PluginScanTool.cpp:306`), so this only
 * decides whether to ask for work that would find nothing.
 */
/**
 * Whether scanning needs the audio engine running first.
 *
 * On the web it does, and not incidentally: WebCLAP bundles are fetched and
 * inspected by the AudioWorklet, and the bridge that carries the request only has a
 * transport once `WebAudioWorkletIODevice::start()` has created the worklet node.
 * Scanning with the engine off queues a request nothing will ever deliver, and the
 * scan then sits at 0 bundles - uncancellable, because cancellation is only checked
 * between bundles.
 */
expect val platformNeedsAudioEngineForScan: Boolean

expect val platformNeedsSlowScan: Boolean

expect val platformSupportsRemoteScanner: Boolean

expect val platformStartsWithAudioEngineEnabled: Boolean

/**
 * Desktop/mobile call `notifyPersistentStorageReady()` directly. On web the
 * binding's `initUapmdWasm()` has already mounted IDBFS before this point.
 */
expect fun notifyPersistentStorageReadyForPlatform(model: AppModel)

expect fun cleanupUapmdAppModel()

private fun fixedSeconds(v: Double): String = ((v * 100).toLong() / 100.0).toString()

/** Monotonic elapsed milliseconds, for the dev hook's stall measurement. */
private val clockOrigin = kotlin.time.TimeSource.Monotonic.markNow()

private fun nowMillis(): Long = clockOrigin.elapsedNow().inWholeMilliseconds

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
        launch { runStartupDevHooks(host) }
        var pendingFormat = startupInstantiateFormat()
        var pollTick = 0
        while (true) {
            // Skip the poll while a project load owns the engine; refreshing
            // through it is what froze the UI.
            if (!host.isLoadingProject && !startupSuppressPolling()) {
                pollTick++
                host.refresh(structural = pollTick % 5 == 0)
            }
            tickPlatformFilePicker()
            if (pendingFormat != null && !host.isScanning && host.catalog.isNotEmpty()) {
                val format = pendingFormat
                pendingFormat = null
                println(
                    "uapmd.cmp dev hook: catalog=${host.catalog.size} by format=" +
                        host.catalog.groupingBy { it.format }.eachCount()
                )
                // "*" means "whatever the scan found" - useful when the set of
                // formats on the device is not known up front.
                val candidates =
                    if (format == "*") host.catalog else host.catalog.filter { it.format == format }
                // Keep going after the first success when a count is asked for,
                // so repeated instancing is exercised, not just the first one.
                var succeeded = 0
                for (entry in candidates.take(12)) {
                    println("uapmd.cmp dev hook: instantiating ${entry.format} ${entry.pluginId} ${entry.displayName}")
                    host.instantiate(entry, 0)
                    var waited = 0
                    while (host.isInstantiating && waited < 30_000) {
                        kotlinx.coroutines.delay(50); waited += 50
                    }
                    println(
                        if (host.isInstantiating) "uapmd.cmp dev hook: TIMED OUT after ${waited}ms - no completion"
                        else "uapmd.cmp dev hook: completed in ${waited}ms err=${host.lastInstantiation?.error} " +
                            "id=${host.lastInstantiation?.instanceId}"
                    )
                    if (host.isInstantiating) break
                    if (host.lastInstantiation?.error == null) {
                        succeeded++
                        if (succeeded >= startupInstantiateCount()) break
                    }
                }
                startupSaveProjectPath()?.let { savePath ->
                    kotlinx.coroutines.delay(1500)
                    host.saveProject(savePath)
                    kotlinx.coroutines.delay(4000)
                    println(
                        "uapmd.cmp dev hook: saveProject -> ${host.lastProjectResult?.success} " +
                            "err=${host.lastProjectResult?.error} path=$savePath"
                    )
                }
            }
            kotlinx.coroutines.delay(100)
        }
    }
    return host
}

/**
 * Dev hook: instantiate one catalog entry by name and show its plug-in UI.
 *
 * Split out of the load-project hook so it can also run on its own - on Android
 * it is the only headless way to reach the plug-in UI code path, since the test
 * device is lock-screened and cannot be driven by taps.
 */
private suspend fun runPreloadPluginUiHook(host: UapmdHost) {
    val requestedName = startupPreloadPlugin() ?: return
    var waited = 0
    while ((host.isScanning || host.catalog.isEmpty()) && waited < 120_000) {
        kotlinx.coroutines.delay(200); waited += 200
    }
    val matchingEntries = host.catalog.filter {
        it.displayName.contains(requestedName, ignoreCase = true)
    }
    val entry = matchingEntries.firstOrNull { it.format == "AU" }
        ?: matchingEntries.firstOrNull()
    if (entry == null) {
        println("uapmd.cmp dev hook: no preload plug-in matches '$requestedName'")
        return
    }
    println(
        "uapmd.cmp dev hook: preloading ${entry.format} " +
            "${entry.pluginId} ${entry.displayName}"
    )
    host.instantiate(entry, 0)
    var instantiateWait = 0
    while (host.isInstantiating && instantiateWait < 30_000) {
        kotlinx.coroutines.delay(50)
        instantiateWait += 50
    }
    println(
        "uapmd.cmp dev hook: preload completed in ${instantiateWait}ms " +
            "id=${host.lastInstantiation?.instanceId} " +
            "err=${host.lastInstantiation?.error}"
    )
    val preloadedId = host.lastInstantiation?.instanceId ?: -1
    if (startupShowPreloadUi() && preloadedId >= 0 && host.lastInstantiation?.error == null) {
        host.showPluginUi(preloadedId)
        println(
            "uapmd.cmp dev hook: preload UI visible=" +
                host.isPluginUiVisible(preloadedId) +
                " status=${host.pluginUiStatusMessage}"
        )
        kotlinx.coroutines.delay(1000)
    }
}

/**
 * Startup dev hooks, run concurrently with the UI poll.
 *
 * They used to run ahead of the poll loop, which meant `refresh()` never ran
 * while a hook was measuring - playback looked frozen at playhead 0 even
 * though the engine was advancing. Anything measured here needs the same
 * refresh cadence the real UI has.
 */
private suspend fun runStartupDevHooks(host: UapmdHost) = kotlinx.coroutines.coroutineScope {
        // Dev hook: exercise the "+" button path with the poll running.
        // The audio device is created asynchronously when the engine starts, so
        // the block size can only be applied once it exists.
        run {
            var waited = 0
            while (!host.isAudioEngineEnabled && waited < 5_000) {
                kotlinx.coroutines.delay(100); waited += 100
            }
            kotlinx.coroutines.delay(500)
            host.applyDefaultAudioBufferSize()
        }
        startupBufferSize().takeIf { it > 0 }?.let { bs ->
            val sr = host.model.sampleRate.takeIf { it > 0 } ?: 48000
            println("uapmd.cmp dev hook: reconfiguring audio device to bufferSize=$bs")
            host.applyDeviceSettings(-1, -1, sr, bs)
            kotlinx.coroutines.delay(1500)
        }
        if (startupLoadProjectPath() == null)
            runPreloadPluginUiHook(host)
        startupLoadProjectPath()?.let { path ->
            // Wait for the plug-in scan: a project can only resolve plug-ins the
            // catalog knows about, so loading before the scan finishes silently
            // drops instances.
            if (startupForceRescan()) {
                // Clear the blocklist too: a plug-in that crashed an earlier scan
                // stays excluded from the catalog, which looks identical to "not
                // installed" when a project cannot resolve it.
                println("uapmd.cmp dev hook: clearing blocklist and rescanning")
                host.clearPluginBlocklist()
                host.scanPlugins(forceRescan = true)
                // Wait for the scan to actually start, then to finish.
                var spin = 0
                while (!host.isScanning && spin < 5_000) { kotlinx.coroutines.delay(100); spin += 100 }
                var scanning = 0
                while (host.isScanning && scanning < 180_000) {
                    kotlinx.coroutines.delay(500); scanning += 500
                }
                println("uapmd.cmp dev hook: rescan finished after ${scanning}ms")
            }
            var waited = 0
            while ((host.isScanning || host.catalog.isEmpty()) && waited < 120_000) {
                kotlinx.coroutines.delay(200); waited += 200
            }
            println("uapmd.cmp dev hook: catalog ready after ${waited}ms, entries=${host.catalog.size}")
            host.catalog.forEach { println("uapmd.cmp catalog: ${it.format} | ${it.pluginId} | ${it.displayName}") }
            runPreloadPluginUiHook(host)
            repeat(startupLoadCount()) { pass ->
            // Heartbeat on the UI dispatcher: every gap longer than a frame is a
            // stall the user would see as a freeze.
            var worstGapMs = 0L
            var ticks = 0
            val beat = launch {
                var last = nowMillis()
                while (true) {
                    kotlinx.coroutines.delay(16)
                    val t = nowMillis()
                    val gap = t - last
                    if (gap > worstGapMs) worstGapMs = gap
                    last = t
                    ticks++
                }
            }
            println("uapmd.cmp dev hook: load pass ${pass + 1}")
            val started = nowMillis()
            // The watchdog must not live on the UI dispatcher: that is the thread
            // a hung load blocks, so a timer there would never fire.
            val watchdog = launch(backgroundDispatcher()) {
                kotlinx.coroutines.delay(20_000)
                if (host.isLoadingProject) {
                    println("uapmd.cmp dev hook: load exceeded 20s, stacks follow")
                    dumpThreadStacks().lineSequence().forEach { println("uapmd.cmp stack $it") }
                }
            }
            host.loadProject(path)
            while (host.isLoadingProject) kotlinx.coroutines.delay(16)
            watchdog.cancel()
            val elapsed = nowMillis() - started
            beat.cancel()
            println(
                "uapmd.cmp dev hook: loadProject took ${elapsed}ms, " +
                    "UI ticks=$ticks worstStall=${worstGapMs}ms, " +
                    "result=${host.lastProjectResult?.success} err=${host.lastProjectResult?.error}"
            )
            startupShowLoadedUi()?.let { requestedName ->
                val pluginHost = host.model.sequencer.engine.pluginHost
                val restored = pluginHost.getInstanceIds().mapNotNull { id ->
                    pluginHost.getInstance(id)?.let { id to it }
                }
                println(
                    "uapmd.cmp dev hook: restored instances=" +
                        restored.joinToString { (id, instance) ->
                            "$id:${instance.formatName}:${instance.displayName}"
                        }
                )
                val match = restored.firstOrNull { (_, instance) ->
                    instance.displayName.contains(requestedName, ignoreCase = true)
                }
                if (match == null) {
                    println("uapmd.cmp dev hook: no restored plug-in matches '$requestedName'")
                } else {
                    println(
                        "uapmd.cmp dev hook: showing restored plug-in " +
                            "id=${match.first} format=${match.second.formatName} " +
                            "name=${match.second.displayName}"
                    )
                    host.showPluginUi(match.first)
                    println(
                        "uapmd.cmp dev hook: restored UI visible=" +
                            host.isPluginUiVisible(match.first) +
                            " status=${host.pluginUiStatusMessage}"
                    )
                }
            }
            kotlinx.coroutines.delay(2000)
            }
            startupRenderPath()?.let { renderPath ->
                println("uapmd.cmp dev hook: rendering to $renderPath")
                host.startRender(renderPath, 0.0, null, 2.0, false)
                var waited = 0
                while (host.isRendering && waited < 300_000) {
                    kotlinx.coroutines.delay(500); waited += 500
                }
                println("uapmd.cmp dev hook: render done in ${waited}ms status=${host.renderStatus}")
            }
            startupPlaySeconds().takeIf { it > 0 }?.let { seconds ->
                println(
                    "uapmd.cmp dev hook: engine=${host.isAudioEngineEnabled} playing=${host.isPlaying}"
                )
                println("uapmd.cmp dev hook: playing for ${seconds}s")
                host.playOrStop()
                repeat(5) {
                    kotlinx.coroutines.delay(400)
                    println(
                        "uapmd.cmp dev hook: t.isPlaying=${host.model.transport.isPlaying} " +
                            "host.isPlaying=${host.isPlaying} " +
                            "rawPos=${host.model.sequencer.engine.playbackPosition} " +
                            "playhead=${fixedSeconds(host.playheadSeconds)}"
                    )
                }
                // Real-time ratio: the engine's own sample position against the
                // monotonic clock. If playback is real-time these advance together;
                // anything less means the audio graph is not keeping up.
                val sr0 = host.model.sampleRate.takeIf { it > 0 } ?: 48000
                val posA = host.model.sequencer.engine.playbackPosition
                val tA = nowMillis()
                val startPos = host.playheadSeconds
                // Sample finely so a uniform slowdown can be told apart from
                // periodic stalls.
                val samples = mutableListOf<Pair<Long, Long>>()
                repeat((seconds * 4).coerceAtMost(80)) {
                    kotlinx.coroutines.delay(250)
                    samples += nowMillis() to host.model.sequencer.engine.playbackPosition
                }
                var prevT = tA; var prevP = posA
                val ratios = samples.map { (t, pos) ->
                    val r = ((pos - prevP).toDouble() / sr0) / ((t - prevT) / 1000.0)
                    prevT = t; prevP = pos
                    r
                }
                println(
                    "uapmd.cmp dev hook: per-250ms realtime ratios: " +
                        ratios.joinToString(" ") { ((it * 100).toInt()).toString() }
                )
                val posB = host.model.sequencer.engine.playbackPosition
                val tB = nowMillis()
                val endPos = host.playheadSeconds
                host.playOrStop()
                val audioSeconds = (posB - posA).toDouble() / sr0
                val wallSeconds = (tB - tA) / 1000.0
                println(
                    "uapmd.cmp dev hook: realtime ratio = " +
                        "${fixedSeconds(audioSeconds)}s audio / ${fixedSeconds(wallSeconds)}s wall = " +
                        "${fixedSeconds(100.0 * audioSeconds / wallSeconds)}%"
                )
                println(
                    "uapmd.cmp dev hook: playback finished, playhead ${fixedSeconds(startPos)} -> " +
                        "${fixedSeconds(endPos)} (advanced ${fixedSeconds(endPos - startPos)}s)"
                )
            }
        }

        val devAddTracks = startupAddTracks()
        repeat(devAddTracks) {
            host.addTrack()
            kotlinx.coroutines.delay(150)
        }
        if (devAddTracks > 0) {
            kotlinx.coroutines.delay(1000)
            host.refresh()
            println(
                "uapmd.cmp dev hook: addTracks=$devAddTracks " +
                    "completions=${host.addTrackCompletions} trackCount=${host.trackCount}"
            )
        }
}

/**
 * The clip's events as one flat word array plus one tick per *word*, which is
 * the shape `replaceMidiClipContent` reads and writes (see UmpNotes.kt).
 */
private fun dev.atsushieno.uapmd.UmpEventsResult.flatten(): Pair<UIntArray, LongArray> =
    events.flatMap { it.words.toList() }.toUIntArray() to
        events.flatMap { e -> List(e.words.size) { e.tick } }.toLongArray()

/** 480 ticks per quarter is what `createEmptyMidiClip` defaults to. */
private val dev.atsushieno.uapmd.UmpEventsResult.tickResolutionOrDefault: Int
    get() = tickResolution.toInt().takeIf { it > 0 } ?: 480
