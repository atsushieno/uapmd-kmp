package dev.atsushieno.uapmd.cmp.ui

import dev.atsushieno.uapmd.cmp.TempoMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Which ruler the timeline is showing. */
enum class TimeUnit { Seconds, Beats }

/**
 * One line of the ruler, positioned in seconds.
 *
 * [seconds] rather than a pixel x so the same tick list serves the ruler strip
 * and the lane grid, which are laid out separately but share one scale.
 */
data class RulerTick(
    val seconds: Double,
    val major: Boolean,
    val label: String? = null
)

/**
 * Where the ruler's ticks and the lane grid's lines go, for either time unit.
 *
 * A port of `uapmd_app_gui::TimelineAxis` (`uapmd-app/gui/TimelineAxis.cpp`).
 * Seconds and beats are one widget, not two: the view mode is a property of the
 * ruler rather than a separate editor.
 *
 * What does *not* port is the frame axis. Upstream maps seconds onto ImTimeline's
 * `int32` frame index, because that is where ImTimeline stores a clip's start and
 * end while it is on screen; the frame unit therefore bounds how finely a clip can
 * be dragged. Compose has no such intermediate — uapmd-cmp's lanes are laid out
 * directly in seconds at a `Float` pixels-per-second — so the conversions,
 * clamping and zoom bounds expressed in frames upstream have nothing to attach to
 * here and are deliberately absent.
 *
 * Kept free of Compose so it can be exercised from the bootstrap probe.
 */
object TimelineAxis {

    /**
     * Upper bound on ticks returned per pass. Every step choice below already
     * targets a minimum pixel spacing, so this only trips on a degenerate scale,
     * where drawing nothing beats hanging the frame.
     */
    const val MaxTicks = 4000

    /**
     * Smallest "round" number of seconds at least as large as [minimum], from a
     * ladder that stays readable as labels (no 2.5s or 7s gradations) and lines
     * up with clock time above a minute.
     */
    fun niceSecondsStep(minimum: Double): Double {
        val ladder = doubleArrayOf(
            0.001, 0.002, 0.005, 0.01, 0.02, 0.05, 0.1, 0.2, 0.5,
            1.0, 2.0, 5.0, 10.0, 15.0, 30.0,
            60.0, 120.0, 300.0, 600.0, 900.0, 1800.0, 3600.0
        )
        ladder.firstOrNull { it >= minimum }?.let { return it }
        // Past an hour, keep doubling rather than inventing more ladder entries.
        var step = ladder.last()
        while (step < minimum && step < 1e9) step *= 2.0
        return step
    }

    /**
     * `m:ss` above a minute, whole seconds at a step of a second or more, and
     * otherwise exactly as many decimals as the step needs for neighbouring
     * labels to differ.
     */
    fun formatSecondsLabel(seconds: Double, step: Double): String {
        val t = max(0.0, seconds)
        if (step >= 1.0) {
            val total = t.roundToLong()
            return if (total >= 60) "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
            else "${total}s"
        }
        val decimals = ceil(-log10(step)).toInt().coerceIn(1, 3)
        return "${fixedDecimals(t, decimals)}s"
    }

    private fun fixedDecimals(value: Double, decimals: Int): String {
        var scale = 1.0
        repeat(decimals) { scale *= 10.0 }
        val scaled = (value * scale).roundToLong()
        val whole = scaled / scale.toLong()
        val frac = (scaled % scale.toLong()).toString().padStart(decimals, '0')
        return "$whole.$frac"
    }

    /**
     * Ticks for the seconds ruler across the visible span.
     *
     * A labelled tick every ~80dp keeps labels from colliding at any zoom, and
     * the step is subdivided by five only when the minor lines would land far
     * enough apart to read as separate.
     */
    fun secondsTicks(
        startSeconds: Double,
        endSeconds: Double,
        pixelsPerSecond: Float,
        uiScale: Float = 1f
    ): List<RulerTick> {
        if (pixelsPerSecond <= 0f || endSeconds <= startSeconds) return emptyList()
        val pps = pixelsPerSecond.toDouble()
        val majorStep = niceSecondsStep((80.0 * uiScale) / pps)
        val minorStep = if ((majorStep / 5.0) * pps >= 6.0) majorStep / 5.0 else majorStep
        // Compare in step units rather than seconds: majorStep is often not
        // representable in binary, so a modulo on the seconds value drifts into
        // false negatives as the index grows.
        val perMajor = (majorStep / minorStep).roundToLong()

        val out = ArrayList<RulerTick>()
        var index = floor(startSeconds / minorStep).toLong()
        while (out.size < MaxTicks) {
            val seconds = index * minorStep
            if (seconds > endSeconds + minorStep) break
            index++
            if (seconds < 0.0) continue
            val major = perMajor <= 1L || (index - 1) % perMajor == 0L
            out += RulerTick(
                seconds = seconds,
                major = major,
                label = if (major) formatSecondsLabel(seconds, majorStep) else null
            )
        }
        return out
    }

