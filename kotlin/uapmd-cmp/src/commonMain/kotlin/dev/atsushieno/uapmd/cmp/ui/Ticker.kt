package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * A value that changes every [periodMillis] for as long as [enabled].
 *
 * Read it wherever a composable shows something that moves on its own but that
 * Compose cannot observe: an addin command carries its own elapsed time inside
 * its title, and uapmd-app keeps that ticking only because an immediate-mode
 * frame re-reads the title every time it draws. Compose reads it once and then
 * has no reason to look again, so the count stops at whatever it said when the
 * menu opened.
 *
 * Ticking stops as soon as [enabled] goes false, so a closed menu costs nothing.
 */
@Composable
fun rememberTicker(enabled: Boolean, periodMillis: Long = 250L): Int {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(enabled, periodMillis) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            delay(periodMillis)
            tick++
        }
    }
    return tick
}
