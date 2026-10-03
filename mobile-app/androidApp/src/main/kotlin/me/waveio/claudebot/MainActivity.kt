package me.waveio.claudebot

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import me.waveio.claudebot.platform.AndroidBridge

class MainActivity : ComponentActivity() {
    private lateinit var bridge: AndroidBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        bridge = AndroidBridge(this)
        deliverPairingIntent(intent)
        setContent { App(bridge) }
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
