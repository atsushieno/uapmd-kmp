package dev.atsushieno.uapmd.cmp

import dev.atsushieno.uapmd.AppModel
import dev.atsushieno.uapmd.cleanupAppModel
import dev.atsushieno.uapmd.getAppModel
import dev.atsushieno.uapmd.initJvmEventLoop
import dev.atsushieno.uapmd.instantiateAppModel
import java.io.File
import kotlin.test.fail

/**
 * PASS/FAIL bookkeeping for the integration tests.
 *
 * Every check is printed as it runs and a failed one does not stop the test, so a
 * single run against the real engine reports all of what broke instead of the first
 * thing; [assertAllPassed] then fails the test with the list.
 */
internal class Checks {
    private val failed = mutableListOf<String>()

    fun check(label: String, ok: Boolean) {
        println("${if (ok) "PASS" else "FAIL"}  $label")
        if (!ok) failed += label
    }

    fun assertAllPassed() {
        if (failed.isNotEmpty())
            fail("${failed.size} check(s) failed:\n" + failed.joinToString("\n") { "  $it" })
    }
}

/**
 * Runs [body] against a freshly instantiated AppModel, then fails the test with every
 * check that did not hold.
 *
 * The model is cleaned up even when [body] throws or is skipped by an assumption.
 * Cleanup is idempotent, so a body that tears down in an order of its own may call
 * `cleanupAppModel()` itself as well.
 */
internal fun runAgainstAppModel(body: (model: AppModel, check: (String, Boolean) -> Unit) -> Unit) {
    val checks = Checks()
    initJvmEventLoop()
    instantiateAppModel()
    try {
        body(getAppModel(), checks::check)
    } finally {
        cleanupAppModel()
    }
    checks.assertAllPassed()
}

/**
 * A file in the uapmd-kmp checkout. `jvmIntegrationTest` passes the checkout's
 * location; the fallback is the module directory Gradle runs tests from.
 */
internal fun repoFile(relative: String): File =
    File(System.getProperty("uapmd.test.repoRoot") ?: "../..", relative)

/** The MIDI file the timeline tests import unless `-Duapmd.probe.midi` names another. */
internal fun defaultMidiFile(): File =
    repoFile("external/uapmd/cmake-build-debug/_deps/libremidi-src/tests/corpus/You're No Good.mid")
