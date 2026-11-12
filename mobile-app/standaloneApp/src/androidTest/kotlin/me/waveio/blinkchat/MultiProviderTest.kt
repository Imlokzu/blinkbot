package me.waveio.blinkchat

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MultiProviderTest {
    private val first = ProviderAccount(LEGACY_PROVIDER_ID,"First API",ProviderSettings(baseUrl="https://first.example.test/v1",chatModel="shared-model",imageModel="first-image",imageEndpoint="https://first.example.test/v1/images/generations",imageProtocol="openai"))
    private val second = ProviderAccount("second","Second API",ProviderSettings(baseUrl="https://second.example.test/v1",chatModel="shared-model",imageModel="second-image",imageEndpoint="https://second.example.test/v1/images/generations",imageProtocol="openai"))

    private fun storage():ChatStorage {
        val app=ApplicationProvider.getApplicationContext<Context>()
        val name="multi-${UUID.randomUUID()}"
        val context=object:ContextWrapper(app) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=File(app.cacheDir,name).apply { mkdirs() }
            override fun getSharedPreferences(key:String,mode:Int)=app.getSharedPreferences("$name-$key",mode)
        }
        return ChatStorage(context).also {
            it.saveProviderBook(ProviderBook(listOf(first,second),ModelSelection(first.id,"shared-model"),ModelSelection(second.id,"second-image")),
                mapOf(first.id to "first-key",second.id to "second-key"))
        }
    }
    private suspend fun awaitCondition(condition:()->Boolean) { withTimeout(15000) { while (!condition()) delay(20) } }
    private fun answer(text:String)="data: {\"model\":\"shared-model\",\"choices\":[{\"delta\":{\"content\":\"$text\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
    private val sse=headersOf(HttpHeaders.ContentType,"text/event-stream")
    /** Picking a chat model probes its effort levels; those unbilled checks are not chat turns. */
    private fun isEffortProbe(request:io.ktor.client.request.HttpRequestData)=
        (request.body as? TextContent)?.text?.contains(ProviderClient.EFFORT_PROBE)==true
    private val probeRejection="{\"error\":{\"message\":\"'reasoning_effort' must be one of: 'low', 'medium', 'high'\"}}"
    private val probeHeaders=headersOf(HttpHeaders.ContentType,"application/json")

    @Test fun duplicateModelNamesRouteToTheirOwnKeysAndExistingChatsIgnoreNewDefaults() = runBlocking {
        val storage=storage()
        val calls=mutableListOf<Pair<String,String?>>()
        val platform=TestPlatform()
        fun client()=ProviderClient(HttpClient(MockEngine { request ->
            calls += request.url.host to request.headers[HttpHeaders.Authorization]
            val body=Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("shared-model",body["model"]!!.jsonPrimitive.content)
            assertFalse(body["model"]!!.jsonPrimitive.content.startsWith("chat:"))
            respond(answer("Reply ${calls.size}"),headers=sse)
        }))
        var controller=withContext(Dispatchers.Main) { ChatController(platform,storage,client()) }
        try {
            awaitCondition { !controller.state.value.loading }
            val models=controller.state.value.models.filter { it.label=="shared-model" }
            assertEquals(2,models.size); assertEquals(2,models.map { it.id }.distinct().size)
            withContext(Dispatchers.Main) { controller.draft("Use first"); controller.send() }
            awaitCondition { !controller.state.value.busy && controller.state.value.messages.size==2 }
            val firstChat=controller.state.value.sessionId
            withContext(Dispatchers.Main) { controller.setDefaultModel(ModelSelection(second.id,"shared-model"),false) }
            awaitCondition { controller.providerBook.value.defaultChat.providerId==second.id }
            withContext(Dispatchers.Main) { controller.draft("Still first"); controller.send() }
            awaitCondition { !controller.state.value.busy && controller.state.value.messages.size==4 }
            withContext(Dispatchers.Main) { controller.newChat(); controller.draft("Use second"); controller.send() }
            awaitCondition { !controller.state.value.busy && controller.state.value.messages.size==2 }
            assertEquals(listOf("first.example.test" to "Bearer first-key","first.example.test" to "Bearer first-key","second.example.test" to "Bearer second-key"),calls)
            withContext(Dispatchers.Main) { controller.close() }
            controller=withContext(Dispatchers.Main) { ChatController(platform,storage,client()) }
            awaitCondition { !controller.state.value.loading }
            assertEquals(second.id,controller.providerBook.value.defaultChat.providerId)
            withContext(Dispatchers.Main) { controller.openChat(firstChat); controller.draft("After restart"); controller.send() }
            awaitCondition { !controller.state.value.busy && controller.state.value.messages.size==6 }
            assertEquals("first.example.test" to "Bearer first-key",calls.last())
        } finally { withContext(Dispatchers.Main) { controller.close() } }
    }

    @Test fun pickerChoiceAndImageToolCanUseDifferentProviders() = runBlocking {
        val storage=storage()
        val calls=mutableListOf<Pair<String,String?>>()
        val bytes=ByteArrayOutputStream().also { Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888).apply { compress(Bitmap.CompressFormat.PNG,100,it); recycle() } }.toByteArray()
        val encoded=android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)
        var textRound=0
        val probes=mutableListOf<Pair<String,String?>>()
        val controller=withContext(Dispatchers.Main) { ChatController(TestPlatform(),storage,ProviderClient(HttpClient(MockEngine { request ->
            if (isEffortProbe(request)) {
                probes += request.url.host to request.headers[HttpHeaders.Authorization]
                return@MockEngine respond(probeRejection,HttpStatusCode.BadRequest,probeHeaders)
            }
            calls += request.url.host to request.headers[HttpHeaders.Authorization]
            if (request.url.encodedPath.endsWith("images/generations")) {
                val body=Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertEquals("second-image",body["model"]!!.jsonPrimitive.content)
                respond("{\"data\":[{\"b64_json\":\"$encoded\"}]}",headers=headersOf(HttpHeaders.ContentType,"application/json"))
            } else if (++textRound==1) {
                val chunk=buildJsonObject { putJsonArray("choices") { addJsonObject { put("finish_reason","tool_calls");putJsonObject("delta") { putJsonArray("tool_calls") { addJsonObject { put("index",0);put("id","draw1");putJsonObject("function") { put("name","generate_image");put("arguments","{\"prompt\":\"A blue square\"}") } } } } } } }
                respond("data: $chunk\n\ndata: [DONE]\n\n",headers=sse)
            } else respond(answer("Image created"),headers=sse)
        }))) }
        try {
            awaitCondition { !controller.state.value.loading }
            withContext(Dispatchers.Main) { controller.draft("Draw a square using the tool"); controller.send() }
            awaitCondition { !controller.state.value.busy && controller.state.value.messages.lastOrNull()?.text=="Image created" }
            assertEquals(listOf("first.example.test" to "Bearer first-key","second.example.test" to "Bearer second-key","first.example.test" to "Bearer first-key"),calls)
            assertTrue(controller.state.value.messages.last().attachments.isNotEmpty())
            withContext(Dispatchers.Main) { controller.selectModel(ModelSelection(second.id,"shared-model").pickerId()); controller.draft("Now second"); controller.send() }
            awaitCondition { !controller.state.value.busy && calls.size==4 }
            assertEquals("second.example.test" to "Bearer second-key",calls.last())
            // The probe for the newly picked model went only to that model's own provider.
            assertEquals(listOf("second.example.test" to "Bearer second-key"),probes)
        } finally { withContext(Dispatchers.Main) { controller.close() } }
    }

    @Test fun deletingProviderRetainsHistoryWithoutSendingItsKeyToAnotherService() = runBlocking {
        val storage=storage()
        var requests=0
        val controller=withContext(Dispatchers.Main) { ChatController(TestPlatform(),storage,ProviderClient(HttpClient(MockEngine { request ->
            if (isEffortProbe(request)) return@MockEngine respond(probeRejection,HttpStatusCode.BadRequest,probeHeaders)
            requests++;respond(answer("Saved reply"),headers=sse)
        }))) }
        try {
            awaitCondition { !controller.state.value.loading }
            withContext(Dispatchers.Main) { controller.selectModel(ModelSelection(second.id,"shared-model").pickerId());controller.draft("Keep this history");controller.send() }
            awaitCondition { !controller.state.value.busy && controller.state.value.messages.size==2 }
            withContext(Dispatchers.Main) { controller.deleteProvider(second.id) }
            awaitCondition { controller.providerBook.value.providers.size==1 }
            assertNull(storage.readProviderApiKey(second.id))
            assertEquals("first-key",storage.readProviderApiKey(first.id))
            withContext(Dispatchers.Main) { controller.draft("Do not reroute");controller.send() }
            assertEquals(1,requests)
            assertEquals("standalone.providers.missing",controller.state.value.error)
            assertEquals(2,controller.state.value.messages.size)
            withContext(Dispatchers.Main) { controller.selectModel(ModelSelection(first.id,"shared-model").pickerId());controller.send() }
            awaitCondition { !controller.state.value.busy && controller.state.value.messages.size==4 }
            assertEquals(2,requests)
        } finally { withContext(Dispatchers.Main) { controller.close() } }
    }

    @Test fun discoveryChangesOnlyTheRequestedProviderAndDefaultsSurviveRestart() = runBlocking {
        val storage=storage()
        val controller=withContext(Dispatchers.Main) { ChatController(TestPlatform(),storage,ProviderClient(HttpClient(MockEngine {
            assertEquals("second.example.test",it.url.host)
            assertEquals("Bearer second-key",it.headers[HttpHeaders.Authorization])
            respond("{\"data\":[{\"id\":\"new-model\"},{\"id\":\"shared-model\"}]}",headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }))) }
        try {
            awaitCondition { !controller.state.value.loading }
            withContext(Dispatchers.Main) { controller.refreshProviderModels(second.id) }
            awaitCondition { controller.providerBook.value.account(second.id)?.chatModels?.contains("new-model")==true }
            assertFalse(controller.providerBook.value.account(first.id)!!.chatModels.contains("new-model"))
            assertEquals(first.id,controller.providerBook.value.defaultChat.providerId)
            withContext(Dispatchers.Main) { controller.setDefaultModel(ModelSelection(second.id,"new-model"),false) }
            awaitCondition { controller.providerBook.value.defaultChat.modelId=="new-model" }
            assertEquals(ModelSelection(second.id,"new-model"),storage.loadProviderBook().defaultChat)
            withContext(Dispatchers.Main) { controller.savePersonalization("Brief answers"); controller.setToolsEnabled(false) }
            awaitCondition { controller.providerBook.value.systemPrompt=="Brief answers" && !controller.providerBook.value.toolsEnabled }
            assertEquals(ModelSelection(second.id,"new-model"),storage.loadProviderBook().defaultChat)
            assertEquals(ModelSelection(second.id,"second-image"),storage.loadProviderBook().defaultImage)
        } finally { withContext(Dispatchers.Main) { controller.close() } }
    }
}
