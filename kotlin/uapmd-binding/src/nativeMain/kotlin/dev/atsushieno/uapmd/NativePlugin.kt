package dev.atsushieno.uapmd

import kotlinx.cinterop.*
import uapmd.*

class NativePluginInstance internal constructor(
    internal val handle: uapmd_plugin_instance_t
) : PluginInstance {

    // Held for the lifetime of the UI to prevent GC of the resize handler StableRef.
    private var resizeHandlerRef: StableRef<(UInt, UInt) -> Boolean>? = null

    override val displayName: String
        get() = readCString { buf, size -> uapmd_instance_display_name(handle, buf, size) }

    override val formatName: String
        get() = readCString { buf, size -> uapmd_instance_format_name(handle, buf, size) }

    override val pluginId: String
        get() = readCString { buf, size -> uapmd_instance_plugin_id(handle, buf, size) }

    override var bypassed: Boolean
        get() = uapmd_instance_get_bypassed(handle)
        set(value) { uapmd_instance_set_bypassed(handle, value) }

    override fun startProcessing(): Int = uapmd_instance_start_processing(handle)
    override fun stopProcessing(): Int = uapmd_instance_stop_processing(handle)

    override val latencyInSamples: UInt get() = uapmd_instance_latency_in_samples(handle)
    override val tailLengthInSeconds: Double get() = uapmd_instance_tail_length_in_seconds(handle)
    override val requiresReplacingProcess: Boolean get() = uapmd_instance_requires_replacing_process(handle)

    override val parameterCount: UInt get() = uapmd_instance_parameter_count(handle)

    override fun getParameterMetadata(index: UInt): ParameterMetadata? = memScoped {
        val out = alloc<uapmd_parameter_metadata_t>()
        if (!uapmd_instance_get_parameter_metadata(handle, index, out.ptr)) return null
        ParameterMetadata(
            index = out.index,
            stableId = out.stable_id?.toKString() ?: "",
            name = out.name?.toKString() ?: "",
            path = out.path?.toKString() ?: "",
            defaultPlainValue = out.default_plain_value,
            minPlainValue = out.min_plain_value,
            maxPlainValue = out.max_plain_value,
            automatable = out.automatable,
            hidden = out.hidden,
            discrete = out.discrete,
            namedValues = (0 until out.named_values_count.toInt()).map { i ->
                val nv = out.named_values!![i]
                ParameterNamedValue(nv.value, nv.name?.toKString() ?: "")
            }
        )
    }

    override fun getParameterValue(index: Int): Double =
        uapmd_instance_get_parameter_value(handle, index)

    override fun setParameterValue(index: Int, value: Double) =
        uapmd_instance_set_parameter_value(handle, index, value)

    override fun getParameterValueString(index: Int, value: Double): String =
        readCString { buf, size -> uapmd_instance_get_parameter_value_string(handle, index, value, buf, size) }

    override fun setPerNoteControllerValue(note: UByte, index: UByte, value: Double) =
        uapmd_instance_set_per_note_controller_value(handle, note, index, value)

    override fun getPerNoteControllerValueString(note: UByte, index: UByte, value: Double): String =
        readCString { buf, size ->
            uapmd_instance_get_per_note_controller_value_string(handle, note, index, value, buf, size)
        }

    override val presetCount: UInt get() = uapmd_instance_preset_count(handle)

    override fun getPresetMetadata(index: UInt): PresetMetadata? = memScoped {
        val out = alloc<uapmd_preset_metadata_t>()
        if (!uapmd_instance_get_preset_metadata(handle, index, out.ptr)) return null
        PresetMetadata(
            bank = out.bank,
            index = out.index,
            stableId = out.stable_id?.toKString() ?: "",
            name = out.name?.toKString() ?: "",
            path = out.path?.toKString() ?: ""
        )
    }

    override fun loadPreset(presetIndex: Int) =
        uapmd_instance_load_preset(handle, presetIndex)

    override fun saveStateSync(): ByteArray {
        val needed = uapmd_instance_save_state_sync(handle, null, 0u)
        if (needed == 0uL) return ByteArray(0)
        val buf = ByteArray(needed.toInt())
        buf.usePinned { pinned ->
            uapmd_instance_save_state_sync(handle, pinned.addressOf(0).reinterpret(), needed)
        }
        return buf
    }

    override fun loadStateSync(data: ByteArray) {
        data.usePinned { pinned ->
            uapmd_instance_load_state_sync(handle, pinned.addressOf(0).reinterpret(), data.size.toULong())
        }
    }

    override fun requestState(
        ctx: StateContextType,
        includeUiState: Boolean,
        callback: (ByteArray?, String?) -> Unit
    ) {
        val ref = StableRef.create(callback)
        uapmd_instance_request_state(
            handle, ctx.toNative().toUInt(), includeUiState, ref.asCPointer(),
            staticCFunction { state, stateSize, error, userData ->
                if (userData == null) return@staticCFunction
                val cb = userData.asStableRef<(ByteArray?, String?) -> Unit>()
                val data = if (state != null && stateSize > 0u)
                    state.reinterpret<ByteVar>().readBytes(stateSize.toInt())
                else null
                cb.get()(data, error?.toKString())
                cb.dispose()
            }
        )
    }

    override fun loadState(
        data: ByteArray,
        ctx: StateContextType,
        includeUiState: Boolean,
        callback: (String?) -> Unit
    ) {
        val ref = StableRef.create(callback)
        // Pin data for the duration of the call; assumes the C implementation calls the
        // callback before returning (synchronous path) or copies the buffer internally.
        data.usePinned { pinned ->
            uapmd_instance_load_state(
                handle, pinned.addressOf(0).reinterpret(), data.size.toULong(),
                ctx.toNative().toUInt(), includeUiState, ref.asCPointer(),
                staticCFunction { error, userData ->
                    if (userData == null) return@staticCFunction
                    val cb = userData.asStableRef<(String?) -> Unit>()
                    cb.get()(error?.toKString())
                    cb.dispose()
                }
            )
        }
    }

    override val hasUiSupport: Boolean get() = uapmd_instance_has_ui_support(handle)

    override fun createUi(
        isFloating: Boolean,
        parentHandle: Long,
        resizeHandler: ((UInt, UInt) -> Boolean)?
    ): Boolean {
        resizeHandlerRef?.dispose()
        resizeHandlerRef = resizeHandler?.let { StableRef.create(it) }
        return uapmd_instance_create_ui(
            handle, isFloating,
            parentHandle.toCPointer<COpaque>(),
            resizeHandlerRef?.asCPointer(),
            resizeHandler?.let {
                staticCFunction { w, h, userData ->
                    userData?.asStableRef<(UInt, UInt) -> Boolean>()?.get()?.invoke(w, h) ?: false
                }
            }
        )
    }

    override fun destroyUi() {
        uapmd_instance_destroy_ui(handle)
        resizeHandlerRef?.dispose()
        resizeHandlerRef = null
    }
    override fun showUi(): Boolean = uapmd_instance_show_ui(handle)
    override fun hideUi() = uapmd_instance_hide_ui(handle)
    override val isUiVisible: Boolean get() = uapmd_instance_is_ui_visible(handle)

    override fun setUiSize(width: UInt, height: UInt): Boolean =
        uapmd_instance_set_ui_size(handle, width, height)

    override fun getUiSize(): UiSize? = memScoped {
        val w = alloc<UIntVar>()
        val h = alloc<UIntVar>()
        if (!uapmd_instance_get_ui_size(handle, w.ptr, h.ptr)) return null
        UiSize(w.value, h.value)
    }

    override val canUiResize: Boolean get() = uapmd_instance_can_ui_resize(handle)
}

