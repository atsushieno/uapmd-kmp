package dev.atsushieno.uapmd.cmp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.atsushieno.uapmd.JniBridge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Dev hooks, the Android counterpart of the JVM -Duapmd.cmp.* properties.
        // Read before setContent so UapmdHost.start() sees them:
        //   adb shell am start -n <pkg>/.MainActivity --ei uapmd.cmp.addTracks 3
        androidStartupAddTracks = intent?.getIntExtra("uapmd.cmp.addTracks", 0) ?: 0
        androidStartupImportPath = intent?.getStringExtra("uapmd.cmp.import")
        androidStartupInstantiateFormat = intent?.getStringExtra("uapmd.cmp.instantiate")
        androidStartupInstantiateCount = intent?.getIntExtra("uapmd.cmp.instantiateCount", 1) ?: 1
        androidStartupLoadProject = intent?.getStringExtra("uapmd.cmp.loadProject")
        androidStartupSaveProject = intent?.getStringExtra("uapmd.cmp.saveProject")
        androidStartupForceRescan = intent?.getBooleanExtra("uapmd.cmp.forceRescan", false) ?: false
        androidStartupLoadCount = intent?.getIntExtra("uapmd.cmp.loadCount", 1) ?: 1
        androidStartupPlaySeconds = intent?.getIntExtra("uapmd.cmp.playSeconds", 0) ?: 0
        androidStartupNoPoll = intent?.getBooleanExtra("uapmd.cmp.noPoll", false) ?: false
        androidStartupRenderPath = intent?.getStringExtra("uapmd.cmp.renderTo")
        androidStartupBufferSize = intent?.getIntExtra("uapmd.cmp.bufferSize", 0) ?: 0
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Idempotent, and UapmdHost.start() does it too; doing it here as well
        // keeps it on the Android main thread at the earliest possible point.
        initPlatformEventLoop()
        // The document provider needs the Activity before any pick can run, and
        // its SAF result comes back through onActivityResult below.
        JniBridge.uapmdDocumentProviderInit(this)
        AndroidDocumentPicker.init()
        // Lets a freeze be inspected even when the main looper is stuck.
        StackDumpTrigger.start(this)
        setContent {
            App()
        }
    }

    @Deprecated("SAF results for the uapmd document provider arrive here.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        JniBridge.uapmdDocumentProviderOnActivityResult(requestCode, resultCode, data)
    }
}
