package dev.atsushieno.uapmd_kmp.timeline

data class WaveformPoint(val minValue: Float, val maxValue: Float)

data class MidiNote(
    val startSeconds: Double,
    val durationSeconds: Double,
    val note: Int,
    val velocity: Float
)

data class TempoPoint(val timeSeconds: Double, val bpm: Double)

data class TimeSignaturePoint(val timeSeconds: Double, val numerator: Int, val denominator: Int)

data class ClipMarker(val clipPositionSeconds: Double, val name: String = "")

sealed class ClipPreviewData {
    data class Audio(
        val waveform: List<WaveformPoint>,
        val durationSeconds: Double,
        val markers: List<ClipMarker> = emptyList()
    ) : ClipPreviewData()

    data class Midi(
        val notes: List<MidiNote>,
        val durationSeconds: Double,
        val minNote: Int = 0,
        val maxNote: Int = 127
    ) : ClipPreviewData()

    data class MasterMeta(
        val tempoPoints: List<TempoPoint>,
        val timeSignatures: List<TimeSignaturePoint>,
        val durationSeconds: Double
    ) : ClipPreviewData()

    data object Loading : ClipPreviewData()
    data class Error(val message: String) : ClipPreviewData()
}
