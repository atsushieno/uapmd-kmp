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

    val hasUiSupport: Boolean
    /** parentHandle is platform-specific (e.g. NSView* on iOS cast to Long). */
    fun createUi(isFloating: Boolean, parentHandle: Long, resizeHandler: ((UInt, UInt) -> Boolean)?): Boolean
    fun destroyUi()
    fun showUi(): Boolean
    fun hideUi()
    val isUiVisible: Boolean
    fun setUiSize(width: UInt, height: UInt): Boolean
    fun getUiSize(): UiSize?
    val canUiResize: Boolean
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
