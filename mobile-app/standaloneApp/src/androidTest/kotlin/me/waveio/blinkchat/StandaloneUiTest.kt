package me.waveio.blinkchat

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.*
import me.waveio.claudebot.state.Screen
import me.waveio.claudebot.ui.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
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

    @Test fun addsAnotherProviderAndChoosesIndependentDefaultModels() {
        val app=ApplicationProvider.getApplicationContext<Context>()
        val name="ui-providers-${UUID.randomUUID()}"
        val context=object:ContextWrapper(app) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=File(app.cacheDir,name).apply { mkdirs() }
            override fun getSharedPreferences(key:String,mode:Int)=app.getSharedPreferences("$name-$key",mode)
        }
        val storage=ChatStorage(context)
        storage.writeApiKey("original-key")
        val hosts=java.util.Collections.synchronizedSet(mutableSetOf<String>())
        lateinit var controller:ChatController
        compose.runOnIdle { controller=ChatController(TestPlatform(),storage,ProviderClient(HttpClient(MockEngine { request ->
            // Connect may only probe the typed endpoint; nothing here generates a reply.
            hosts+=request.url.host
            val path=request.url.encodedPath
            when {
                request.method==HttpMethod.Get && path=="/v1/models" && request.headers[HttpHeaders.Authorization]=="Bearer other-key" ->
                    respond(DISCOVERY_CATALOG,headers=headersOf(HttpHeaders.ContentType,"application/json"))
                request.method==HttpMethod.Post && (path=="/v1/chat/completions" || path=="/v1/images/generations") ->
                    respond("{\"error\":{\"message\":\"model is required\"}}",HttpStatusCode.BadRequest,headersOf(HttpHeaders.ContentType,"application/json"))
                else -> respond("",HttpStatusCode.NotFound)
            }
        }))) }
        val overrides=app.assets.open("locales/en.json").use { Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject.mapValues { entry -> entry.value.jsonPrimitive.content } }
        val text=runBlocking { LocaleText.load("en") }.withOverrides(overrides)
        try {
            compose.setContent {
                val state by controller.state.collectAsState()
                MobileTheme(false) { CompositionLocalProvider(LocalText provides text,LocalChatFeatures provides ChatFeatures(false,false)) {
                    MobileShell(state,controller,true) { screen -> StandaloneScreen(screen,state,controller) }
                } }
            }
            compose.waitUntil(15000) { !controller.state.value.loading }
            compose.runOnIdle { controller.navigate(Screen.Models) }
            compose.onNodeWithTag("provider-add").performScrollTo().performClick()
            fun edit(tag:String,value:String) {
                compose.onNodeWithTag(tag).performScrollTo()
                compose.onNode(hasSetTextAction() and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag)))).performTextReplacement(value)
            }
            edit("provider-base-url","https://other.example.test/v1")
            edit("provider-api-key","other-key")
            compose.onNodeWithTag("provider-connect").performScrollTo().performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("provider-found").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(setOf("other.example.test"),hosts.toSet())
            // Chat Completions answered the probe; Anthropic and Responses did not.
            compose.onNodeWithTag("provider-api-openai").performScrollTo().assertIsSelected()
            edit("provider-name","Another API")
            // Done on the name field closes the keyboard so the captures show the whole form.
            compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("provider-name"))).performImeAction()
            for ((index,tag) in listOf("provider-connect","provider-api-responses","provider-save").withIndex()) {
                compose.onNodeWithTag(tag).performScrollTo(); compose.waitForIdle()
                val shot=compose.onRoot().captureToImage().asAndroidBitmap()
                File(app.cacheDir,"provider-setup-$index.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            }
            compose.onNodeWithTag("provider-save").performScrollTo().performClick()
            compose.waitUntil(15000) { controller.providerBook.value.providers.size==2 }
            compose.onNodeWithText("Another API").assertExists()
            val other=controller.providerBook.value.providers.single { it.name=="Another API" }
            assertEquals(ApiLayer.OPENAI_CHAT,other.settings.api)
            assertEquals(listOf("custom-chat","custom-fast"),other.chatModels)
            assertEquals(listOf("custom-image"),other.imageModels)
            assertEquals(ImageLayer.OPENAI,other.settings.imageProtocol)
            assertEquals("https://other.example.test/v1/images/generations",other.settings.imageEndpoint)
            assertEquals(listOf("low","medium","high"),other.info("custom-fast")!!.efforts)
            assertEquals(200_000L,other.info("custom-chat")!!.context)
            assertEquals("other-key",storage.readProviderApiKey(other.id))
            assertEquals("original-key",storage.readProviderApiKey(LEGACY_PROVIDER_ID))
            compose.onNodeWithText(text.get("standalone.models.defaultChat")).performScrollTo().performClick()
            compose.onNodeWithText("Custom Fast").performClick()
            compose.waitUntil(15000) { controller.providerBook.value.defaultChat==ModelSelection(other.id,"custom-fast") }
            assertEquals(LEGACY_PROVIDER_ID,controller.providerBook.value.defaultImage?.providerId)
            compose.onNodeWithText(text.get("standalone.models.defaultImage")).performScrollTo().performClick()
            compose.onNodeWithText("custom-image").performClick()
            compose.waitUntil(15000) { controller.providerBook.value.defaultImage==ModelSelection(other.id,"custom-image") }
            compose.runOnIdle { controller.dismissNotice() }
            val screenshot=compose.onRoot().captureToImage().asAndroidBitmap()
            File(app.cacheDir,"multi-provider-settings.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            compose.onNodeWithText("Another API").performScrollTo().performClick()
            compose.onNodeWithTag("provider-api-key").performScrollTo()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("")))
            edit("provider-name","Unsaved provider name")
            compose.onNodeWithContentDescription(text.get("nav.back")).performClick()
            compose.onNodeWithText(text.get("standalone.models.discardTitle")).assertExists()
            compose.onNodeWithText(text.get("standalone.models.keepEditing")).performClick()
            compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("provider-name"))).assertTextEquals("Unsaved provider name")
            val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitForIdle()
            // The first Back may close the IME before Android delivers navigation Back.
            if (compose.onAllNodesWithText(text.get("standalone.models.discardTitle")).fetchSemanticsNodes().isEmpty())
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.onNodeWithText(text.get("standalone.models.discardTitle")).assertExists()
            compose.onNodeWithText(text.get("standalone.models.discard")).performClick()
            compose.onNodeWithTag("provider-add").assertExists()
            assertEquals(Screen.Models,controller.state.value.screen)
            assertEquals("Another API",controller.providerBook.value.account(other.id)!!.name)
        } finally { compose.runOnIdle { controller.close() } }
    }

    @Test fun discoveryFinishingDuringAnEditKeepsBothTheEditAndTheNewModels() {
        val app=ApplicationProvider.getApplicationContext<Context>()
        val name="ui-discovery-${UUID.randomUUID()}"
        val context=object:ContextWrapper(app) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=File(app.cacheDir,name).apply { mkdirs() }
            override fun getSharedPreferences(key:String,mode:Int)=app.getSharedPreferences("$name-$key",mode)
        }
        val storage=ChatStorage(context)
        storage.writeApiKey("discovery-key")
        val release=CompletableDeferred<Unit>()
        lateinit var controller:ChatController
        compose.runOnIdle { controller=ChatController(TestPlatform(),storage,ProviderClient(HttpClient(MockEngine {
            release.await()
            respond("{\"data\":[{\"id\":\"discovered-model\",\"context_length\":32000}]}",headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }))) }
        val overrides=app.assets.open("locales/en.json").use { Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject.mapValues { entry -> entry.value.jsonPrimitive.content } }
        val text=runBlocking { LocaleText.load("en") }.withOverrides(overrides)
        try {
            compose.setContent {
                val state by controller.state.collectAsState()
                MobileTheme(false) { CompositionLocalProvider(LocalText provides text) {
                    MobileShell(state,controller,true) { screen -> StandaloneScreen(screen,state,controller) }
                } }
            }
            compose.waitUntil(15000) { !controller.state.value.loading }
            compose.runOnIdle { controller.navigate(Screen.Models) }
            val account=controller.providerBook.value.providers.single()
            compose.onNodeWithText(account.name).performScrollTo().performClick()
            // A background catalog refresh (for example from the chat screen) lands mid-edit.
            compose.runOnIdle { controller.refreshProviderModels(account.id) }
            compose.waitUntil(15000) { controller.state.value.modelsLoading }
            compose.onNodeWithTag("provider-name").performScrollTo()
            compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("provider-name"))).performTextReplacement("Edited while loading")
            release.complete(Unit)
            compose.waitUntil(15000) { !controller.state.value.modelsLoading }
            compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("provider-name"))).assertTextEquals("Edited while loading")
            compose.onNodeWithTag("provider-save").performScrollTo().performClick()
            compose.waitUntil(15000) { controller.providerBook.value.account(account.id)?.name=="Edited while loading" }
            val saved=controller.providerBook.value.account(account.id)!!
            assertTrue(saved.chatModels.contains("discovered-model"))
            assertEquals(32_000L,saved.info("discovered-model")?.context)
        } finally { release.complete(Unit);compose.runOnIdle { controller.close() } }
    }

    @Test fun chatPickerLearnsEffortLevelsFromAnEndpointWithoutACatalogList() {
        val app=ApplicationProvider.getApplicationContext<Context>()
        val name="ui-effort-${UUID.randomUUID()}"
        val context=object:ContextWrapper(app) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=File(app.cacheDir,name).apply { mkdirs() }
            override fun getSharedPreferences(key:String,mode:Int)=app.getSharedPreferences("$name-$key",mode)
        }
        val storage=ChatStorage(context)
        storage.writeApiKey("effort-key")
        val sentEfforts=java.util.Collections.synchronizedList(mutableListOf<String>())
        lateinit var controller:ChatController
        compose.runOnIdle { controller=ChatController(TestPlatform(),storage,ProviderClient(HttpClient(MockEngine { request ->
            val body=Json.parseToJsonElement((request.body as io.ktor.http.content.TextContent).text).jsonObject
            val effort=body["reasoning_effort"]?.jsonPrimitive?.content
            if (effort==ProviderClient.EFFORT_PROBE)
                // QwenCloud's real rejection; its catalog publishes no effort levels at all.
                respond("{\"error\":{\"message\":\"'reasoning_effort' must be one of: 'none', 'minimal', 'low', 'medium', 'high', 'xhigh'\"}}",HttpStatusCode.BadRequest,headersOf(HttpHeaders.ContentType,"application/json"))
            else {
                sentEfforts+=effort.orEmpty()
                respond("data: {\"choices\":[{\"delta\":{\"content\":\"391\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n",headers=headersOf(HttpHeaders.ContentType,"text/event-stream"))
            }
        }))) }
        val text=runBlocking { LocaleText.load("en") }
        try {
            compose.setContent {
                val state by controller.state.collectAsState()
                MobileTheme(false) { CompositionLocalProvider(LocalText provides text,LocalChatFeatures provides ChatFeatures(false,false)) {
                    MobileShell(state,controller,true) { screen -> StandaloneScreen(screen,state,controller) }
                } }
            }
            compose.waitUntil(15000) { !controller.state.value.loading }
            compose.runOnIdle { controller.navigate(Screen.Chat); controller.modelPicker(true) }
            compose.waitUntil(15000) { controller.state.value.models.firstOrNull { it.id==controller.state.value.selectedModel }?.efforts?.isNotEmpty()==true }
            val row=controller.state.value.models.single { it.id==controller.state.value.selectedModel }
            assertEquals(listOf("off","minimal","low","medium","high","xhigh"),row.efforts)
            // The slider shows the provider default instead of "unavailable".
            compose.onNodeWithText(text.get("model.medium")).assertExists()
            assertTrue(compose.onAllNodesWithText(text.get("model.effortUnavailable")).fetchSemanticsNodes().isEmpty())
            val saved=storage.loadProviderBook().providers.single()
            assertEquals(EffortSource.PROBE,saved.info(saved.settings.chatModel)!!.effortSource)
            compose.runOnIdle { controller.selectEffort("off"); controller.modelPicker(false) }
            assertEquals("off",controller.state.value.effort)
            compose.onNodeWithTag("composer-input").performTextInput("17*23")
            compose.onNodeWithContentDescription(text.get("chat.send")).performClick()
            compose.waitUntil(15000) { controller.state.value.messages.lastOrNull()?.text=="391" }
            assertEquals(listOf("none"),sentEfforts.toList())
        } finally { compose.runOnIdle { controller.close() } }
    }

    private companion object {
        /** An OpenRouter-shaped catalog: one reasoning model, one plain chat model, one image model. */
        const val DISCOVERY_CATALOG = """{"data":[
            {"id":"custom-chat","name":"custom-chat","context_length":200000,"architecture":{"input_modalities":["text","image"],"output_modalities":["text"]}},
            {"id":"custom-fast","name":"Custom Fast","context_length":64000,"supported_parameters":["reasoning","tools"]},
            {"id":"custom-image","architecture":{"output_modalities":["image"]}}
        ]}"""
    }
}
