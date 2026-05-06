package dev.atsushieno.uapmd

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import dev.atsushieno.uapmd.jna.EventLoopEnqueueCb
import dev.atsushieno.uapmd.jna.EventLoopIsMainThreadCb
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

private interface JvmEventLoopDispatcher {
    fun isMainThread(): Boolean
    fun enqueueNative(taskFn: Pointer?, taskCtx: Pointer?)
    fun <T> runSync(action: () -> T): T
}

private class ExecutorJvmEventLoopDispatcher : JvmEventLoopDispatcher {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "uapmd-native-main").also { it.isDaemon = true }
    }

    @Volatile
    private var nativeMainThread: Thread? = null

    fun install(block: () -> Unit) {
        executor.submit {
            nativeMainThread = Thread.currentThread()
            block()
        }.get()
    }

    override fun isMainThread(): Boolean =
        Thread.currentThread() === nativeMainThread

    override fun enqueueNative(taskFn: Pointer?, taskCtx: Pointer?) {
        if (taskFn == null) return
        executor.submit {
            Function.getFunction(taskFn).invoke(Void::class.java, arrayOf<Any?>(taskCtx))
        }
    }

    override fun <T> runSync(action: () -> T): T {
        if (isMainThread())
            return action()
        return executor.submit<T> { action() }.get()
    }
}

private object AppleMainQueueDispatcher {
    private val library = NativeLibrary.getInstance("System")
    private val dispatchAsync = library.getFunction("dispatch_async_f")
    private val dispatchSync = library.getFunction("dispatch_sync_f")
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

    fun enqueueNative(taskFn: Pointer?, taskCtx: Pointer?) {
        if (taskFn == null) return
        dispatchAsync.invoke(Void::class.java, arrayOf(mainQueue, taskCtx, taskFn))
    }

    fun <T> runSync(action: () -> T): T {
        var result: Result<T>? = null
        val token = nextToken.getAndIncrement()
        pendingWork[token] = {
            result = runCatching(action)
        }
        dispatchSync.invoke(Void::class.java, arrayOf(mainQueue, Pointer.createConstant(token), workCallback))
        return result!!.getOrThrow()
    }
}

private class SystemMainThreadJvmEventLoopDispatcher(
    private val uiMainThread: Thread
) : JvmEventLoopDispatcher {
    override fun isMainThread(): Boolean =
        Thread.currentThread() === uiMainThread

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

    if (isMacOs) {
        val dispatcher = SystemMainThreadJvmEventLoopDispatcher(Thread.currentThread())
        installedDispatcher = dispatcher
        lib.uapmd_set_event_loop(null, null, isMainThreadCb, enqueueTaskCb)
    } else {
        val dispatcher = ExecutorJvmEventLoopDispatcher()
        dispatcher.install {
            installedDispatcher = dispatcher
            lib.uapmd_set_event_loop(null, null, isMainThreadCb, enqueueTaskCb)
        }
    }
}

internal fun <T> runOnJvmEventLoopThread(action: () -> T): T =
    installedDispatcher?.runSync(action)
        ?: error("uapmd JVM event loop is not initialized.")
