package dev.atsushieno.uapmd_kmp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd_kmp.nodegraph.BusType
import dev.atsushieno.uapmd_kmp.nodegraph.GraphLink
import dev.atsushieno.uapmd_kmp.nodegraph.GraphNode
import dev.atsushieno.uapmd_kmp.nodegraph.GraphPin
import dev.atsushieno.uapmd_kmp.nodegraph.NodeGraphEditor
import dev.atsushieno.uapmd_kmp.timeline.ClipTimeline
import dev.atsushieno.uapmd_kmp.timeline.TimelineClip
import dev.atsushieno.uapmd_kmp.timeline.TimelineTrack

private data class ContextMenuInfo(
    val trackIndex: Int,
    val clipId: Int,
    val positionMs: Long,
    val offset: DpOffset
)

@Composable
fun App() {
    MaterialTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .safeContentPadding()
                .fillMaxSize()
        ) {
            var selectedTab by remember { mutableStateOf(0) }
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                    text = { Text("Timeline") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                    text = { Text("Node Graph") })
            }

            when (selectedTab) {
                0 -> TimelineTab()
                1 -> NodeGraphTab()
            }
        }
    }
}

@Composable
private fun TimelineTab() {
    var tracks by remember {
        mutableStateOf(
            listOf(
                TimelineTrack(0, "Track 1", listOf(
                    TimelineClip(0, 0L, 3000L, "Intro"),
                    TimelineClip(1, 4000L, 7500L, "Verse A"),
                    TimelineClip(2, 5000L, 6000L, "Overlap"),
                    TimelineClip(3, 9000L, 12000L, "Chorus"),
                )),
                TimelineTrack(1, "Track 2", listOf(
                    TimelineClip(4, 1500L, 5000L, "Bass line"),
                    TimelineClip(5, 6000L, 10000L, "Pad"),
                )),
                TimelineTrack(2, "Master", listOf(
                    TimelineClip(6, 0L, 12000L, "Full mix"),
                )),
            )
        )
    }

    var contextMenu by remember { mutableStateOf<ContextMenuInfo?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        ClipTimeline(
            tracks = tracks,
            playheadMs = 2500L,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            onClipMoved = { trackIdx, clipId, newStartMs ->
                tracks = tracks.map { track ->
                    if (track.index != trackIdx) track
                    else track.copy(clips = track.clips.map { clip ->
                        if (clip.id != clipId) clip
                        else clip.copy(
                            startMs = newStartMs,
                            endMs = newStartMs + (clip.endMs - clip.startMs)
                        )
                    })
                }
            },
            onClipDoubleClicked = { trackIdx, clipId ->
                contextMenu = ContextMenuInfo(trackIdx, clipId, -1L,
                    DpOffset(120.dp, (trackIdx * 64 + 28).dp))
            },
            onEmptyDoubleClicked = { trackIdx, posMs ->
                contextMenu = ContextMenuInfo(trackIdx, -1, posMs,
                    DpOffset(120.dp, (trackIdx * 64 + 28).dp))
            }
        )

        DropdownMenu(
            expanded = contextMenu != null,
            onDismissRequest = { contextMenu = null },
            offset = contextMenu?.offset ?: DpOffset.Zero
        ) {
            val menu = contextMenu
            if (menu != null && menu.clipId >= 0) {
                DropdownMenuItem(text = { Text("Open Editor") }, onClick = {
                    println("Open editor for clip ${menu.clipId} on track ${menu.trackIndex}")
                    contextMenu = null
                })
                DropdownMenuItem(text = { Text("Remove Clip") }, onClick = {
                    tracks = tracks.map { track ->
                        if (track.index != menu.trackIndex) track
                        else track.copy(clips = track.clips.filter { it.id != menu.clipId })
                    }
                    contextMenu = null
                })
            } else if (menu != null) {
                DropdownMenuItem(text = { Text("Add Clip Here") }, onClick = {
                    println("Add clip on track ${menu.trackIndex} at ${menu.positionMs}ms")
                    contextMenu = null
                })
            }
        }
    }
}

@Composable
private fun NodeGraphTab() {
    // Graph Input node: audio out, event out
    // SimpleSynth: event in → audio out L/R
    // Compressor: audio in L/R → audio out L/R
    // Graph Output: audio in L/R
    val nodes = remember {
        listOf(
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
    }

    var links by remember {
        mutableStateOf(listOf(
            GraphLink(1, sourcePinId = 2, targetPinId = 3),  // event chain
            GraphLink(2, sourcePinId = 4, targetPinId = 6),  // synth L → comp L
            GraphLink(3, sourcePinId = 5, targetPinId = 7),  // synth R → comp R
            GraphLink(4, sourcePinId = 8, targetPinId = 10), // comp L → output L
            GraphLink(5, sourcePinId = 9, targetPinId = 11), // comp R → output R
        ))
    }

    var nextLinkId by remember { mutableStateOf(6) }

    NodeGraphEditor(
        nodes = nodes,
        links = links,
        initialNodePositions = mapOf(
            0 to Offset(40f, 120f),
            1 to Offset(280f, 80f),
            2 to Offset(520f, 100f),
            3 to Offset(760f, 120f),
        ),
        modifier = Modifier.fillMaxSize().padding(4.dp),
        onLinkCreated = { srcPin, dstPin ->
            links = links + GraphLink(nextLinkId++, srcPin, dstPin)
        },
        onLinkDeleted = { linkId ->
            links = links.filter { it.id != linkId }
        }
    )
}
