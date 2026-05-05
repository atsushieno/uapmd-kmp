package dev.atsushieno.uapmd

// ─── AndroidPluginInstance ───────────────────────────────────────────────────

class AndroidPluginInstance internal constructor(
    internal val handle: Long
) : PluginInstance {

    // Keep strong reference to prevent GC of resize handler while UI is alive.
    private var resizeHandlerRef: Any? = null

    override val displayName: String
        get() = JniBridge.uapmdInstanceDisplayName(handle)

    override val formatName: String
        get() = JniBridge.uapmdInstanceFormatName(handle)

    override val pluginId: String
        get() = JniBridge.uapmdInstancePluginId(handle)

    override var bypassed: Boolean
        get() = JniBridge.uapmdInstanceGetBypassed(handle)
        set(value) { JniBridge.uapmdInstanceSetBypassed(handle, value) }

    override fun startProcessing(): Int = JniBridge.uapmdInstanceStartProcessing(handle)
    override fun stopProcessing(): Int = JniBridge.uapmdInstanceStopProcessing(handle)

    override val latencyInSamples: UInt get() = JniBridge.uapmdInstanceLatencyInSamples(handle).toUInt()
    override val tailLengthInSeconds: Double get() = JniBridge.uapmdInstanceTailLengthInSeconds(handle)
    override val requiresReplacingProcess: Boolean get() = JniBridge.uapmdInstanceRequiresReplacingProcess(handle)

    override val parameterCount: UInt get() = JniBridge.uapmdInstanceParameterCount(handle).toUInt()

    override fun getParameterMetadata(index: UInt): ParameterMetadata? {
        val outIndex = IntArray(1)
        val outStrings = arrayOfNulls<String>(3)
        val outDoubles = DoubleArray(3)
        val outBools = BooleanArray(3)
        val outNamedCount = IntArray(1)
        if (!JniBridge.uapmdInstanceGetParameterMetadata(
                handle, index.toInt(), outIndex, outStrings, outDoubles, outBools, outNamedCount
            )
        ) return null
        return ParameterMetadata(
            index = outIndex[0].toUInt(),
            stableId = outStrings[0] ?: "",
            name = outStrings[1] ?: "",
            path = outStrings[2] ?: "",
            defaultPlainValue = outDoubles[0],
            minPlainValue = outDoubles[1],
            maxPlainValue = outDoubles[2],
            automatable = outBools[0],
            hidden = outBools[1],
            discrete = outBools[2],
            namedValues = emptyList()  // JNI does not return named values in this call
        )
    }

    override fun getParameterValue(index: Int): Double =
        JniBridge.uapmdInstanceGetParameterValue(handle, index)

    override fun setParameterValue(index: Int, value: Double) =
        JniBridge.uapmdInstanceSetParameterValue(handle, index, value)

    override fun getParameterValueString(index: Int, value: Double): String =
        JniBridge.uapmdInstanceGetParameterValueString(handle, index, value)

    override fun setPerNoteControllerValue(note: UByte, index: UByte, value: Double) =
        JniBridge.uapmdInstanceSetPerNoteControllerValue(handle, note.toByte(), index.toByte(), value)

    override fun getPerNoteControllerValueString(note: UByte, index: UByte, value: Double): String =
        JniBridge.uapmdInstanceGetPerNoteControllerValueString(handle, note.toByte(), index.toByte(), value)

    override val presetCount: UInt get() = JniBridge.uapmdInstancePresetCount(handle).toUInt()

    override fun getPresetMetadata(index: UInt): PresetMetadata? {
        val outBank = ByteArray(1)
        val outIndex = IntArray(1)
        val outStrings = arrayOfNulls<String>(3)
        if (!JniBridge.uapmdInstanceGetPresetMetadata(handle, index.toInt(), outBank, outIndex, outStrings))
            return null
        return PresetMetadata(
            bank = outBank[0].toUByte(),
            index = outIndex[0].toUInt(),
            stableId = outStrings[0] ?: "",
            name = outStrings[1] ?: "",
            path = outStrings[2] ?: ""
        )
    }

    override fun loadPreset(presetIndex: Int) = JniBridge.uapmdInstanceLoadPreset(handle, presetIndex)

    override fun saveStateSync(): ByteArray = JniBridge.uapmdInstanceSaveStateSync(handle)

    override fun loadStateSync(data: ByteArray) = JniBridge.uapmdInstanceLoadStateSync(handle, data)

    override fun requestState(
        ctx: StateContextType,
        includeUiState: Boolean,
        callback: (ByteArray?, String?) -> Unit
    ) {
        val cb = object : Any() {
            @Suppress("unused")
            fun invoke(state: ByteArray?, error: String?) = callback(state, error)
        }
        JniBridge.uapmdInstanceRequestState(handle, ctx.nativeValue, includeUiState, cb)
    }

    override fun loadState(
        data: ByteArray,
        ctx: StateContextType,
        includeUiState: Boolean,
        callback: (String?) -> Unit
    ) {
        val cb = object : Any() {
            @Suppress("unused")
            fun invoke(error: String?) = callback(error)
        }
        JniBridge.uapmdInstanceLoadState(handle, data, ctx.nativeValue, includeUiState, cb)
    }

    override val hasUiSupport: Boolean get() = JniBridge.uapmdInstanceHasUiSupport(handle)

    override fun createUi(
        isFloating: Boolean,
        parentHandle: Long,
        resizeHandler: ((UInt, UInt) -> Boolean)?
    ): Boolean {
        resizeHandlerRef = resizeHandler?.let { handler ->
            object : Any() {
                @Suppress("unused")
                fun invoke(w: Int, h: Int): Boolean = handler(w.toUInt(), h.toUInt())
            }
        }
        return JniBridge.uapmdInstanceCreateUi(handle, isFloating, parentHandle, resizeHandlerRef)
    }

    override fun destroyUi() {
        JniBridge.uapmdInstanceDestroyUi(handle)
        resizeHandlerRef = null
    }

    override fun showUi(): Boolean = JniBridge.uapmdInstanceShowUi(handle)
    override fun hideUi() = JniBridge.uapmdInstanceHideUi(handle)
    override val isUiVisible: Boolean get() = JniBridge.uapmdInstanceIsUiVisible(handle)

    override fun setUiSize(width: UInt, height: UInt): Boolean =
        JniBridge.uapmdInstanceSetUiSize(handle, width.toInt(), height.toInt())

    override fun getUiSize(): UiSize? {
        val arr = JniBridge.uapmdInstanceGetUiSize(handle) ?: return null
        return UiSize(arr[0].toUInt(), arr[1].toUInt())
    }

    override val canUiResize: Boolean get() = JniBridge.uapmdInstanceCanUiResize(handle)
}

