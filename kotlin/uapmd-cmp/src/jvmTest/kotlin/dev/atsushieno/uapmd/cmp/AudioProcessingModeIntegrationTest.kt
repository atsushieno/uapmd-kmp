package dev.atsushieno.uapmd.cmp

import dev.atsushieno.uapmd.AppModel
import dev.atsushieno.uapmd.AudioProcessingMode
import dev.atsushieno.uapmd.OfflineRenderSettings
import dev.atsushieno.uapmd.PluginInstanceConfig
import java.io.File
import kotlin.test.Test

/**
 * `AudioProcessingMode` and the render result's `plugin_processing_restored` through the binding:
 * the app model, the engine and an instance, and the render result's layout across the C API.
 *
 * The part that needs a plug-in uses a JSFX effect and says so when the catalogue has none.
 *
 * Run with: ./gradlew :uapmd-cmp:jvmIntegrationTest --tests '*AudioProcessingModeIntegrationTest'
 */
class AudioProcessingModeIntegrationTest {
    @Test
    fun processingModeAndRenderResultCrossTheBinding() = runAgainstAppModel(::checkProcessingMode)
}

private fun checkProcessingMode(model: AppModel, check: (String, Boolean) -> Unit) {
    model.notifyUiReady()
    model.notifyPersistentStorageReady()
    val engine = model.sequencer.engine

    val refused = model.setAudioProcessingMode(-1, AudioProcessingMode.Offline)
    check("the model refuses an unknown instance and says why (${refused.error})",
        !refused.success && !refused.error.isNullOrEmpty())
    check("the engine refuses an unknown instance",
        engine.setAudioProcessingMode(-1, AudioProcessingMode.Offline) != 0)

    val output = File.createTempFile("uapmd-render", ".wav").apply { deleteOnExit() }
    val rendered = engine.renderOffline(
        OfflineRenderSettings(outputPath = output.path, startSeconds = 0.0, endSeconds = 0.2, sampleRate = model.sampleRate)
    )
    check("a render succeeds (${rendered.errorMessage})", rendered.success && !rendered.canceled)
    // Every field, because a wrong struct layout across the C API shows up as nonsense here.
    check("it reports its length (${rendered.renderedSeconds})", rendered.renderedSeconds > 0.0 && rendered.renderedSeconds < 10.0)
    check("it has no error message", rendered.errorMessage.isNullOrEmpty())
    check("it restored plug-in processing", rendered.pluginProcessingRestored)

    val scanDeadline = System.currentTimeMillis() + 120_000
    while (model.isScanning && System.currentTimeMillis() < scanDeadline)
        Thread.sleep(100)
    val pluginHost = engine.pluginHost
    val jsfx = (0 until pluginHost.catalogEntryCount.toInt())
        .mapNotNull { pluginHost.getCatalogEntry(it.toUInt()) }
        .firstOrNull { it.format == "JSFX" }
    if (jsfx == null) {
        println("SKIP  no JSFX effect in the catalogue, so nothing checked on a real instance")
        return
    }

    var instanceId = -1
    var error: String? = null
    var done = false
    model.createPluginInstance(jsfx.format, jsfx.pluginId, -1, PluginInstanceConfig()) { result ->
        instanceId = result.instanceId
        error = result.error
        done = true
    }
    val deadline = System.currentTimeMillis() + 15_000
    while (!done && System.currentTimeMillis() < deadline)
        Thread.sleep(20)
    check("${jsfx.displayName} is instantiated (${error})", instanceId >= 0)
    val instance = engine.getPluginInstance(instanceId)
    check("the instance is retrievable", instance != null)
    if (instance == null)
        return

    check("an instance starts in realtime", instance.audioProcessingMode == AudioProcessingMode.Realtime)

    val viaModel = model.setAudioProcessingMode(instanceId, AudioProcessingMode.Offline)
    check("the model selects offline (${viaModel.error})", viaModel.success)
    check("the instance reports offline", instance.audioProcessingMode == AudioProcessingMode.Offline)

    check("the engine selects realtime", engine.setAudioProcessingMode(instanceId, AudioProcessingMode.Realtime) == 0)
    check("the instance reports realtime", instance.audioProcessingMode == AudioProcessingMode.Realtime)

    instance.stopProcessing()
    instance.audioProcessingMode = AudioProcessingMode.Offline
    check("the instance takes a mode directly while stopped", instance.audioProcessingMode == AudioProcessingMode.Offline)
    instance.audioProcessingMode = AudioProcessingMode.Realtime
    instance.startProcessing()

    model.removePluginInstance(instanceId)
}
