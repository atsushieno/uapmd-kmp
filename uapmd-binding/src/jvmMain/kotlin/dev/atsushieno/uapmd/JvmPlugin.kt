package dev.atsushieno.uapmd

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import dev.atsushieno.uapmd.jna.*

// ─── JvmPluginInstance ───────────────────────────────────────────────────────

class JvmPluginInstance internal constructor(
    internal val handle: Pointer
) : PluginInstance {

    // Keep a strong reference to the resize handler to prevent GC while UI is alive.
    private var resizeHandlerRef: UiResizeHandler? = null

    override val displayName: String
        get() = readJvmString { buf, size -> lib.uapmd_instance_display_name(handle, buf, size) }

    override val formatName: String
        get() = readJvmString { buf, size -> lib.uapmd_instance_format_name(handle, buf, size) }

    override val pluginId: String
        get() = readJvmString { buf, size -> lib.uapmd_instance_plugin_id(handle, buf, size) }

    override var bypassed: Boolean
        get() = lib.uapmd_instance_get_bypassed(handle)
        set(value) { lib.uapmd_instance_set_bypassed(handle, value) }

    override fun startProcessing(): Int = lib.uapmd_instance_start_processing(handle)
    override fun stopProcessing(): Int = lib.uapmd_instance_stop_processing(handle)

    override val latencyInSamples: UInt get() = lib.uapmd_instance_latency_in_samples(handle).toUInt()
    override val tailLengthInSeconds: Double get() = lib.uapmd_instance_tail_length_in_seconds(handle)
    override val requiresReplacingProcess: Boolean get() = lib.uapmd_instance_requires_replacing_process(handle)

    override val parameterCount: UInt get() = lib.uapmd_instance_parameter_count(handle).toUInt()

    override fun getParameterMetadata(index: UInt): ParameterMetadata? {
        val out = UapmdParameterMetadata()
        if (!lib.uapmd_instance_get_parameter_metadata(handle, index.toInt(), out)) return null
        val namedValues = (0 until out.named_values_count).map { i ->
            val base = out.named_values ?: return@map ParameterNamedValue(0.0, "")
            // Each element: double (8) + pointer (8) = 16 bytes
            val elemOffset = (i * 16).toLong()
            val value = base.getDouble(elemOffset)
            val namePtr = base.getPointer(elemOffset + 8)
            ParameterNamedValue(value, namePtr?.getString(0) ?: "")
        }
        return ParameterMetadata(
            index = out.index.toUInt(),
            stableId = out.stable_id ?: "",
            name = out.name ?: "",
            path = out.path ?: "",
            defaultPlainValue = out.default_plain_value,
            minPlainValue = out.min_plain_value,
            maxPlainValue = out.max_plain_value,
            automatable = out.automatable != 0.toByte(),
            hidden = out.hidden != 0.toByte(),
            discrete = out.discrete != 0.toByte(),
            namedValues = namedValues
        )
    }

    override fun getParameterValue(index: Int): Double =
        lib.uapmd_instance_get_parameter_value(handle, index)

    override fun setParameterValue(index: Int, value: Double) =
        lib.uapmd_instance_set_parameter_value(handle, index, value)

    override fun getParameterValueString(index: Int, value: Double): String =
        readJvmString { buf, size -> lib.uapmd_instance_get_parameter_value_string(handle, index, value, buf, size) }

    override fun setPerNoteControllerValue(note: UByte, index: UByte, value: Double) =
        lib.uapmd_instance_set_per_note_controller_value(handle, note.toByte(), index.toByte(), value)

    override fun getPerNoteControllerValueString(note: UByte, index: UByte, value: Double): String =
        readJvmString { buf, size ->
            lib.uapmd_instance_get_per_note_controller_value_string(handle, note.toByte(), index.toByte(), value, buf, size)
        }

    override val presetCount: UInt get() = lib.uapmd_instance_preset_count(handle).toUInt()

    override fun getPresetMetadata(index: UInt): PresetMetadata? {
        val out = UapmdPresetMetadata()
        if (!lib.uapmd_instance_get_preset_metadata(handle, index.toInt(), out)) return null
        return PresetMetadata(
            bank = out.bank.toUByte(),
            index = out.index.toUInt(),
            stableId = out.stable_id ?: "",
            name = out.name ?: "",
            path = out.path ?: ""
        )
    }

    override fun loadPreset(presetIndex: Int) =
        lib.uapmd_instance_load_preset(handle, presetIndex)

    override fun saveStateSync(): ByteArray {
        val size = lib.uapmd_instance_save_state_sync(handle, null, 0L)
        if (size == 0L) return ByteArray(0)
        val mem = Memory(size)
        lib.uapmd_instance_save_state_sync(handle, mem, size)
        return mem.getByteArray(0, size.toInt())
    }

    override fun loadStateSync(data: ByteArray) =
        lib.uapmd_instance_load_state_sync(handle, data, data.size.toLong())

    override fun requestState(
        ctx: StateContextType,
        includeUiState: Boolean,
        callback: (ByteArray?, String?) -> Unit
    ) {
        // Capture callback in the JNA Callback closure — no need for user_data indirection.
        val cb = object : RequestStateCb {
            override fun invoke(state: Pointer?, stateSize: Long, error: String?, userData: Pointer?) {
                val data = if (state != null && stateSize > 0L) state.getByteArray(0, stateSize.toInt()) else null
                callback(data, error)
            }
        }
        lib.uapmd_instance_request_state(handle, ctx.toJvmInt(), includeUiState, null, cb)
    }

    override fun loadState(
        data: ByteArray,
        ctx: StateContextType,
        includeUiState: Boolean,
        callback: (String?) -> Unit
    ) {
        val cb = object : LoadStateCb {
            override fun invoke(error: String?, userData: Pointer?) = callback(error)
        }
        lib.uapmd_instance_load_state(handle, data, data.size.toLong(), ctx.toJvmInt(), includeUiState, null, cb)
    }

    override val hasUiSupport: Boolean get() = lib.uapmd_instance_has_ui_support(handle)

    override fun createUi(
        isFloating: Boolean,
        parentHandle: Long,
        resizeHandler: ((UInt, UInt) -> Boolean)?
    ): Boolean {
        resizeHandlerRef = resizeHandler?.let { handler ->
            object : UiResizeHandler {
                override fun invoke(width: Int, height: Int, userData: Pointer?): Boolean =
                    handler(width.toUInt(), height.toUInt())
            }
        }
        return lib.uapmd_instance_create_ui(
            handle, isFloating,
            if (parentHandle != 0L) Pointer(parentHandle) else null,
            null,
            resizeHandlerRef
        )
    }

    override fun destroyUi() {
        lib.uapmd_instance_destroy_ui(handle)
        resizeHandlerRef = null
    }

    override fun showUi(): Boolean = lib.uapmd_instance_show_ui(handle)
    override fun hideUi() = lib.uapmd_instance_hide_ui(handle)
    override val isUiVisible: Boolean get() = lib.uapmd_instance_is_ui_visible(handle)

    override fun setUiSize(width: UInt, height: UInt): Boolean =
        lib.uapmd_instance_set_ui_size(handle, width.toInt(), height.toInt())

    override fun getUiSize(): UiSize? {
        val w = IntByReference()
        val h = IntByReference()
        if (!lib.uapmd_instance_get_ui_size(handle, w, h)) return null
        return UiSize(w.value.toUInt(), h.value.toUInt())
    }

    override val canUiResize: Boolean get() = lib.uapmd_instance_can_ui_resize(handle)
}

