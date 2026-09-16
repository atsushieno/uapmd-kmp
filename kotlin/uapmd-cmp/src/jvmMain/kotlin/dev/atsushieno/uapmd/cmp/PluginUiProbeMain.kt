package dev.atsushieno.uapmd.cmp

import dev.atsushieno.uapmd.PluginInstanceResult
import dev.atsushieno.uapmd.cleanupAppModel
import dev.atsushieno.uapmd.getAppModel
import dev.atsushieno.uapmd.initJvmEventLoop
import dev.atsushieno.uapmd.instantiateAppModel

/**
 * Drives one plug-in's UI through the lifecycle the app uses: create the
 * presentation, show, hide, show again, destroy — reporting `isVisible` at each
 * step.
 *
 * This is the path that was broken per format, and none of it is reachable from
 * a headless snapshot: the UI is a real native window. Not headless, therefore,
 * but still unattended.
 *
 * `uapmd.probe.uiPlugin` is matched against the display name **exactly** first,
 * and only falls back to a substring when that names exactly one plug-in. A
 * substring is not a plug-in identity — `ADLplug` and `ADLplug-AE` are different
 * plug-ins that happen to share a prefix, and picking whichever the catalog
 * listed first would report a pass for a plug-in nobody asked about.
 *
 * Run with:
 *   ./gradlew :uapmd-cmp:runPluginUiProbe -Duapmd.probe.uiPlugin=ADLplug-AE -Duapmd.probe.uiFormat=CLAP
 */
fun main() {
    val wanted = System.getProperty("uapmd.probe.uiPlugin") ?: "ADLplug-AE"
    val wantedFormat = System.getProperty("uapmd.probe.uiFormat")
    var failures = 0
    fun check(label: String, ok: Boolean) {
        println("${if (ok) "PASS" else "FAIL"}  $label")
        if (!ok) failures++
    }

    initJvmEventLoop()
    instantiateAppModel()
    val model = getAppModel()
    model.notifyUiReady()
    model.notifyPersistentStorageReady()

    model.setAudioEngineEnabled(true)
    val pluginHost = model.sequencer.engine.pluginHost

    // AppModel scans at startup; the catalog is empty until it finishes.
    val scanUntil = System.currentTimeMillis() + 180_000
    while ((model.isScanning || pluginHost.catalogEntryCount.toInt() == 0) &&
        System.currentTimeMillis() < scanUntil) Thread.sleep(200)
    val catalog = (0 until pluginHost.catalogEntryCount.toInt())
        .mapNotNull { pluginHost.getCatalogEntry(it.toUInt()) }
    println("-- catalog ${catalog.size} entries")

    val inFormat = catalog.filter { wantedFormat == null || it.format.equals(wantedFormat, true) }
    val exact = inFormat.filter { it.displayName.equals(wanted, true) }
    val partial = inFormat.filter { it.displayName.contains(wanted, true) }
    val label = "'$wanted'${wantedFormat?.let { " ($it)" } ?: ""}"
    val entry = when {
        exact.size == 1 -> exact.single()
        exact.size > 1 -> {
            // Same display name twice in one format is a catalog the probe
            // cannot disambiguate; say so rather than pick.
            println("FAIL  $label is ambiguous — ${exact.size} entries share that display name")
            exact.forEach { println("   candidate: ${it.format} | ${it.pluginId} | ${it.displayName}") }
            null
        }
        partial.size == 1 -> partial.single().also {
            println("-- no exact match for $label; one plug-in contains it")
        }
        partial.size > 1 -> {
            println("FAIL  $label matches ${partial.size} different plug-ins; name one exactly")
            partial.forEach { println("   candidate: ${it.format} | ${it.pluginId} | ${it.displayName}") }
            null
        }
        else -> {
            println("FAIL  no catalog entry matches $label")
            catalog.filter { it.displayName.contains(wanted, true) }
                .forEach { println("   other format: ${it.format} | ${it.displayName}") }
            null
        }
    }
    if (entry == null) {
        cleanupAppModel()
        kotlin.system.exitProcess(1)
    }
    println("-- ${entry.format} | ${entry.pluginId} | ${entry.displayName}")

    var result: PluginInstanceResult? = null
    model.createPluginInstance(entry.format, entry.pluginId, -1) { r -> result = r }
    val instUntil = System.currentTimeMillis() + 30_000
    while (result == null && System.currentTimeMillis() < instUntil) Thread.sleep(50)
    val created = result
    check("instantiated (${created?.error ?: "no error"})", created?.error == null && (created?.instanceId ?: -1) >= 0)
    if (created == null || created.error != null) {
        cleanupAppModel()
        kotlin.system.exitProcess(1)
    }

    val instance = pluginHost.getInstance(created.instanceId)!!
    check("reports UI support", instance.uiCapabilities.hasUiSupport)

    val presentation = instance.createUiPresentation()
    check("createUiPresentation returned a presentation", presentation != null)
    if (presentation == null) {
        cleanupAppModel()
        kotlin.system.exitProcess(1)
    }

    check("show() succeeded", presentation.show())
    check("isVisible after show", presentation.isVisible)
    println("   size after show: ${presentation.getSize()}")
    Thread.sleep(1500)

    presentation.hide()
    check("not visible after hide", !presentation.isVisible)
    Thread.sleep(500)

    // The second show must reuse the same UI, which is what Hide/Show does in
    // the app — destroying and rebuilding is the behaviour this replaced.
    check("show() again succeeded", presentation.show())
    check("isVisible after second show", presentation.isVisible)
    Thread.sleep(1500)

    presentation.close()
    println("   presentation closed")

    model.setAudioEngineEnabled(false)
    Thread.sleep(1000)
    cleanupAppModel()
    println(if (failures == 0) "ALL PASS" else "$failures FAILURE(S)")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
