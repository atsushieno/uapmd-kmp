package dev.atsushieno.uapmd

// ─── JsPluginInstance ─────────────────────────────────────────────────────────

class JsPluginInstance internal constructor(
    internal val handle: Int
) : PluginInstance {

    private val activeUiPresentations = linkedSetOf<JsPluginUiPresentation>()

    override val displayName: String
        get() = readJsString(handle) { h, buf, sz -> jsMod._uapmd_instance_display_name(h, buf, sz) as Int }

    override val formatName: String
        get() = readJsString(handle) { h, buf, sz -> jsMod._uapmd_instance_format_name(h, buf, sz) as Int }

    override val pluginId: String
        get() = readJsString(handle) { h, buf, sz -> jsMod._uapmd_instance_plugin_id(h, buf, sz) as Int }

    override var bypassed: Boolean
        get() = jsMod._uapmd_instance_get_bypassed(handle) as Boolean
        set(v) { jsMod._uapmd_instance_set_bypassed(handle, v) }

    override fun startProcessing(): Int = jsMod._uapmd_instance_start_processing(handle) as Int
    override fun stopProcessing(): Int  = jsMod._uapmd_instance_stop_processing(handle) as Int

    override val latencyInSamples: UInt
        get() = (jsMod._uapmd_instance_latency_in_samples(handle) as Int).toUInt()

    override val tailLengthInSeconds: Double
        get() = jsMod._uapmd_instance_tail_length_in_seconds(handle) as Double

    override val requiresReplacingProcess: Boolean
        get() = jsMod._uapmd_instance_requires_replacing_process(handle) as Boolean

    override val parameterCount: UInt
        get() = (jsMod._uapmd_instance_parameter_count(handle) as Int).toUInt()

    override fun getParameterMetadata(index: UInt): ParameterMetadata? =
        withWasmMem(64) { ptr ->
            if (!(jsMod._uapmd_instance_get_parameter_metadata(handle, index.toInt(), ptr) as Boolean)) null
            else jsDecodeParameterMetadata(ptr)
        }

    override fun getParameterValue(index: Int): Double =
        jsMod._uapmd_instance_get_parameter_value(handle, index) as Double

    override fun setParameterValue(index: Int, value: Double) =
        jsMod._uapmd_instance_set_parameter_value(handle, index, value)

    override fun getParameterValueString(index: Int, value: Double): String {
        val sz = jsMod._uapmd_instance_get_parameter_value_string(handle, index, value, 0, 0) as Int
        if (sz <= 0) return ""
        return withWasmMem(sz) { ptr ->
            jsMod._uapmd_instance_get_parameter_value_string(handle, index, value, ptr, sz)
            jsMod.UTF8ToString(ptr, sz - 1) as String
        }
    }

    override fun setPerNoteControllerValue(note: UByte, index: UByte, value: Double) =
        jsMod._uapmd_instance_set_per_note_controller_value(handle, note.toInt(), index.toInt(), value)

    override fun getPerNoteControllerValueString(note: UByte, index: UByte, value: Double): String {
        val sz = jsMod._uapmd_instance_get_per_note_controller_value_string(handle, note.toInt(), index.toInt(), value, 0, 0) as Int
        if (sz <= 0) return ""
        return withWasmMem(sz) { ptr ->
            jsMod._uapmd_instance_get_per_note_controller_value_string(handle, note.toInt(), index.toInt(), value, ptr, sz)
            jsMod.UTF8ToString(ptr, sz - 1) as String
        }
    }

    override val presetCount: UInt
        get() = (jsMod._uapmd_instance_preset_count(handle) as Int).toUInt()

    override fun getPresetMetadata(index: UInt): PresetMetadata? =
        withWasmMem(24) { ptr ->
            if (!(jsMod._uapmd_instance_get_preset_metadata(handle, index.toInt(), ptr) as Boolean)) null
            else jsDecodePresetMetadata(ptr)
        }

    override fun loadPreset(presetIndex: Int) =
        jsMod._uapmd_instance_load_preset(handle, presetIndex)

    override fun saveStateSync(): ByteArray {
        val initialBuf = 65536
        val actualPtr = jsMod._malloc(4) as Int
        val buf = jsMod._malloc(initialBuf) as Int
        return try {
            jsMod._uapmd_instance_save_state_sync(handle, buf, initialBuf, actualPtr)
            val actual = jsGetI32(actualPtr)
            ByteArray(actual) { i -> (jsMod.getValue(buf + i, "i8") as Int).toByte() }
        } finally { jsMod._free(buf); jsMod._free(actualPtr) }
    }

    override fun loadStateSync(data: ByteArray) {
        val ptr = jsMod._malloc(data.size) as Int
        try {
            data.forEachIndexed { i, b -> jsSetI8(ptr + i, b.toInt()) }
            jsMod._uapmd_instance_load_state_sync(handle, ptr, data.size)
        } finally { jsMod._free(ptr) }
    }

    override fun requestState(ctx: StateContextType, includeUiState: Boolean, callback: (ByteArray?, String?) -> Unit) {
        val fnPtr = makeJsStateCallback(callback)
        jsMod._uapmd_instance_request_state(handle, ctx.nativeValue, includeUiState, fnPtr, 0)
    }

    override fun loadState(data: ByteArray, ctx: StateContextType, includeUiState: Boolean, callback: (String?) -> Unit) {
        val ptr = jsMod._malloc(data.size) as Int
        try {
            data.forEachIndexed { i, b -> jsSetI8(ptr + i, b.toInt()) }
            val fnPtr = makeJsErrorCallback(callback)
            jsMod._uapmd_instance_load_state(handle, ptr, data.size, ctx.nativeValue, includeUiState, fnPtr, 0)
        } finally { jsMod._free(ptr) }
    }

    override val uiCapabilities: PluginUiCapabilities
        get() = withWasmMem(4) { ptr ->
            jsMod._uapmd_instance_get_ui_capabilities(handle, ptr)
            jsDecodeUiCapabilities(ptr)
        }

    override fun createUiPresentation(request: PluginUiPresentationRequest): PluginUiPresentation? {
        if (!uiCapabilities.hasUiSupport)
            return null
        if (activeUiPresentations.isNotEmpty() && !uiCapabilities.supportsMultiplePresentations)
            return null

        val presentationHandle = withWasmMem(16) { reqPtr ->
            jsSetI32(reqPtr + 4, when (request.role) {
                PluginUiPresentationRole.COMPACT -> 0
                PluginUiPresentationRole.FULL -> 1
                PluginUiPresentationRole.AUXILIARY -> 2
            })
            when (val host = request.host) {
                PluginUiHost.FloatingWindow -> {
                    jsSetI32(reqPtr, 0)
                    jsSetI32(reqPtr + 8, 0)
                    jsSetI32(reqPtr + 12, 0)
                    jsMod._uapmd_instance_create_ui_presentation(handle, reqPtr, 0, 0) as Int
                }
                is PluginUiHost.NativeEmbedded -> {
                    jsSetI32(reqPtr, 1)
                    jsSetI32(reqPtr + 8, host.parentHandle.toInt())
                    jsSetI32(reqPtr + 12, 0)
                    jsMod._uapmd_instance_create_ui_presentation(handle, reqPtr, 0, 0) as Int
                }
                is PluginUiHost.WebEmbedded ->
                    withJsCString(host.containerId) { strPtr ->
                        jsSetI32(reqPtr, 2)
                        jsSetI32(reqPtr + 8, 0)
                        jsSetI32(reqPtr + 12, strPtr)
                        jsMod._uapmd_instance_create_ui_presentation(handle, reqPtr, 0, 0) as Int
                    }
            }
        }
        if (presentationHandle == 0)
            return null

        return JsPluginUiPresentation(request, presentationHandle).also { activeUiPresentations += it }
    }

    private fun getUiSizeInternal(presentationHandle: Int): UiSize? {
        val wPtr = jsMod._malloc(4) as Int
        val hPtr = jsMod._malloc(4) as Int
        return try {
            if (!(jsMod._uapmd_ui_presentation_get_size(presentationHandle, wPtr, hPtr) as Boolean)) null
            else UiSize(jsGetI32(wPtr).toUInt(), jsGetI32(hPtr).toUInt())
        } finally { jsMod._free(wPtr); jsMod._free(hPtr) }
    }

    private inner class JsPluginUiPresentation(
        private val request: PluginUiPresentationRequest,
        private val presentationHandle: Int
    ) : PluginUiPresentation {

        override val host: PluginUiHost get() = request.host
        override val role: PluginUiPresentationRole get() = request.role
        override val mode: PluginUiPresentationMode get() = request.mode
        override val isVisible: Boolean get() = jsMod._uapmd_ui_presentation_is_visible(presentationHandle) as Boolean
        override val canResize: Boolean get() = jsMod._uapmd_ui_presentation_can_resize(presentationHandle) as Boolean

        override fun show(): Boolean = jsMod._uapmd_ui_presentation_show(presentationHandle) as Boolean

        override fun hide() {
            jsMod._uapmd_ui_presentation_hide(presentationHandle)
        }

        override fun close() {
            jsMod._uapmd_ui_presentation_destroy(presentationHandle)
            activeUiPresentations.remove(this)
        }

        override fun setSize(width: UInt, height: UInt): Boolean =
            jsMod._uapmd_ui_presentation_set_size(presentationHandle, width.toInt(), height.toInt()) as Boolean

        override fun getSize(): UiSize? = getUiSizeInternal(presentationHandle)
    }
}

