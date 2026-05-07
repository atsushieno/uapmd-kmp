package dev.atsushieno.uapmd

import java.awt.EventQueue

private val threadDebugEnabled: Boolean
    get() = System.getProperty("uapmd.debug.threads") == "true"

fun debugJvmThread(tag: String) {
    if (!threadDebugEnabled)
        return
    val thread = Thread.currentThread()
    val isEdt = EventQueue.isDispatchThread()
    println("[uapmd-thread] $tag | name=${thread.name} id=${thread.threadId()} edt=$isEdt")
}