// ---------------------------------------------------------------------------

class NativePluginHost internal constructor(
    private val handle: uapmd_plugin_host_t
) : PluginHost {

    override val catalogEntryCount: UInt get() = uapmd_plugin_host_catalog_entry_count(handle)

    override fun getCatalogEntry(index: UInt): CatalogEntry? = memScoped {
        val fmtBuf = allocArray<ByteVar>(256)
        val idBuf = allocArray<ByteVar>(512)
        val nameBuf = allocArray<ByteVar>(512)
        if (!uapmd_plugin_host_get_catalog_entry(handle, index, fmtBuf, 256u, idBuf, 512u, nameBuf, 512u))
            return null
        CatalogEntry(fmtBuf.toKString(), idBuf.toKString(), nameBuf.toKString())
    }

    override fun saveCatalog(path: String) = uapmd_plugin_host_save_catalog(handle, path)
    override fun performScanning(rescan: Boolean) = uapmd_plugin_host_perform_scanning(handle, rescan)
    override fun reloadCatalogFromCache() = uapmd_plugin_host_reload_catalog_from_cache(handle)

    override fun createInstance(
        sampleRate: UInt, bufferSize: UInt,
        mainInputChannels: Int, mainOutputChannels: Int,
        offlineMode: Boolean, format: String, pluginId: String,
        callback: (Int, String?) -> Unit
    ) {
        val ref = StableRef.create(callback)
        uapmd_plugin_host_create_instance(
            handle, sampleRate, bufferSize, mainInputChannels, mainOutputChannels,
            offlineMode, format, pluginId, ref.asCPointer(),
            staticCFunction { instanceId, error, userData ->
                if (userData == null) return@staticCFunction
                val cb = userData.asStableRef<(Int, String?) -> Unit>()
                cb.get()(instanceId, error?.toKString())
                cb.dispose()
            }
        )
    }

    override fun deleteInstance(instanceId: Int) =
        uapmd_plugin_host_delete_instance(handle, instanceId)

    override fun getInstance(instanceId: Int): PluginInstance? =
        uapmd_plugin_host_get_instance(handle, instanceId)?.let { NativePluginInstance(it) }

    override fun getInstanceIds(): List<Int> = memScoped {
        val count = uapmd_plugin_host_instance_id_count(handle)
        if (count == 0u) return emptyList()
        val arr = allocArray<IntVar>(count.toInt())
        uapmd_plugin_host_get_instance_ids(handle, arr, count)
        (0 until count.toInt()).map { arr[it] }
    }

    override fun close() = uapmd_plugin_host_destroy(handle)
}

