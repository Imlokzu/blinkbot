@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package me.waveio.claudebot.state

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.waveio.claudebot.data.BotApi
import me.waveio.claudebot.platform.PlatformBridge
import kotlin.test.*

/** Startup must preload real data without flashing login or serializing network latency. */
class ControllerStartupRegressionTest {
    private val corePaths = setOf("/api/mobile/capabilities", "/api/brain/models", "/api/sessions")

    @Test fun savedConnectionPublishesLoadingBeforeAnyCoroutineRuns() = runTest {
        val f = ControllerTestFixture(StandardTestDispatcher(testScheduler))
        try {
            assertTrue(f.controller.state.value.initializing)
            assertTrue(f.controller.state.value.connecting)
            assertFalse(f.controller.state.value.connected)
            assertTrue(f.requests.isEmpty())
            runCurrent()
            assertTrue(f.controller.state.value.connected)
            assertFalse(f.controller.state.value.initializing)
            assertEquals("chat_1", f.controller.state.value.conversations.single().id)
            assertEquals("regolo/model", f.controller.state.value.models.single().id)
            corePaths.forEach { path -> assertEquals(1, f.requests.count { it.path == path }, path) }
        } finally { f.close() }
    }

    @Test fun unpairedPhoneKeepsLoginAndMakesNoStartupRequests() = runTest {
        val f = ControllerTestFixture(StandardTestDispatcher(testScheduler), FakePlatformBridge().apply { secrets.clear() })
        try {
            runCurrent()
            assertFalse(f.controller.state.value.initializing)
            assertFalse(f.controller.state.value.connecting)
            assertTrue(f.requests.isEmpty())
        } finally { f.close() }
    }

    @Test fun allCoreReadsStartTogetherAndChatWaitsForTheLastOne() = runTest {
        val f = ControllerTestFixture(StandardTestDispatcher(testScheduler))
        val gates = corePaths.associateWith { CompletableDeferred<Unit>() }
        try {
            f.handler = { request -> gates[request.url.encodedPath]?.await(); null }
            runCurrent()
            assertEquals(corePaths, f.requests.map { it.path }.toSet())
            gates.getValue("/api/mobile/capabilities").complete(Unit)
            gates.getValue("/api/brain/models").complete(Unit)
            runCurrent()
            assertTrue(f.controller.state.value.models.isNotEmpty())
            assertTrue(f.controller.state.value.initializing)
            assertFalse(f.controller.state.value.connected)
            gates.getValue("/api/sessions").complete(Unit)
            runCurrent()
            assertTrue(f.controller.state.value.connected)
            assertTrue(f.controller.state.value.conversations.isNotEmpty())
            assertFalse(f.controller.state.value.initializing)
        } finally { f.close() }
    }

    @Test fun startupLatencyIsTheSlowestReadRatherThanTheirSum() = runTest {
        val f = ControllerTestFixture(StandardTestDispatcher(testScheduler))
        try {
            f.handler = { request ->
                when (request.url.encodedPath) {
                    "/api/mobile/capabilities" -> delay(300)
                    "/api/brain/models" -> delay(700)
                    "/api/sessions" -> delay(500)
                }
                null
            }
            runCurrent()
            advanceTimeBy(699); runCurrent()
            assertTrue(f.controller.state.value.initializing)
            advanceTimeBy(1); runCurrent()
            assertEquals(700, currentTime)
            assertTrue(f.controller.state.value.connected)
        } finally { f.close() }
    }

    @Test fun qrExchangePreloadsBeforeOpeningChat() = runTest {
        val f = ControllerTestFixture(StandardTestDispatcher(testScheduler), FakePlatformBridge().apply { secrets.clear() })
        val gate = CompletableDeferred<Unit>()
        try {
            f.handler = { request -> if (request.url.encodedPath in corePaths) gate.await(); null }
            runCurrent(); f.pairNewOwner(); runCurrent()
            assertTrue(f.controller.state.value.initializing)
            assertTrue(f.controller.state.value.connecting)
            assertFalse(f.controller.state.value.connected)
            assertEquals(corePaths, f.requests.filter { it.path in corePaths }.map { it.path }.toSet())
            gate.complete(Unit); runCurrent()
            assertTrue(f.controller.state.value.connected)
            assertEquals("owner_new", f.bridge.preferences["device_id"])
        } finally { f.close() }
    }