    /**
     * Ticks for the beats ruler, laid out one meter region at a time.
     *
     * The region list comes from the tempo map and starts at beat zero, so each
     * region's own start is where its bars begin. Bar numbers run continuously
     * across meter changes — the count does not restart at a new meter, it just
     * starts measuring in different-length bars.
     *
     * A "signature beat" is one beat of the meter's denominator: an eighth in
     * 6/8, not a quarter. Sub-beat lines are dropped when they would crowd, and
     * whole bars are thinned by a power-of-two stride rather than drawn as a
     * solid block when even those collide.
     */
    fun beatsTicks(
        startSeconds: Double,
        endSeconds: Double,
        pixelsPerSecond: Float,
        tempoMap: TempoMap,
        uiScale: Float = 1f,
        fallbackBeatsPerBar: Int = 4
    ): List<RulerTick> {
        if (pixelsPerSecond <= 0f || endSeconds <= startSeconds) return emptyList()

        val startBeat = tempoMap.secondsToBeats(max(0.0, startSeconds))
        val endBeat = tempoMap.secondsToBeats(endSeconds)
        if (endBeat <= startBeat) return emptyList()

        // Beats are not evenly spaced in seconds once the tempo changes, so the
        // pixels-per-beat used to pick a stride is the average across the view.
        val pixelsPerBeat = ((endSeconds - startSeconds) * pixelsPerSecond) / (endBeat - startBeat)
        if (pixelsPerBeat <= 0.0) return emptyList()

        val regions = tempoMap.signatures.ifEmpty {
            listOf(
                TempoMap.EffectiveSignature(
                    0.0, Double.POSITIVE_INFINITY,
                    fallbackBeatsPerBar.coerceAtLeast(1), 4
                )
            )
        }

        // Bar numbers run continuously, so each region needs the count of bars
        // before it.
        class Laid(
            val startBeat: Double,
            val endBeat: Double,
            val signatureBeatLength: Double,
            val numerator: Int,
            val firstBarNumber: Long
        )

        val laid = ArrayList<Laid>(regions.size)
        var nextBarNumber = 1L
        for (region in regions) {
            val numerator = if (region.numerator > 0) region.numerator else 4
            val denominator = if (region.denominator > 0) region.denominator else 4
            // One signature beat spans this many quarter-note beats.
            val signatureBeatLength = 4.0 / denominator
            if (signatureBeatLength <= 0.0) continue
            val barLength = signatureBeatLength * numerator
            laid.lastOrNull()?.let { previous ->
                val previousBarLength = previous.signatureBeatLength * previous.numerator
                if (previousBarLength > 0.0)
                    nextBarNumber +=
                        ((region.startBeat - previous.startBeat) / previousBarLength).roundToLong()
            }
            laid += Laid(
                max(0.0, region.startBeat), region.endBeat,
                signatureBeatLength, numerator, nextBarNumber
            )
            if (barLength <= 0.0) continue
        }

        val out = ArrayList<RulerTick>()
        for (region in laid) {
            if (region.endBeat <= startBeat || region.startBeat >= endBeat) continue

            val pixelsPerSignatureBeat = region.signatureBeatLength * pixelsPerBeat
            val drawSubBeatLines = pixelsPerSignatureBeat >= 3.0
            val pixelsPerBar = pixelsPerSignatureBeat * region.numerator
            var barStride = 1L
            while (pixelsPerBar * barStride < 4.0 && barStride < (1L shl 20)) barStride *= 2
            val labelBars = pixelsPerBar * barStride >= 48.0 * uiScale

            // A region owns [start, end), the same half-open range
            // TempoMap.signatureAtBeat uses. Upstream's loop is inclusive at
            // both ends, so at a meter change the outgoing and incoming regions
            // both emit the boundary bar — invisible in ImGui because the two
            // lines land on the same pixel, but here it would number that bar
            // twice and paint its grid line at double alpha.
            val regionIsLast = !region.endBeat.isFinite() || region.endBeat >= endBeat
            val regionEnd = min(region.endBeat, endBeat)
            val visibleStart = max(region.startBeat, startBeat)
            var index = floor((visibleStart - region.startBeat) / region.signatureBeatLength).toLong()
            if (index < 0) index = 0

            while (out.size < MaxTicks) {
                val beat = region.startBeat + index * region.signatureBeatLength
                if (regionIsLast) {
                    if (beat > regionEnd + 1e-9) break
                } else if (beat >= regionEnd - 1e-9) break
                val isBar = index % region.numerator == 0L
                val barIndex = index / region.numerator
                index++
                if (beat < visibleStart - 1e-9) continue
                if (isBar) {
                    if (barIndex % barStride != 0L) continue
                } else if (!drawSubBeatLines) continue

                out += RulerTick(
                    seconds = tempoMap.beatsToSeconds(beat),
                    major = isBar,
                    // Bars are numbered from 1, the way every other tool counts them.
                    label = if (isBar && labelBars) "${region.firstBarNumber + barIndex}" else null
                )
            }
        }
        return out
    }

