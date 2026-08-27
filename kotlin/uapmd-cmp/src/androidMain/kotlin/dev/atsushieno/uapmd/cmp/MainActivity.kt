package dev.atsushieno.uapmd.cmp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Idempotent, and UapmdHost.start() does it too; doing it here as well
        // keeps it on the Android main thread at the earliest possible point.
        initPlatformEventLoop()
        setContent {
            App()
        }
    }
}
