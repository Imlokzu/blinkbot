package me.waveio.claudebot

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import me.waveio.claudebot.platform.AndroidBridge

class MainActivity : ComponentActivity() {
    private lateinit var bridge: AndroidBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            // Vote for smooth animation on adaptive-refresh devices. Android
            // remains responsible for power policy and the actual display rate.
            window.decorView.setRequestedFrameRate(android.view.View.REQUESTED_FRAME_RATE_CATEGORY_HIGH)
        }
        bridge = AndroidBridge(this)
        deliverPairingIntent(intent)
        setContent { App(bridge, onSystemBarAppearance = ::systemBarAppearance) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deliverPairingIntent(intent)
    }

    private fun deliverPairingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.dataString?.let(bridge::deliverIncomingPairing)
    }
}

/** Keep system icons readable when the app theme differs from the phone theme. */
internal fun ComponentActivity.systemBarAppearance(darkStatusIcons: Boolean, darkNavigationIcons: Boolean) {
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = darkStatusIcons
        isAppearanceLightNavigationBars = darkNavigationIcons
    }
}
