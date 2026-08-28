package dev.atsushieno.uapmd

import android.os.Handler
import android.os.HandlerThread

/**
 * Kotlin object that the JNI side calls to dispatch remidy `EventLoop` tasks.
 * The single instance is passed to `uapmdSetupAndroidEventLoop()`.
 *
 * The tasks run on a **dedicated looper thread, not the Android main looper.**
 *
 * uapmd routes plug-in instantiation through this loop
 * (`PluginInstancing::makeAlive` posts `setupInstance` via
 * `EventLoop::runTaskOnMainThread` whenever the format wants a UI thread, which
 * AAP does). On Android that instantiation ends in
 * `AudioPluginHostHelper.ensureBinderConnected`, which `runBlocking`s until the
 * plug-in's `AudioPluginService` connects — and `onServiceConnected` is delivered
 * on the **main** looper. Dispatching here to the main looper therefore made the
 * task block the very thread that had to complete it: loading any project with
 * plug-ins deadlocked, with main parked in `joinBlocking` underneath
 * `uapmdRunEventLoopTask`.
 *
 * On its own thread the bind blocks only this loop, the main looper stays free to
 * deliver the connection, and the task resumes — the same shape as an
 * instantiation issued from a worker, which always worked.
 */
private class AndroidEventLoopDispatcher {
    private val thread = HandlerThread("uapmd-event-loop").apply { start() }
    private val handler = Handler(thread.looper)

    // Called by native code via JNI to schedule a queued task.
    @Suppress("unused")
    fun dispatchTask(token: Long) {
        handler.post { JniBridge.uapmdRunEventLoopTask(token) }
    }
}

private val dispatcher = AndroidEventLoopDispatcher()

private val eventLoopInit: Unit by lazy {
    JniBridge.uapmdSetupAndroidEventLoop(dispatcher)
}

// Call once before creating any engine or sequencer.
fun initAndroidEventLoop() {
    eventLoopInit
}
