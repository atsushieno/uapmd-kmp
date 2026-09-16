package dev.atsushieno.uapmd

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import dev.atsushieno.uapmd.jna.EventLoopEnqueueCb
import dev.atsushieno.uapmd.jna.EventLoopIsMainThreadCb
import java.awt.EventQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private interface JvmEventLoopDispatcher {
    fun isMainThread(): Boolean
    fun enqueueNative(taskFn: Pointer?, taskCtx: Pointer?)
    fun <T> runSync(action: () -> T): T
}

private class AwtJvmEventLoopDispatcher : JvmEventLoopDispatcher {
    override fun isMainThread(): Boolean =
        EventQueue.isDispatchThread()

    override fun enqueueNative(taskFn: Pointer?, taskCtx: Pointer?) {
        if (taskFn == null) return
        EventQueue.invokeLater {
            debugJvmThread("AwtJvmEventLoopDispatcher.enqueueNative")
            Function.getFunction(taskFn).invoke(Void::class.java, arrayOf<Any?>(taskCtx))
        }
    }

    override fun <T> runSync(action: () -> T): T {
        if (isMainThread())
            return action()
        var result: Result<T>? = null
        EventQueue.invokeAndWait {
            result = runCatching(action)
        }
        return result!!.getOrThrow()
    }
}

/**
 * The AppKit main thread, reached through its **run loop** rather than through
 * the main dispatch queue.
 *
 * The main queue is serial and non-reentrant: while it drains one item, nothing
 * else on it runs, not even from a nested run loop. remidy's AU instantiation
 * depends on that nesting — `PluginFormatAU.mm` spins
 * `while (!instantiationCompleted) CFRunLoopRunInMode(...)` waiting for a
 * completion that `AVAudioUnit` delivers through the main queue — so a task
 * dispatched into the queue deadlocks it outright, permanently, on plug-ins
 * like Mela. Work performed on the run loop is a run-loop callout instead, and
 * a nested run loop inside it still drains the queue.
 *
 * The scheduling is done natively by `uapmd_internal_enqueue_on_main_thread`:
 * `CFRunLoopPerformBlock` takes an Objective-C block, which JNA cannot build.
 */
private object AppleMainThreadDispatcher {
    private val library = NativeLibrary.getInstance("System")
    private val pthreadMainNp = library.getFunction("pthread_main_np")
    private val nextToken = AtomicLong(1L)
    private val pendingWork = ConcurrentHashMap<Long, () -> Unit>()
    private val workCallback = object : Callback {
        @Suppress("unused")
        fun invoke(context: Pointer?) {
            val token = context?.let(Pointer::nativeValue) ?: return
            pendingWork.remove(token)?.invoke()
        }
    }

    fun isMainThread(): Boolean =
        (pthreadMainNp.invokeInt(emptyArray()) != 0)

    fun enqueueNative(taskFn: Pointer?, taskCtx: Pointer?) {
        if (taskFn == null) return
        debugJvmThread("AppleMainThreadDispatcher.enqueueNative")
        lib.uapmd_internal_enqueue_on_main_thread(taskFn, taskCtx)
    }

    fun <T> runSync(action: () -> T): T {
        debugJvmThread("AppleMainThreadDispatcher.runSync.request")
        var result: Result<T>? = null
        val token = nextToken.getAndIncrement()
        val latch = java.util.concurrent.CountDownLatch(1)
        pendingWork[token] = {
            debugJvmThread("AppleMainThreadDispatcher.runSync.invoke")
            result = runCatching(action)
            latch.countDown()
        }
        lib.uapmd_internal_enqueue_on_main_thread(
            CallbackReference.getFunctionPointer(workCallback),
            Pointer.createConstant(token)
        )
        latch.await()
        return result!!.getOrThrow()
    }
}

private class SystemMainThreadJvmEventLoopDispatcher : JvmEventLoopDispatcher {
    override fun isMainThread(): Boolean =
        AppleMainThreadDispatcher.isMainThread()

    override fun enqueueNative(taskFn: Pointer?, taskCtx: Pointer?) {
        AppleMainThreadDispatcher.enqueueNative(taskFn, taskCtx)
    }

    override fun <T> runSync(action: () -> T): T {
        if (isMainThread())
            return action()
        return AppleMainThreadDispatcher.runSync(action)
    }
}

private val isMacOs: Boolean
    get() = System.getProperty("os.name")?.contains("mac", ignoreCase = true) == true

@Volatile
private var installedDispatcher: JvmEventLoopDispatcher? = null

private val isMainThreadCb = object : EventLoopIsMainThreadCb {
    override fun invoke(userData: Pointer?): Boolean =
        installedDispatcher?.isMainThread() == true
}

private val enqueueTaskCb = object : EventLoopEnqueueCb {
    override fun invoke(taskFn: Pointer?, taskCtx: Pointer?, userData: Pointer?) {
        installedDispatcher?.enqueueNative(taskFn, taskCtx)
            ?: error("uapmd JVM event loop is not initialized.")
    }
}

/**
 * Installs remidy's event loop.
 *
 * **On macOS the main thread is the AppKit main thread, not the AWT event
 * queue.** Everything remidy marshals to "the main thread" has to land there:
 * `clap_plugin_factory.create_plugin` and `clap_entry.init` are main-thread
 * calls by the CLAP spec, and a JUCE-based plug-in binds its MessageManager to
 * whichever thread runs them. Bind that to the AWT event queue and the plug-in's
 * message thread is a thread with no CFRunLoop — one that AWT also retires when
 * it goes idle — so the `MessageManagerLock` the same plug-in takes in
 * `guiCreate` is never granted and creating its UI hangs for good. Instancing on
 * the AppKit thread is also what uapmd-app does, since there the main thread is
 * the only thread.
 *
 * Elsewhere the AWT event queue *is* the UI thread and is used directly.
 */
@Synchronized
fun initJvmEventLoop() {
    if (installedDispatcher != null)
        return

    debugJvmThread("initJvmEventLoop")
    // Touch AWT first either way: on macOS the AppKit run loop — which is what
    // drains the main dispatch queue — is only started when AWT comes up, and
    // dispatching to a queue nobody drains would hang on the first task.
    if (EventQueue.isDispatchThread()) debugJvmThread("initJvmEventLoop.awt (already on it)")
    else EventQueue.invokeAndWait { debugJvmThread("initJvmEventLoop.awt") }
    installedDispatcher =
        if (isMacOs) SystemMainThreadJvmEventLoopDispatcher() else AwtJvmEventLoopDispatcher()
    lib.uapmd_set_event_loop(null, null, isMainThreadCb, enqueueTaskCb)
}

internal fun <T> runOnJvmEventLoopThread(action: () -> T): T =
    installedDispatcher?.runSync {
        debugJvmThread("runOnJvmEventLoopThread")
        action()
    }
        ?: error("uapmd JVM event loop is not initialized.")

/**
 * The thread plug-in UI calls must run on.
 *
 * On macOS that is the AppKit main thread, and the presentation entry points in
 * the C API already hop to it natively — so the call is made from here, on
 * whatever thread we are on. Going through [runOnJvmEventLoopThread] instead
 * would run a JNA callback on the main queue and then re-enter JNA from inside
 * it, which is the shape that deadlocked once a plug-in view had attached (see
 * `on_native_ui_thread` in `c-api/src/uapmd-c-api.cpp`). The main-thread
 * identity remidy sees inside the call is correct either way, because the
 * installed loop reports the AppKit thread.
 */
internal fun <T> runOnJvmNativeUiThread(action: () -> T): T =
    if (isMacOs) action() else runOnJvmEventLoopThread(action)