// ─── AndroidPluginHost ───────────────────────────────────────────────────────

class AndroidPluginHost internal constructor(
    private val handle: Long
) : PluginHost {

    override val catalogEntryCount: UInt get() =
        JniBridge.uapmdPluginHostCatalogEntryCount(handle).toUInt()

    override fun getCatalogEntry(index: UInt): CatalogEntry? {
        val arr = JniBridge.uapmdPluginHostGetCatalogEntry(handle, index.toInt()) ?: return null
        return CatalogEntry(arr[0] ?: "", arr[1] ?: "", arr[2] ?: "")
    }

    override fun saveCatalog(path: String) = JniBridge.uapmdPluginHostSaveCatalog(handle, path)
    override fun performScanning(rescan: Boolean) = JniBridge.uapmdPluginHostPerformScanning(handle, rescan)
    override fun reloadCatalogFromCache() = JniBridge.uapmdPluginHostReloadCatalogFromCache(handle)

    override fun createInstance(
        sampleRate: UInt, bufferSize: UInt,
        mainInputChannels: Int, mainOutputChannels: Int,
        offlineMode: Boolean, format: String, pluginId: String,
        callback: (Int, String?) -> Unit
    ) {
        val cb = object : Any() {
            @Suppress("unused")
            fun invoke(instanceId: Int, error: String?) = callback(instanceId, error)
        }
        JniBridge.uapmdPluginHostCreateInstance(
            handle, sampleRate.toInt(), bufferSize.toInt(),
            mainInputChannels, mainOutputChannels,
            offlineMode, format, pluginId, cb
        )
    }

    override fun deleteInstance(instanceId: Int) =
        JniBridge.uapmdPluginHostDeleteInstance(handle, instanceId)

    override fun getInstance(instanceId: Int): PluginInstance? {
        val h = JniBridge.uapmdPluginHostGetInstance(handle, instanceId)
        return if (h == 0L) null else AndroidPluginInstance(h)
    }

    override fun getInstanceIds(): List<Int> =
        JniBridge.uapmdPluginHostGetInstanceIds(handle).toList()

    override fun close() = JniBridge.uapmdPluginHostDestroy(handle)
}

