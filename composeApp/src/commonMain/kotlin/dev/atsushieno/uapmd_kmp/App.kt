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
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd_kmp.timeline.ClipTimeline
import dev.atsushieno.uapmd_kmp.timeline.TimelineClip
import dev.atsushieno.uapmd_kmp.timeline.TimelineTrack

private data class ContextMenuInfo(
    val trackIndex: Int,
    val clipId: Int,      // -1 means empty space
    val positionMs: Long,
    val offset: DpOffset
)

@Composable
fun App() {
    MaterialTheme {
        var tracks by remember {
            mutableStateOf(
                listOf(
                    TimelineTrack(0, "Track 1", listOf(
                        TimelineClip(0, 0L, 3000L, "Intro"),
                        TimelineClip(1, 4000L, 7500L, "Verse A"),
                        TimelineClip(2, 5000L, 6000L, "Overlap"),  // overlaps → lane 1
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

        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .safeContentPadding()
                .fillMaxSize()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    "uapmd-kmp timeline",
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.titleMedium
                )
                ClipTimeline(
                    tracks = tracks,
                    playheadMs = 2500L,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
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
                        contextMenu = ContextMenuInfo(
                            trackIndex = trackIdx,
                            clipId = clipId,
                            positionMs = -1L,
                            offset = DpOffset(120.dp, (trackIdx * 64 + 28).dp)
                        )
                    },
                    onEmptyDoubleClicked = { trackIdx, posMs ->
                        contextMenu = ContextMenuInfo(
                            trackIndex = trackIdx,
                            clipId = -1,
                            positionMs = posMs,
                            offset = DpOffset(120.dp, (trackIdx * 64 + 28).dp)
                        )
                    }
                )
            }

            DropdownMenu(
                expanded = contextMenu != null,
                onDismissRequest = { contextMenu = null },
                offset = contextMenu?.offset ?: DpOffset.Zero
            ) {
                val menu = contextMenu
                if (menu != null && menu.clipId >= 0) {
                    DropdownMenuItem(
                        text = { Text("Open Editor") },
                        onClick = {
                            println("Open editor for clip ${menu.clipId} on track ${menu.trackIndex}")
                            contextMenu = null
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Remove Clip") },
                        onClick = {
                            tracks = tracks.map { track ->
                                if (track.index != menu.trackIndex) track
                                else track.copy(clips = track.clips.filter { it.id != menu.clipId })
                            }
                            contextMenu = null
                        }
                    )
                } else if (menu != null) {
                    DropdownMenuItem(
                        text = { Text("Add Clip Here") },
                        onClick = {
                            println("Add clip on track ${menu.trackIndex} at ${menu.positionMs}ms")
                            contextMenu = null
                        }
                    )
                }
            }
        }
    }
}