    @Test fun eachCoreFailureCanRetryWithoutRedeemingAnotherQr() = runTest {
        for (failedPath in corePaths) {
            val f = ControllerTestFixture(StandardTestDispatcher(testScheduler))
            try {
                f.handler = { request -> if (request.url.encodedPath == failedPath)
                    jsonResponse("""{"detail":"unavailable"}""", HttpStatusCode.ServiceUnavailable) else null }
                runCurrent()
                assertFalse(f.controller.state.value.connected)
                assertFalse(f.controller.state.value.initializing)
                assertFalse(f.controller.state.value.modelsLoading)
                assertEquals("error.service", f.controller.state.value.initializationError)
                f.controller.dismissNotice()
                assertEquals("error.service", f.controller.state.value.initializationError)
                assertEquals("old-device-token", f.bridge.secrets["device_token"])
                f.handler = { null }
                f.requests.clear()
                f.controller.refresh(); f.controller.refresh()
                assertTrue(f.controller.state.value.initializing)
                runCurrent()
                assertTrue(f.controller.state.value.connected)
                assertNull(f.controller.state.value.initializationError)
                corePaths.forEach { path -> assertEquals(1, f.requests.count { it.path == path }, path) }
                assertFalse(f.requests.any { it.path == "/api/mobile/pair/exchange" })
            } finally { f.close() }
        }
    }

    @Test fun retryAfterFailedQrPreloadRetainsTheNewTokensOrigin() = runTest {
        val f = ControllerTestFixture(StandardTestDispatcher(testScheduler), FakePlatformBridge().apply { secrets.clear() })
        try {
            f.handler = { request -> if (request.url.encodedPath == "/api/sessions")
                jsonResponse("""{"detail":"unavailable"}""", HttpStatusCode.ServiceUnavailable) else null }
            runCurrent(); f.pairNewOwner(); runCurrent()
            assertEquals("error.service", f.controller.state.value.initializationError)
            assertEquals("https://new.example", f.controller.state.value.baseUrl)
            assertEquals("new-device-token", f.bridge.secrets["device_token"])
            f.handler = { null }
            f.requests.clear()
            f.controller.refresh(); runCurrent()
            assertTrue(f.controller.state.value.connected)
            val authenticated = f.requests.filter { it.bearer != null }
            assertTrue(authenticated.isNotEmpty())
            assertTrue(authenticated.all { it.host == "new.example" && it.bearer == "Bearer new-device-token" },
                "Retry must never send a newly paired token to the previous server")
            assertFalse(f.requests.any { it.path == "/api/mobile/pair/exchange" })
        } finally { f.close() }
    }

