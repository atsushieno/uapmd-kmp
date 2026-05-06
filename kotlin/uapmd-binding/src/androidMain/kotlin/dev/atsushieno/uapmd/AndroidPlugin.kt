package dev.atsushieno.uapmd

// ─── AndroidPluginInstance ───────────────────────────────────────────────────

class AndroidPluginInstance internal constructor(
    internal val handle: Long
) : PluginInstance {

    private val activeUiPresentations = linkedSetOf<AndroidPluginUiPresentation>()

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

    override val uiCapabilities: PluginUiCapabilities
        get() {
            val caps = JniBridge.uapmdInstanceGetUiCapabilities(handle)
            return PluginUiCapabilities(
                hasUiSupport = caps.getOrElse(0) { false },
                supportsEmbeddedPresentations = caps.getOrElse(1) { false },
                supportsFloatingPresentations = caps.getOrElse(2) { false },
                supportsMultiplePresentations = caps.getOrElse(3) { false }
            )
        }

    override fun createUiPresentation(request: PluginUiPresentationRequest): PluginUiPresentation? {
        if (!uiCapabilities.hasUiSupport)
            return null
        if (activeUiPresentations.isNotEmpty() && !uiCapabilities.supportsMultiplePresentations)
            return null

        val resizeHandlerRef = request.resizeHandler?.let { handler ->
            object : Any() {
                @Suppress("unused")
                fun invoke(w: Int, h: Int): Boolean = handler(w.toUInt(), h.toUInt())
            }
        }
        val hostKind: Int
        val parent: Long
        val webContainerId: String?
        when (val host = request.host) {
            PluginUiHost.FloatingWindow -> {
                hostKind = 0
                parent = 0L
                webContainerId = null
            }
            is PluginUiHost.NativeEmbedded -> {
                hostKind = 1
                parent = host.parentHandle
                webContainerId = null
            }
            is PluginUiHost.WebEmbedded -> {
                hostKind = 2
                parent = 0L
                webContainerId = host.containerId
            }
        }
        val role = when (request.role) {
            PluginUiPresentationRole.COMPACT -> 0
            PluginUiPresentationRole.FULL -> 1
            PluginUiPresentationRole.AUXILIARY -> 2
        }
        val presentationHandle = JniBridge.uapmdInstanceCreateUiPresentation(
            handle,
            hostKind,
            role,
            parent,
            webContainerId,
            resizeHandlerRef
        )
        if (presentationHandle == 0L)
            return null

        return AndroidPluginUiPresentation(request, presentationHandle, resizeHandlerRef).also { activeUiPresentations += it }
    }

    private fun getUiSizeInternal(presentationHandle: Long): UiSize? {
        val arr = JniBridge.uapmdUiPresentationGetUiSize(presentationHandle) ?: return null
        return UiSize(arr[0].toUInt(), arr[1].toUInt())
    }

    private inner class AndroidPluginUiPresentation(
        private val request: PluginUiPresentationRequest,
        private val presentationHandle: Long,
        private val resizeHandlerRef: Any?
    ) : PluginUiPresentation {

        override val host: PluginUiHost get() = request.host
        override val role: PluginUiPresentationRole get() = request.role
        override val mode: PluginUiPresentationMode get() = request.mode
        override val isVisible: Boolean get() = JniBridge.uapmdUiPresentationIsVisible(presentationHandle)
        override val canResize: Boolean get() = JniBridge.uapmdUiPresentationCanUiResize(presentationHandle)

        override fun show(): Boolean = JniBridge.uapmdUiPresentationShow(presentationHandle)

        override fun hide() {
            JniBridge.uapmdUiPresentationHide(presentationHandle)
        }

        override fun close() {
            JniBridge.uapmdUiPresentationDestroy(presentationHandle)
            activeUiPresentations.remove(this)
        }

        override fun setSize(width: UInt, height: UInt): Boolean =
            JniBridge.uapmdUiPresentationSetUiSize(presentationHandle, width.toInt(), height.toInt())

        override fun getSize(): UiSize? = getUiSizeInternal(presentationHandle)
    }
}

// ─── AndroidPluginHost ───────────────────────────────────────────────────────

class AndroidPluginHost internal constructor(
    private val handle: Long
) : PluginHost {

    override val catalogEntryCount: UInt get() =
        JniBridge.uapmdPluginHostCatalogEntryCount(handle).toUInt()

    override fun getCatalogEntry(index: UInt): CatalogEntry? {
        val arr = JniBridge.uapmdPluginHostGetCatalogEntry(handle, index.toInt()) ?: return null
        return CatalogEntry(arr[0] ?: "", arr[1] ?: "", arr[2] ?: "", arr[3] ?: "")
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