    /** Dispatches to [secondsTicks] or [beatsTicks] for [unit]. */
    fun ticks(
        unit: TimeUnit,
        startSeconds: Double,
        endSeconds: Double,
        pixelsPerSecond: Float,
        tempoMap: TempoMap,
        uiScale: Float = 1f,
        fallbackBeatsPerBar: Int = 4
    ): List<RulerTick> = when (unit) {
        TimeUnit.Seconds -> secondsTicks(startSeconds, endSeconds, pixelsPerSecond, uiScale)
        TimeUnit.Beats ->
            beatsTicks(startSeconds, endSeconds, pixelsPerSecond, tempoMap, uiScale, fallbackBeatsPerBar)
    }

    /**
     * The readout beside the zoom control: how long the content is, and where
     * the playhead sits, in whichever unit is showing.
     */
    fun positionLabel(
        unit: TimeUnit,
        seconds: Double,
        tempoMap: TempoMap,
        decimals: Int = 2
    ): String = when (unit) {
        TimeUnit.Seconds -> "${fixedDecimals(seconds, decimals)}s"
        TimeUnit.Beats -> {
            val beat = tempoMap.secondsToBeats(seconds)
            val region = tempoMap.signatures.lastOrNull { beat >= it.startBeat }
            if (region == null) fixedDecimals(beat, decimals)
            else {
                // Bar:beat, both counted from 1, which is how a musician reads a
                // position — "17.5 beats" names nothing anyone is looking for.
                val barLength = tempoMap.barLengthBeats(region)
                val barsBefore = tempoMap.signatures
                    .takeWhile { it.startBeat < region.startBeat }
                    .foldIndexed(0L) { i, acc, sig ->
                        val next = tempoMap.signatures[i + 1].startBeat
                        val length = tempoMap.barLengthBeats(sig)
                        acc + if (length > 0.0) ((next - sig.startBeat) / length).roundToLong() else 0L
                    }
                val into = beat - region.startBeat
                val bar = if (barLength > 0.0) floor(into / barLength).toLong() else 0L
                val beatInBar = if (barLength > 0.0) into - bar * barLength else into
                val signatureBeat = beatInBar / (4.0 / region.denominator.coerceAtLeast(1))
                "${barsBefore + bar + 1}:${fixedDecimals(signatureBeat + 1.0, 1)}"
            }
        }
    }

    /** True when [a] and [b] name the same tick position, within rounding. */
    internal fun sameTick(a: Double, b: Double) = abs(a - b) < 1e-9

    /** Pixel x for a tick at [seconds], for a lane that starts at second zero. */
    fun xOf(seconds: Double, pixelsPerSecond: Float): Float =
        (seconds * pixelsPerSecond).toFloat()

    /** Convenience for callers that want an integral pixel. */
    fun xOfRounded(seconds: Double, pixelsPerSecond: Float): Int =
        xOf(seconds, pixelsPerSecond).roundToInt()
}