    @Test fun unreadableCredentialsKeepARecoverableStartupError() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val bridge = FakePlatformBridge()
        var readable = false
        val platform = object : PlatformBridge by bridge {
            override fun readSecret(key: String): String? {
                if (!readable) throw IllegalStateException("local simulated locked keychain")
                return bridge.readSecret(key)
            }
        }
        val requestedPaths = mutableListOf<String>()
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            this.dispatcher = dispatcher
            addHandler { request ->
                requestedPaths += request.url.encodedPath
                jsonResponse(when (request.url.encodedPath) {
                    "/api/brain/models" -> """{"models":[],"thinking_levels":[]}"""
                    "/api/sessions" -> """{"sessions":[]}"""
                    "/api/setup" -> """{"profile":{}}"""
                    else -> "{}"
                })
            }
        }))
        val controller = AppController(platform, makeApi = { server, token -> BotApi(server, token, client) }, dispatcher = dispatcher)
        try {
            assertEquals("startup.storage", controller.state.value.initializationError)
            assertFalse(controller.state.value.initializing)
            controller.refresh(); runCurrent()
            assertEquals("startup.storage", controller.state.value.initializationError)
            assertTrue(requestedPaths.isEmpty(), "Unreadable credentials must not fall back to an anonymous API request")
            readable = true
            controller.refresh()
            assertTrue(controller.state.value.initializing)
            runCurrent()
            assertTrue(controller.state.value.connected)
            assertNull(controller.state.value.initializationError)
        } finally { controller.close(); client.close() }
    }

    @Test fun retryWithRemovedCredentialsReturnsToPairing() = runTest {
        val f = ControllerTestFixture(StandardTestDispatcher(testScheduler))
        try {
            f.handler = { request -> if (request.url.encodedPath == "/api/mobile/capabilities")
                jsonResponse("""{"detail":"unauthorized"}""", HttpStatusCode.Unauthorized) else null }
            runCurrent()
            assertEquals("error.auth", f.controller.state.value.initializationError)
            assertEquals("old-device-token", f.bridge.secrets["device_token"])
            f.bridge.secrets.clear()
            f.requests.clear()
            f.controller.refresh(); runCurrent()
            assertFalse(f.controller.state.value.connected)
            assertFalse(f.controller.state.value.initializing)
            assertNull(f.controller.state.value.initializationError)
            assertTrue(f.requests.isEmpty())
        } finally { f.close() }
    }

    @Test fun oldStartupCannotReplaceANewlyPairedConnection() = runTest {
        val f = ControllerTestFixture(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        try {
            f.handler = { request -> if (request.url.host == "old.example" && request.url.encodedPath in corePaths) gate.await(); null }
            runCurrent()
            f.controller.disconnect(); f.pairNewOwner(); runCurrent()
            assertTrue(f.controller.state.value.connected)
            gate.complete(Unit); runCurrent()
            assertEquals("https://new.example", f.controller.state.value.baseUrl)
            assertTrue(f.controller.state.value.connected)
            assertNull(f.controller.state.value.initializationError)
        } finally { f.close() }
    }

    @Test fun optionalProfileAndIntelligenceNeverHoldTheLoadingScreen() = runTest {
        val f = ControllerTestFixture(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        try {
            f.handler = { request -> if (request.url.encodedPath in setOf("/api/setup", "/api/brain/intelligence")) gate.await(); null }
            runCurrent()
            assertTrue(f.controller.state.value.connected)
            assertFalse(f.controller.state.value.initializing)
            assertTrue(f.controller.state.value.models.isNotEmpty())
            assertTrue(f.controller.state.value.conversations.isNotEmpty())
        } finally { f.close() }
    }

    @Test fun permittedOutboxRetriesImmediatelyWhileProfileIsStillLoading() = runTest {
        val bridge = FakePlatformBridge()
        val pending = OutboxItem("queued_startup", "chat_1", "Send after reconnect", "regolo/model", "none")
        bridge.preferences["owner_old.outbox.v1"] = Json.encodeToString(listOf(pending))
        bridge.preferences["owner_old.outbox.allowed"] = Json.encodeToString(listOf(pending.clientId))
        val f = ControllerTestFixture(StandardTestDispatcher(testScheduler), bridge)
        val profile = CompletableDeferred<Unit>()
        try {
            f.handler = { request -> when {
                request.url.encodedPath == "/api/setup" -> { profile.await(); null }
                request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Post ->
                    jsonResponse("""{"id":"startup_job","session_id":"chat_1","state":"completed"}""")
                else -> null
            } }
            runCurrent()
            assertTrue(f.controller.state.value.connected)
            assertFalse(profile.isCompleted)
            assertEquals(1, f.requests.count { it.path == "/api/mobile/messages" && it.method == HttpMethod.Post })
            assertEquals(0, currentTime, "Optional profile loading must not defer the first outbox delivery until the periodic retry")
            assertTrue(Json.decodeFromString<List<OutboxItem>>(bridge.preferences.getValue("owner_old.outbox.v1")).isEmpty())
        } finally { f.close() }
    }

    @Test fun startupOutboxStorageFailureIsReportedWithoutAnUncaughtException() = runTest {
        val f = ControllerTestFixture(StandardTestDispatcher(testScheduler))
        try {
            f.bridge.beforePreferenceWrite = { key, _ -> if (key == "owner_old.outbox.v1")
                throw IllegalStateException("local simulated full storage") }
            runCurrent()
            assertTrue(f.controller.state.value.connected)
            assertFalse(f.controller.state.value.initializing)
            assertNull(f.controller.state.value.initializationError)
            assertNotNull(f.controller.state.value.error)
        } finally { f.close() }
    }

    @Test fun closingControllerCancelsOptionalReadsWithoutFurtherWrites() = runTest {
        val f = ControllerTestFixture(StandardTestDispatcher(testScheduler))
        val optionalReads = CompletableDeferred<Unit>()
        try {
            f.handler = { request -> if (request.url.encodedPath in setOf("/api/setup", "/api/brain/intelligence"))
                { optionalReads.await(); null } else null }
            runCurrent()
            assertTrue(f.controller.state.value.connected)
            assertTrue(f.controller.state.value.intelligenceLoading)
            val beforeClose = f.controller.state.value
            val writesAfterClose = mutableListOf<String>()
            f.bridge.beforePreferenceWrite = { key, _ -> writesAfterClose += key }
            f.close(); runCurrent()
            assertEquals(beforeClose, f.controller.state.value, "Cancelled optional reads must not update a disposed controller")
            assertTrue(writesAfterClose.isEmpty(), "Cancelled profile work must not continue into queued-message persistence")
        } finally { f.close() }
    }

    @Test fun foregroundRefreshStartsChatsAndModelsTogether() = runTest {
        val f = ControllerTestFixture(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        try {
            runCurrent()
            f.requests.clear()
            f.handler = { request -> if (request.url.encodedPath in corePaths) gate.await(); null }
            f.bridge.foreground.value = false; runCurrent()
            f.bridge.foreground.value = true; runCurrent()
            assertTrue(f.requests.any { it.path == "/api/brain/models" })
            assertTrue(f.requests.any { it.path == "/api/sessions" })
            assertTrue(f.controller.state.value.connected)
            assertFalse(f.controller.state.value.initializing)
            gate.complete(Unit); runCurrent()
        } finally { f.close() }
    }
}
