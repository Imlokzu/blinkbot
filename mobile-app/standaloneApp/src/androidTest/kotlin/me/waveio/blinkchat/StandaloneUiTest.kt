package me.waveio.blinkchat

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import me.waveio.claudebot.state.Screen
import me.waveio.claudebot.ui.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.UUID

/** The actual Blink composer and drawer must dispatch into the standalone controller. */
class StandaloneUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun composerSendsTextAndSettingsUseStandaloneEndpoints() {
        val app=ApplicationProvider.getApplicationContext<Context>()
        val name="ui-${UUID.randomUUID()}"
        val context=object:ContextWrapper(app) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=File(app.cacheDir,name).apply { mkdirs() }
            override fun getSharedPreferences(key:String,mode:Int)=app.getSharedPreferences("$name-$key",mode)
        }
        val storage=ChatStorage(context)
        storage.writeApiKey("test-key")
        storage.saveSettings(ProviderSettings(imageProtocol="openai",imageEndpoint="https://example.test/v1/images/generations"))
        val bitmap=android.graphics.Bitmap.createBitmap(16,16,android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        val png=ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
        bitmap.recycle()
        val encoded=android.util.Base64.encodeToString(png,android.util.Base64.NO_WRAP)
        val platform=TestPlatform()
        val provider=ProviderClient(HttpClient(MockEngine {
            if (it.url.encodedPath.endsWith("images/generations")) respond("{\"data\":[{\"b64_json\":\"$encoded\"}]}",headers=headersOf(HttpHeaders.ContentType,"application/json"))
            else respond("data: {\"model\":\"fixture-model\",\"choices\":[{\"delta\":{\"content\":\"Text answer from the API\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n",headers=headersOf(HttpHeaders.ContentType,"text/event-stream"))
        }))
        lateinit var controller:ChatController
        compose.runOnIdle { controller=ChatController(platform,storage,provider) }
        val text=runBlocking { LocaleText.load("en") }.withOverrides(mapOf("profile.models" to "API & models"))
        try {
            compose.setContent {
                val state by controller.state.collectAsState()
                MobileTheme(false) {
                    CompositionLocalProvider(LocalText provides text,LocalChatFeatures provides ChatFeatures(false,false)) {
                        MobileShell(state,controller,true) { screen -> StandaloneScreen(screen,state,controller) }
                    }
                }
            }
            compose.waitUntil(15000) { !controller.state.value.loading }
            compose.onNodeWithTag("composer-input").performTextInput("Hello from the composer")
            compose.onNodeWithContentDescription(text.get("chat.send")).performClick()
            compose.waitUntil(15000) { controller.state.value.messages.lastOrNull()?.text == "Text answer from the API" }
            compose.onNodeWithText("Text answer from the API").assertExists()
            compose.onNodeWithContentDescription(text.get("input.microphone")).assertDoesNotExist()
            compose.runOnIdle { controller.createImage() }
            compose.onNodeWithTag("composer-input").performTextInput("Draw a blue square")
            compose.onNodeWithContentDescription(text.get("chat.send")).performClick()
            compose.waitUntil(15000) { controller.state.value.messages.lastOrNull()?.attachments?.isNotEmpty() == true }
            compose.onNodeWithTag("attachment-gallery").assertExists()
            val path=controller.state.value.messages.last().attachments.single().path
            compose.onNodeWithTag("gallery:upload:$path").performClick()
            compose.waitUntil(15000) { controller.state.value.previewBytes != null }
            compose.onNodeWithText("image.png").assertExists()
            compose.runOnIdle { controller.closePreview(); controller.navigate(Screen.Models) }
            compose.onNodeWithText("API & models").assertExists()
        } finally { compose.runOnIdle { controller.close() } }
    }
}
