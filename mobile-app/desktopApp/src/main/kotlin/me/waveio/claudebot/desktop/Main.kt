package me.waveio.claudebot.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.runBlocking
import me.waveio.claudebot.App
import me.waveio.claudebot.ui.DesktopBackActions
import me.waveio.claudebot.ui.DesktopBrowserRuntime
import me.waveio.claudebot.ui.DesktopEditorExit
import me.waveio.claudebot.ui.LocaleText
import me.waveio.claudebot.state.AppController
import java.awt.Desktop
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Path
import java.nio.file.Files

fun main() {
    System.setProperty("apple.awt.application.name", "Blink")
    System.setProperty("apple.laf.useScreenMenuBar", "true")
    try {
        val resources = listOfNotNull(System.getProperty("compose.application.resources.dir"),
            System.getProperty("blink.development.resources")).firstOrNull { Files.isExecutable(Path.of(it, "blink-keychain-helper")) }
        require(!resources.isNullOrBlank())
        System.setProperty("blink.keychain.helper", Path.of(resources, "blink-keychain-helper").toString())
        System.setProperty("blink.share.helper", Path.of(resources, "blink-share-helper").toString())
        DesktopBrowserRuntime.initialize()
        val bridge = DesktopBridge()
        val titleText = runBlocking { LocaleText.load(bridge.systemLanguage) }.get("app.name")
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_URI)) {
            Desktop.getDesktop().setOpenURIHandler { event -> bridge.deliverIncomingPairing(event.uri.toString()) }
        }
        application {
            var closing by remember { mutableStateOf(false) }
            val controller = remember { arrayOfNulls<AppController>(1) }
            fun close(complete: (Boolean) -> Unit = {}) {
                if (closing) { complete(false); return }
                closing = true
                DesktopEditorExit.flush { success ->
                    closing = false
                    val durable = controller[0]?.state?.value?.fileStorageError != true
                    complete(success && durable)
                    if (success && durable) exitApplication()
                }
            }
            Window(
                onCloseRequest = { close() },
                title = titleText,
                state = rememberWindowState(width = 1100.dp, height = 800.dp),
            ) {
                DisposableEffect(window) {
                    window.minimumSize = Dimension(480, 560)
                    val listener = object : WindowAdapter() {
                        override fun windowActivated(event: WindowEvent) { bridge.setForeground(true) }
                        override fun windowDeactivated(event: WindowEvent) { bridge.setForeground(false) }
                    }
                    window.addWindowListener(listener)
                    val desktop = Desktop.getDesktop()
                    if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) desktop.setQuitHandler { _, response ->
                        javax.swing.SwingUtilities.invokeLater { close { response.cancelQuit() } }
                    }
                    onDispose { window.removeWindowListener(listener); bridge.close() }
                }
                Box(Modifier.fillMaxSize().onPreviewKeyEvent {
                    it.type == KeyEventType.KeyDown && it.key == Key.Escape && DesktopBackActions.invoke()
                }) { App(bridge, createController = { AppController(it).also { owner -> controller[0] = owner } }) }
            }
        }
    } finally { DesktopBrowserRuntime.close() }
}