// ---------------------------------------------------------------------------

class NativePluginNode internal constructor(
    private val handle: uapmd_plugin_node_t,
    override val instance: PluginInstance
) : PluginNode {

    override val instanceId: Int get() = uapmd_node_instance_id(handle)

    override fun scheduleEvents(timestamp: Long, events: ByteArray): Boolean {
        events.usePinned { pinned ->
            return uapmd_node_schedule_events(handle, timestamp, pinned.addressOf(0), events.size.toULong())
        }
    }

    override fun sendAllNotesOff() = uapmd_node_send_all_notes_off(handle)
}

// ---------------------------------------------------------------------------

class NativePluginGraph internal constructor(
    private val handle: uapmd_plugin_graph_t
) : PluginGraph {

    // Holds StableRefs for delete callbacks so they're not GC'd.
    private val deleteRefs = mutableListOf<StableRef<() -> Unit>>()

    override fun appendNode(instanceId: Int, instance: PluginInstance, onDelete: (() -> Unit)?): Int {
        val nativeInstance = (instance as NativePluginInstance).handle
        val deleteRef = onDelete?.let {
            val ref = StableRef.create(it)
            deleteRefs += ref
            ref
        }
        return uapmd_graph_append_node(
            handle, instanceId, nativeInstance,
            deleteRef?.asCPointer(),
            if (onDelete != null) staticCFunction { userData ->
                userData?.asStableRef<() -> Unit>()?.let { ref ->
                    ref.get().invoke()
                    ref.dispose()
                }
            } else null
        )
    }

    override fun removeNode(instanceId: Int): Boolean = uapmd_graph_remove_node(handle, instanceId)
    override val pluginCount: UInt get() = uapmd_graph_plugin_count(handle)

    override fun getPluginNode(instanceId: Int): PluginNode? {
        val nodeHandle = uapmd_graph_get_plugin_node(handle, instanceId) ?: return null
        val instHandle = uapmd_node_instance(nodeHandle) ?: return null
        return NativePluginNode(nodeHandle, NativePluginInstance(instHandle))
    }

    private var eventOutputRef: StableRef<(Int, UIntArray, Int) -> Unit>? = null

    override fun setEventOutputCallback(callback: ((Int, UIntArray, Int) -> Unit)?) {
        eventOutputRef?.dispose()
        eventOutputRef = null
        if (callback == null) {
            uapmd_graph_set_event_output_callback(handle, null, null)
            return
        }
        val ref = StableRef.create(callback)
        eventOutputRef = ref
        uapmd_graph_set_event_output_callback(
            handle, ref.asCPointer(),
            staticCFunction { instanceId, data, dataSizeBytes, userData ->
                if (userData == null || data == null) return@staticCFunction
                val cb = userData.asStableRef<(Int, UIntArray, Int) -> Unit>().get()
                val count = (dataSizeBytes / 4u).toInt()
                cb(instanceId, UIntArray(count) { i -> data[i] }, dataSizeBytes.toInt())
            }
        )
    }

    override val outputBusCount: UInt get() = uapmd_graph_output_bus_count(handle)
    override fun getOutputLatencyInSamples(busIndex: UInt): UInt = uapmd_graph_output_latency_in_samples(handle, busIndex)
    override fun getOutputTailLengthInSeconds(busIndex: UInt): Double = uapmd_graph_output_tail_length_in_seconds(handle, busIndex)
    override val renderLeadInSamples: UInt get() = uapmd_graph_render_lead_in_samples(handle)
    override val mainOutputLatencyInSamples: UInt get() = uapmd_graph_main_output_latency_in_samples(handle)
    override val mainOutputTailLengthInSeconds: Double get() = uapmd_graph_main_output_tail_length_in_seconds(handle)

    override fun close() {
        eventOutputRef?.dispose()
        eventOutputRef = null
        uapmd_graph_destroy(handle)
    }
}
