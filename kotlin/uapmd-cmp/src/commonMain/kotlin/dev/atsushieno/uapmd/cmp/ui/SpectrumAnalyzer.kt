package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp


/** The In/Out level meters uapmd-app puts at the right of its toolbar. */
@Composable
fun SpectrumAnalyzer(bars: FloatArray, modifier: Modifier = Modifier) {
    val c = editorPalette
    Canvas(modifier.width(80.dp).height(28.dp)) {
        drawRect(c.meterBackground, Offset.Zero, size)
        if (bars.isEmpty()) return@Canvas
        val w = size.width / bars.size
        bars.forEachIndexed { i, v ->
            val h = (v.coerceIn(0f, 1f)) * size.height
            drawRect(c.meterBar, Offset(i * w, size.height - h), Size(w - 1f, h))
        }
    }
}
