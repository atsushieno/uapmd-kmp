package dev.atsushieno.uapmd_kmp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.atsushieno.uapmd_kmp.nodegraph.GraphLink
import dev.atsushieno.uapmd_kmp.nodegraph.NodeGraphEditor
import dev.atsushieno.uapmd_kmp.timeline.*
import dev.atsushieno.uapmd_kmp.ui.PluginEntry
import dev.atsushieno.uapmd_kmp.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Main entry point ───────────────────────────────────────────────────────

@Composable
fun MainWindow() {
    val model = remember { createUapmdModel() }

    // Polling tick: transport position + spectrum ~60fps
    LaunchedEffect(model) {
        while (true) {
            model.tick()
            model.refreshSpectrum()
            delay(16)
        }
    }

    // One-time initialisation
    LaunchedEffect(model) {
        model.refreshDevices()
        model.refreshCatalog()
        model.refreshTracks()
        model.refreshTimeline()
        model.refreshGraph()
    }

    MaterialTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .safeContentPadding()
                .fillMaxSize()
        ) {
            MainWindowContent(model)
        }
    }
}

@Composable
private fun MainWindowContent(model: UapmdModel) {
    // ── Overlay / dialog visibility flags ──────────────────────────────────
    var showDeviceSettings   by remember { mutableStateOf(false) }
    var showNodeGraph        by remember { mutableStateOf(false) }
    var showExporter         by remember { mutableStateOf(false) }
    var showAudioImport      by remember { mutableStateOf(false) }
    var showScriptEditor     by remember { mutableStateOf(false) }
    var showPluginSelector   by remember { mutableStateOf(false) }
    var showMidiDump         by remember { mutableStateOf(false) }

    // ── Timeline UI state ──────────────────────────────────────────────────
    var contextMenu by remember { mutableStateOf<ClipContextMenuInfo?>(null) }

    // ── Node graph link edits (nodes come from model, topology-edits are local) ─
    var localGraphLinks by remember { mutableStateOf(emptyList<GraphLink>()) }
    var nextLinkId      by remember { mutableStateOf(1) }

    // ── Plugin list selection + sort state ────────────────────────────────
    var selectedPluginId     by remember { mutableStateOf<String?>(null) }
    var selectedPluginFormat by remember { mutableStateOf<String?>(null) }
    var addToTrackMenuPlugin by remember { mutableStateOf<PluginEntry?>(null) }
    var pluginSortColumn     by remember { mutableStateOf(PluginSortColumn.Name) }
    var pluginSortAsc        by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // ── Dialog-specific local state ────────────────────────────────────────
    var exportSettings  by remember { mutableStateOf(ExportSettings()) }
    var exportProgress  by remember { mutableStateOf<Float?>(null) }
    var exportStatus    by remember { mutableStateOf("") }
    var importFilePath  by remember { mutableStateOf("") }
    var scriptText      by remember { mutableStateOf("") }

    // ── Selected tab (default to Tracks) ──────────────────────────────────
    var selectedTab by remember { mutableStateOf(1) }

    // ── Toolbar ────────────────────────────────────────────────────────────
    Toolbar(
        isPlaying            = model.isPlaying,
        isPaused             = model.isPaused,
        isAudioEngineRunning = model.isAudioEngineRunning,
        onPlay               = { model.play() },
        onPause              = { model.pause() },
        onAudioEngine        = { model.toggleAudioEngine() },
        onDeviceSettings     = { showDeviceSettings  = !showDeviceSettings  },
        onAudioGraph         = { showNodeGraph        = !showNodeGraph       },
        onPlugins            = { showPluginSelector   = !showPluginSelector  },
        onScript             = { showScriptEditor     = !showScriptEditor    },
        onImportAudio        = { showAudioImport      = !showAudioImport     },
        onExport             = { showExporter         = !showExporter        },
        onTrackList          = { selectedTab = 1 },
        onPluginList         = { selectedTab = 2 },
        onMidiDump           = { showMidiDump         = !showMidiDump        },
        inputSpectrum        = model.inputSpectrum,
        outputSpectrum       = model.outputSpectrum,
        modifier             = Modifier.fillMaxWidth()
    )

    // ── Tab row ────────────────────────────────────────────────────────────
    PrimaryTabRow(selectedTabIndex = selectedTab) {
        Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
            text = { Text("Timeline") })
        Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
            text = { Text("Tracks") })
        Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 },
            text = { Text("Plugins") })
    }

    // ── Main content area ──────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize()) {
        when (selectedTab) {
            0 -> TimelineContent(
                tracks        = model.timelineTracks,
                playheadMs    = model.playheadMs,
                contextMenu   = contextMenu,
                onTracksChange      = { model.refreshTimeline() },
                onContextMenuChange = { contextMenu = it }
            )
            1 -> Row(modifier = Modifier.fillMaxSize()) {
                TrackList(
                    entries = model.trackEntries,
                    catalogEntries = model.catalogEntries,
                    onEnabledChanged = { id, en -> model.setInstanceEnabled(id, en) },
                    onDetailsRequested = { id -> model.openInstance(id) },
                    onRemoveInstance = { id -> model.removeInstance(id) },
                    onAddTrack = { model.addEmptyTrack() },
                    onAddPluginToTrack = { ti, fmt, pid ->
                        scope.launch(Dispatchers.IO) {
                            model.addPluginToTrack(ti, fmt, pid)
                        }
                    },
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(4.dp)
                )
                val selectedInfo = model.selectedInstanceInfo
                if (selectedInfo != null) {
                    VerticalDivider(modifier = Modifier.fillMaxHeight())
                    PluginEditorPane(
                        info = selectedInfo,
                        statusMessage = model.pluginUiStatusMessage,
                        onClose = { model.selectInstance(null) },
                        onEnabledChanged = { en -> model.setInstanceEnabled(selectedInfo.instanceId, en) },
                        onGroupChanged = { g -> model.setInstanceGroup(selectedInfo.instanceId, g) },
                        onPresetSelected = { p -> model.loadPreset(selectedInfo.instanceId, p) },
                        onParameterChanged = { idx, v -> model.setParameterValue(selectedInfo.instanceId, idx, v) },
                        onNoteOn = { note -> model.sendNoteOn(selectedInfo.instanceId, note) },
                        onNoteOff = { note -> model.sendNoteOff(selectedInfo.instanceId, note) },
                        onShowUi = {
                            scope.launch(Dispatchers.IO) {
                                model.showPluginUi(selectedInfo.instanceId)
                            }
                        },
                        onDismissStatus = { model.clearPluginUiStatus() },
                        modifier = Modifier.width(420.dp).fillMaxHeight()
                    )
                }
            }
            2 -> Box(modifier = Modifier.fillMaxSize()) {
                PluginList(
                    plugins      = model.catalogEntries,
                    selectedId   = selectedPluginId,
                    onSelect     = { plugin ->
                        selectedPluginId     = plugin.pluginId
                        selectedPluginFormat = plugin.format
                        addToTrackMenuPlugin = plugin
                    },
                    sortColumn   = pluginSortColumn,
                    sortAsc      = pluginSortAsc,
                    onSortChanged = { col, asc -> pluginSortColumn = col; pluginSortAsc = asc },
                    modifier     = Modifier.fillMaxSize().padding(4.dp)
                )
                // "Add to track" dropdown — appears after a plugin is selected
                DropdownMenu(
                    expanded = addToTrackMenuPlugin != null,
                    onDismissRequest = { addToTrackMenuPlugin = null }
                ) {
                    val plugin = addToTrackMenuPlugin
                    if (plugin != null) {
                        Text(
                            "Add \"${plugin.name}\" to track:",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                        HorizontalDivider()
                        val trackCount = model.trackEntries.map { it.trackIndex }.distinct()
                        if (trackCount.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Track 1 (new)") },
                                onClick = {
                                    addToTrackMenuPlugin = null
                                    scope.launch(Dispatchers.IO) {
                                        model.addPluginToTrack(0, plugin.format, plugin.pluginId)
                                    }
                                }
                            )
                        } else {
                            trackCount.forEach { ti ->
                                val label = model.trackEntries.firstOrNull { it.trackIndex == ti }?.trackName
                                    ?: "Track ${ti + 1}"
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        addToTrackMenuPlugin = null
                                        scope.launch(Dispatchers.IO) {
                                            model.addPluginToTrack(ti, plugin.format, plugin.pluginId)
                                        }
                                    }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("New track") },
                                onClick = {
                                    addToTrackMenuPlugin = null
                                    val newIdx = (trackCount.maxOrNull() ?: -1) + 1
                                    scope.launch(Dispatchers.IO) {
                                        model.addPluginToTrack(newIdx, plugin.format, plugin.pluginId)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        // ── Floating dialogs ───────────────────────────────────────────────

        if (showDeviceSettings) {
            AppDialog(title = "Device Settings", onDismiss = { showDeviceSettings = false }) {
                AudioDeviceSettings(
                    inputDevices     = model.inputDevices,
                    outputDevices    = model.outputDevices,
                    selectedInputId  = model.selectedInputId,
                    selectedOutputId = model.selectedOutputId,
                    selectedSampleRate = model.selectedSampleRate,
                    selectedBufferSize = model.selectedBufferSize,
                    onInputSelected    = { model.selectedInputId  = it },
                    onOutputSelected   = { model.selectedOutputId = it },
                    onSampleRateSelected = { model.selectedSampleRate = it; model.applyDeviceSettings() },
                    onBufferSizeSelected = { model.selectedBufferSize = it; model.applyDeviceSettings() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (showNodeGraph) {
            AppDialog(title = "Audio Graph Editor", onDismiss = { showNodeGraph = false }, wide = true) {
                NodeGraphEditor(
                    nodes = model.graphNodes,
                    links = model.graphLinks + localGraphLinks,
                    modifier = Modifier.fillMaxWidth().height(360.dp),
                    onLinkCreated = { src, dst -> localGraphLinks = localGraphLinks + GraphLink(nextLinkId++, src, dst) },
                    onLinkDeleted = { id -> localGraphLinks = localGraphLinks.filter { it.id != id } }
                )
            }
        }

        if (showExporter) {
            AppDialog(title = "Export", onDismiss = { showExporter = false }) {
                ExporterWindow(
                    settings         = exportSettings,
                    onSettingsChanged = { exportSettings = it },
                    progress         = exportProgress,
                    statusMessage    = exportStatus,
                    onStartExport    = {
                        exportProgress = 0f
                        exportStatus   = "Rendering…"
                        model.renderOffline(exportSettings,
                            onProgress = { p -> exportProgress = p },
                            onDone = { ok, err ->
                                exportProgress = null
                                exportStatus = if (ok) "Done." else "Error: $err"
                            }
                        )
                    },
                    onCancelExport   = { exportProgress = null; exportStatus = "Cancelled." },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (showAudioImport) {
            AppDialog(title = "Import Audio", onDismiss = { showAudioImport = false }) {
                AudioImportWindow(
                    filePath         = importFilePath,
                    onFilePathChanged = { importFilePath = it },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (showScriptEditor) {
            AppDialog(title = "Script Editor", onDismiss = { showScriptEditor = false }, wide = true) {
                ScriptEditor(
                    script         = scriptText,
                    onScriptChanged = { scriptText = it },
                    modifier = Modifier.fillMaxWidth().height(400.dp)
                )
            }
        }

        if (showPluginSelector) {
            AppDialog(title = "Plugin Selector", onDismiss = { showPluginSelector = false }) {
                PluginSelector(
                    isScanning   = model.isScanning,
                    scanProgress = model.scanProgress,
                    scanReport   = model.scanReport,
                    blocklist    = model.blocklist,
                    onScanRequested  = { model.scanPlugins() },
                    onClearBlocklist = { model.clearBlocklist() },
                    modifier = Modifier.fillMaxWidth().height(400.dp)
                )
            }
        }

        if (showMidiDump) {
            AppDialog(title = "MIDI Event Dump", onDismiss = { showMidiDump = false }, wide = true) {
                MidiDumpWindow(
                    events   = mockMidiEvents(),
                    modifier = Modifier.fillMaxWidth().height(360.dp)
                )
            }
        }
    }
}

@Composable
private fun PluginEditorPane(
    info: InstanceInfo,
    statusMessage: String?,
    onClose: () -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    onGroupChanged: (Int) -> Unit,
    onPresetSelected: (PresetEntry) -> Unit,
    onParameterChanged: (Int, Float) -> Unit,
    onNoteOn: (Int) -> Unit,
    onNoteOff: (Int) -> Unit,
    onShowUi: () -> Unit,
    onDismissStatus: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = modifier
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Plugin Editor", style = MaterialTheme.typography.titleMedium)
                    Text(
                        info.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onClose) { Text("Close") }
            }
            HorizontalDivider()
            if (statusMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            statusMessage,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = onDismissStatus) { Text("Dismiss") }
                    }
                }
            }
            if (info.hasUiSupport) {
                Text(
                    if (info.nativeUiVisible) "Native UI is active in a separate presentation."
                    else "Native UI is available. Open it from here while keeping parameters docked.",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "This plugin does not expose a native UI. Parameter editing remains available here.",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                InstanceDetails(
                    info = info,
                    onEnabledChanged = onEnabledChanged,
                    onGroupChanged = onGroupChanged,
                    onPresetSelected = onPresetSelected,
                    onParameterChanged = onParameterChanged,
                    onShowUi = onShowUi,
                    modifier = Modifier.fillMaxSize()
                )
            }
            HorizontalDivider()
            MidiKeyboard(
                onNoteOn = onNoteOn,
                onNoteOff = onNoteOff,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

// ── Toolbar ────────────────────────────────────────────────────────────────

@Composable
private fun Toolbar(
    isPlaying: Boolean,
    isPaused: Boolean,
    isAudioEngineRunning: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onAudioEngine: () -> Unit,
    onDeviceSettings: () -> Unit,
    onAudioGraph: () -> Unit,
    onPlugins: () -> Unit,
    onScript: () -> Unit,
    onImportAudio: () -> Unit,
    onExport: () -> Unit,
    onTrackList: () -> Unit,
    onPluginList: () -> Unit,
    onMidiDump: () -> Unit,
    inputSpectrum: FloatArray,
    outputSpectrum: FloatArray,
    modifier: Modifier = Modifier
) {
    var importMenuExpanded  by remember { mutableStateOf(false) }
    var projectMenuExpanded by remember { mutableStateOf(false) }

    Surface(tonalElevation = 3.dp, modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick = onAudioEngine,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isAudioEngineRunning) MaterialTheme.colorScheme.secondary
                                     else MaterialTheme.colorScheme.surfaceVariant
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    if (isAudioEngineRunning) "Audio Engine: On" else "Audio Engine: Off",
                    fontSize = 11.sp
                )
            }

            Button(
                onClick = onPlay,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPlaying) MaterialTheme.colorScheme.error
                                     else MaterialTheme.colorScheme.primary
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(if (isPlaying) "■ Stop" else "▶ Play", fontSize = 12.sp)
            }

            Button(
                onClick = onPause,
                enabled = isPlaying || isPaused,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(if (isPaused) "▶ Resume" else "⏸ Pause", fontSize = 12.sp)
            }

            Spacer(Modifier.width(4.dp))
            ToolbarButton("Device",  onDeviceSettings)
            ToolbarButton("Graph",   onAudioGraph)
            ToolbarButton("Tracks",  onTrackList)
            ToolbarButton("Plugins", onPluginList)
            ToolbarButton("Script",  onScript)
            ToolbarButton("MIDI",    onMidiDump)

            Box {
                ToolbarButton("Import", { importMenuExpanded = true })
                DropdownMenu(expanded = importMenuExpanded, onDismissRequest = { importMenuExpanded = false }) {
                    DropdownMenuItem(text = { Text("Import Audio (Demucs)") }, onClick = {
                        importMenuExpanded = false; onImportAudio()
                    })
                    DropdownMenuItem(text = { Text("Plugin Selector") }, onClick = {
                        importMenuExpanded = false; onPlugins()
                    })
                }
            }

            Box {
                ToolbarButton("Project", { projectMenuExpanded = true })
                DropdownMenu(expanded = projectMenuExpanded, onDismissRequest = { projectMenuExpanded = false }) {
                    DropdownMenuItem(text = { Text("Load Project") },  onClick = { projectMenuExpanded = false; println("Load project") })
                    DropdownMenuItem(text = { Text("Save Project") },  onClick = { projectMenuExpanded = false; println("Save project") })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Render To File…") }, onClick = { projectMenuExpanded = false; onExport() })
                }
            }

            Spacer(Modifier.weight(1f))

            Text("In",  fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(2.dp))
            SpectrumAnalyzer(bands = inputSpectrum,  modifier = Modifier.width(60.dp).height(30.dp))
            Spacer(Modifier.width(6.dp))
            Text("Out", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(2.dp))
            SpectrumAnalyzer(bands = outputSpectrum, modifier = Modifier.width(60.dp).height(30.dp))
        }
    }
}

@Composable
private fun ToolbarButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        modifier = Modifier.height(36.dp)
    ) {
        Text(label, fontSize = 11.sp)
    }
}

// ── Reusable dialog wrapper ────────────────────────────────────────────────

@Composable
private fun AppDialog(
    title: String,
    onDismiss: () -> Unit,
    wide: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(if (wide) 0.95f else 0.85f)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                HorizontalDivider()
                content()
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

// ── Timeline section ───────────────────────────────────────────────────────

private data class ClipContextMenuInfo(
    val trackIndex: Int,
    val clipId: Int,
    val positionMs: Long,
    val offset: DpOffset
)

@Composable
private fun TimelineContent(
    tracks: List<TimelineTrack>,
    playheadMs: Long,
    contextMenu: ClipContextMenuInfo?,
    onTracksChange: (List<TimelineTrack>) -> Unit,
    onContextMenuChange: (ClipContextMenuInfo?) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        ClipTimeline(
            tracks = tracks,
            playheadMs = playheadMs,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            onClipMoved = { trackIdx, clipId, newStartMs ->
                onTracksChange(tracks.map { track ->
                    if (track.index != trackIdx) track
                    else track.copy(clips = track.clips.map { clip ->
                        if (clip.id != clipId) clip
                        else clip.copy(startMs = newStartMs, endMs = newStartMs + (clip.endMs - clip.startMs))
                    })
                })
            },
            onClipDoubleClicked = { trackIdx, clipId ->
                onContextMenuChange(ClipContextMenuInfo(trackIdx, clipId, -1L,
                    DpOffset(120.dp, (trackIdx * 64 + 28).dp)))
            },
            onEmptyDoubleClicked = { trackIdx, posMs ->
                onContextMenuChange(ClipContextMenuInfo(trackIdx, -1, posMs,
                    DpOffset(120.dp, (trackIdx * 64 + 28).dp)))
            }
        )

        DropdownMenu(
            expanded = contextMenu != null,
            onDismissRequest = { onContextMenuChange(null) },
            offset = contextMenu?.offset ?: DpOffset.Zero
        ) {
            val menu = contextMenu
            if (menu != null && menu.clipId >= 0) {
                DropdownMenuItem(text = { Text("Open Editor") }, onClick = {
                    println("Open editor for clip ${menu.clipId} on track ${menu.trackIndex}")
                    onContextMenuChange(null)
                })
                DropdownMenuItem(text = { Text("Remove Clip") }, onClick = {
                    onTracksChange(tracks.map { track ->
                        if (track.index != menu.trackIndex) track
                        else track.copy(clips = track.clips.filter { it.id != menu.clipId })
                    })
                    onContextMenuChange(null)
                })
            } else if (menu != null) {
                DropdownMenuItem(text = { Text("Add Clip Here") }, onClick = {
                    println("Add clip on track ${menu.trackIndex} at ${menu.positionMs}ms")
                    onContextMenuChange(null)
                })
            }
        }
    }
}

// ── Mock data (MIDI event dump only — no engine log API exists) ────────────

private fun mockMidiEvents() = (0 until 20).map { i ->
    MidiEventEntry(
        index        = i,
        tickPosition = i * 480L,
        deltaTicks   = if (i == 0) 0L else 480L,
        words        = listOf(when (i % 3) {
            0    -> 0x20904060u
            1    -> 0x20804000u
            else -> 0x20B07F00u
        })
    )
}
