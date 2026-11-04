package dev.atsushieno.uapmd

import android.os.Handler
import android.os.Looper

/**
 * Kotlin object that the JNI side calls to dispatch remidy `EventLoop` tasks.
 * The single instance is passed to `uapmdSetupAndroidEventLoop()`.
 *
 * The tasks run on the **Android main looper**, the thread that creates AppModel,
 * because uapmd treats its event loop's main thread as the model thread: completions
 * it posts there run synchronous project commands, which the model refuses from any
 * other thread. A dedicated looper thread made every such command fail silently
 * (a re-saved `.uapmdz` lost its master track's tempo map that way).
 *
 * Anything slow posted here holds up the UI, so uapmd-cmp issues what it can from
 * background dispatchers. Waiting for an AAP service bind here is not a deadlock:
 * aap-core delivers `onServiceConnected` on its own threads, not on this looper.
 */
private class AndroidEventLoopDispatcher {
    private val handler = Handler(Looper.getMainLooper())

    // Called by native code via JNI to schedule a queued task.
    @Suppress("unused")
    fun dispatchTask(token: Long) {
        handler.post { JniBridge.uapmdRunEventLoopTask(token) }
    }
}

private val dispatcher = AndroidEventLoopDispatcher()

private val eventLoopInit: Unit by lazy {
    // The native side records the calling thread as the event loop's main thread.
    check(Looper.myLooper() == Looper.getMainLooper()) { "initAndroidEventLoop() must run on the main thread" }
    JniBridge.uapmdSetupAndroidEventLoop(dispatcher)
}

// Call once, on the main thread, before creating any engine or sequencer.
fun initAndroidEventLoop() {
    eventLoopInit
}
