package dev.atsushieno.uapmd

import com.sun.jna.Function
import com.sun.jna.Pointer
import dev.atsushieno.uapmd.jna.EventLoopEnqueueCb
import dev.atsushieno.uapmd.jna.EventLoopIsMainThreadCb
import java.util.concurrent.Executors

// ─── JVM native event loop ────────────────────────────────────────────────────
//
// remidy::EventLoop::runTaskOnMainThread() needs a thread to dispatch tasks to.
// The default choc/Cocoa implementation posts to the macOS main dispatch queue,
// but the JVM's thread-0 does not run a Cocoa run-loop, causing a deadlock when
// plugin loading enqueues a task that never gets picked up.
//
// We create a single-threaded executor ("uapmd-native-main") and register it as
// the remidy event loop.  Plugin instantiation then runs on that thread instead,
// unblocking the Dispatchers.IO thread that called addPluginToTrack.

private val nativeMainExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "uapmd-native-main").also { it.isDaemon = true }
}

@Volatile private var nativeMainThread: Thread? = null

private val isMainThreadCb = object : EventLoopIsMainThreadCb {
    override fun invoke(userData: Pointer?): Boolean =
        Thread.currentThread() === nativeMainThread
}

private val enqueueTaskCb = object : EventLoopEnqueueCb {
    override fun invoke(taskFn: Pointer?, taskCtx: Pointer?, userData: Pointer?) {
        if (taskFn == null) return
        nativeMainExecutor.submit {
            // Call the C function pointer taskFn(taskCtx).
            // JNA's Function lets us call arbitrary C function pointers.
            Function.getFunction(taskFn).invoke(Void::class.java, arrayOf<Any?>(taskCtx))
        }
        // Note: the C++ caller (runTaskOnMainThread) blocks on std::atomic::wait
        // until the task sets done=true after running — we must not block here.
    }
}

fun initJvmEventLoop() {
    // Submit setup from within the executor so that isMainThreadCb returns true
    // at the moment setEventLoop() calls the assert(runningOnMainThread()).
    nativeMainExecutor.submit {
        nativeMainThread = Thread.currentThread()
        lib.uapmd_set_event_loop(null, null, isMainThreadCb, enqueueTaskCb)
    }.get()
}