// ─── JvmPluginHost ───────────────────────────────────────────────────────────

class JvmPluginHost internal constructor(
    private val handle: Pointer
) : PluginHost {

    override val catalogEntryCount: UInt get() = lib.uapmd_plugin_host_catalog_entry_count(handle).toUInt()

    override fun getCatalogEntry(index: UInt): CatalogEntry? {
        val fmtBuf = ByteArray(256)
        val idBuf = ByteArray(512)
        val nameBuf = ByteArray(512)
        if (!lib.uapmd_plugin_host_get_catalog_entry(
                handle, index.toInt(),
                fmtBuf, 256L,
                idBuf, 512L,
                nameBuf, 512L
            )
        ) return null
        return CatalogEntry(
            fmtBuf.decodeToNullTerminated(),
            idBuf.decodeToNullTerminated(),
            nameBuf.decodeToNullTerminated()
        )
    }

    override fun saveCatalog(path: String) = lib.uapmd_plugin_host_save_catalog(handle, path)
    override fun performScanning(rescan: Boolean) = lib.uapmd_plugin_host_perform_scanning(handle, rescan)
    override fun reloadCatalogFromCache() = lib.uapmd_plugin_host_reload_catalog_from_cache(handle)

    override fun createInstance(
        sampleRate: UInt, bufferSize: UInt,
        mainInputChannels: Int, mainOutputChannels: Int,
        offlineMode: Boolean, format: String, pluginId: String,
        callback: (Int, String?) -> Unit
    ) {
        val cb = object : CreateInstanceCallback {
            override fun invoke(instanceId: Int, error: String?, userData: Pointer?) =
                callback(instanceId, error)
        }
        lib.uapmd_plugin_host_create_instance(
            handle,
            sampleRate.toInt(), bufferSize.toInt(),
            mainInputChannels, mainOutputChannels,
            offlineMode, format, pluginId,
            null, cb
        )
    }

    override fun deleteInstance(instanceId: Int) =
        lib.uapmd_plugin_host_delete_instance(handle, instanceId)

    override fun getInstance(instanceId: Int): PluginInstance? =
        lib.uapmd_plugin_host_get_instance(handle, instanceId)?.let { JvmPluginInstance(it) }

    override fun getInstanceIds(): List<Int> {
        val count = lib.uapmd_plugin_host_instance_id_count(handle)
        if (count == 0) return emptyList()
        val arr = IntArray(count)
        lib.uapmd_plugin_host_get_instance_ids(handle, arr, count)
        return arr.toList()
    }

    override fun close() = lib.uapmd_plugin_host_destroy(handle)
}

