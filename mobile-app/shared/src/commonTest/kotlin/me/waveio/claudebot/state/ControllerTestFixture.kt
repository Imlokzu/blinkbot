package me.waveio.claudebot.state

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.waveio.claudebot.data.BotApi
import me.waveio.claudebot.platform.PickedFile
import me.waveio.claudebot.platform.PlatformBridge

/** A silent native boundary. Retained callbacks intentionally model late OS results. */
internal class FakePlatformBridge : PlatformBridge {
    override val platformName = "android"
    override var appVersionCode: Int = 0
    override var appVersionName: String = "0.0.0"
    override val systemLanguage = "en"
    override val reducedMotion = true
    override val foreground = MutableStateFlow(true)
    override val incomingPairing = MutableStateFlow<String?>(null)
    val preferences = mutableMapOf("device_id" to "owner_old", "server" to "https://old.example")
    val secrets = mutableMapOf("device_token" to "old-device-token")
    var qrResult: ((String?) -> Unit)? = null
    var pickedResult: ((PickedFile?) -> Unit)? = null
    var recordingResult: ((PickedFile?) -> Unit)? = null
    var recordingPartial: ((PickedFile) -> Unit)? = null
    var recordingAmplitude: ((Float) -> Unit)? = null
    val notifications = mutableListOf<Triple<String, String, String>>()
    val openedLinks = mutableListOf<String>()
    val copiedTexts = mutableListOf<String>()
    var failCopy: Boolean = false
    val savedFiles = mutableListOf<PickedFile>()
    var deferFileSave: Boolean = false
    var saveFileResult: ((Boolean) -> Unit)? = null
    val installedPackages = mutableListOf<PickedFile>()
    var packageDigest: String? = null
    var packageAccepted: Boolean = true
    var beforePreferenceWrite: ((String, String?) -> Unit)? = null
    private var nextId = 0

    override fun readPreference(key: String): String? = preferences[key]
    override fun writePreference(key: String, value: String?) {
        beforePreferenceWrite?.invoke(key, value)
        if (value == null) preferences.remove(key) else preferences[key] = value
    }
    override fun readSecret(key: String): String? = secrets[key]
    override fun writeSecret(key: String, value: String?) { if (value == null) secrets.remove(key) else secrets[key] = value }
    override fun scanQr(onResult: (String?) -> Unit) { qrResult = onResult }
    override fun pickFile(kind: String, onResult: (PickedFile?) -> Unit) { pickedResult = onResult }
    override fun startRecording(onAmplitude: (Float) -> Unit, onResult: (PickedFile?) -> Unit, onPartial: (PickedFile) -> Unit) {
        recordingAmplitude = onAmplitude; recordingResult = onResult; recordingPartial = onPartial
    }
    override fun stopRecording() = Unit
    override fun cancelRecording() = Unit
    override fun haptic() = Unit
    override fun copyText(value: String) {
        if (failCopy) error("Clipboard unavailable")
        copiedTexts += value
    }
    override fun shareText(value: String) = Unit
    override fun saveFile(file: PickedFile, onResult: (Boolean) -> Unit) {
        savedFiles += file
        saveFileResult = onResult
        if (!deferFileSave) onResult(false)
    }
    override fun openExternalUrl(url: String) { openedLinks += url }
    override fun sha256(bytes: ByteArray): String? = packageDigest
    override fun installPackage(file: PickedFile, onResult: (Boolean) -> Unit) { installedPackages += file; onResult(packageAccepted) }
    override fun requestNotifications(onResult: (Boolean) -> Unit) { onResult(true) }
    override fun notifyReply(title: String, body: String, conversationId: String) { notifications += Triple(title, body, conversationId) }
    override fun nowMillis(): Long = 1_791_043_200_000
    override fun newId(): String = "local_${++nextId}"
}

internal data class RecordedRequest(val host: String, val path: String, val method: HttpMethod, val bearer: String?, val body: JsonObject?)

/** Every request uses a MockEngine on the same virtual-time dispatcher as the controller. */
internal class ControllerTestFixture(testDispatcher: CoroutineDispatcher, val bridge: FakePlatformBridge = FakePlatformBridge()) {
    val requests = mutableListOf<RecordedRequest>()
    var handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData? = { null }
    private val clients = mutableListOf<HttpClient>()
    val controller = AppController(bridge, makeApi = { server, token ->
        val engine = MockEngine(MockEngineConfig().apply {
            this.dispatcher = testDispatcher
            addHandler { request ->
                requests += RecordedRequest(request.url.host, request.url.encodedPath, request.method,
                    request.headers[HttpHeaders.Authorization], (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it) as? JsonObject })
                handler(request) ?: defaultResponse(request)
            }
        })
        val client = HttpClient(engine)
        clients += client
        BotApi(server, token, client)
    }, dispatcher = testDispatcher)

    fun pairNewOwner() {
        controller.connect()
        bridge.qrResult?.invoke("claudebot://pair?server=https%3A%2F%2Fnew.example&code=one-time-code")
    }

    fun close() { controller.close(); clients.forEach { it.close() } }

    private fun MockRequestHandleScope.defaultResponse(request: HttpRequestData): HttpResponseData = when {
        request.url.encodedPath == "/api/mobile/pair/exchange" -> jsonResponse("""{"token":"new-device-token","device_id":"owner_new","expires_at":1791129600}""")
        request.url.encodedPath == "/api/mobile/capabilities" -> jsonResponse("""{"steer":false,"queue":true,"event_replay":true}""")
        request.url.encodedPath == "/api/brain/models" -> jsonResponse("""{"models":[{"id":"regolo/model","label":"Model","provider":"regolo","available":true}],"selected":"regolo/model","thinking_levels":["off","low"]}""")
        request.url.encodedPath == "/api/sessions" -> jsonResponse("""{"sessions":[{"id":"chat_1","title":"Chat","updated":1791043200}]}""")
        request.url.encodedPath.startsWith("/api/sessions/") -> jsonResponse("""{"id":"chat_1","messages":[]}""")
        request.url.encodedPath == "/api/setup" -> jsonResponse("""{"profile":{"name":"Bot","language":"en","persona":"calm","persona_custom":"","greeting":""}}""")
        request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Get -> jsonResponse("""{"messages":[]}""")
        request.url.encodedPath.endsWith("/events") -> sseResponse(": keepalive\n\n")
        request.url.encodedPath == "/api/workspace/list" -> jsonResponse("""{"entries":[]}""")
        else -> error("Unconfigured fake route: ${request.method.value} ${request.url.encodedPath}")
    }
}

internal fun MockRequestHandleScope.jsonResponse(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

internal fun MockRequestHandleScope.sseResponse(body: String): HttpResponseData =
    respond(body, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
