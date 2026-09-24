package dev.atsushieno.uapmd.cmp

import dev.atsushieno.uapmd.AddinState
import dev.atsushieno.uapmd.AudioWorkerFault
import dev.atsushieno.uapmd.Augene2

/**
 * Headless check of the addin wiring of uapmd f5d490d, booted through [UapmdHost]
 * exactly as the app boots:
 *
 *  - every built-in addin reaches Active - the MIR analyses fail to load
 *    without the project-command registry, and Virtual MIDI Devices without
 *    the app model;
 *  - project commands and application commands land in their own menus;
 *  - the Virtual MIDI Devices and Augene2 commands open their windows;
 *  - audio workers can be resized;
 *  - with a plug-in (-Duapmd.probe.instantiate=<format>), its virtual MIDI 2.0
 *    device can be enabled and disabled from the Virtual MIDI Devices row.
 *
 * Run with: ./gradlew :uapmd-cmp:runAddinProbe [-Duapmd.probe.instantiate=CLAP]
 */
fun main() {
    var failures = 0
    fun check(label: String, ok: Boolean) {
        println("${if (ok) "PASS" else "FAIL"}  $label")
        if (!ok) failures++
    }
    // Everything the host posts to the UI thread has landed once this returns.
    fun drainUi() = java.awt.EventQueue.invokeAndWait { }

    // UapmdHost.start() is the app's own bootstrap; this thread becomes the model
    // thread, so the model services must be ticked from here as well.
    val host = UapmdHost.start()
    fun pump(millis: Long) {
        val until = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < until) {
            host.tickModelServices()
            drainUi()
            Thread.sleep(50)
        }
    }
    pump(500)

    println("-- addins")
    val addins = host.addins?.addins.orEmpty()
    addins.forEach { println("   ${it.packageId} ${it.addinId}: ${it.state} ${it.message}") }
    check("addins are listed", addins.isNotEmpty())
    check("every addin is active", addins.all { it.state == AddinState.Active })
    check("Virtual MIDI Devices addin is listed", addins.any { it.addinId == "virtual-midi-devices" })
    check("Augene2 addin is listed", addins.any { it.packageId == "/uapmd/augene2" })

    println("-- commands")
    val appCommands = host.addinCommands()
    val projectCommands = host.projectCommands()
    appCommands.forEach { println("   System: ${it.id} '${it.title}'") }
    projectCommands.forEach { println("   Project: ${it.id} '${it.title}'") }
    check("project commands are published", projectCommands.isNotEmpty())
    check("Virtual MIDI Devices command is in System", appCommands.any { it.id == "uapmd.virtual-midi-devices" })
    check("Augene2 command is in System", appCommands.any { it.id == "augene2.integration" })

    println("-- Virtual MIDI Devices")
    host.refresh()
    check("addin reports itself enabled", host.virtualMidiDevicesEnabled)
    check("automatic creation is off by default", !host.autoCreateVirtualMidiDevices)
    host.autoCreateVirtualMidiDevices = true
    check("automatic creation can be turned on", host.autoCreateVirtualMidiDevices)
    host.autoCreateVirtualMidiDevices = false
    host.invokeAddinCommand("uapmd.virtual-midi-devices")
    drainUi()
    check("command opens the window", host.isVirtualMidiDevicesOpen)
    host.invokeAddinCommand("uapmd.virtual-midi-devices")
    drainUi()
    check("command toggles the window closed", !host.isVirtualMidiDevicesOpen)

    println("-- Augene2")
    check("Augene2 is in this build", Augene2.isAvailable)
    val augene2 = host.augene2
    check("the project service is registered", augene2 != null)
    if (augene2 != null) {
        host.invokeAddinCommand("augene2.integration")
        pump(200)
        check("command opens the panel", host.isAugene2Open && augene2.isOpen)
        check("no sources in a new project", augene2.sources.isEmpty())
        augene2.resourceFolder = "mml"
        check("resource folder round-trips", augene2.resourceFolder == "mml")
        host.openAugene2(false)
        check("panel closes", !augene2.isOpen)
    }

    println("-- audio workers")
    val initial = host.audioWorkerCount
    println("   configured workers: $initial, fault: ${host.audioWorkerFault}")
    check("no worker fault", host.audioWorkerFault == AudioWorkerFault.None)
    check("resize to 2 workers", host.configureAudioWorkers(2u) == null && host.audioWorkerCount == 2u)
    check("resize to serial", host.configureAudioWorkers(0u) == null && host.audioWorkerCount == 0u)
    host.configureAudioWorkers(initial)
    val stop = host.stopAudioEngineOnDeadline
    host.stopAudioEngineOnDeadline = !stop
    check("stop-on-deadline toggles", host.stopAudioEngineOnDeadline == !stop)
    host.stopAudioEngineOnDeadline = stop

    System.getProperty("uapmd.probe.instantiate")?.let { format ->
        println("-- plug-in virtual device ($format)")
        var waited = 0
        while ((host.isScanning || host.catalog.isEmpty()) && waited < 120_000) { pump(500); waited += 500; host.refreshCatalog() }
        val entry = host.catalog.firstOrNull { it.format == format }
        check("catalog has a $format plug-in", entry != null)
        if (entry != null) {
            host.instantiate(entry, -1)
            waited = 0
            while ((host.isInstantiating || host.trackInstances.flatten().isEmpty()) && waited < 30_000) {
                pump(200); waited += 200; host.refresh()
            }
            val instance = host.trackInstances.flatten().firstOrNull()
            check("instance created", instance != null)
            if (instance != null) {
                val row = host.virtualMidiDeviceRow(instance.instanceId)
                println("   row: $row")
                check("row carries the track suffix",
                    row != null && row.defaultDeviceName.endsWith(" T${row.trackIndex + 1}"))
                check("no device until asked for", row?.running == false)
                if (row != null && row.supported) {
                    host.enableVirtualMidiDevice(instance.instanceId, row.defaultDeviceName)
                    pump(1000)
                    check("device enabled", host.virtualMidiDeviceRow(instance.instanceId)?.running == true)
                    host.disableVirtualMidiDevice(instance.instanceId)
                    pump(500)
                    check("device disabled", host.virtualMidiDeviceRow(instance.instanceId)?.running == false)
                } else println("   (virtual MIDI 2.0 unsupported here; enable/disable skipped)")
            }
        }
    }

    println("-- shutdown")
    // Deliberately while the startup scan may still run: shutdown() has to stop
    // it, or the process aborts on the way out.
    println("   scanning at shutdown: ${host.isScanning}")
    host.shutdown()
    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
