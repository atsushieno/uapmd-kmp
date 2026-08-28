package dev.atsushieno.uapmd.cmp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Dev hooks, the Android counterpart of the JVM -Duapmd.cmp.* system properties.
        // Read before setContent so UapmdHost.start() sees them:
        //   adb shell am start -n <pkg>/.MainActivity --ei uapmd.cmp.addTracks 3
        androidStartupAddTracks = intent?.getIntExtra("uapmd.cmp.addTracks", 0) ?: 0
        androidStartupImportPath = intent?.getStringExtra("uapmd.cmp.import")
        androidStartupInstantiateFormat = intent?.getStringExtra("uapmd.cmp.instantiate")
        androidStartupInstantiateCount = intent?.getIntExtra("uapmd.cmp.instantiateCount", 1) ?: 1
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
