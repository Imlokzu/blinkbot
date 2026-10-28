@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package me.waveio.claudebot.state

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

/** Web repair must resume the existing identity rather than discard phone data. */
class ControllerSessionRepairTest {
    @Test fun rejectedQrStaysInPairingWithoutClaimingASavedSessionExpired() = runTest {
        val bridge = FakePlatformBridge().apply { secrets.clear() }
        val f = ControllerTestFixture(StandardTestDispatcher(testScheduler), bridge)
        try {
            f.handler = { request -> if (request.url.encodedPath == "/api/mobile/pair/exchange")
                jsonResponse("""{"detail":{"code":"invalid_pairing"}}""", HttpStatusCode.Unauthorized) else null }
            runCurrent()
            f.controller.connect()
            bridge.qrResult?.invoke("claudebot://pair?server=https%3A%2F%2Fold.example&code=used-code")
            runCurrent()
            assertFalse(f.controller.state.value.connecting)
            assertFalse(f.controller.state.value.connected)
            assertEquals("error.qr", f.controller.state.value.error)
            assertNull(f.controller.state.value.initializationError)
            f.controller.repairConnection(); runCurrent()
            assertTrue(bridge.openedLinks.isEmpty())
            assertNull(bridge.secrets["device_token"])
            f.handler = { null }
            f.controller.connect()
            bridge.qrResult?.invoke("claudebot://pair?server=https%3A%2F%2Fold.example&code=fresh-code")
            runCurrent()
            assertTrue(f.controller.state.value.connected, "A fresh QR remains usable after rejection")
        } finally { f.close() }
    }

    @Test fun rejectedStartupOffersRepairAndRetriesTheSameSavedCredential() = runTest {
        val bridge = FakePlatformBridge().apply {
            preferences["server"] = "https://api-bot.waveio.me"
            preferences["owner_old.draft.new"] = "Keep this draft"
        }
        val f = ControllerTestFixture(StandardTestDispatcher(testScheduler), bridge)
        try {
            f.handler = { request -> if (request.url.encodedPath == "/api/mobile/capabilities")
                jsonResponse("""{"detail":{"code":"invalid_mobile_token"}}""", HttpStatusCode.Unauthorized) else null }
            runCurrent()
            assertFalse(f.controller.state.value.connected)
            assertEquals("error.auth", f.controller.state.value.initializationError)
            assertNull(f.controller.state.value.error)
            f.controller.repairConnection(); runCurrent()
            assertEquals(listOf("https://klodbot.waveio.me/dash/#/settings?tab=devices"), bridge.openedLinks)
            assertEquals("old-device-token", bridge.secrets["device_token"])
            assertEquals("owner_old", bridge.preferences["device_id"])
            assertEquals("Keep this draft", f.controller.state.value.draft)
            f.handler = { null }
            f.requests.clear()
            f.controller.refresh(); runCurrent()
            assertTrue(f.controller.state.value.connected)
            assertNull(f.controller.state.value.initializationError)
            assertEquals("Keep this draft", f.controller.state.value.draft)
            assertTrue(f.requests.all { it.bearer == "Bearer old-device-token" })
            assertFalse(f.requests.any { it.path.contains("pair/exchange") })
        } finally { f.close() }
    }

    @Test fun authFailureAfterConnectionRemainsVisibleUntilRepaired() = runTest {
        val f = ControllerTestFixture(StandardTestDispatcher(testScheduler))
        try {
            runCurrent()
            assertTrue(f.controller.state.value.connected)
            f.controller.draft("Unsaved input")
            f.handler = { request -> if (request.url.encodedPath == "/api/sessions")
                jsonResponse("{}", HttpStatusCode.Unauthorized) else null }
            f.controller.refresh(); runCurrent()
            assertFalse(f.controller.state.value.connected)
            assertEquals("error.auth", f.controller.state.value.initializationError)
            assertNull(f.controller.state.value.error)
            f.controller.dismissNotice(); runCurrent()
            assertEquals("error.auth", f.controller.state.value.initializationError)
            assertEquals("Unsaved input", f.controller.state.value.draft)
            f.controller.repairConnection(); runCurrent()
            assertEquals("https://old.example/dash/#/settings?tab=devices", f.bridge.openedLinks.single())
        } finally { f.close() }
    }

