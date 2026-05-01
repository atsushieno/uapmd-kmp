@file:Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")

package dev.atsushieno.uapmd

import kotlin.js.Promise

// ── Module singleton ──────────────────────────────────────────────────────────

private var _jsMod: dynamic = null

/**
 * The initialized Emscripten module (dynamic JS object).
 * Throws if [initUapmdJs] has not been called.
 */
internal val jsMod: dynamic
    get() = _jsMod ?: error("uapmd JS module not initialized. Call initUapmdJs(factory) first.")

/**
 * Initialize the uapmd JS module from an Emscripten factory function.
 *
 *   import UapmdCApi from './uapmd-c-api.js'
 *   initUapmdJs(UapmdCApi)
 *
 * @param factory The default export of `uapmd-c-api.js` (an async factory function).
 */
suspend fun initUapmdJs(factory: dynamic) {
    if (_jsMod != null) return
    _jsMod = (factory() as Promise<dynamic>).await()
}

// ── Memory / string helpers ───────────────────────────────────────────────────

/** Allocate a block of Wasm memory, execute [block] with the pointer, then free. */
internal fun <T> withWasmMem(size: Int, block: (Int) -> T): T {
    val mod = jsMod
    val ptr = mod._malloc(size) as Int
    return try { block(ptr) } finally { mod._free(ptr) }
}

/**
 * Read an output string via the two-argument pattern:
 *   size_t fn(handle, char* buf, size_t buf_size)
 * Call with buf=0 to get required size, then allocate + call again.
 */
internal fun readJsString(handle: Int, fn: (Int, Int, Int) -> Int): String {
    val mod = jsMod
    val size = fn(handle, 0, 0)
    if (size <= 0) return ""
    val ptr = mod._malloc(size) as Int
    return try {
        fn(handle, ptr, size)
        mod.UTF8ToString(ptr, size - 1) as String
    } finally { mod._free(ptr) }
}

/**
 * Read an output string that takes an extra index parameter:
 *   size_t fn(handle, index, char* buf, size_t)
 */
internal fun readJsStringIndexed(handle: Int, index: Int, fn: (Int, Int, Int, Int) -> Int): String {
    val mod = jsMod
    val size = fn(handle, index, 0, 0)
    if (size <= 0) return ""
    val ptr = mod._malloc(size) as Int
    return try {
        fn(handle, index, ptr, size)
        mod.UTF8ToString(ptr, size - 1) as String
    } finally { mod._free(ptr) }
}

/** Allocate a temporary C string (UTF-8), run [block] with its pointer, then free. */
internal fun <T> withJsCString(str: String?, block: (Int) -> T): T {
    val mod = jsMod
    if (str.isNullOrEmpty()) return block(0)
    val len = (mod.lengthBytesUTF8(str) as Int) + 1
    val ptr = mod._malloc(len) as Int
    return try {
        mod.stringToUTF8(str, ptr, len)
        block(ptr)
    } finally { mod._free(ptr) }
}

internal fun <T> withJsTwoCStrings(s1: String, s2: String, block: (Int, Int) -> T): T =
    withJsCString(s1) { p1 -> withJsCString(s2) { p2 -> block(p1, p2) } }

internal fun <T> withJsThreeCStrings(s1: String, s2: String, s3: String, block: (Int, Int, Int) -> T): T =
    withJsCString(s1) { p1 -> withJsCString(s2) { p2 -> withJsCString(s3) { p3 -> block(p1, p2, p3) } } }

// ── Memory read helpers ───────────────────────────────────────────────────────

internal fun jsGetI32(ptr: Int): Int         = jsMod.getValue(ptr, "i32") as Int
internal fun jsGetU32(ptr: Int): Int         = (jsMod.getValue(ptr, "i32") as Int).let { it ushr 0 }
internal fun jsGetI8(ptr: Int): Int          = jsMod.getValue(ptr, "i8") as Int
internal fun jsGetBool(ptr: Int): Boolean    = jsGetI8(ptr) != 0
internal fun jsGetF64(ptr: Int): Double      = jsMod.getValue(ptr, "double") as Double
internal fun jsGetPtr(ptr: Int): Int         = (jsMod.getValue(ptr, "i32") as Int)
internal fun jsGetStr(ptr: Int): String {
    val strPtr = jsGetPtr(ptr)
    return if (strPtr == 0) "" else jsMod.UTF8ToString(strPtr) as String
}
internal fun jsGetI64(ptr: Int): Long {
    val lo = (jsMod.getValue(ptr,     "i32") as Int).toLong() and 0xFFFFFFFFL
    val hi = (jsMod.getValue(ptr + 4, "i32") as Int).toLong()
    return hi * 4294967296L + lo
}

internal fun jsSetI32(ptr: Int, v: Int)    { jsMod.setValue(ptr, v, "i32") }
internal fun jsSetI8(ptr: Int, v: Int)     { jsMod.setValue(ptr, v, "i8") }
internal fun jsSetF32(ptr: Int, v: Float)  { jsMod.setValue(ptr, v, "float") }

