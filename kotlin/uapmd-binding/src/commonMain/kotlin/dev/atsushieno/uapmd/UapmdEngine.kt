package dev.atsushieno.uapmd

interface SequencerEngine {
    fun enqueueUmp(instanceId: Int, ump: UIntArray, timestamp: Long)
    val pluginHost: PluginHost
    fun getPluginInstance(instanceId: Int): PluginInstance?
    val functionBlockManager: FunctionBlockManager

    val trackCount: UInt
    fun getTrack(index: UInt): SequencerTrack
    val masterTrack: SequencerTrack
    fun addEmptyTrack(): Int
    fun addPluginToTrack(
        trackIndex: Int,
        format: String,
        pluginId: String,
        callback: (instanceId: Int, trackIndex: Int, error: String?) -> Unit
    )
    fun removePluginInstance(instanceId: Int): Boolean
    fun removeTrack(trackIndex: Int): Boolean
    fun cleanupEmptyTracks()
    fun findTrackForInstance(instanceId: Int): Int
    fun getInstanceGroup(instanceId: Int): UByte
    fun setInstanceGroup(instanceId: Int, group: UByte): Boolean

    fun getTrackLatency(trackIndex: Int): UInt
    val masterTrackLatency: UInt
    fun getTrackRenderLead(trackIndex: Int): UInt
    val masterTrackRenderLead: UInt

    fun setDefaultChannels(inputChannels: UInt, outputChannels: UInt)
    var sampleRate: Int
    var offlineRendering: Boolean
    fun setActive(active: Boolean)
    fun setExternalPump(enabled: Boolean)

    val isPlaybackActive: Boolean
    var playbackPosition: Long
    val renderPlaybackPosition: Long
    fun startPlayback()
    fun stopPlayback()
    fun pausePlayback()
    fun resumePlayback()

    fun sendNoteOn(instanceId: Int, note: Int)
    fun sendNoteOff(instanceId: Int, note: Int)
    fun sendPitchBend(instanceId: Int, value: Float)
    fun sendChannelPressure(instanceId: Int, pressure: Float)
    fun setParameterValue(instanceId: Int, index: Int, value: Double)

    fun getInputSpectrum(numBars: Int): FloatArray
    fun getOutputSpectrum(numBars: Int): FloatArray

    val timeline: TimelineFacade

    fun renderOffline(
        settings: OfflineRenderSettings,
        progressCallback: ((OfflineRenderProgress) -> Unit)? = null,
        shouldCancel: (() -> Boolean)? = null
    ): OfflineRenderResult
}

interface SequencerTrack {
    val graph: PluginGraph
    val latencyInSamples: UInt
    val renderLeadInSamples: UInt
    val tailLengthInSeconds: Double
    var bypassed: Boolean
    var frozen: Boolean
    fun getOrderedInstanceIds(): List<Int>
    fun setInstanceGroup(instanceId: Int, group: UByte)
    fun getInstanceGroup(instanceId: Int): UByte
    fun findAvailableGroup(): UByte
    fun removeInstance(instanceId: Int)
}

interface AudioDeviceManager {
    val deviceCount: UInt
    fun getDeviceInfo(index: UInt): AudioDeviceInfo?
    fun open(inputDeviceIndex: Int, outputDeviceIndex: Int, sampleRate: UInt, bufferSize: UInt): AudioIODevice
}

interface AudioIODevice {
    val sampleRate: Double
    val channels: UInt
    val inputChannels: UInt
    val outputChannels: UInt
    fun start(): Int
    fun stop(): Int
    val isPlaying: Boolean
}

/** Marker for a platform MIDI I/O device handle. */
interface MidiIODevice

interface DeviceIODispatcher {
    fun start(): Int
    fun stop(): Int
    val isPlaying: Boolean
    fun clearOutputBuffers()
}

interface RealtimeSequencer : AutoCloseable {
    val engine: SequencerEngine
    fun startAudio(): Int
    fun stopAudio(): Int
    fun isAudioPlaying(): Int
    fun clearOutputBuffers()
    var sampleRate: Int
    fun reconfigureAudioDevice(
        inputDeviceIndex: Int,
        outputDeviceIndex: Int,
        sampleRate: UInt,
        bufferSize: UInt
    ): Boolean
}
