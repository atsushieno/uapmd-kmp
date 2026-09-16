package dev.atsushieno.uapmd.cmp

import dev.atsushieno.uapmd.TempoPoint
import dev.atsushieno.uapmd.TimeSignaturePoint
import kotlin.math.max

/**
 * Seconds ⇄ quarter-note beats over a piecewise-constant tempo map.
 *
 * A port of `uapmd::TempoMap` (`uapmd-data/…/timeline/TempoMap.hpp`), kept in
 * Kotlin because the beats ruler converts on every frame and an FFI round trip
 * per conversion would be absurd. This is derived logic, so by the layering rule
 * it belongs in the app rather than the binding.
 *
 * The engine's map is the single source of truth, exposed by the binding as
 * `TimelineFacade.masterTempoMap` — the same curve playback is scheduled
 * against. That one is authoritative: if this port and it ever disagree, this
 * port is wrong. Keep `build()` in step with `TempoMap::rebuild`,
 * in particular the implicit bar-one signature and the restatement dedup below,
 * which are what make bar lines land in the right place across a meter change.
 *
 * "Beat" is always a quarter note, matching BPM and ticks-per-quarter elsewhere.
 */
class TempoMap private constructor(
    private val segments: List<Segment>,
    val signatures: List<EffectiveSignature>,
    val hasTempoData: Boolean
) {
    private class Segment(
        val startTime: Double,
        val endTime: Double,          // POSITIVE_INFINITY for the final segment
        val bpm: Double,
        val accumulatedBeats: Double
    )

    data class EffectiveSignature(
        val startBeat: Double,
        val endBeat: Double,
        val numerator: Int,
        val denominator: Int
    )

    fun secondsToBeats(seconds: Double): Double {
        if (segments.isEmpty()) return seconds * (DefaultBpm / 60.0)
        val t = max(0.0, seconds)
        for (s in segments) {
            if (t < s.endTime) {
                val bpm = if (s.bpm > 0.0) s.bpm else DefaultBpm
                return s.accumulatedBeats + (t - s.startTime) * (bpm / 60.0)
            }
        }
        val last = segments.last()
        val bpm = if (last.bpm > 0.0) last.bpm else DefaultBpm
        return last.accumulatedBeats + (t - last.startTime) * (bpm / 60.0)
    }

    fun beatsToSeconds(beats: Double): Double {
        if (segments.isEmpty()) return beats * (60.0 / DefaultBpm)
        val b = max(0.0, beats)
        for (s in segments) {
            val bpm = if (s.bpm > 0.0) s.bpm else DefaultBpm
            val segmentEndBeats =
                if (s.endTime.isFinite())
                    s.accumulatedBeats + (s.endTime - s.startTime) * (bpm / 60.0)
                else Double.POSITIVE_INFINITY
            if (b < segmentEndBeats)
                return s.startTime + (b - s.accumulatedBeats) * (60.0 / bpm)
        }
        val last = segments.last()
        val bpm = if (last.bpm > 0.0) last.bpm else DefaultBpm
        return last.startTime + (b - last.accumulatedBeats) * (60.0 / bpm)
    }

    /** The signature in force at a beat position, or 4/4 when the map says nothing. */
    fun signatureAtBeat(beat: Double): Pair<Int, Int> {
        for (s in signatures)
            if (beat >= s.startBeat && beat < s.endBeat) return s.numerator to s.denominator
        return signatures.lastOrNull()?.let { it.numerator to it.denominator } ?: (4 to 4)
    }

    /** How many quarter notes one bar of this meter spans (3.5 for 7/8). */
    fun barLengthBeats(signature: EffectiveSignature): Double {
        val numerator = if (signature.numerator > 0) signature.numerator else 4
        val denominator = if (signature.denominator > 0) signature.denominator else 4
        return numerator * 4.0 / denominator
    }

    companion object {
        const val DefaultBpm = 120.0

        /** An empty map: everything converts at [DefaultBpm]. */
        val Empty = TempoMap(emptyList(), emptyList(), hasTempoData = false)

        fun build(
            tempoPoints: List<TempoPoint>,
            signaturePoints: List<TimeSignaturePoint>,
            defaultBpm: Double = DefaultBpm
        ): TempoMap {
            if (tempoPoints.isEmpty() && signaturePoints.isEmpty()) return Empty

            val segments = mutableListOf<Segment>()
            if (tempoPoints.isNotEmpty()) {
                var currentBpm = tempoPoints.first().bpm.takeIf { it > 0.0 } ?: defaultBpm
                var lastTime = 0.0
                var accumulated = 0.0
                for (p in tempoPoints) {
                    val eventTime = max(0.0, p.timeSeconds)
                    if (eventTime > lastTime) {
                        val bpm = if (currentBpm > 0.0) currentBpm else defaultBpm
                        segments += Segment(lastTime, eventTime, bpm, accumulated)
                        accumulated += (eventTime - lastTime) * (bpm / 60.0)
                        lastTime = eventTime
                    }
                    if (p.bpm > 0.0) currentBpm = p.bpm
                }
                segments += Segment(
                    lastTime, Double.POSITIVE_INFINITY,
                    if (currentBpm > 0.0) currentBpm else defaultBpm, accumulated
                )
            }

            val map = TempoMap(segments, emptyList(), tempoPoints.isNotEmpty())
            // Signature ranges are expressed in beats, so they need the tempo
            // segments above to convert their seconds positions first.
            val started = signaturePoints
                .sortedBy { it.timeSeconds }
                .map { p ->
                    EffectiveSignature(
                        startBeat = map.secondsToBeats(max(0.0, p.timeSeconds)),
                        endBeat = Double.POSITIVE_INFINITY,
                        numerator = p.numerator.coerceAtLeast(1),
                        denominator = p.denominator.coerceAtLeast(1)
                    )
                }
                .sortedBy { it.startBeat }
                .toMutableList()

            // Bar one starts at beat zero. Without a signature there, the bars
            // before the first change have no meter to be counted in — and
            // signatureAtBeat() would fall through to the *last* signature,
            // reporting the final meter for the opening bars.
            if (started.isEmpty() || started.first().startBeat > 1e-9)
                started.add(0, EffectiveSignature(0.0, Double.POSITIVE_INFINITY, 4, 4))

            // A meta event restating the meter already in force does not start a
            // new region. It is the region's start that sets where its bars
            // fall, so honouring a restatement would re-phase the bar grid onto
            // whatever beat that event happens to sit on — moving every later
            // bar line, and every genuine meter change with it.
            val deduped = mutableListOf<EffectiveSignature>()
            for (sig in started) {
                val previous = deduped.lastOrNull()
                if (previous != null &&
                    previous.numerator == sig.numerator &&
                    previous.denominator == sig.denominator
                ) continue
                deduped += sig
            }

            // Each region runs to the next one's start.
            val sigs = deduped.mapIndexed { i, sig ->
                sig.copy(
                    endBeat = if (i + 1 < deduped.size) deduped[i + 1].startBeat
                    else Double.POSITIVE_INFINITY
                )
            }
            return TempoMap(segments, sigs, tempoPoints.isNotEmpty())
        }
    }
}
