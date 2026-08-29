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
import dev.atsushieno.uapmd.AddinManager
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
 * inherits everything it used to do:
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
    fun removeInstance(instanceId: Int) = offUiThread {
        model.removePluginInstance(instanceId)
        onUiThread { refresh() }
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
        val result = try {
            engine.timeline.loadProject(prepared.path)
        } finally {
            if (wasRunning) {
                engine.setActive(true)
                sequencer.startAudio()
            }
        }

        if (result.success) {
            nativeUiPresentations.values.forEach { runCatching { it.close() } }
            nativeUiPresentations.clear()
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
    fun trackMuted(trackIndex: Int) = engineTrackAt(trackIndex)?.muted ?: false
    fun trackSolo(trackIndex: Int) = engineTrackAt(trackIndex)?.solo ?: false
    fun trackBypassed(trackIndex: Int) = engineTrackAt(trackIndex)?.bypassed ?: false

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
        // falling edge of `isScanning`. Refreshing only while the catalog is empty
        // — which is what this used to do — meant a rescan never reached the list:
        // press Scan, watch nothing happen, conclude scanning is broken.
        if (wasScanning && !isScanning) refreshCatalog()
        wasScanning = isScanning
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
            repeat(startupLoadCount()) { pass ->
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
