package dev.atsushieno.uapmd

import kotlinx.cinterop.*
import uapmd.*

/** Two-call C string helper (null buf → returns needed size, then copies). */
internal fun readCString(block: (CPointer<ByteVar>?, ULong) -> ULong): String = memScoped {
    val needed = block(null, 0u)
    if (needed == 0uL) return@memScoped ""
    val buf = allocArray<ByteVar>(needed.toInt())
    block(buf, needed)
    buf.toKString()
}

internal fun StateContextType.toNative(): Int = nativeValue

internal fun uapmd_timeline_position_t.toKotlin() =
    TimelinePosition(samples, legacy_beats)

internal fun TimelinePosition.toNativeCValue(): CValue<uapmd_timeline_position_t> =
    cValue<uapmd_timeline_position_t> {
        samples = this@toNativeCValue.samples
        legacy_beats = this@toNativeCValue.legacyBeats
    }

internal fun uapmd_timeline_state_t.toKotlin() = TimelineState(
    playheadPosition = playhead_position.toKotlin(),
    isPlaying = is_playing,
    loopEnabled = loop_enabled,
    loopStart = loop_start.toKotlin(),
    loopEnd = loop_end.toKotlin(),
    tempo = tempo,
    timeSignatureNumerator = time_signature_numerator,
    timeSignatureDenominator = time_signature_denominator,
    sampleRate = sample_rate
)
