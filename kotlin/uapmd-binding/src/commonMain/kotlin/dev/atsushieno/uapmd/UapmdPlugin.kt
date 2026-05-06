package dev.atsushieno.uapmd

interface PluginInstance {
    val displayName: String
    val formatName: String
    val pluginId: String
    var bypassed: Boolean

    fun startProcessing(): Int
    fun stopProcessing(): Int

    val latencyInSamples: UInt
    val tailLengthInSeconds: Double
    val requiresReplacingProcess: Boolean

    val parameterCount: UInt
    fun getParameterMetadata(index: UInt): ParameterMetadata?
    fun getParameterValue(index: Int): Double
    fun setParameterValue(index: Int, value: Double)
    fun getParameterValueString(index: Int, value: Double): String
    fun setPerNoteControllerValue(note: UByte, index: UByte, value: Double)
    fun getPerNoteControllerValueString(note: UByte, index: UByte, value: Double): String

    val presetCount: UInt
    fun getPresetMetadata(index: UInt): PresetMetadata?
    fun loadPreset(presetIndex: Int)

    fun saveStateSync(): ByteArray
    fun loadStateSync(data: ByteArray)
    fun requestState(ctx: StateContextType, includeUiState: Boolean, callback: (ByteArray?, String?) -> Unit)
    fun loadState(data: ByteArray, ctx: StateContextType, includeUiState: Boolean, callback: (String?) -> Unit)

    val uiCapabilities: PluginUiCapabilities
    val hasUiSupport: Boolean get() = uiCapabilities.hasUiSupport
    fun createUiPresentation(request: PluginUiPresentationRequest = PluginUiPresentationRequest()): PluginUiPresentation?
}

data class PluginUiCapabilities(
    val hasUiSupport: Boolean,
    val supportsEmbeddedPresentations: Boolean = hasUiSupport,
    val supportsFloatingPresentations: Boolean = hasUiSupport,
    val supportsMultiplePresentations: Boolean = false
)

sealed interface PluginUiHost {
    data object FloatingWindow : PluginUiHost
    data class NativeEmbedded(val parentHandle: Long) : PluginUiHost
    data class WebEmbedded(val containerId: String) : PluginUiHost
}

enum class PluginUiPresentationRole {
    COMPACT,
    FULL,
    AUXILIARY
}

enum class PluginUiPresentationMode {
    EMBEDDED,
    FLOATING
}

data class PluginUiPresentationRequest(
    val host: PluginUiHost = PluginUiHost.FloatingWindow,
    val role: PluginUiPresentationRole = PluginUiPresentationRole.FULL,
    val resizeHandler: ((UInt, UInt) -> Boolean)? = null
) {
    val mode: PluginUiPresentationMode
        get() = when (host) {
            PluginUiHost.FloatingWindow -> PluginUiPresentationMode.FLOATING
            is PluginUiHost.NativeEmbedded, is PluginUiHost.WebEmbedded -> PluginUiPresentationMode.EMBEDDED
        }
}

interface PluginUiPresentation {
    val host: PluginUiHost
    val role: PluginUiPresentationRole
    val mode: PluginUiPresentationMode
    val isVisible: Boolean
    val canResize: Boolean

    fun show(): Boolean
    fun hide()
    fun close()
    fun setSize(width: UInt, height: UInt): Boolean
    fun getSize(): UiSize?
}

interface PluginHost : AutoCloseable {
    val catalogEntryCount: UInt
    fun getCatalogEntry(index: UInt): CatalogEntry?
    fun saveCatalog(path: String)
    fun performScanning(rescan: Boolean)
    fun reloadCatalogFromCache()
    fun createInstance(
        sampleRate: UInt,
        bufferSize: UInt,
        mainInputChannels: Int,
        mainOutputChannels: Int,
        offlineMode: Boolean,
        format: String,
        pluginId: String,
        callback: (instanceId: Int, error: String?) -> Unit
    )
    fun deleteInstance(instanceId: Int)
    fun getInstance(instanceId: Int): PluginInstance?
    fun getInstanceIds(): List<Int>
}

interface PluginNode {
    val instanceId: Int
    val instance: PluginInstance
    fun scheduleEvents(timestamp: Long, events: ByteArray): Boolean
    fun sendAllNotesOff()
}

interface PluginGraph : AutoCloseable {
    fun appendNode(instanceId: Int, instance: PluginInstance, onDelete: (() -> Unit)? = null): Int
    fun removeNode(instanceId: Int): Boolean
    val pluginCount: UInt
    fun getPluginNode(instanceId: Int): PluginNode?
    fun setEventOutputCallback(callback: ((instanceId: Int, data: UIntArray, dataSizeInBytes: Int) -> Unit)?)
    val outputBusCount: UInt
    fun getOutputLatencyInSamples(busIndex: UInt): UInt
    fun getOutputTailLengthInSeconds(busIndex: UInt): Double
    val renderLeadInSamples: UInt
    val mainOutputLatencyInSamples: UInt
    val mainOutputTailLengthInSeconds: Double
}