// ─── AndroidPluginNode ───────────────────────────────────────────────────────

class AndroidPluginNode internal constructor(
    private val handle: Long,
    override val instance: PluginInstance
) : PluginNode {

    override val instanceId: Int get() = JniBridge.uapmdNodeInstanceId(handle)

    override fun scheduleEvents(timestamp: Long, events: ByteArray): Boolean =
        JniBridge.uapmdNodeScheduleEvents(handle, timestamp, events)

    override fun sendAllNotesOff() = JniBridge.uapmdNodeSendAllNotesOff(handle)
}

// ─── AndroidPluginGraph ──────────────────────────────────────────────────────

class AndroidPluginGraph internal constructor(
    private val handle: Long
) : PluginGraph {

    // Keep strong references so the JVM won't GC them before C++ calls them.
    private val deleteCallbacks = mutableListOf<Any>()
    private var eventOutputCbRef: Any? = null

    override fun appendNode(instanceId: Int, instance: PluginInstance, onDelete: (() -> Unit)?): Int {
        val cb: Any? = onDelete?.let { fn ->
            object : Any() {
                @Suppress("unused")
                fun invoke() = fn()
            }.also { deleteCallbacks += it }
        }
        return JniBridge.uapmdGraphAppendNode(
            handle, instanceId, (instance as AndroidPluginInstance).handle, cb
        )
    }

    override fun removeNode(instanceId: Int): Boolean =
        JniBridge.uapmdGraphRemoveNode(handle, instanceId)

    override val pluginCount: UInt get() = JniBridge.uapmdGraphPluginCount(handle).toUInt()

    override fun getPluginNode(instanceId: Int): PluginNode? {
        val nh = JniBridge.uapmdGraphGetPluginNode(handle, instanceId)
        if (nh == 0L) return null
        val instHandle = JniBridge.uapmdNodeInstance(nh)
        val inst = if (instHandle != 0L) AndroidPluginInstance(instHandle) else return null
        return AndroidPluginNode(nh, inst)
    }

    override fun setEventOutputCallback(callback: ((Int, UIntArray, Int) -> Unit)?) {
        eventOutputCbRef = callback?.let { fn ->
            object : Any() {
                @Suppress("unused")
                fun invoke(instId: Int, data: IntArray, sizeInBytes: Int) =
                    fn(instId, UIntArray(data.size) { data[it].toUInt() }, sizeInBytes)
            }
        }
        JniBridge.uapmdGraphSetEventOutputCallback(handle, eventOutputCbRef)
    }

    override val outputBusCount: UInt get() = JniBridge.uapmdGraphOutputBusCount(handle).toUInt()

    override fun getOutputLatencyInSamples(busIndex: UInt): UInt =
        JniBridge.uapmdGraphOutputLatencyInSamples(handle, busIndex.toInt()).toUInt()

    override fun getOutputTailLengthInSeconds(busIndex: UInt): Double =
        JniBridge.uapmdGraphOutputTailLengthInSeconds(handle, busIndex.toInt())

    override val renderLeadInSamples: UInt get() = JniBridge.uapmdGraphRenderLeadInSamples(handle).toUInt()
    override val mainOutputLatencyInSamples: UInt get() = JniBridge.uapmdGraphMainOutputLatencyInSamples(handle).toUInt()
    override val mainOutputTailLengthInSeconds: Double get() = JniBridge.uapmdGraphMainOutputTailLengthInSeconds(handle)

    override fun close() = JniBridge.uapmdGraphDestroy(handle)
}