// ─── JsPluginHost ─────────────────────────────────────────────────────────────

class JsPluginHost internal constructor(
    internal val handle: Int
) : PluginHost {

    override val catalogEntryCount: UInt
        get() = (jsMod._uapmd_plugin_host_catalog_entry_count(handle) as Int).toUInt()

    override fun getCatalogEntry(index: UInt): CatalogEntry? {
        val fmtSz = 256; val idSz = 256; val nameSz = 256
        val fBuf = jsMod._malloc(fmtSz) as Int
        val iBuf = jsMod._malloc(idSz)  as Int
        val nBuf = jsMod._malloc(nameSz) as Int
        return try {
            if (!(jsMod._uapmd_plugin_host_get_catalog_entry(handle, index.toInt(), fBuf, fmtSz, iBuf, idSz, nBuf, nameSz) as Boolean)) null
            else CatalogEntry(
                format      = jsMod.UTF8ToString(fBuf) as String,
                pluginId    = jsMod.UTF8ToString(iBuf) as String,
                displayName = jsMod.UTF8ToString(nBuf) as String
            )
        } finally { jsMod._free(fBuf); jsMod._free(iBuf); jsMod._free(nBuf) }
    }

    override fun saveCatalog(path: String) =
        withJsCString(path) { ptr -> jsMod._uapmd_plugin_host_save_catalog(handle, ptr) }

    override fun performScanning(rescan: Boolean) =
        jsMod._uapmd_plugin_host_perform_scanning(handle, rescan)

    override fun reloadCatalogFromCache() =
        jsMod._uapmd_plugin_host_reload_catalog_from_cache(handle)

    override fun createInstance(
        sampleRate: UInt, bufferSize: UInt,
        mainInputChannels: Int, mainOutputChannels: Int,
        offlineMode: Boolean, format: String, pluginId: String,
        callback: (instanceId: Int, error: String?) -> Unit
    ) {
        val fnPtr = makeJsCreateInstanceCallback(callback)
        withJsTwoCStrings(format, pluginId) { fPtr, idPtr ->
            jsMod._uapmd_plugin_host_create_instance(
                handle, sampleRate.toInt(), bufferSize.toInt(),
                mainInputChannels, mainOutputChannels, offlineMode,
                fPtr, idPtr, fnPtr, 0
            )
        }
    }

    override fun deleteInstance(instanceId: Int) =
        jsMod._uapmd_plugin_host_delete_instance(handle, instanceId)

    override fun getInstance(instanceId: Int): PluginInstance? {
        val inst = jsMod._uapmd_plugin_host_get_instance(handle, instanceId) as Int
        return if (inst == 0) null else JsPluginInstance(inst)
    }

    override fun getInstanceIds(): List<Int> {
        val count = jsMod._uapmd_plugin_host_instance_id_count(handle) as Int
        if (count <= 0) return emptyList()
        return withWasmMem(count * 4) { buf ->
            jsMod._uapmd_plugin_host_get_instance_ids(handle, buf, count)
            List(count) { i -> jsGetI32(buf + i * 4) }
        }
    }

    override fun close() = jsMod._uapmd_plugin_host_destroy(handle)
}

