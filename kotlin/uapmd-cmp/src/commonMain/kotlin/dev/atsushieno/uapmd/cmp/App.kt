package dev.atsushieno.uapmd.cmp

import androidx.compose.runtime.Composable

@Composable
fun App() {
    // Back-to-quit where the platform has a back gesture; no-op elsewhere.
    PlatformQuitBackHandler()
    MainWindow()
}
