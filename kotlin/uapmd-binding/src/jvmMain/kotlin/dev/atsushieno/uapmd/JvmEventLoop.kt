package dev.atsushieno.uapmd

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import dev.atsushieno.uapmd.jna.EventLoopEnqueueCb
import dev.atsushieno.uapmd.jna.EventLoopIsMainThreadCb
import java.awt.EventQueue
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.withLock

private interface JvmEventLoopDispatcher {
    fun isMainThread(): Boolean
    fun enqueueNative(taskFn: Pointer?, taskCtx: Pointer?)
    fun <T> runSync(action: () -> T): T
}

private class AwtJvmEventLoopDispatcher : JvmEventLoopDispatcher {
    fun install(block: () -> Unit) {
        EventQueue.invokeAndWait {
            debugJvmThread("AwtJvmEventLoopDispatcher.install")
            block()
        }
    }

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

private object AppleMainQueueDispatcher {
    private val library = NativeLibrary.getInstance("System")
    private val dispatchAsync = library.getFunction("dispatch_async_f")
    private val dispatchSync = library.getFunction("dispatch_sync_f")
    private val pthreadMainNp = library.getFunction("pthread_main_np")
    private val mainQueue = library.getGlobalVariableAddress("_dispatch_main_q")
    private val nextToken = AtomicLong(1L)
    private val pendingWork = ConcurrentHashMap<Long, () -> Unit>()
    private val workCallback = object : Callback {
        @Suppress("unused")
        fun invoke(context: Pointer?) {
            val token = context?.let(Pointer::nativeValue) ?: return
            pendingWork.remove(token)?.invoke()
        }
    }
    private val captureThreadCallback = object : Callback {
        @Suppress("unused")
        fun invoke(context: Pointer?) = Unit
    }

    fun install() {
        debugJvmThread("AppleMainQueueDispatcher.install.request")
        dispatchSync.invoke(Void::class.java, arrayOf(mainQueue, Pointer.NULL, captureThreadCallback))
        debugJvmThread("AppleMainQueueDispatcher.install.captured")
    }

    fun isMainQueueThread(): Boolean =
        (pthreadMainNp.invokeInt(emptyArray()) != 0)

    fun enqueueNative(taskFn: Pointer?, taskCtx: Pointer?) {
        if (taskFn == null) return
        debugJvmThread("AppleMainQueueDispatcher.enqueueNative")
        dispatchAsync.invoke(Void::class.java, arrayOf(mainQueue, taskCtx, taskFn))
    }

    fun <T> runSync(action: () -> T): T {
        debugJvmThread("AppleMainQueueDispatcher.runSync.request")
        var result: Result<T>? = null
        val token = nextToken.getAndIncrement()
        pendingWork[token] = {
            debugJvmThread("AppleMainQueueDispatcher.runSync.invoke")
            result = runCatching(action)
        }
        dispatchSync.invoke(Void::class.java, arrayOf(mainQueue, Pointer.createConstant(token), workCallback))
        return result!!.getOrThrow()
    }
}

private class SystemMainThreadJvmEventLoopDispatcher : JvmEventLoopDispatcher {
    override fun isMainThread(): Boolean =
        AppleMainQueueDispatcher.isMainQueueThread()

    override fun enqueueNative(taskFn: Pointer?, taskCtx: Pointer?) {
        AppleMainQueueDispatcher.enqueueNative(taskFn, taskCtx)
    }

    override fun <T> runSync(action: () -> T): T {
        if (isMainThread())
            return action()
        return AppleMainQueueDispatcher.runSync(action)
    }
}

private val isMacOs: Boolean
    get() = System.getProperty("os.name")?.contains("mac", ignoreCase = true) == true

@Volatile
private var installedDispatcher: JvmEventLoopDispatcher? = null
private val eventLoopDispatcherLock = ReentrantLock()

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

@Synchronized
fun initJvmEventLoop() {
    if (installedDispatcher != null)
        return

    debugJvmThread("initJvmEventLoop")
    val dispatcher = AwtJvmEventLoopDispatcher()
    dispatcher.install {
        installedDispatcher = dispatcher
        lib.uapmd_set_event_loop(null, null, isMainThreadCb, enqueueTaskCb)
    }
}

private inline fun <T> withInstalledDispatcher(dispatcher: JvmEventLoopDispatcher, action: () -> T): T =
    eventLoopDispatcherLock.withLock {
        val previous = installedDispatcher
        installedDispatcher = dispatcher
        try {
            action()
        } finally {
            installedDispatcher = previous
        }
    }

internal fun <T> runOnJvmEventLoopThread(action: () -> T): T =
    installedDispatcher?.runSync {
        debugJvmThread("runOnJvmEventLoopThread")
        action()
    }
        ?: error("uapmd JVM event loop is not initialized.")

internal fun <T> runOnJvmNativeUiThread(action: () -> T): T {
    if (!isMacOs)
        return runOnJvmEventLoopThread(action)
    AppleMainQueueDispatcher.install()
    val dispatcher = SystemMainThreadJvmEventLoopDispatcher()
    return if (AppleMainQueueDispatcher.isMainQueueThread()) {
        withInstalledDispatcher(dispatcher) { action() }
    } else {
        AppleMainQueueDispatcher.runSync {
            withInstalledDispatcher(dispatcher) { action() }
        }
    }
}