// ─── JsPluginNode ─────────────────────────────────────────────────────────────

class JsPluginNode internal constructor(
    internal val handle: Int,
    override val instance: PluginInstance
) : PluginNode {
    override val instanceId: Int get() = jsMod._uapmd_node_instance_id(handle) as Int

    override fun scheduleEvents(timestamp: Long, events: ByteArray): Boolean {
        val ptr = jsMod._malloc(events.size) as Int
        return try {
            events.forEachIndexed { i, b -> jsSetI8(ptr + i, b.toInt()) }
            jsMod._uapmd_node_schedule_events(handle, timestamp.toInt(), ptr, events.size) as Boolean
        } finally { jsMod._free(ptr) }
    }

    override fun sendAllNotesOff() = jsMod._uapmd_node_send_all_notes_off(handle)
}

// ─── JsPluginGraph ────────────────────────────────────────────────────────────

class JsPluginGraph internal constructor(
    internal val handle: Int
) : PluginGraph {

    private var eventOutputFnPtr: Int = 0
    private val deleteCallbackPtrs = mutableListOf<Int>()

    override fun appendNode(instanceId: Int, instance: PluginInstance, onDelete: (() -> Unit)?): Int {
        val delPtr = if (onDelete != null) {
            val fn: () -> Unit = { onDelete() }
            addJsCallback(fn.asDynamic(), "v")
                .also { deleteCallbackPtrs += it }
        } else 0
        val instHandle = (instance as JsPluginInstance).handle
        return jsMod._uapmd_graph_append_node(handle, instanceId, instHandle, delPtr, 0) as Int
    }

    override fun removeNode(instanceId: Int): Boolean =
        jsMod._uapmd_graph_remove_node(handle, instanceId) as Boolean

    override val pluginCount: UInt
        get() = (jsMod._uapmd_graph_plugin_count(handle) as Int).toUInt()

    override fun getPluginNode(instanceId: Int): PluginNode? {
        val nodeHandle = jsMod._uapmd_graph_get_plugin_node(handle, instanceId) as Int
        if (nodeHandle == 0) return null
        val instHandle = jsMod._uapmd_node_instance(nodeHandle) as Int
        return JsPluginNode(nodeHandle, JsPluginInstance(instHandle))
    }

    override fun setEventOutputCallback(callback: ((instanceId: Int, data: UIntArray, dataSizeInBytes: Int) -> Unit)?) {
        if (eventOutputFnPtr != 0) { removeJsCallback(eventOutputFnPtr); eventOutputFnPtr = 0 }
        if (callback != null) {
            eventOutputFnPtr = makeJsEventOutputCallback { id, data, sz -> callback(id, data.toUIntArray(), sz) }
            jsMod._uapmd_graph_set_event_output_callback(handle, eventOutputFnPtr, 0)
        } else {
            jsMod._uapmd_graph_set_event_output_callback(handle, 0, 0)
        }
    }

    override val outputBusCount: UInt
        get() = (jsMod._uapmd_graph_output_bus_count(handle) as Int).toUInt()

    override fun getOutputLatencyInSamples(busIndex: UInt): UInt =
        (jsMod._uapmd_graph_output_latency_in_samples(handle, busIndex.toInt()) as Int).toUInt()

    override fun getOutputTailLengthInSeconds(busIndex: UInt): Double =
        jsMod._uapmd_graph_output_tail_length_in_seconds(handle, busIndex.toInt()) as Double

    override val renderLeadInSamples: UInt
        get() = (jsMod._uapmd_graph_render_lead_in_samples(handle) as Int).toUInt()

    override val mainOutputLatencyInSamples: UInt
        get() = (jsMod._uapmd_graph_main_output_latency_in_samples(handle) as Int).toUInt()

    override val mainOutputTailLengthInSeconds: Double
        get() = jsMod._uapmd_graph_main_output_tail_length_in_seconds(handle) as Double

    override fun close() {
        if (eventOutputFnPtr != 0) removeJsCallback(eventOutputFnPtr)
        deleteCallbackPtrs.forEach { removeJsCallback(it) }
        jsMod._uapmd_graph_destroy(handle)
    }
}
