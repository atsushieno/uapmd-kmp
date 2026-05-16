package dev.atsushieno.uapmd_kmp

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.atsushieno.uapmd.CatalogEntry
import dev.atsushieno.uapmd.createRealtimeSequencer
import dev.atsushieno.uapmd.createScanTool
import dev.atsushieno.uapmd.debugJvmThread
import dev.atsushieno.uapmd.getDefaultDeviceIODispatcher
import dev.atsushieno.uapmd.initJvmEventLoop
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.EventQueue

private fun probeThread(tag: String) {
    val thread = Thread.currentThread()
    println(
        "[uapmd-probe-thread] $tag | name=${thread.name} id=${thread.id} " +
            "edt=${EventQueue.isDispatchThread()}"
    )
}

private data class ProbeResult(
    val format: String,
    val pluginName: String,
    val pluginId: String,
    val outcome: String,
    val details: String
)

private fun loadCatalogEntries(model: UapmdProbeContext): List<CatalogEntry> {
    model.host.reloadCatalogFromCache()
    var entries = (0 until model.host.catalogEntryCount.toInt()).mapNotNull {
        model.host.getCatalogEntry(it.toUInt())
    }
    if (entries.isNotEmpty())
        return entries

    createScanTool().use { scanTool ->
        println("[uapmd-probe] cache empty, performing full scan")
        scanTool.performScanning(requireFastScanning = false, observer = null)
    }
    model.host.reloadCatalogFromCache()
    entries = (0 until model.host.catalogEntryCount.toInt()).mapNotNull {
        model.host.getCatalogEntry(it.toUInt())
    }
    return entries
}

private suspend fun instantiateViaEngine(
    model: UapmdProbeContext,
    entry: CatalogEntry,
    timeoutMs: Long
): ProbeResult {
    val trackIndex = model.engine.addEmptyTrack()
    val completion = CompletableDeferred<Pair<Int, String?>>()
    val invocation = CoroutineScope(Dispatchers.IO).launch {
        probeThread("probe.instantiate.invoke.start format=${entry.format} name=${entry.displayName}")
        debugJvmThread("probe.instantiate.request format=${entry.format} name=${entry.displayName}")
        runCatching {
            model.engine.addPluginToTrack(trackIndex, entry.format, entry.pluginId) { instanceId, _, error ->
                probeThread(
                    "probe.instantiate.callback format=${entry.format} name=${entry.displayName} " +
                        "instanceId=$instanceId error=${error ?: ""}"
                )
                completion.complete(instanceId to error)
            }
        }.onFailure { error ->
            completion.complete(-1 to "${error::class.simpleName}: ${error.message}")
        }
        probeThread("probe.instantiate.invoke.return format=${entry.format} name=${entry.displayName}")
    }
    val result = withTimeoutOrNull(timeoutMs) { completion.await() }
    return when {
        result == null -> {
            invocation.cancel()
            ProbeResult(
                entry.format,
                entry.displayName,
                entry.pluginId,
                "timeout",
                "callback not invoked within ${timeoutMs}ms; invocationActive=${invocation.isActive}"
            )
        }
        result.second != null ->
            ProbeResult(entry.format, entry.displayName, entry.pluginId, "error", result.second ?: "")
        result.first < 0 ->
            ProbeResult(entry.format, entry.displayName, entry.pluginId, "error", "negative instance id without error message")
        else -> {
            model.engine.removePluginInstance(result.first)
            ProbeResult(entry.format, entry.displayName, entry.pluginId, "ok", "instanceId=${result.first}")
        }
    }
}

private class UapmdProbeContext {
    val dispatcher = getDefaultDeviceIODispatcher()
    val sequencer = createRealtimeSequencer(
        bufferSize = 512u,
        umpBufferSize = 8192u,
        sampleRate = 48000,
        dispatcher = dispatcher
    )
    val engine = sequencer.engine
    val host = engine.pluginHost

    fun startAudio() {
        val result = sequencer.startAudio()
        println("[uapmd-probe] startAudio result=$result")
    }

    fun close() {
        runCatching { sequencer.stopAudio() }
        runCatching { sequencer.close() }
        runCatching { host.close() }
    }
}

private suspend fun runProbeAndPrint() {
    val formats = (System.getProperty("uapmd.probe.formats") ?: "VST3,CLAP,LV2,AU")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    val attemptsPerFormat = (System.getProperty("uapmd.probe.attemptsPerFormat") ?: "1").toInt()
    val timeoutMs = (System.getProperty("uapmd.probe.timeoutMs") ?: "15000").toLong()
    val startAudio = (System.getProperty("uapmd.probe.startAudio") ?: "true").toBoolean()

    probeThread("probe.run.start")
    debugJvmThread("probe.run.start")
    val context = UapmdProbeContext()
    try {
        if (startAudio)
            context.startAudio()
        val entries = loadCatalogEntries(context)
        println("[uapmd-probe] catalog entries=${entries.size}")
        val grouped = formats.associateWith { format ->
            entries.filter { it.format == format }.take(attemptsPerFormat)
        }
        for ((format, selected) in grouped) {
            if (selected.isEmpty()) {
                println("[uapmd-probe] format=$format outcome=missing details=no catalog entries")
                continue
            }
            for (entry in selected) {
                val result = instantiateViaEngine(context, entry, timeoutMs)
                println(
                    "[uapmd-probe] format=${result.format} plugin=${result.pluginName} " +
                        "pluginId=${result.pluginId} outcome=${result.outcome} details=${result.details}"
                )
            }
        }
    } finally {
        context.close()
    }
}

fun main() {
    probeThread("probe.main.beforeInit")
    debugJvmThread("probe.main.beforeInit")
    initJvmEventLoop()
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "uapmd-kmp instantiation probe",
        ) {
            LaunchedEffect(Unit) {
                probeThread("probe.window.launchedEffect")
                runProbeAndPrint()
                exitApplication()
            }
            MaterialTheme {
                Text("Running instantiation probe...")
            }
        }
    }
}
