package dev.atsushieno.uapmd

import dev.atsushieno.uapmd.jna.UapmdLibrary
import dev.atsushieno.uapmd.jna.UapmdTimelinePosition

internal val lib: UapmdLibrary get() = UapmdLibrary.INSTANCE

/**
 * Calls a C string-getter function that follows the two-call convention:
 *   - first call with null/0 returns required buffer size (including NUL)
 *   - second call with allocated buffer fills it and returns chars written (excluding NUL)
 */
internal fun readJvmString(fn: (buf: ByteArray?, size: Long) -> Long): String {
    val needed = fn(null, 0L).toInt()
    if (needed <= 0) return ""
    val buf = ByteArray(needed)
    fn(buf, needed.toLong())
    return String(buf, 0, needed - 1, Charsets.UTF_8)
}

internal fun TimelinePosition.toJvmByVal(): UapmdTimelinePosition.ByVal =
    UapmdTimelinePosition.ByVal().also {
        it.samples = samples
        it.legacy_beats = legacyBeats
    }

internal fun UapmdTimelinePosition.toKotlin(): TimelinePosition =
    TimelinePosition(samples, legacy_beats)

internal fun StateContextType.toJvmInt(): Int = nativeValue
