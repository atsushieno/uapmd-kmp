package dev.atsushieno.uapmd

interface AudioFileReader : AutoCloseable {
    fun getProperties(): AudioFileProperties?
    /**
     * Read [framesToRead] interleaved frames starting at [startFrame] into [dest].
     * [dest] has one FloatArray per channel (planar layout matching the C API).
     */
    fun readFrames(startFrame: Long, framesToRead: Long, dest: Array<FloatArray>)
}

/** Marker for a timeline track handle (extended by platform implementations). */
interface TimelineTrack

interface TimelineFacade {
    fun getState(): TimelineState?
    fun setTempo(tempo: Double)
    fun setTimeSignature(numerator: Int, denominator: Int)
    fun setLoop(enabled: Boolean, start: TimelinePosition, end: TimelinePosition)

    val trackCount: UInt
    fun getTrack(index: UInt): TimelineTrack
    val masterTimelineTrack: TimelineTrack

    fun addAudioClip(
        trackIndex: Int,
        position: TimelinePosition,
        reader: AudioFileReader,
        filepath: String
    ): ClipAddResult

    fun addMidiClipFromFile(
        trackIndex: Int,
        position: TimelinePosition,
        filepath: String,
        nrpnToParameterMapping: Boolean = false
    ): ClipAddResult

    fun removeClip(trackIndex: Int, clipId: Int): Boolean
    fun loadProject(filePath: String): ProjectResult
    fun calculateContentBounds(): ContentBounds
}
