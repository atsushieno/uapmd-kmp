package dev.atsushieno.uapmd_kmp

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.atsushieno.uapmd.createRealtimeSequencer
import dev.atsushieno.uapmd.getDefaultDeviceIODispatcher
import dev.atsushieno.uapmd.initJvmEventLoop
import kotlinx.coroutines.delay
import java.io.File

private fun probeProjectPath(): String =
    System.getProperty("uapmd.probe.project")
        ?: error("Missing -Duapmd.probe.project=/abs/path/to/project.uapmdz")

private suspend fun runProjectPlaybackProbe() {
    val projectPath = probeProjectPath()
    val dispatcher = getDefaultDeviceIODispatcher()
    val sequencer = createRealtimeSequencer(
        bufferSize = 512u,
        umpBufferSize = 8192u,
        sampleRate = 48000,
        dispatcher = dispatcher
    )
    val model = UapmdModel(sequencer)
    try {
        val startResult = sequencer.startAudio()
        println("[uapmd-project-probe] startAudio result=$startResult isAudioPlaying=${sequencer.isAudioPlaying()}")

        model.refreshTracks()
        model.refreshTimeline()
        val loadResult = model.loadProject(projectPath)
        println("[uapmd-project-probe] load success=${loadResult.success} error=${loadResult.error}")
        println("[uapmd-project-probe] audioRunning=${model.isAudioEngineRunning} audioStatus=${model.audioEngineStatusMessage}")
        println("[uapmd-project-probe] trackEntries=${model.trackEntries.size} timelineTracks=${model.timelineTracks.size}")

        model.timelineTracks.forEach { track ->
            val nonMaster = track.name != "Master"
            println("[uapmd-project-probe] track=${track.index} name=${track.name} clipCount=${track.clips.size}")
            track.clips.forEach { clip ->
                val exists = File(clip.label).exists()
                println("[uapmd-project-probe] clip id=${clip.id} label=${clip.label} start=${clip.startMs} end=${clip.endMs} nonMaster=$nonMaster labelExists=$exists")
            }
        }

        repeat(10) { i ->
            model.tick()
            val maxOut = model.outputSpectrum.maxOrNull() ?: 0f
            println("[uapmd-project-probe] idle tick=$i playheadMs=${model.playheadMs} isPlaying=${model.isPlaying} outMax=$maxOut")
            delay(100)
        }

        model.play()
        println("[uapmd-project-probe] transport started")
        repeat(30) { i ->
            model.tick()
            model.refreshSpectrum()
            val maxOut = model.outputSpectrum.maxOrNull() ?: 0f
            println("[uapmd-project-probe] play tick=$i playheadMs=${model.playheadMs} isPlaying=${model.isPlaying} outMax=$maxOut")
            delay(100)
        }
        model.play()
    } finally {
        runCatching { sequencer.stopAudio() }
        runCatching { sequencer.close() }
    }
}

fun main() {
    initJvmEventLoop()
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "uapmd-kmp project playback probe",
        ) {
            LaunchedEffect(Unit) {
                runProjectPlaybackProbe()
                exitApplication()
            }
            MaterialTheme {
                Text("Running project playback probe...")
            }
        }
    }
}
