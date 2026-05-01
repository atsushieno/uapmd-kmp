package dev.atsushieno.uapmd

data class ParameterNamedValue(val value: Double, val name: String)

data class ParameterMetadata(
    val index: UInt,
    val stableId: String,
    val name: String,
    val path: String,
    val defaultPlainValue: Double,
    val minPlainValue: Double,
    val maxPlainValue: Double,
    val automatable: Boolean,
    val hidden: Boolean,
    val discrete: Boolean,
    val namedValues: List<ParameterNamedValue>
)

data class PresetMetadata(
    val bank: UByte,
    val index: UInt,
    val stableId: String,
    val name: String,
    val path: String
)

enum class StateContextType(val nativeValue: Int) {
    Remember(0), Copyable(1), Preset(2), Project(3)
}

data class CatalogEntry(val format: String, val pluginId: String, val displayName: String)

data class UiSize(val width: UInt, val height: UInt)

enum class AudioIoDirection(val nativeValue: Int) {
    Input(1), Output(2), Duplex(3);
    companion object {
        fun fromNative(v: Int) = entries.first { it.nativeValue == v }
    }
}

data class AudioDeviceInfo(
    val directions: AudioIoDirection,
    val id: Int,
    val name: String,
    val sampleRate: UInt,
    val channels: UInt
)

data class BlocklistEntry(
    val id: String,
    val format: String,
    val pluginId: String,
    val reason: String
)

data class ClipAddResult(
    val clipId: Int,
    val sourceNodeId: Int,
    val success: Boolean,
    val error: String?
)

data class ProjectResult(val success: Boolean, val error: String?)

data class ContentBounds(
    val hasContent: Boolean,
    val firstSample: Long,
    val lastSample: Long,
    val firstSeconds: Double,
    val lastSeconds: Double
)

data class TimelinePosition(val samples: Long, val legacyBeats: Double)

data class TimelineState(
    val playheadPosition: TimelinePosition,
    val isPlaying: Boolean,
    val loopEnabled: Boolean,
    val loopStart: TimelinePosition,
    val loopEnd: TimelinePosition,
    val tempo: Double,
    val timeSignatureNumerator: Int,
    val timeSignatureDenominator: Int,
    val sampleRate: Int
)

data class OfflineRenderSettings(
    val outputPath: String,
    val startSeconds: Double,
    val endSeconds: Double? = null,
    val useContentFallback: Boolean = true,
    val contentBoundsValid: Boolean = false,
    val contentStartSeconds: Double = 0.0,
    val contentEndSeconds: Double = 0.0,
    val tailSeconds: Double = 0.0,
    val enableSilenceStop: Boolean = false,
    val silenceDurationSeconds: Double = 3.0,
    val silenceThresholdDb: Double = -60.0,
    val sampleRate: Int = 44100,
    val bufferSize: UInt = 1024u,
    val outputChannels: UInt = 2u,
    val umpBufferSize: UInt = 8192u
)

data class OfflineRenderProgress(
    val progress: Double,
    val renderedSeconds: Double,
    val totalSeconds: Double,
    val renderedFrames: Long,
    val totalFrames: Long
)

data class OfflineRenderResult(
    val success: Boolean,
    val canceled: Boolean,
    val renderedSeconds: Double,
    val errorMessage: String?
)

data class AudioFileProperties(
    val numFrames: Long,
    val numChannels: UInt,
    val sampleRate: UInt
)

enum class ScanMode { InProcess, Remote }

enum class InstancingState { Created, Preparing, Ready, Error, Terminating, Terminated;
    companion object {
        fun fromNative(v: Int): InstancingState = entries.getOrElse(v) { Created }
    }
}
