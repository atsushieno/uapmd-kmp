package dev.atsushieno.uapmd

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

// ─── WasmJsPluginInstance ─────────────────────────────────────────────────────

class WasmJsPluginInstance internal constructor(
    internal val handle: Int
) : PluginInstance {

    private val activeUiPresentations = linkedSetOf<WasmJsPluginUiPresentation>()

    override val displayName: String
        get() = readString(handle) { h, buf, size -> uapmdInstanceDisplayName(h, buf, size) }

    override val formatName: String
        get() = readString(handle) { h, buf, size -> uapmdInstanceFormatName(h, buf, size) }

    override val pluginId: String
        get() = readString(handle) { h, buf, size -> uapmdInstancePluginId(h, buf, size) }

    override var bypassed: Boolean
        get() = wasmMod.uapmdInstanceGetBypassed(handle)
        set(v) { wasmMod.uapmdInstanceSetBypassed(handle, v) }

    override fun startProcessing(): Int = wasmMod.uapmdInstanceStartProcessing(handle)
    override fun stopProcessing(): Int  = wasmMod.uapmdInstanceStopProcessing(handle)

    override val latencyInSamples: UInt
        get() = wasmMod.uapmdInstanceLatencyInSamples(handle).toUInt()

    override val tailLengthInSeconds: Double
        get() = wasmMod.uapmdInstanceTailLengthInSeconds(handle)

    override val requiresReplacingProcess: Boolean
        get() = wasmMod.uapmdInstanceRequiresReplacingProcess(handle)

    override val parameterCount: UInt
        get() = wasmMod.uapmdInstanceParameterCount(handle).toUInt()

    override fun getParameterMetadata(index: UInt): ParameterMetadata? {
        val mod = wasmMod
        val ptr = mod.malloc(64) // sizeof uapmd_parameter_metadata_t
        return try {
            if (!mod.uapmdInstanceGetParameterMetadata(handle, index.toInt(), ptr)) null
            else parseParameterMetadata(ptr)
        } finally {
            mod.free(ptr)
        }
    }

    override fun getParameterValue(index: Int): Double =
        wasmMod.uapmdInstanceGetParameterValue(handle, index)

    override fun setParameterValue(index: Int, value: Double) =
        wasmMod.uapmdInstanceSetParameterValue(handle, index, value)

    override fun getParameterValueString(index: Int, value: Double): String {
        val mod = wasmMod
        val size = mod.uapmdInstanceGetParameterValueString(handle, index, value, 0, 0)
        if (size <= 0) return ""
        val ptr = mod.malloc(size)
        return try {
            mod.uapmdInstanceGetParameterValueString(handle, index, value, ptr, size)
            mod.utf8ToString(ptr, size - 1)
        } finally { mod.free(ptr) }
    }

    override fun setPerNoteControllerValue(note: UByte, index: UByte, value: Double) =
        wasmMod.uapmdInstanceSetPerNoteControllerValue(handle, note.toInt(), index.toInt(), value)

    override fun getPerNoteControllerValueString(note: UByte, index: UByte, value: Double): String {
        val mod = wasmMod
        val size = mod.uapmdInstanceGetPerNoteControllerValueString(handle, note.toInt(), index.toInt(), value, 0, 0)
        if (size <= 0) return ""
        val ptr = mod.malloc(size)
        return try {
            mod.uapmdInstanceGetPerNoteControllerValueString(handle, note.toInt(), index.toInt(), value, ptr, size)
            mod.utf8ToString(ptr, size - 1)
        } finally { mod.free(ptr) }
    }

    override val presetCount: UInt
        get() = wasmMod.uapmdInstancePresetCount(handle).toUInt()

    override fun getPresetMetadata(index: UInt): PresetMetadata? {
        val mod = wasmMod
        val ptr = mod.malloc(24) // sizeof uapmd_preset_metadata_t
        return try {
            if (!mod.uapmdInstanceGetPresetMetadata(handle, index.toInt(), ptr)) null
            else parsePresetMetadata(ptr)
        } finally { mod.free(ptr) }
    }

    override fun loadPreset(presetIndex: Int) =
        wasmMod.uapmdInstanceLoadPreset(handle, presetIndex)

    override fun saveStateSync(): ByteArray {
        val mod = wasmMod
        val initialBuf = 65536
        val buf = mod.malloc(initialBuf)
        val actualPtr = mod.malloc(4)
        return try {
            mod.uapmdInstanceSaveStateSync(handle, buf, initialBuf, actualPtr)
            val actual = mod.getValue(actualPtr, "i32").toInt()
            ByteArray(actual) { i -> mod.getValue(buf + i, "i8").toInt().toByte() }
        } finally { mod.free(buf); mod.free(actualPtr) }
    }

    override fun loadStateSync(data: ByteArray) {
        val mod = wasmMod
        val ptr = mod.malloc(data.size)
        try {
            data.forEachIndexed { i, b -> mod.setValue(ptr + i, b.toDouble(), "i8") }
            mod.uapmdInstanceLoadStateSync(handle, ptr, data.size)
        } finally { mod.free(ptr) }
    }

    override fun requestState(ctx: StateContextType, includeUiState: Boolean, callback: (ByteArray?, String?) -> Unit) {
        val cbId = nextCallbackId()
        pendingRequestStateCallbacks[cbId] = callback
        val fnPtr = makeStateCallbackPtr(cbId, "uapmdDispatchRequestState")
        wasmMod.uapmdInstanceRequestState(handle, ctx.nativeValue, includeUiState, fnPtr, 0)
    }

    override fun loadState(data: ByteArray, ctx: StateContextType, includeUiState: Boolean, callback: (String?) -> Unit) {
        val mod = wasmMod
        val ptr = mod.malloc(data.size)
        try {
            data.forEachIndexed { i, b -> mod.setValue(ptr + i, b.toDouble(), "i8") }
            val cbId = nextCallbackId()
            pendingLoadStateCallbacks[cbId] = callback
            val fnPtr = makeCFunctionPtr(cbId, "uapmdDispatchLoadState", "vii")
            mod.uapmdInstanceLoadState(handle, ptr, data.size, ctx.nativeValue, includeUiState, 0, fnPtr)
        } finally { mod.free(ptr) }
    }

    override val uiCapabilities: PluginUiCapabilities
        get() {
            val capsPtr = wasmMod.malloc(4)
            return try {
                wasmMod.uapmdInstanceGetUiCapabilities(handle, capsPtr)
                PluginUiCapabilities(
                    hasUiSupport = wasmMod.getValue(capsPtr, "i8").toInt() != 0,
                    supportsEmbeddedPresentations = wasmMod.getValue(capsPtr + 1, "i8").toInt() != 0,
                    supportsFloatingPresentations = wasmMod.getValue(capsPtr + 2, "i8").toInt() != 0,
                    supportsMultiplePresentations = wasmMod.getValue(capsPtr + 3, "i8").toInt() != 0
                )
            } finally {
                wasmMod.free(capsPtr)
            }
        }

    override fun createUiPresentation(request: PluginUiPresentationRequest): PluginUiPresentation? {
        if (!uiCapabilities.hasUiSupport)
            return null
        if (activeUiPresentations.isNotEmpty() && !uiCapabilities.supportsMultiplePresentations)
            return null

        val presentationHandle = wasmMod.malloc(16).let { reqPtr ->
            try {
                wasmMod.setValue(reqPtr + 4, when (request.role) {
                    PluginUiPresentationRole.COMPACT -> 0.0
                    PluginUiPresentationRole.FULL -> 1.0
                    PluginUiPresentationRole.AUXILIARY -> 2.0
                }, "i32")
                when (val host = request.host) {
                    PluginUiHost.FloatingWindow -> {
                        wasmMod.setValue(reqPtr, 0.0, "i32")
                        wasmMod.setValue(reqPtr + 8, 0.0, "i32")
                        wasmMod.setValue(reqPtr + 12, 0.0, "i32")
                        wasmMod.uapmdInstanceCreateUiPresentation(handle, reqPtr, 0, 0)
                    }
                    is PluginUiHost.NativeEmbedded -> {
                        wasmMod.setValue(reqPtr, 1.0, "i32")
                        wasmMod.setValue(reqPtr + 8, host.parentHandle.toDouble(), "i32")
                        wasmMod.setValue(reqPtr + 12, 0.0, "i32")
                        wasmMod.uapmdInstanceCreateUiPresentation(handle, reqPtr, 0, 0)
                    }
                    is PluginUiHost.WebEmbedded ->
                        withCStringKt(host.containerId) { ptr ->
                            wasmMod.setValue(reqPtr, 2.0, "i32")
                            wasmMod.setValue(reqPtr + 8, 0.0, "i32")
                            wasmMod.setValue(reqPtr + 12, ptr.toDouble(), "i32")
                            wasmMod.uapmdInstanceCreateUiPresentation(handle, reqPtr, 0, 0)
                        }
                }
            } finally {
                wasmMod.free(reqPtr)
            }
        }
        if (presentationHandle == 0)
            return null

        return WasmJsPluginUiPresentation(request, presentationHandle).also { activeUiPresentations += it }
    }

    private fun getUiSizeInternal(presentationHandle: Int): UiSize? {
        val mod = wasmMod
        val wPtr = mod.malloc(4)
        val hPtr = mod.malloc(4)
        return try {
            if (!mod.uapmdUiPresentationGetSize(presentationHandle, wPtr, hPtr)) null
            else UiSize(
                mod.getValue(wPtr, "i32").toInt().toUInt(),
                mod.getValue(hPtr, "i32").toInt().toUInt()
            )
        } finally { mod.free(wPtr); mod.free(hPtr) }
    }

    private inner class WasmJsPluginUiPresentation(
        private val request: PluginUiPresentationRequest,
        private val presentationHandle: Int
    ) : PluginUiPresentation {

        override val host: PluginUiHost get() = request.host
        override val role: PluginUiPresentationRole get() = request.role
        override val mode: PluginUiPresentationMode get() = request.mode
        override val isVisible: Boolean get() = wasmMod.uapmdUiPresentationIsVisible(presentationHandle)
        override val canResize: Boolean get() = wasmMod.uapmdUiPresentationCanResize(presentationHandle)

        override fun show(): Boolean = wasmMod.uapmdUiPresentationShow(presentationHandle)

        override fun hide() {
            wasmMod.uapmdUiPresentationHide(presentationHandle)
        }

        override fun close() {
            wasmMod.uapmdUiPresentationDestroy(presentationHandle)
            activeUiPresentations.remove(this)
        }

        override fun setSize(width: UInt, height: UInt): Boolean =
            wasmMod.uapmdUiPresentationSetSize(presentationHandle, width.toInt(), height.toInt())

        override fun getSize(): UiSize? = getUiSizeInternal(presentationHandle)
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun parseParameterMetadata(ptr: Int): ParameterMetadata {
        val mod = wasmMod
        fun getI32(o: Int) = mod.getValue(ptr + o, "i32").toInt()
        fun getU32(o: Int) = mod.getValue(ptr + o, "i32").toInt()
        fun getF64(o: Int) = mod.getValue(ptr + o, "double")
        fun getPtr(o: Int) = mod.getValue(ptr + o, "i32").toInt()
        fun getBool(o: Int) = mod.getValue(ptr + o, "i8").toInt() != 0
        fun getStr(o: Int): String { val p = getPtr(o); return if (p == 0) "" else mod.utf8ToString(p) }

        val namedCount = getU32(44)
        val namedBase  = getPtr(48)
        val namedValues = List(namedCount) { i ->
            val b = namedBase + i * 16
            ParameterNamedValue(
                value = mod.getValue(b, "double"),
                name  = getStr(b + 8 - ptr) // relative offset hack — read directly
            )
        }.let {
            // Re-read properly
            List(namedCount) { i ->
                val b = namedBase + i * 16
                val strPtr = mod.getValue(b + 8, "i32").toInt()
                ParameterNamedValue(
                    value = mod.getValue(b, "double"),
                    name  = if (strPtr != 0) mod.utf8ToString(strPtr) else ""
                )
            }
        }

        return ParameterMetadata(
            index             = getU32(0).toUInt(),
            stableId          = getStr(4),
            name              = getStr(8),
            path              = getStr(12),
            defaultPlainValue = getF64(16),
            minPlainValue     = getF64(24),
            maxPlainValue     = getF64(32),
            automatable       = getBool(40),
            hidden            = getBool(41),
            discrete          = getBool(42),
            namedValues       = namedValues
        )
    }

    private fun parsePresetMetadata(ptr: Int): PresetMetadata {
        val mod = wasmMod
        fun getU8(o: Int)  = mod.getValue(ptr + o, "i8").toInt() and 0xFF
        fun getU32(o: Int) = mod.getValue(ptr + o, "i32").toInt()
        fun getPtr(o: Int) = mod.getValue(ptr + o, "i32").toInt()
        fun getStr(o: Int): String { val p = getPtr(o); return if (p == 0) "" else mod.utf8ToString(p) }

        return PresetMetadata(
            bank     = getU8(0).toUByte(),
            index    = getU32(4).toUInt(),
            stableId = getStr(8),
            name     = getStr(12),
            path     = getStr(16)
        )
    }
}

// ─── WasmJsPluginHost ────────────────────────────────────────────────────────

class WasmJsPluginHost internal constructor(
    internal val handle: Int
) : PluginHost {

    override val catalogEntryCount: UInt
        get() = wasmMod.uapmdPluginHostCatalogEntryCount(handle).toUInt()

    override fun getCatalogEntry(index: UInt): CatalogEntry? {
        val mod = wasmMod
        val fmtBufSize = 256; val idBufSize = 256; val nameBufSize = 256
        val fmtBuf  = mod.malloc(fmtBufSize)
        val idBuf   = mod.malloc(idBufSize)
        val nameBuf = mod.malloc(nameBufSize)
        return try {
            if (!mod.uapmdPluginHostGetCatalogEntry(handle, index.toInt(), fmtBuf, fmtBufSize, idBuf, idBufSize, nameBuf, nameBufSize)) null
            else CatalogEntry(
                format      = mod.utf8ToString(fmtBuf),
                pluginId    = mod.utf8ToString(idBuf),
                displayName = mod.utf8ToString(nameBuf)
            )
        } finally {
            mod.free(fmtBuf); mod.free(idBuf); mod.free(nameBuf)
        }
    }

    override fun saveCatalog(path: String) =
        withCStringKt(path) { pathPtr -> wasmMod.uapmdPluginHostSaveCatalog(handle, pathPtr) }

    override fun performScanning(rescan: Boolean) =
        wasmMod.uapmdPluginHostPerformScanning(handle, rescan)

    override fun reloadCatalogFromCache() =
        wasmMod.uapmdPluginHostReloadCatalogFromCache(handle)

    override fun createInstance(
        sampleRate: UInt,
        bufferSize: UInt,
        mainInputChannels: Int,
        mainOutputChannels: Int,
        offlineMode: Boolean,
        format: String,
        pluginId: String,
        callback: (instanceId: Int, error: String?) -> Unit
    ) {
        val cbId = nextCallbackId()
        pendingCreateInstanceCallbacks[cbId] = callback
        val fnPtr = makeCFunctionPtr(cbId, "uapmdDispatchCreateInstance", "viii")
        withTwoCStringsKt(format, pluginId) { fmtPtr, idPtr ->
            wasmMod.uapmdPluginHostCreateInstance(
                handle, sampleRate.toInt(), bufferSize.toInt(),
                mainInputChannels, mainOutputChannels, offlineMode,
                fmtPtr, idPtr, 0, fnPtr
            )
        }
    }

    override fun deleteInstance(instanceId: Int) =
        wasmMod.uapmdPluginHostDeleteInstance(handle, instanceId)

    override fun getInstance(instanceId: Int): PluginInstance? {
        val inst = wasmMod.uapmdPluginHostGetInstance(handle, instanceId)
        return if (inst == 0) null else WasmJsPluginInstance(inst)
    }

    override fun getInstanceIds(): List<Int> {
        val mod = wasmMod
        val count = mod.uapmdPluginHostInstanceIdCount(handle)
        if (count <= 0) return emptyList()
        val buf = mod.malloc(count * 4)
        return try {
            mod.uapmdPluginHostGetInstanceIds(handle, buf, count)
            List(count) { i -> mod.getValue(buf + i * 4, "i32").toInt() }
        } finally { mod.free(buf) }
    }

    override fun close() = wasmMod.uapmdPluginHostDestroy(handle)
}

// ─── WasmJsPluginNode ────────────────────────────────────────────────────────

class WasmJsPluginNode internal constructor(
    internal val handle: Int,
    override val instance: PluginInstance
) : PluginNode {

    override val instanceId: Int get() = wasmMod.uapmdNodeInstanceId(handle)

    override fun scheduleEvents(timestamp: Long, events: ByteArray): Boolean {
        val mod = wasmMod
        val ptr = mod.malloc(events.size)
        return try {
            events.forEachIndexed { i, b -> mod.setValue(ptr + i, b.toDouble(), "i8") }
            mod.uapmdNodeScheduleEvents(handle, timestamp.toInt(), ptr, events.size)
        } finally { mod.free(ptr) }
    }

    override fun sendAllNotesOff() = wasmMod.uapmdNodeSendAllNotesOff(handle)
}

// ─── WasmJsPluginGraph ───────────────────────────────────────────────────────

class WasmJsPluginGraph internal constructor(
    internal val handle: Int
) : PluginGraph {

    // Callback registrations kept alive
    private var eventOutputCbId: Int = 0
    private var eventOutputFnPtr: Int = 0
    private val deleteCallbackIds = mutableListOf<Int>()
    private val deleteCallbackPtrs = mutableListOf<Int>()

    override fun appendNode(instanceId: Int, instance: PluginInstance, onDelete: (() -> Unit)?): Int {
        val delPtr = if (onDelete != null) {
            val cbId = nextCallbackId()
            deleteCallbackIds += cbId
            // Store the callback as a scan observer slot (reuse registry for simplicity)
            // Actually use a dedicated registry entry
            val ptr = makeCFunctionPtr(cbId, "uapmdDispatchGraphNodeDelete_$cbId", "v")
            deleteCallbackPtrs += ptr
            // Note: we can't dispatch without a dedicated dispatcher. Use 0 for now.
            // TODO: add dedicated node-delete dispatcher
            0
        } else 0
        val instHandle = (instance as WasmJsPluginInstance).handle
        return wasmMod.uapmdGraphAppendNode(handle, instanceId, instHandle, delPtr, 0)
    }

    override fun removeNode(instanceId: Int): Boolean =
        wasmMod.uapmdGraphRemoveNode(handle, instanceId)

    override val pluginCount: UInt
        get() = wasmMod.uapmdGraphPluginCount(handle).toUInt()

    override fun getPluginNode(instanceId: Int): PluginNode? {
        val nodeHandle = wasmMod.uapmdGraphGetPluginNode(handle, instanceId)
        if (nodeHandle == 0) return null
        val instHandle = wasmMod.uapmdNodeInstance(nodeHandle)
        return WasmJsPluginNode(nodeHandle, WasmJsPluginInstance(instHandle))
    }

    override fun setEventOutputCallback(callback: ((instanceId: Int, data: UIntArray, dataSizeInBytes: Int) -> Unit)?) {
        // Remove previous
        if (eventOutputFnPtr != 0) {
            removeCFunctionPtr(eventOutputFnPtr)
            eventOutputCallbacks.remove(eventOutputCbId)
            eventOutputFnPtr = 0
        }
        if (callback != null) {
            eventOutputCbId = nextCallbackId()
            eventOutputCallbacks[eventOutputCbId] = { instanceId, data, size ->
                callback(instanceId, data.toUIntArray(), size)
            }
            eventOutputFnPtr = makeCFunctionPtr(eventOutputCbId, "uapmdDispatchEventOutput", "viiii")
            wasmMod.uapmdGraphSetEventOutputCallback(handle, eventOutputFnPtr, 0)
        } else {
            wasmMod.uapmdGraphSetEventOutputCallback(handle, 0, 0)
        }
    }

    override val outputBusCount: UInt
        get() = wasmMod.uapmdGraphOutputBusCount(handle).toUInt()

    override fun getOutputLatencyInSamples(busIndex: UInt): UInt =
        wasmMod.uapmdGraphOutputLatencyInSamples(handle, busIndex.toInt()).toUInt()

    override fun getOutputTailLengthInSeconds(busIndex: UInt): Double =
        wasmMod.uapmdGraphOutputTailLengthInSeconds(handle, busIndex.toInt())

    override val renderLeadInSamples: UInt
        get() = wasmMod.uapmdGraphRenderLeadInSamples(handle).toUInt()

    override val mainOutputLatencyInSamples: UInt
        get() = wasmMod.uapmdGraphMainOutputLatencyInSamples(handle).toUInt()

    override val mainOutputTailLengthInSeconds: Double
        get() = wasmMod.uapmdGraphMainOutputTailLengthInSeconds(handle)

    override fun close() {
        if (eventOutputFnPtr != 0) {
            removeCFunctionPtr(eventOutputFnPtr)
            eventOutputCallbacks.remove(eventOutputCbId)
        }
        deleteCallbackPtrs.forEach { removeCFunctionPtr(it) }
        wasmMod.uapmdGraphDestroy(handle)
    }
}

// ── Shared callback ID counter ────────────────────────────────────────────────

private var _nextCallbackId = 1
internal fun nextCallbackId(): Int = _nextCallbackId++
