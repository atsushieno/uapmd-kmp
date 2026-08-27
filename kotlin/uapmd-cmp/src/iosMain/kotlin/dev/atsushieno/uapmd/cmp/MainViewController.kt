package dev.atsushieno.uapmd.cmp

import androidx.compose.ui.window.ComposeUIViewController

fun MainViewController() = ComposeUIViewController {
    initPlatformEventLoop()
    App()
}
