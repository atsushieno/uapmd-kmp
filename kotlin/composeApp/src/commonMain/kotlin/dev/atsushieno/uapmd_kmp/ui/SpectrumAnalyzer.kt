package dev.atsushieno.uapmd_kmp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

@Composable
fun SpectrumAnalyzer(
    bands: FloatArray,           // magnitudes in 0..1
    barColor: Color = Color(0xFF44AACC),
    bgColor: Color = Color(0xFF111111),
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(bgColor)
        if (bands.isEmpty()) return@Canvas
        val barW = size.width / bands.size
        bands.forEachIndexed { i, mag ->
            val barH = mag.coerceIn(0f, 1f) * size.height
            drawRect(
                color = barColor,
                topLeft = Offset(i * barW, size.height - barH),
                size = Size(barW - 1f, barH)
            )
        }
    }
}
