package dev.atsushieno.uapmd

import android.os.Handler
import android.os.Looper

// Kotlin object that the JNI side calls to dispatch tasks to the main thread.
// The single instance is passed to uapmdSetupAndroidEventLoop().
private class AndroidEventLoopDispatcher {
    private val handler = Handler(Looper.getMainLooper())

    // Called by native code via JNI to schedule a queued task on the main thread.
    @Suppress("unused")
    fun dispatchTask(token: Long) {
        handler.post { JniBridge.uapmdRunEventLoopTask(token) }
    }
}

private val dispatcher = AndroidEventLoopDispatcher()

private val eventLoopInit: Unit by lazy {
    JniBridge.uapmdSetupAndroidEventLoop(dispatcher)
}

// Call once from the Android main thread (e.g. in createUapmdModel) before
// creating any engine or sequencer.
fun initAndroidEventLoop() {
    eventLoopInit
}
