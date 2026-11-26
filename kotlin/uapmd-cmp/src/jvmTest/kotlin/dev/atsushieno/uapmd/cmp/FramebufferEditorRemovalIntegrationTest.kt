package dev.atsushieno.uapmd.cmp

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.atsushieno.uapmd.AppModel
import dev.atsushieno.uapmd.PluginInstanceConfig
import dev.atsushieno.uapmd.cmp.ui.FloatingWindowLayer
import dev.atsushieno.uapmd.cmp.ui.FloatingWindowManager
import dev.atsushieno.uapmd.cmp.ui.rememberFloatingWindowManager
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.fail

/**
 * Removing a JSFX instance whose editor is on screen.
 *
 * The editor polls the instance every frame and, when it is disposed, stops it drawing
 * and uninstalls its host, so the instance has to outlive it. Taking the instance id out
 * of the host's editor set only asks for the window to close; the editor is disposed by
 * a later frame. So this removes the instance without rendering, checks that it is
 * still there, and only then lets the scene render the editor away.
 *
 *   ./gradlew :uapmd-cmp:jvmIntegrationTest --tests '*FramebufferEditorRemovalIntegrationTest'
 *
 * -Duapmd.probe.plugin names the effect (default: the first whose name has "OTT").
 */
class FramebufferEditorRemovalIntegrationTest {
    @Test
    fun anInstanceOutlivesItsDrawnEditor() = runAgainstAppModel(::checkRemovalWithEditorOpen)
}

private fun checkRemovalWithEditorOpen(model: AppModel, check: (String, Boolean) -> Unit) {
    model.notifyUiReady()
    model.notifyPersistentStorageReady()
    model.setAudioEngineEnabled(true)

    // The model scans on its own once the UI and storage are ready; see
    // FramebufferUiIntegrationTest for why a second scan is not started here.
    val scanDeadline = System.currentTimeMillis() + 120000
    while (model.isScanning && System.currentTimeMillis() < scanDeadline)
        Thread.sleep(100)
    Thread.sleep(500)

    val pluginHost = model.sequencer.engine.pluginHost
    val catalog = (0 until pluginHost.catalogEntryCount.toInt())
        .mapNotNull { pluginHost.getCatalogEntry(it.toUInt()) }
    val wanted = System.getProperty("uapmd.probe.plugin") ?: "OTT"
    val jsfxEntries = catalog.filter { it.format == "JSFX" }
    val jsfx = jsfxEntries.firstOrNull { it.displayName.contains(wanted, ignoreCase = true) }
        ?: jsfxEntries.firstOrNull()
    assumeTrue("a JSFX effect is in the catalogue (${catalog.size} entries)", jsfx != null)
    jsfx!!
    println("using ${jsfx.displayName} (${jsfx.pluginId})")

    var instanceId = -1
    var error: String? = null
    var done = false
    model.createPluginInstance(jsfx.format, jsfx.pluginId, -1, PluginInstanceConfig()) { result ->
        instanceId = result.instanceId
        error = result.error
        done = true
    }
    val createDeadline = System.currentTimeMillis() + 15000
    while (!done && System.currentTimeMillis() < createDeadline)
        Thread.sleep(20)
    if (instanceId < 0)
        fail("could not instantiate: ${error ?: "timed out"}")
    val instance = model.sequencer.engine.getPluginInstance(instanceId)
        ?: fail("instance $instanceId is not retrievable")
    assumeTrue("${jsfx.displayName} has a drawn editor", instance.framebufferUi != null)

    val host = UapmdHost.attach(model)
    val windowKey = framebufferUiWindowKey(instanceId)
    var windows: FloatingWindowManager? = null
    fun exists() = model.sequencer.engine.getPluginInstance(instanceId) != null

    val scene = ImageComposeScene(width = 1280, height = 900, density = Density(1f))
    var now = 0L
    // The host's coroutines run on the UI thread, so it is drained after every frame:
    // closing a window takes a frame to notice the change and another to dispose it.
    fun renderFrames(count: Int) = repeat(count) {
        now += 16_000_000L
        scene.render(now)
        java.awt.EventQueue.invokeAndWait { }
    }
    try {
        scene.setContent {
            val manager = rememberFloatingWindowManager()
            windows = manager
            FloatingWindowLayer(manager) {
                FramebufferPluginUiLayer(host, manager)
            }
        }
        renderFrames(2)

        java.awt.EventQueue.invokeAndWait { host.showPluginUi(instanceId) }
        renderFrames(5)
        check("the editor window opened", windows?.isOpen(windowKey) == true)

        // Nothing renders now, so the editor stays composed however long removal takes.
        java.awt.EventQueue.invokeAndWait { host.removeInstance(instanceId) }
        Thread.sleep(2000)
        check("the instance is not deleted while its editor is composed", exists())

        renderFrames(5)
        check("the editor window closed", windows?.isOpen(windowKey) == false)

        val removeDeadline = System.currentTimeMillis() + 10000
        while (exists() && System.currentTimeMillis() < removeDeadline) {
            renderFrames(1)
            Thread.sleep(50)
        }
        check("the instance is deleted once its editor is gone", !exists())
    } finally {
        scene.close()
        java.awt.EventQueue.invokeAndWait { }
    }
}
