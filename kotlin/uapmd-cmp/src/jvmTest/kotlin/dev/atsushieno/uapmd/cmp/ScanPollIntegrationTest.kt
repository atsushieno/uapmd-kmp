package dev.atsushieno.uapmd.cmp

import dev.atsushieno.uapmd.AppModel
import dev.atsushieno.uapmd.ScanMode
import org.junit.Assume.assumeTrue
import kotlin.test.Test

/**
 * Measures what the UI poll costs *while a scan is running*.
 *
 * The poll reads app state every 100 ms on the UI thread. During a scan the scanner
 * thread holds the same locks for every bundle, and the catalog read walks hundreds
 * of entries through JNA. Either can stall the UI, which is what a frozen window and
 * a pile of suspended coroutines look like. This times each call so the cost is a
 * number rather than a suspicion.
 *
 * Only the scan progress and error reads happen on every tick, so only they are
 * held to a frame. `UapmdHost` reads the whole catalog once, when a scan finishes;
 * its cost is reported here, not judged against a frame.
 *
 *   ./gradlew :uapmd-cmp:jvmIntegrationTest --tests '*ScanPollIntegrationTest'
 */
class ScanPollIntegrationTest {
    @Test
    fun pollingDuringAScanStaysInsideAFrame() = runAgainstAppModel(::measurePollCost)
}

private fun measurePollCost(model: AppModel, check: (String, Boolean) -> Unit) {
    model.notifyUiReady()
    notifyPersistentStorageReadyForPlatform(model)

    val pluginHost = model.sequencer.engine.pluginHost
    println("remote scanner available: $platformSupportsRemoteScanner")

    // Let the startup fast scan finish first, or its tail is what gets measured
    // instead of the scan this test starts.
    var settle = 0
    while (model.isScanning && settle < 60_000) { Thread.sleep(100); settle += 100 }

    val progressUs = mutableListOf<Long>()
    val errorUs = mutableListOf<Long>()
    val catalogUs = mutableListOf<Long>()

    model.performPluginScanning(
        forceRescan = true,
        mode = if (platformSupportsRemoteScanner) ScanMode.Remote else ScanMode.InProcess,
        remoteTimeoutSeconds = 20.0
    )
    var waited = 0
    while (!model.isScanning && waited < 10_000) { Thread.sleep(50); waited += 50 }

    var elapsed = 0
    while (model.isScanning && elapsed < 300_000) {
        var t = System.nanoTime()
        val p = model.slowScanProgress
        progressUs += (System.nanoTime() - t) / 1_000

        t = System.nanoTime()
        model.lastPluginScanError
        errorUs += (System.nanoTime() - t) / 1_000

        t = System.nanoTime()
        val count = pluginHost.catalogEntryCount.toInt()
        (0 until count).mapNotNull { pluginHost.getCatalogEntry(it.toUInt()) }
        catalogUs += (System.nanoTime() - t) / 1_000

        if (progressUs.size % 50 == 0)
            println("   ${p.processedBundles}/${p.totalBundles} bundles, ${progressUs.size} samples")
        Thread.sleep(100)
        elapsed += 100
    }

    fun report(label: String, xs: List<Long>) {
        if (xs.isEmpty()) { println("   $label: no samples"); return }
        val sorted = xs.sorted()
        println("   %-22s first %6dµs  median %6dµs  p95 %6dµs  max %6dµs".format(
            label, xs.first(), sorted[sorted.size / 2], sorted[(sorted.size * 95) / 100], sorted.last()))
    }

    println("── poll cost during a scan (${progressUs.size} samples) ──")
    report("slowScanProgress", progressUs)
    report("lastPluginScanError", errorUs)
    report("full catalog read", catalogUs)
    // A scan that ends before the first sample has nothing to measure: no plug-ins here.
    assumeTrue("the scan ran long enough to be sampled", progressUs.isNotEmpty())

    // The first call carries JNA layout and JIT cost; what the UI pays every tick is
    // the steady state, so judge on the median and p95.
    val steadyMs = listOf(progressUs, errorUs)
        .maxOf { it.sorted()[(it.size * 95) / 100] } / 1000.0
    check("per-tick p95 ${steadyMs}ms stays well inside a 16ms frame", steadyMs < 16.0)
}
