package me.waveio.blinkchat

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import me.waveio.claudebot.platform.*
import me.waveio.claudebot.state.Screen
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch

/** Real Android storage, Keystore, images and controller wired to the real protocol client. */
@RunWith(AndroidJUnit4::class)
class StandaloneSmokeTest {
    private fun fixture(): Pair<ChatStorage, TestPlatform> {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val prefix = "test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(app.cacheDir, prefix).apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("$prefix-$name", mode)
        }
        return ChatStorage(context) to TestPlatform()
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(15000) { while (!condition()) delay(25) }
    }

    @Test fun textHistorySettingsAndCredentialsSurviveRelaunch() = runBlocking {
        val (storage, platform) = fixture()
        val engine = MockEngine { request ->
            assertEquals("Bearer test-key", request.headers[HttpHeaders.Authorization])
            assertEquals("/compatible-mode/v1/chat/completions", request.url.encodedPath)
            respond("data: {\"model\":\"actual-model\",\"choices\":[{\"delta\":{\"content\":\"Hello from the API\"},\"finish_reason\":null}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val controller = withContext(Dispatchers.Main) { ChatController(platform,storage,ProviderClient(HttpClient(engine))) }
        try {
            awaitCondition { !controller.state.value.loading }
            assertEquals(Screen.Profile, controller.state.value.screen)
            withContext(Dispatchers.Main) { controller.saveProvider(ProviderSettings(),"test-key") }
            awaitCondition { controller.hasApiKey.value }
            assertEquals("test-key", storage.readApiKey())
            withContext(Dispatchers.Main) { controller.navigate(Screen.Chat); controller.draft("Hello"); controller.send() }
            awaitCondition { controller.state.value.messages.any { it.text == "Hello from the API" } && !controller.state.value.busy }
            assertEquals("actual-model",controller.state.value.messages.last().model)
            withContext(Dispatchers.Main) { controller.close() }
            val saved = storage.loadChats().single()
            assertEquals(listOf("user","assistant"),saved.messages.map { it.role })
            assertEquals("Hello",saved.messages.first().text)
            assertFalse(Json.encodeToString(saved).contains("test-key"))
            assertEquals(ProviderSettings(),storage.loadSettings())
        } finally { withContext(Dispatchers.Main) { controller.close() } }
    }

    @Test fun imagesAreSentInlineAndGenerationIsStoredLocally() = runBlocking {
        val (storage,platform) = fixture()
        storage.writeApiKey("test-key")
        val png = ByteArrayOutputStream().also { Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888).apply { eraseColor(0xff3366ff.toInt()); compress(Bitmap.CompressFormat.PNG,100,it); recycle() } }.toByteArray()
        val encoded = android.util.Base64.encodeToString(png,android.util.Base64.NO_WRAP)
        var imageSent = false
        val client = HttpClient(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("chat/completions") -> {
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    imageSent = body["messages"].toString().contains("data:image/jpeg;base64,")
                    respond("data: {\"model\":\"vision\",\"choices\":[{\"delta\":{\"content\":\"A blue square\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n",headers=headersOf(HttpHeaders.ContentType,"text/event-stream"))
                }
                else -> respond("{\"data\":[{\"b64_json\":\"$encoded\"}]}",headers=headersOf(HttpHeaders.ContentType,"application/json"))
            }
        })
        val controller = withContext(Dispatchers.Main) { ChatController(platform,storage,ProviderClient(client)) }
        try {
            awaitCondition { !controller.state.value.loading }
            platform.picked = listOf(PickedFile("square.png","image/png",png))
            withContext(Dispatchers.Main) { controller.pickFile("photo") }
            awaitCondition { controller.state.value.attachments.isNotEmpty() }
            withContext(Dispatchers.Main) { controller.draft("Describe this"); controller.send() }
            awaitCondition { !controller.state.value.busy && controller.state.value.messages.any { it.text == "A blue square" } }
            assertTrue(imageSent)
            val chatId=controller.state.value.sessionId
            withContext(Dispatchers.Main) { controller.newChat(); controller.openChat(chatId) }
            assertTrue("Sent images returned to the composer",controller.state.value.attachments.isEmpty())
            withContext(Dispatchers.Main) { controller.saveProvider(ProviderSettings(imageProtocol="openai",imageEndpoint="https://example.test/v1/images/generations"),null) }
            awaitCondition { controller.settings.value.imageProtocol == "openai" }
            withContext(Dispatchers.Main) { controller.createImage(); controller.draft("Draw a blue square"); controller.send() }
            awaitCondition { !controller.state.value.busy && controller.state.value.messages.lastOrNull()?.attachments?.isNotEmpty() == true }
            val result = controller.state.value.messages.last().attachments.single()
            assertArrayEquals(png, storage.mediaBytes(result.path))
        } finally { withContext(Dispatchers.Main) { controller.close() } }
    }

    @Test fun workspaceRejectsTraversalAndPreservesNotes() {
        val (storage,_) = fixture()
        storage.writeWorkspace("notes/today.md","A private note")
        assertEquals("A private note",storage.readWorkspace("notes/today.md"))
        assertTrue(storage.listWorkspace().contains("notes/today.md"))
        listOf("../outside.txt","/tmp/outside.txt","notes/../../escape.md").forEach { path ->
            try { storage.writeWorkspace(path,"bad"); fail("Traversal accepted") } catch (_: ProviderFailure) { }
        }
    }

    @Test fun toolRoundTripWritesOnlyInsideThePrivateWorkspace() = runBlocking {
        val (storage, platform) = fixture()
        storage.writeApiKey("test-key")
        var round = 0
        val client = HttpClient(MockEngine { request ->
            val body = (request.body as TextContent).text
            round++
            if (round == 1) {
                assertTrue(body.contains("write_file"))
                val function = buildJsonObject {
                    put("name","write_file")
                    put("arguments",buildJsonObject { put("path","notes.md"); put("content","Saved by the tool") }.toString())
                }
                val frame = buildJsonObject {
                    put("model","tool-model")
                    putJsonArray("choices") { addJsonObject {
                        putJsonObject("delta") { putJsonArray("tool_calls") { addJsonObject { put("index",0); put("id","call1"); putJsonObject("function") { function.forEach { (k,v) -> put(k,v) } } } } }
                        put("finish_reason","tool_calls")
                    } }
                }
                respond("data: $frame\n\ndata: [DONE]\n\n",headers=headersOf(HttpHeaders.ContentType,"text/event-stream"))
            } else {
                assertTrue(body.contains("tool_call_id")); assertTrue(body.contains("call1"))
                respond("data: {\"model\":\"tool-model\",\"choices\":[{\"delta\":{\"content\":\"The note is saved.\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n",headers=headersOf(HttpHeaders.ContentType,"text/event-stream"))
            }
        })
        val controller = withContext(Dispatchers.Main) { ChatController(platform,storage,ProviderClient(client)) }
        try {
            awaitCondition { !controller.state.value.loading }
            withContext(Dispatchers.Main) { controller.draft("Save a note"); controller.send() }
            awaitCondition { !controller.state.value.busy && controller.state.value.messages.lastOrNull()?.text == "The note is saved." }
            assertEquals(2,round)
            assertEquals("Saved by the tool",storage.readWorkspace("notes.md"))
            assertEquals("done",controller.state.value.messages.last().steps.single().status)
        } finally { withContext(Dispatchers.Main) { controller.close() } }
    }

    @Test fun stopThenImmediateSendRetainsTheNewRequestsHandle() = runBlocking {
        val (storage,platform) = fixture()
        storage.writeApiKey("test-key")
        val started = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val releaseNew = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val client = HttpClient(MockEngine {
            if (calls.incrementAndGet() == 1) {
                started.complete(Unit)
                try { awaitCancellation() } finally { withContext(NonCancellable) { releaseOld.await() } }
            } else {
                secondStarted.complete(Unit); releaseNew.await()
                respond("data: {\"choices\":[{\"delta\":{\"content\":\"Second reply\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n",headers=headersOf(HttpHeaders.ContentType,"text/event-stream"))
            }
        })
        val controller = withContext(Dispatchers.Main) { ChatController(platform,storage,ProviderClient(client)) }
        try {
            awaitCondition { !controller.state.value.loading }
            withContext(Dispatchers.Main) { controller.draft("First"); controller.send() }
            withTimeout(10000) { started.await() }
            withContext(Dispatchers.Main) { controller.stop(); controller.draft("Second"); controller.send() }
            withTimeout(10000) { secondStarted.await() }
            releaseOld.complete(Unit)
            delay(100)
            assertTrue("Old cleanup cleared the new request",controller.state.value.busy)
            releaseNew.complete(Unit)
            awaitCondition { !controller.state.value.busy }
            assertEquals("Second reply",controller.state.value.messages.last().text)
        } finally { releaseOld.complete(Unit); releaseNew.complete(Unit); withContext(Dispatchers.Main) { controller.close() } }
    }

    @Test fun editingAnOldMessageBranchesHistoryAndDraftsSurviveNavigation() = runBlocking {
        val (storage,platform) = fixture()
        storage.writeApiKey("test-key")
        storage.saveSettings(ProviderSettings(chatModel="new-default"))
        storage.saveChats(listOf(StoredChat("original","Original chat","qwen4-max-preview",1,listOf(
            StoredMessage("u1","user","Original question"),StoredMessage("a1","assistant","Original answer"),StoredMessage("u2","user","Later question"),StoredMessage("a2","assistant","Later answer")))))
        val client = HttpClient(MockEngine { respond("data: {\"choices\":[{\"delta\":{\"content\":\"Branched answer\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n",headers=headersOf(HttpHeaders.ContentType,"text/event-stream")) })
        val controller = withContext(Dispatchers.Main) { ChatController(platform,storage,ProviderClient(client)) }
        try {
            awaitCondition { !controller.state.value.loading }
            withContext(Dispatchers.Main) { controller.openChat("original"); controller.draft("Unsent original draft"); controller.newChat(); controller.draft("Unsent new draft"); controller.openChat("original") }
            assertEquals("Unsent original draft",controller.state.value.draft)
            assertTrue("The old chat model disappeared from the picker",controller.state.value.models.any { it.id=="qwen4-max-preview" })
            withContext(Dispatchers.Main) { controller.newChat() }
            assertEquals("Unsent new draft",controller.state.value.draft)
            withContext(Dispatchers.Main) { controller.openChat("original"); controller.editMessage("u1"); controller.draft("Edited question"); controller.send() }
            awaitCondition { !controller.state.value.busy && controller.state.value.messages.lastOrNull()?.text == "Branched answer" }
            assertNotEquals("original",controller.state.value.sessionId)
            withContext(Dispatchers.Main) { controller.close() }
            val saved = storage.loadChats()
            assertEquals(4,saved.first { it.id == "original" }.messages.size)
            assertEquals("Original question",saved.first { it.id == "original" }.messages.first().text)
            assertEquals("Edited question",saved.first { it.id != "original" }.messages.first().text)
            val branchId=saved.first { it.id != "original" }.id
            withContext(Dispatchers.Main) { controller.openChat("original"); controller.openChat(branchId) }
            assertNull("Sent edit returned to the composer",controller.state.value.editingMessageId)
        } finally { withContext(Dispatchers.Main) { controller.close() } }
    }

    @Test fun failedWorkspaceSaveRetainsDirtyRecoveryAcrossNavigation() = runBlocking {
        val (storage,platform) = fixture()
        val controller = withContext(Dispatchers.Main) { ChatController(platform,storage,ProviderClient(HttpClient(MockEngine { error("No network expected") }))) }
        try {
            awaitCondition { !controller.state.value.loading }
            withContext(Dispatchers.Main) {
                controller.editWorkspacePath("../outside.md"); controller.editWorkspace("Keep this unsaved note"); controller.saveWorkspace("../outside.md")
            }
            awaitCondition { controller.state.value.error != null }
            withContext(Dispatchers.Main) { controller.navigate(Screen.Chat); controller.navigate(Screen.Files) }
            assertTrue(controller.workspaceDirty.value)
            assertEquals("Keep this unsaved note",controller.workspaceText.value)
            withContext(Dispatchers.Main) { controller.close() }
            val restored = withContext(Dispatchers.Main) { ChatController(platform,storage,ProviderClient(HttpClient(MockEngine { error("No network expected") }))) }
            try {
                awaitCondition { !restored.state.value.loading }
                assertTrue(restored.workspaceDirty.value)
                assertEquals("Keep this unsaved note",restored.workspaceText.value)
            } finally { withContext(Dispatchers.Main) { restored.close() } }
        } finally { withContext(Dispatchers.Main) { controller.close() } }
    }

    @Test fun simultaneousToolAndEditorWritesStayAtomic() = runBlocking {
        val (storage,_) = fixture()
        val first="a".repeat(32*1024)
        val second="b".repeat(32*1024)
        coroutineScope {
            launch(Dispatchers.IO) { repeat(20) { storage.writeWorkspace("shared.md",first) } }
            launch(Dispatchers.IO) { repeat(20) { storage.writeWorkspace("shared.md",second) } }
        }
        assertTrue(storage.readWorkspace("shared.md") in listOf(first,second))
    }

    @Test fun endpointAndKeyRollbackTogetherWhenTheCommitFails() {
        val (storage,prefs)=controlledStorage()
        val original=ProviderSettings()
        storage.saveConnection(original,"old-key")
        prefs.failNext=true
        val changed=original.copy(baseUrl="https://new.example.test/v1")
        try { storage.saveConnection(changed,"new-key"); fail("Failed commit accepted") } catch (_:ProviderFailure) { }
        assertEquals(original,storage.loadSettings())
        assertEquals("old-key",storage.readApiKey())
        storage.saveConnection(changed,"new-key")
        assertEquals(changed,storage.loadSettings())
        assertEquals("new-key",storage.readApiKey())
    }

    @Test fun requestsCannotStartHalfwayThroughAConnectionSave() = runBlocking {
        val (storage,prefs)=controlledStorage()
        storage.saveConnection(ProviderSettings(),"old-key")
        val entered=CountDownLatch(1)
        val release=CountDownLatch(1)
        val calls=AtomicInteger()
        val platform=TestPlatform()
        val controller=withContext(Dispatchers.Main) { ChatController(platform,storage,ProviderClient(HttpClient(MockEngine { calls.incrementAndGet(); error("No request may run during save") }))) }
        try {
            awaitCondition { !controller.state.value.loading }
            prefs.beforeCommit={ entered.countDown(); release.await() }
            val changed=ProviderSettings(baseUrl="https://new.example.test/v1")
            withContext(Dispatchers.Main) { controller.saveProvider(changed,"new-key") }
            awaitCondition { entered.count==0L }
            withContext(Dispatchers.Main) { controller.draft("Wait for settings"); controller.send(); controller.refreshModels() }
            assertEquals(0,calls.get())
            assertTrue(controller.state.value.messages.isEmpty())
            assertEquals("standalone.error.busy",controller.state.value.error)
            release.countDown()
            awaitCondition { controller.settings.value==changed }
        } finally { release.countDown(); withContext(Dispatchers.Main) { controller.close() } }
    }

    @Test fun malformedAppearancePreferencesDoNotDisableReadableChats() = runBlocking {
        val (storage,platform)=fixture()
        storage.writeApiKey("test-key")
        platform.writePreference("preferences.v1","{broken")
        platform.writePreference("workspace.draft.v1","{keep-corrupt-recovery")
        val provider=ProviderClient(HttpClient(MockEngine { respond("data: {\"choices\":[{\"delta\":{\"content\":\"Recovered\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n",headers=headersOf(HttpHeaders.ContentType,"text/event-stream")) }))
        val controller=withContext(Dispatchers.Main) { ChatController(platform,storage,provider) }
        try {
            awaitCondition { !controller.state.value.loading }
            withContext(Dispatchers.Main) { controller.draft("Continue"); controller.send() }
            awaitCondition { !controller.state.value.busy && controller.state.value.messages.lastOrNull()?.text=="Recovered" }
        } finally { withContext(Dispatchers.Main) { controller.close() } }
        assertEquals("{keep-corrupt-recovery",platform.readPreference("workspace.draft.v1"))
        assertEquals(1,storage.loadChats().size)
    }

    private fun controlledStorage():Pair<ChatStorage,ControlledPreferences> {
        val app=ApplicationProvider.getApplicationContext<Context>()
        val prefix="controlled-${UUID.randomUUID()}"
        val preferences=mutableMapOf<String,ControlledPreferences>()
        val context=object:ContextWrapper(app) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=File(app.cacheDir,prefix).apply { mkdirs() }
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=preferences.getOrPut(name) { ControlledPreferences(app.getSharedPreferences("$prefix-$name",mode)) }
        }
        val storage=ChatStorage(context)
        return storage to preferences.getValue("chat-settings")
    }
}