// ── Struct decoders ───────────────────────────────────────────────────────────

internal fun jsDecodeTimelinePosition(ptr: Int) =
    TimelinePosition(samples = jsGetI64(ptr), legacyBeats = jsGetF64(ptr + 8))

internal fun jsDecodeTimelineState(ptr: Int): TimelineState = TimelineState(
    playheadPosition         = jsDecodeTimelinePosition(ptr),
    isPlaying                = jsGetBool(ptr + 16),
    loopEnabled              = jsGetBool(ptr + 17),
    loopStart                = jsDecodeTimelinePosition(ptr + 24),
    loopEnd                  = jsDecodeTimelinePosition(ptr + 40),
    tempo                    = jsGetF64(ptr + 56),
    timeSignatureNumerator   = jsGetI32(ptr + 64),
    timeSignatureDenominator = jsGetI32(ptr + 68),
    sampleRate               = jsGetI32(ptr + 72)
)

internal fun jsDecodeParameterMetadata(ptr: Int): ParameterMetadata {
    val namedCount = jsGetU32(ptr + 44)
    val namedBase  = jsGetPtr(ptr + 48)
    return ParameterMetadata(
        index             = jsGetU32(ptr).toUInt(),
        stableId          = jsGetStr(ptr + 4),
        name              = jsGetStr(ptr + 8),
        path              = jsGetStr(ptr + 12),
        defaultPlainValue = jsGetF64(ptr + 16),
        minPlainValue     = jsGetF64(ptr + 24),
        maxPlainValue     = jsGetF64(ptr + 32),
        automatable       = jsGetBool(ptr + 40),
        hidden            = jsGetBool(ptr + 41),
        discrete          = jsGetBool(ptr + 42),
        namedValues       = List(namedCount) { i ->
            val b = namedBase + i * 16
            ParameterNamedValue(value = jsGetF64(b), name = jsGetStr(b + 8))
        }
    )
}

internal fun jsDecodePresetMetadata(ptr: Int) = PresetMetadata(
    bank     = (jsGetI8(ptr) and 0xFF).toUByte(),
    index    = jsGetU32(ptr + 4).toUInt(),
    stableId = jsGetStr(ptr + 8),
    name     = jsGetStr(ptr + 12),
    path     = jsGetStr(ptr + 16)
)

internal fun jsDecodeBlocklistEntry(ptr: Int) = BlocklistEntry(
    id       = jsGetStr(ptr),
    format   = jsGetStr(ptr + 4),
    pluginId = jsGetStr(ptr + 8),
    reason   = jsGetStr(ptr + 12)
)

internal fun jsDecodeAudioDeviceInfo(ptr: Int) = AudioDeviceInfo(
    directions = AudioIoDirection.fromNative(jsGetI32(ptr)),
    id         = jsGetI32(ptr + 4),
    name       = jsGetStr(ptr + 8),
    sampleRate = jsGetU32(ptr + 12).toUInt(),
    channels   = jsGetU32(ptr + 16).toUInt()
)

internal fun jsDecodeClipAddResult(ptr: Int) = ClipAddResult(
    clipId       = jsGetI32(ptr),
    sourceNodeId = jsGetI32(ptr + 4),
    success      = jsGetBool(ptr + 8),
    error        = jsGetPtr(ptr + 12).let { if (it != 0) jsMod.UTF8ToString(it) as String else null }
)

// ── Callback helpers ──────────────────────────────────────────────────────────
// In K/JS, Kotlin lambdas ARE JavaScript functions, so we can pass them directly
// to Emscripten's addFunction without any dispatch trampoline.

internal fun addJsCallback(fn: dynamic, signature: String): Int =
    jsMod.addFunction(fn, signature) as Int

internal fun removeJsCallback(ptr: Int) {
    if (ptr != 0) jsMod.removeFunction(ptr)
}

/** Make an Emscripten table entry for a callback that delivers a C string error. */
internal fun makeJsErrorCallback(callback: (String?) -> Unit): Int {
    val fn: (dynamic) -> Unit = { errorPtr ->
        val err: String? = if ((errorPtr as Int) != 0) jsMod.UTF8ToString(errorPtr) as String else null
        callback(err)
    }
    return addJsCallback(fn.asDynamic(), "vi")
}

/** Make a table entry for an instance-creation callback (instanceId, errorPtr). */
internal fun makeJsCreateInstanceCallback(callback: (Int, String?) -> Unit): Int {
    val fn: (dynamic, dynamic) -> Unit = { instanceId, errorPtr ->
        val err: String? = if ((errorPtr as Int) != 0) jsMod.UTF8ToString(errorPtr) as String else null
        callback(instanceId as Int, err)
    }
    return addJsCallback(fn.asDynamic(), "vii")
}

