package dev.atsushieno.uapmd.cmp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Root of the uapmd-cmp UI.
 *
 * Placeholder: the toolbar, timeline and floating window manager land in Phase 1
 * (see docs/uapmd-cmp-plan.md). This exists so the module builds on every target
 * before the AppModel bootstrap goes in.
 */
@Composable
fun MainWindow() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("uapmd-cmp", style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
}
