package dev.atsushieno.uapmd.cmp

import android.content.Context
import android.util.Log
import java.io.File

/**
 * On-demand thread dump, for diagnosing freezes.
 *
 * A frozen app cannot be asked for anything through the main looper, and
 * `kill -3` produces nothing without root, so this watches for a sentinel file
 * from an ordinary daemon thread. It keeps working while the main thread is
 * blocked, which is exactly when it is needed.
 *
 *   adb shell run-as dev.atsushieno.uapmd_cmp \
 *       touch /data/data/dev.atsushieno.uapmd_cmp/files/DUMP_STACKS
 *
 * The dump goes to logcat under the tag below and to files/stacks-<millis>.txt.
 */
object StackDumpTrigger {
    private const val TAG = "uapmd-stacks"
    private const val SENTINEL = "DUMP_STACKS"
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val dir = context.filesDir
        Thread({
            while (true) {
                runCatching {
                    val flag = File(dir, SENTINEL)
                    if (flag.exists()) {
                        flag.delete()
                        val dump = dumpThreadStacks()
                        File(dir, "stacks-${System.currentTimeMillis()}.txt").writeText(dump)
                        dump.lineSequence().forEach { Log.w(TAG, it) }
                        Log.w(TAG, "--- dump complete ---")
                    }
                }
                Thread.sleep(700)
            }
        }, "uapmd-stack-dumper").apply { isDaemon = true }.start()
    }
}