/** Make a table entry for an add-plugin callback (instanceId, trackIndex, errorPtr). */
internal fun makeJsAddPluginCallback(callback: (Int, Int, String?) -> Unit): Int {
    val fn: (dynamic, dynamic, dynamic) -> Unit = { instanceId, trackIndex, errorPtr ->
        val err: String? = if ((errorPtr as Int) != 0) jsMod.UTF8ToString(errorPtr) as String else null
        callback(instanceId as Int, trackIndex as Int, err)
    }
    return addJsCallback(fn.asDynamic(), "viii")
}

/** Make a table entry for a state callback (dataPtr, size, errorPtr). */
internal fun makeJsStateCallback(callback: (ByteArray?, String?) -> Unit): Int {
    val fn: (dynamic, dynamic, dynamic) -> Unit = { dataPtr, size, errorPtr ->
        val data: ByteArray? = if ((dataPtr as Int) != 0 && (size as Int) > 0) {
            ByteArray(size) { i -> (jsMod.getValue(dataPtr + i, "i8") as Int).toByte() }
        } else null
        val err: String? = if ((errorPtr as Int) != 0) jsMod.UTF8ToString(errorPtr) as String else null
        callback(data, err)
    }
    return addJsCallback(fn.asDynamic(), "viii")
}

/** Make a table entry for an event-output callback. */
internal fun makeJsEventOutputCallback(
    callback: (instanceId: Int, data: IntArray, dataSizeInBytes: Int) -> Unit
): Int {
    val fn: (dynamic, dynamic, dynamic) -> Unit = { instanceId, dataPtr, size ->
        val iid = instanceId as Int
        val ptr = dataPtr as Int
        val sz  = size as Int
        val data = IntArray(sz / 4) { i -> jsMod.getValue(ptr + i * 4, "i32") as Int }
        callback(iid, data, sz)
    }
    return addJsCallback(fn.asDynamic(), "viii")
}

/** Make a table entry for a MIDI input handler. */
internal fun makeJsMidiInputHandler(handler: (UIntArray, Long) -> Unit): Int {
    val fn: (dynamic, dynamic, dynamic) -> Unit = { messagesPtr, count, timestamp ->
        val ptr = messagesPtr as Int
        val cnt = count as Int
        val data = UIntArray(cnt) { i -> (jsMod.getValue(ptr + i * 4, "i32") as Int).toUInt() }
        handler(data, (timestamp as Double).toLong())
    }
    return addJsCallback(fn.asDynamic(), "viid")
}

// ── Callback ID counter (reuse from plugin module direction) ──────────────────
// Shared across JS source set; used for scan observer keys.

private var _nextJsCbId = 1
internal fun nextJsCbId(): Int = _nextJsCbId++

// Storage for scan observers (keyed by scan cbId)
internal val jsScanObservers = mutableMapOf<Int, ScanObserver>()

/** Build scan-observer function pointers; returns a SextupleFnPtrs. */
internal data class ScanCbPtrs(
    val slowStart: Int, val bundleStart: Int, val bundleComplete: Int,
    val slowComplete: Int, val error: Int, val cancel: Int
)

internal fun makeScanObserverPtrs(cbId: Int): ScanCbPtrs {
    val slowStart: (dynamic) -> Unit = { total ->
        jsScanObservers[cbId]?.onSlowScanStarted((total as Int).toUInt())
    }
    val bundleStart: (dynamic) -> Unit = { pathPtr ->
        val p = pathPtr as Int
        jsScanObservers[cbId]?.onBundleScanStarted(if (p != 0) jsMod.UTF8ToString(p) as String else "")
    }
    val bundleComplete: (dynamic) -> Unit = { pathPtr ->
        val p = pathPtr as Int
        jsScanObservers[cbId]?.onBundleScanCompleted(if (p != 0) jsMod.UTF8ToString(p) as String else "")
    }
    val slowComplete: () -> Unit = {
        jsScanObservers[cbId]?.onSlowScanCompleted()
    }
    val errorFn: (dynamic) -> Unit = { msgPtr ->
        val p = msgPtr as Int
        jsScanObservers[cbId]?.onErrorOccurred(if (p != 0) jsMod.UTF8ToString(p) as String else "")
    }
    val cancelFn: () -> Boolean = {
        jsScanObservers[cbId]?.shouldCancel() ?: false
    }
    return ScanCbPtrs(
        slowStart    = addJsCallback(slowStart.asDynamic(),    "vi"),
        bundleStart  = addJsCallback(bundleStart.asDynamic(),  "vi"),
        bundleComplete = addJsCallback(bundleComplete.asDynamic(), "vi"),
        slowComplete = addJsCallback(slowComplete.asDynamic(), "v"),
        error        = addJsCallback(errorFn.asDynamic(),      "vi"),
        cancel       = addJsCallback(cancelFn.asDynamic(),     "i")
    )
}

internal fun freeScanObserverPtrs(ptrs: ScanCbPtrs) {
    removeJsCallback(ptrs.slowStart)
    removeJsCallback(ptrs.bundleStart)
    removeJsCallback(ptrs.bundleComplete)
    removeJsCallback(ptrs.slowComplete)
    removeJsCallback(ptrs.error)
    removeJsCallback(ptrs.cancel)
}
