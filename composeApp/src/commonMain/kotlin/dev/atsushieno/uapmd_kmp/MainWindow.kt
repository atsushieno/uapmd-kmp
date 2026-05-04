package dev.atsushieno.uapmd_kmp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.atsushieno.uapmd_kmp.nodegraph.BusType
import dev.atsushieno.uapmd_kmp.nodegraph.GraphLink
import dev.atsushieno.uapmd_kmp.nodegraph.GraphNode
import dev.atsushieno.uapmd_kmp.nodegraph.GraphPin
import dev.atsushieno.uapmd_kmp.nodegraph.NodeGraphEditor
import dev.atsushieno.uapmd_kmp.timeline.*
import dev.atsushieno.uapmd_kmp.ui.*
import kotlin.math.PI
import kotlin.math.sin

// ── Transport state (mock) ─────────────────────────────────────────────────

private enum class PlayState { Stopped, Playing, Paused }

// ── Main entry point ───────────────────────────────────────────────────────

@Composable
fun MainWindow() {
    MaterialTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .safeContentPadding()
                .fillMaxSize()
        ) {
            MainWindowContent()
        }
    }
}

@Composable
private fun MainWindowContent() {
    // ── Overlay / dialog visibility flags ──────────────────────────────────
    var showDeviceSettings   by remember { mutableStateOf(false) }
    var showNodeGraph        by remember { mutableStateOf(false) }
    var showExporter         by remember { mutableStateOf(false) }
    var showAudioImport      by remember { mutableStateOf(false) }
    var showScriptEditor     by remember { mutableStateOf(false) }
    var showPluginSelector   by remember { mutableStateOf(false) }
    var showTrackList        by remember { mutableStateOf(false) }
    var showPluginList       by remember { mutableStateOf(false) }
    var showMidiDump         by remember { mutableStateOf(false) }

    // ── Transport ──────────────────────────────────────────────────────────
    var playState by remember { mutableStateOf(PlayState.Stopped) }

    // ── Timeline state (same mock data as before) ──────────────────────────
    var tracks by remember { mutableStateOf(mockTracks()) }
    var contextMenu by remember { mutableStateOf<ClipContextMenuInfo?>(null) }

    // ── Node graph state ───────────────────────────────────────────────────
    val graphNodes = remember { mockGraphNodes() }
    var graphLinks by remember { mutableStateOf(mockGraphLinks()) }
    var nextLinkId by remember { mutableStateOf(6) }

    // ── Dialog-specific local state ────────────────────────────────────────
    var exportSettings  by remember { mutableStateOf(ExportSettings()) }
    var importFilePath  by remember { mutableStateOf("") }
    var scriptText      by remember { mutableStateOf("") }

    // ── Spectrum mock data ─────────────────────────────────────────────────
    val spectrumBands = remember { FloatArray(32) { i -> (0.4f + 0.5f * sin(i * 0.6)).toFloat().coerceIn(0f, 1f) } }

    // ── Selected tab ──────────────────────────────────────────────────────
    var selectedTab by remember { mutableStateOf(0) }

    // ── Toolbar ────────────────────────────────────────────────────────────
    Toolbar(
        playState = playState,
        onPlay  = { playState = if (playState == PlayState.Playing) PlayState.Stopped else PlayState.Playing },
        onPause = { playState = if (playState == PlayState.Paused)  PlayState.Playing else PlayState.Paused  },
        onDeviceSettings   = { showDeviceSettings  = !showDeviceSettings  },
        onAudioGraph       = { showNodeGraph        = !showNodeGraph       },
        onPlugins          = { showPluginSelector   = !showPluginSelector  },
        onScript           = { showScriptEditor     = !showScriptEditor    },
        onImportAudio      = { showAudioImport      = !showAudioImport     },
        onExport           = { showExporter         = !showExporter        },
        onTrackList        = { showTrackList        = !showTrackList       },
        onPluginList       = { showPluginList       = !showPluginList      },
        onMidiDump         = { showMidiDump         = !showMidiDump        },
        spectrumBands      = spectrumBands,
        modifier           = Modifier.fillMaxWidth()
    )

    // ── Tab row ────────────────────────────────────────────────────────────
    PrimaryTabRow(selectedTabIndex = selectedTab) {
        Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
            text = { Text("Timeline") })
        Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
            text = { Text("Track List") })
        Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 },
            text = { Text("Plugins") })
    }

    // ── Main content area ──────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize()) {
        when (selectedTab) {
            0 -> TimelineContent(
                tracks        = tracks,
                contextMenu   = contextMenu,
                onTracksChange     = { tracks = it },
                onContextMenuChange = { contextMenu = it }
            )
            1 -> TrackList(
                entries = mockTrackInstances(),
                modifier = Modifier.fillMaxSize().padding(4.dp)
            )
            2 -> PluginList(
                plugins = mockPlugins(),
                modifier = Modifier.fillMaxSize().padding(4.dp)
            )
        }

        // ── Floating dialogs ───────────────────────────────────────────────

        if (showDeviceSettings) {
            AppDialog(title = "Device Settings", onDismiss = { showDeviceSettings = false }) {
                val devInfo = mockAudioDeviceInfo()
                AudioDeviceSettings(
                    inputDevices   = devInfo.inputs,
                    outputDevices  = devInfo.outputs,
                    selectedInputId  = devInfo.inputs.firstOrNull()?.id,
                    selectedOutputId = devInfo.outputs.firstOrNull { it.name == "Headphones" }?.id,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (showNodeGraph) {
            AppDialog(title = "Audio Graph Editor", onDismiss = { showNodeGraph = false },
                wide = true) {
                NodeGraphEditor(
                    nodes = graphNodes,
                    links = graphLinks,
                    initialNodePositions = mapOf(
                        0 to Offset(40f, 120f),
                        1 to Offset(280f, 80f),
                        2 to Offset(520f, 100f),
                        3 to Offset(760f, 120f),
                    ),
                    modifier = Modifier.fillMaxWidth().height(360.dp),
                    onLinkCreated = { src, dst -> graphLinks = graphLinks + GraphLink(nextLinkId++, src, dst) },
                    onLinkDeleted = { id -> graphLinks = graphLinks.filter { it.id != id } }
                )
            }
        }

        if (showExporter) {
            AppDialog(title = "Export", onDismiss = { showExporter = false }) {
                ExporterWindow(
                    settings = exportSettings,
                    onSettingsChanged = { exportSettings = it },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (showAudioImport) {
            AppDialog(title = "Import Audio", onDismiss = { showAudioImport = false }) {
                AudioImportWindow(
                    filePath = importFilePath,
                    onFilePathChanged = { importFilePath = it },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (showScriptEditor) {
            AppDialog(title = "Script Editor", onDismiss = { showScriptEditor = false }, wide = true) {
                ScriptEditor(
                    script = scriptText,
                    onScriptChanged = { scriptText = it },
                    modifier = Modifier.fillMaxWidth().height(400.dp)
                )
            }
        }

        if (showPluginSelector) {
            AppDialog(title = "Plugin Selector", onDismiss = { showPluginSelector = false }) {
                PluginSelector(
                    isScanning = false,
                    modifier = Modifier.fillMaxWidth().height(400.dp)
                )
            }
        }

        if (showTrackList) {
            AppDialog(title = "Track / Instance List", onDismiss = { showTrackList = false }) {
                TrackList(
                    entries = mockTrackInstances(),
                    modifier = Modifier.fillMaxWidth().height(320.dp)
                )
            }
        }

        if (showPluginList) {
            AppDialog(title = "Plugin List", onDismiss = { showPluginList = false }) {
                PluginList(
                    plugins = mockPlugins(),
                    modifier = Modifier.fillMaxWidth().height(320.dp)
                )
            }
        }

        if (showMidiDump) {
            AppDialog(title = "MIDI Event Dump", onDismiss = { showMidiDump = false }, wide = true) {
                MidiDumpWindow(
                    events = mockMidiEvents(),
                    modifier = Modifier.fillMaxWidth().height(360.dp)
                )
            }
        }
    }
}

// ── Toolbar ────────────────────────────────────────────────────────────────

@Composable
private fun Toolbar(
    playState: PlayState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onDeviceSettings: () -> Unit,
    onAudioGraph: () -> Unit,
    onPlugins: () -> Unit,
    onScript: () -> Unit,
    onImportAudio: () -> Unit,
    onExport: () -> Unit,
    onTrackList: () -> Unit,
    onPluginList: () -> Unit,
    onMidiDump: () -> Unit,
    spectrumBands: FloatArray,
    modifier: Modifier = Modifier
) {
    var importMenuExpanded  by remember { mutableStateOf(false) }
    var projectMenuExpanded by remember { mutableStateOf(false) }

    Surface(
        tonalElevation = 3.dp,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Transport
            val isPlaying = playState == PlayState.Playing
            val isPaused  = playState == PlayState.Paused

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

            // Window toggles
            ToolbarButton("Device", onDeviceSettings)
            ToolbarButton("Graph",  onAudioGraph)
            ToolbarButton("Tracks", onTrackList)
            ToolbarButton("Plugins", onPluginList)
            ToolbarButton("Script", onScript)
            ToolbarButton("MIDI",   onMidiDump)

            // Import popup
            Box {
                ToolbarButton("Import", { importMenuExpanded = true })
                DropdownMenu(
                    expanded = importMenuExpanded,
                    onDismissRequest = { importMenuExpanded = false }
                ) {
                    DropdownMenuItem(text = { Text("Import Audio (Demucs)") }, onClick = {
                        importMenuExpanded = false
                        onImportAudio()
                    })
                    DropdownMenuItem(text = { Text("Plugin Selector") }, onClick = {
                        importMenuExpanded = false
                        onPlugins()
                    })
                }
            }

            // Project popup
            Box {
                ToolbarButton("Project", { projectMenuExpanded = true })
                DropdownMenu(
                    expanded = projectMenuExpanded,
                    onDismissRequest = { projectMenuExpanded = false }
                ) {
                    DropdownMenuItem(text = { Text("Load Project") }, onClick = {
                        projectMenuExpanded = false
                        println("Load project")
                    })
                    DropdownMenuItem(text = { Text("Save Project") }, onClick = {
                        projectMenuExpanded = false
                        println("Save project")
                    })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Render To File…") }, onClick = {
                        projectMenuExpanded = false
                        onExport()
                    })
                }
            }

            Spacer(Modifier.weight(1f))

            // Spectrum analyzers
            Text("In", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(2.dp))
            SpectrumAnalyzer(
                bands = spectrumBands,
                modifier = Modifier.width(60.dp).height(30.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("Out", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(2.dp))
            SpectrumAnalyzer(
                bands = spectrumBands,
                modifier = Modifier.width(60.dp).height(30.dp)
            )
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
            modifier = Modifier
                .fillMaxWidth(if (wide) 0.95f else 0.85f)
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
    contextMenu: ClipContextMenuInfo?,
    onTracksChange: (List<TimelineTrack>) -> Unit,
    onContextMenuChange: (ClipContextMenuInfo?) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        ClipTimeline(
            tracks = tracks,
            playheadMs = 2500L,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            onClipMoved = { trackIdx, clipId, newStartMs ->
                onTracksChange(tracks.map { track ->
                    if (track.index != trackIdx) track
                    else track.copy(clips = track.clips.map { clip ->
                        if (clip.id != clipId) clip
                        else clip.copy(
                            startMs = newStartMs,
                            endMs = newStartMs + (clip.endMs - clip.startMs)
                        )
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

// ── Mock data ──────────────────────────────────────────────────────────────

private fun mockWaveform(durationSeconds: Double, seed: Int = 0): ClipPreviewData.Audio {
    val pts = (0 until 256).map { i ->
        val t = i / 255f
        val env = sin(t * PI).toFloat()
        val s1 = sin(t * (37f + seed * 7f)).toFloat()
        val s2 = sin(t * (91f + seed * 13f)).toFloat()
        val amp = (env * (0.5f + s1 * 0.25f + s2 * 0.1f)).coerceIn(0f, 0.95f)
        val noise = sin(t * (203f + seed * 31f)).toFloat() * 0.08f
        WaveformPoint(-(amp + noise).coerceAtLeast(0f), (amp - noise).coerceAtLeast(0f))
    }
    return ClipPreviewData.Audio(pts, durationSeconds)
}

private fun mockMidiNotes(durationSeconds: Double, rootNote: Int = 60): ClipPreviewData.Midi {
    val pattern = listOf(0, 4, 7, 12, 7, 4, 0, 2, 5, 9, 5, 2)
    val stepSec = durationSeconds / pattern.size
    val notes = pattern.mapIndexed { i, interval ->
        MidiNote(i * stepSec, stepSec * 0.8, rootNote + interval, 0.6f + (i % 3) * 0.1f)
    }
    val allNotes = notes.map { it.note }
    return ClipPreviewData.Midi(notes, durationSeconds, allNotes.min(), allNotes.max())
}

private fun mockTracks() = listOf(
    TimelineTrack(0, "Track 1", listOf(
        TimelineClip(0, 0L, 3000L, "Intro",      mockWaveform(3.0, seed = 1)),
        TimelineClip(1, 4000L, 7500L, "Verse A",  mockMidiNotes(3.5, rootNote = 60)),
        TimelineClip(2, 5000L, 6000L, "Overlap",  mockMidiNotes(1.0, rootNote = 64)),
        TimelineClip(3, 9000L, 12000L, "Chorus",  mockWaveform(3.0, seed = 5)),
    )),
    TimelineTrack(1, "Track 2", listOf(
        TimelineClip(4, 1500L, 5000L, "Bass line", mockMidiNotes(3.5, rootNote = 36)),
        TimelineClip(5, 6000L, 10000L, "Pad",       mockWaveform(4.0, seed = 3)),
    )),
    TimelineTrack(2, "Master", listOf(
        TimelineClip(6, 0L, 12000L, "Full mix",
            ClipPreviewData.MasterMeta(
                tempoPoints = listOf(
                    TempoPoint(0.0, 120.0), TempoPoint(4.0, 128.0), TempoPoint(8.0, 120.0),
                ),
                timeSignatures = listOf(
                    TimeSignaturePoint(0.0, 4, 4), TimeSignaturePoint(6.0, 3, 4),
                ),
                durationSeconds = 12.0
            )),
    )),
)

private fun mockGraphNodes() = listOf(
    GraphNode(0, "Graph Input", inputs = emptyList(), outputs = listOf(
        GraphPin(1, "Audio", isInput = false, busType = BusType.Audio),
        GraphPin(2, "Event", isInput = false, busType = BusType.Event),
    )),
    GraphNode(1, "SimpleSynth", inputs = listOf(
        GraphPin(3, "Event In", isInput = true, busType = BusType.Event),
    ), outputs = listOf(
        GraphPin(4, "Audio L", isInput = false, busType = BusType.Audio),
        GraphPin(5, "Audio R", isInput = false, busType = BusType.Audio),
    )),
    GraphNode(2, "Compressor", inputs = listOf(
        GraphPin(6, "Audio L", isInput = true, busType = BusType.Audio),
        GraphPin(7, "Audio R", isInput = true, busType = BusType.Audio),
    ), outputs = listOf(
        GraphPin(8, "Audio L", isInput = false, busType = BusType.Audio),
        GraphPin(9, "Audio R", isInput = false, busType = BusType.Audio),
    )),
    GraphNode(3, "Graph Output", inputs = listOf(
        GraphPin(10, "Audio L", isInput = true, busType = BusType.Audio),
        GraphPin(11, "Audio R", isInput = true, busType = BusType.Audio),
    ), outputs = emptyList()),
)

private fun mockGraphLinks() = listOf(
    GraphLink(1, sourcePinId = 2, targetPinId = 3),
    GraphLink(2, sourcePinId = 4, targetPinId = 6),
    GraphLink(3, sourcePinId = 5, targetPinId = 7),
    GraphLink(4, sourcePinId = 8, targetPinId = 10),
    GraphLink(5, sourcePinId = 9, targetPinId = 11),
)

private fun mockTrackInstances() = listOf(
    TrackInstanceEntry(0, "Track 1", 1, "Surge XT", "Audio Out", true),
    TrackInstanceEntry(0, "Track 1", 2, "OB-Xd",    "",          false),
    TrackInstanceEntry(1, "Track 2", 3, "ZynFusion", "Audio Out", true),
    TrackInstanceEntry(2, "Master",  4, "Limiter",   "Audio Out", true),
)

private fun mockPlugins() = listOf(
    PluginEntry("VST3", "Surge XT",       "Surge Synth Team",  "com.surge-synth-team.surge-xt"),
    PluginEntry("VST3", "OB-Xd",          "discoDSP",          "com.discodsp.obxd"),
    PluginEntry("LV2",  "ZynFusion",      "ZynAddSubFX",       "http://zynaddsubfx.sourceforge.net"),
    PluginEntry("CLAP", "Diva",           "u-he",              "com.u-he.diva"),
    PluginEntry("VST3", "Limiter No6",    "Tokyo Dawn Labs",   "com.tokyodawn.limiter-no6"),
)

private data class AudioDeviceLists(
    val inputs: List<AudioDeviceInfo>,
    val outputs: List<AudioDeviceInfo>
)

private fun mockAudioDeviceInfo() = AudioDeviceLists(
    inputs = listOf(
        AudioDeviceInfo(id = 0, name = "Built-in Microphone", isInput = true),
        AudioDeviceInfo(id = 1, name = "External Mic",        isInput = true),
    ),
    outputs = listOf(
        AudioDeviceInfo(id = 2, name = "Built-in Speakers", isInput = false),
        AudioDeviceInfo(id = 3, name = "Headphones",        isInput = false),
        AudioDeviceInfo(id = 4, name = "USB DAC",           isInput = false),
    )
)

private fun mockMidiEvents() = (0 until 20).map { i ->
    MidiEventEntry(
        index        = i,
        tickPosition = i * 480L,
        deltaTicks   = if (i == 0) 0L else 480L,
        words        = listOf(
            when (i % 3) {
                0 -> 0x20904060u  // note on ch0 note 64 vel 96
                1 -> 0x20804000u  // note off
                else -> 0x20B07F00u  // CC 127
            }
        )
    )
}