    @Test fun queuedAuthFailureAfterNavigationKeepsDraftAndUnsentMessages() = runTest {
        val queued = listOf(
            OutboxItem("first", "chat_1", "Keep first message", "regolo/model", "none"),
            OutboxItem("second", "chat_1", "Keep second message", "regolo/model", "none"),
        )
        val bridge = FakePlatformBridge().apply {
            preferences["owner_old.outbox.v1"] = Json.encodeToString(queued)
        }
        val gate = CompletableDeferred<Unit>()
        val f = ControllerTestFixture(StandardTestDispatcher(testScheduler), bridge)
        try {
            runCurrent()
            f.handler = { request -> if (request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Post) {
                gate.await()
                jsonResponse("""{"detail":{"code":"invalid_mobile_token"}}""", HttpStatusCode.Unauthorized)
            } else null }
            f.controller.offlineDelivery(true); runCurrent()
            f.controller.newChat()
            f.controller.draft("Keep current draft")
            gate.complete(Unit); runCurrent()
            assertFalse(f.controller.state.value.connected, "Auth loss belongs to the account even after navigation")
            assertEquals("error.auth", f.controller.state.value.initializationError)
            assertNull(f.controller.state.value.error)
            assertEquals("Keep current draft", f.controller.state.value.draft)
            val retained = Json.decodeFromString<List<OutboxItem>>(bridge.preferences.getValue("owner_old.outbox.v1"))
            assertEquals(queued.map { it.message }, retained.map { it.message })
            assertEquals(1, f.requests.count { it.path == "/api/mobile/messages" && it.method == HttpMethod.Post },
                "Stop retries when the shared credential is rejected")
            assertNull(retained.last().lastError, "Do not turn every pending message into an auth failure")
            assertEquals("old-device-token", bridge.secrets["device_token"])
        } finally { gate.complete(Unit); f.close() }
    }

    @Test fun fileReadAuthFailureKeepsLocalRecoveryAndShowsRepair() = runTest {
        val bridge = FakePlatformBridge().apply {
            preferences["owner_old.file.notes.md"] = "Keep file draft"
            preferences["owner_old.fileBaseline.notes.md"] = "Original"
        }
        val f = ControllerTestFixture(StandardTestDispatcher(testScheduler), bridge)
        try {
            runCurrent()
            f.handler = { request -> if (request.url.encodedPath == "/api/mobile/workspace/file")
                jsonResponse("{}", HttpStatusCode.Unauthorized) else null }
            f.controller.openFile("notes.md"); runCurrent()
            assertFalse(f.controller.state.value.connected)
            assertEquals("error.auth", f.controller.state.value.initializationError)
            assertNull(f.controller.state.value.error)
            assertEquals("Keep file draft", f.controller.state.value.fileText)
            assertEquals("Keep file draft", bridge.preferences["owner_old.file.notes.md"])
            assertFalse(f.controller.state.value.fileLoading)
            f.controller.repairConnection(); runCurrent()
            assertEquals("https://old.example/dash/#/settings?tab=devices", bridge.openedLinks.single())
        } finally { f.close() }
    }

    @Test fun fileSaveAuthFailureKeepsEditsForRetryAfterRepair() = runTest {
        val f = ControllerTestFixture(StandardTestDispatcher(testScheduler))
        var revoked = false
        try {
            f.handler = { request -> if (request.url.encodedPath == "/api/mobile/workspace/file") {
                if (revoked) jsonResponse("{}", HttpStatusCode.Unauthorized)
                else jsonResponse("""{"path":"notes.md","content":"Original","binary":false,"revision":"r1"}""")
            } else null }
            runCurrent()
            f.controller.openFile("notes.md"); runCurrent()
            assertEquals("Original", f.controller.state.value.fileText)
            assertTrue(f.controller.state.value.fileEditable)
            f.controller.fileText("Keep edited document")
            revoked = true
            f.controller.retryFileSave(); runCurrent()
            assertFalse(f.controller.state.value.connected)
            assertEquals("error.auth", f.controller.state.value.initializationError)
            assertNull(f.controller.state.value.error)
            assertEquals("failed", f.controller.state.value.fileSaveState)
            assertEquals("Keep edited document", f.controller.state.value.fileText)
            assertEquals("Keep edited document", f.bridge.preferences["owner_old.file.notes.md"])
        } finally { f.close() }
    }

    @Test fun fileCreationAuthFailureShowsPersistentRepairInsteadOfAToast() = runTest {
        val f = ControllerTestFixture(StandardTestDispatcher(testScheduler))
        try {
            runCurrent()
            f.controller.draft("Retain composer")
            f.handler = { request -> if (request.url.encodedPath == "/api/mobile/workspace/file")
                jsonResponse("{}", HttpStatusCode.Unauthorized) else null }
            f.controller.createFile("notes", "markdown"); runCurrent()
            assertFalse(f.controller.state.value.connected)
            assertEquals("error.auth", f.controller.state.value.initializationError)
            assertNull(f.controller.state.value.error)
            assertFalse(f.controller.state.value.fileCreating)
            assertEquals("Retain composer", f.controller.state.value.draft)
            f.controller.dismissNotice(); runCurrent()
            assertEquals("error.auth", f.controller.state.value.initializationError)
        } finally { f.close() }
    }

    @Test fun networkAndServiceFailuresKeepTheirOwnAdviceWithoutRepairLink() = runTest {
        for (status in listOf(HttpStatusCode.ServiceUnavailable, HttpStatusCode.BadGateway)) {
            val f = ControllerTestFixture(StandardTestDispatcher(testScheduler))
            try {
                f.handler = { jsonResponse("{}", status) }
                runCurrent()
                assertEquals("error.service", f.controller.state.value.initializationError)
                f.controller.repairConnection(); runCurrent()
                assertTrue(f.bridge.openedLinks.isEmpty())
            } finally { f.close() }
        }
    }
}