private class ControlledPreferences(private val actual:SharedPreferences):SharedPreferences by actual {
    @Volatile var failNext=false
    @Volatile var beforeCommit:(()->Unit)?=null
    override fun edit():SharedPreferences.Editor {
        val editor=actual.edit()
        return object:SharedPreferences.Editor by editor {
            override fun putString(key:String,value:String?):SharedPreferences.Editor { editor.putString(key,value); return this }
            override fun remove(key:String):SharedPreferences.Editor { editor.remove(key); return this }
            override fun commit():Boolean {
                val hook=beforeCommit; beforeCommit=null; hook?.invoke()
                val result=editor.commit()
                if (failNext) { failNext=false; return false }
                return result
            }
        }
    }
}

internal class TestPlatform : PlatformBridge {
    override val platformName = "android-test"
    override val systemLanguage = "en"
    override val foreground = MutableStateFlow(true)
    override val incomingPairing = MutableStateFlow<String?>(null)
    override val reducedMotion = true
    private val preferences = mutableMapOf<String,String>()
    var picked = emptyList<PickedFile>()
    override fun readPreference(key:String) = preferences[key]
    override fun writePreference(key:String,value:String?) { if(value==null) preferences.remove(key) else preferences[key]=value }
    override fun readSecret(key:String):String? = null
    override fun writeSecret(key:String,value:String?) { }
    override fun scanQr(onResult:(String?)->Unit) = onResult(null)
    override fun pickFile(kind:String,onResult:(PickedFile?)->Unit) = onResult(picked.firstOrNull())
    override fun pickFiles(kind:String,onResult:(List<PickedFile>)->Unit) = onResult(picked)
    override fun startRecording(onAmplitude:(Float)->Unit,onResult:(PickedFile?)->Unit,onPartial:(PickedFile)->Unit) = onResult(null)
    override fun stopRecording() { }
    override fun cancelRecording() { }
    override fun haptic() { }
    override fun copyText(value:String) { }
    override fun shareText(value:String) { }
    override fun requestNotifications(onResult:(Boolean)->Unit) = onResult(false)
    override fun notifyReply(title:String,body:String,conversationId:String) { }
    override fun nowMillis() = System.currentTimeMillis()
    override fun newId() = UUID.randomUUID().toString()
}