// ─── JvmPluginNode ───────────────────────────────────────────────────────────

class JvmPluginNode internal constructor(
    private val handle: Pointer,
    override val instance: PluginInstance
) : PluginNode {

    override val instanceId: Int get() = lib.uapmd_node_instance_id(handle)

    override fun scheduleEvents(timestamp: Long, events: ByteArray): Boolean =
        lib.uapmd_node_schedule_events(handle, timestamp, events, events.size.toLong())

    override fun sendAllNotesOff() = lib.uapmd_node_send_all_notes_off(handle)
}

// ─── JvmPluginGraph ──────────────────────────────────────────────────────────

class JvmPluginGraph internal constructor(
    private val handle: Pointer
) : PluginGraph {

    // Keep strong references to prevent JNA Callback objects from being GC'd.
    private val deleteCallbacks = mutableListOf<GraphDeleteCb>()
    private var eventOutputCb: EventOutputCb? = null

    override fun appendNode(instanceId: Int, instance: PluginInstance, onDelete: (() -> Unit)?): Int {
        val cb: GraphDeleteCb? = onDelete?.let { fn ->
            object : GraphDeleteCb {
                override fun invoke(userData: Pointer?) = fn()
            }.also { deleteCallbacks += it }
        }
        return lib.uapmd_graph_append_node(
            handle, instanceId, (instance as JvmPluginInstance).handle, null, cb
        )
    }

    override fun removeNode(instanceId: Int): Boolean = lib.uapmd_graph_remove_node(handle, instanceId)
    override val pluginCount: UInt get() = lib.uapmd_graph_plugin_count(handle).toUInt()

    override fun getPluginNode(instanceId: Int): PluginNode? {
        val nodeHandle = lib.uapmd_graph_get_plugin_node(handle, instanceId) ?: return null
        val instHandle = lib.uapmd_node_instance(nodeHandle) ?: return null
        return JvmPluginNode(nodeHandle, JvmPluginInstance(instHandle))
    }

    override fun setEventOutputCallback(callback: ((Int, UIntArray, Int) -> Unit)?) {
        if (callback == null) {
            lib.uapmd_graph_set_event_output_callback(handle, null, null)
            eventOutputCb = null
            return
        }
        val cb = object : EventOutputCb {
            override fun invoke(instanceId: Int, data: Pointer?, dataSizeInBytes: Long, userData: Pointer?) {
                if (data == null) return
                val count = (dataSizeInBytes / 4).toInt()
                val arr = UIntArray(count) { i -> data.getInt((i * 4).toLong()).toUInt() }
                callback(instanceId, arr, dataSizeInBytes.toInt())
            }
        }
        eventOutputCb = cb
        lib.uapmd_graph_set_event_output_callback(handle, null, cb)
    }

    override val outputBusCount: UInt get() = lib.uapmd_graph_output_bus_count(handle).toUInt()
    override fun getOutputLatencyInSamples(busIndex: UInt): UInt =
        lib.uapmd_graph_output_latency_in_samples(handle, busIndex.toInt()).toUInt()
    override fun getOutputTailLengthInSeconds(busIndex: UInt): Double =
        lib.uapmd_graph_output_tail_length_in_seconds(handle, busIndex.toInt())
    override val renderLeadInSamples: UInt get() = lib.uapmd_graph_render_lead_in_samples(handle).toUInt()
    override val mainOutputLatencyInSamples: UInt get() = lib.uapmd_graph_main_output_latency_in_samples(handle).toUInt()
    override val mainOutputTailLengthInSeconds: Double get() = lib.uapmd_graph_main_output_tail_length_in_seconds(handle)

    override fun close() {
        eventOutputCb = null
        lib.uapmd_graph_destroy(handle)
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

private fun ByteArray.decodeToNullTerminated(): String {
    val end = indexOf(0.toByte()).let { if (it < 0) size else it }
    return String(this, 0, end, Charsets.UTF_8)
}
