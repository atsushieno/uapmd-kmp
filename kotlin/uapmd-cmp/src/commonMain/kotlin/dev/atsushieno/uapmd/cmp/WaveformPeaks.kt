package dev.atsushieno.uapmd.cmp

import dev.atsushieno.uapmd.createAudioFileReader

/**
 * Min/max of an audio file's mono mixdown, one pair per [framesPerBucket]
 * frames. Finer than uapmd-app's fixed 512 columns, since the timeline here
 * zooms in far enough for those to show as steps.
 */
class WaveformPeaks(
    val min: FloatArray,
    val max: FloatArray,
    val framesPerBucket: Int,
    val sampleRate: Double
) {
    val bucketCount get() = min.size

    companion object {
        val Empty = WaveformPeaks(FloatArray(0), FloatArray(0), 1, 1.0)
        private const val ChunkFrames = 4096
        private const val MaxBuckets = 1 shl 16

        fun read(filepath: String): WaveformPeaks = createAudioFileReader(filepath).use { reader ->
            val props = reader.getProperties() ?: return Empty
            val channels = props.numChannels.toInt()
            val total = props.numFrames
            if (total <= 0 || channels <= 0) return Empty
            val perBucket = maxOf(64L, (total + MaxBuckets - 1) / MaxBuckets).toInt()
            val buckets = ((total + perBucket - 1) / perBucket).toInt()
            val mins = FloatArray(buckets) { Float.MAX_VALUE }
            val maxs = FloatArray(buckets) { -Float.MAX_VALUE }
            val buf = Array(channels) { FloatArray(ChunkFrames) }
            var frame = 0L
            while (frame < total) {
                val n = minOf(ChunkFrames.toLong(), total - frame).toInt()
                reader.readFrames(frame, n.toLong(), buf)
                for (i in 0 until n) {
                    var v = 0f
                    for (ch in 0 until channels) v += buf[ch][i]
                    v = (v / channels).coerceIn(-1f, 1f)
                    val b = ((frame + i) / perBucket).toInt()
                    if (v < mins[b]) mins[b] = v
                    if (v > maxs[b]) maxs[b] = v
                }
                frame += n
            }
            WaveformPeaks(mins, maxs, perBucket, props.sampleRate.toDouble().coerceAtLeast(1.0))
        }
    }
}
