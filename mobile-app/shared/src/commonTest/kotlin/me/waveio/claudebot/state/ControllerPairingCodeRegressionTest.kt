package me.waveio.claudebot.state

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ControllerPairingCodeRegressionTest {
    private fun TestScope.fixture() = ControllerTestFixture(StandardTestDispatcher(testScheduler),
        FakePlatformBridge().apply { secrets.clear() })

    private fun ControllerTestFixture.enter(code: String = "abcd-efgh", server: String = "https://new.example/api/") {
        controller.codePairing(true)
        controller.pairingServer(server)
        controller.pairingCode(code)
    }

    @Test fun manualCodeUsesUnauthenticatedExchangeAndNeverPersistsTheCode() = runTest {
        val f = fixture()
        try {
            runCurrent(); f.enter(); f.controller.connectWithCode(); f.controller.connectWithCode(); runCurrent()
            val exchange = f.requests.filter { it.path == "/api/mobile/pair/exchange" }.single()
            assertNull(exchange.bearer)
            assertEquals("ABCDEFGH", exchange.body?.get("code")?.jsonPrimitive?.content)
            assertEquals("new.example", exchange.host)
            assertTrue(f.controller.state.value.connected)
            assertFalse(f.controller.state.value.codePairingOpen)
            assertEquals("", f.controller.state.value.pairingCode)
            assertEquals("new-device-token", f.bridge.secrets["device_token"])
            assertFalse(f.bridge.preferences.values.any { it.contains("ABCDEFGH", true) || it.contains("abcd-efgh", true) })
        } finally { f.close() }
    }

    @Test fun malformedCodeAndServerAreLocalErrorsWithoutRequests() = runTest {
        val f = fixture()
        try {
            runCurrent(); f.enter("short"); f.controller.connectWithCode(); runCurrent()
            assertEquals("connect.invalidCode", f.controller.state.value.pairingError)
            f.controller.pairingCode("ABCD-EFGH"); f.controller.pairingServer("http://new.example")
            f.controller.connectWithCode(); runCurrent()
            assertEquals("connect.invalidServer", f.controller.state.value.pairingError)
            assertFalse(f.controller.state.value.connecting)
            assertTrue(f.requests.isEmpty())
        } finally { f.close() }
    }

    @Test fun serverRejectionIsInlineAndRetainsEditableInput() = runTest {
        val f = fixture()
        try {
            runCurrent()
            for ((status, code, expected) in listOf(
                Triple(HttpStatusCode.Unauthorized, "invalid_pairing", "connect.invalidCode"),
                Triple(HttpStatusCode.TooManyRequests, "pairing_rate_limited", "connect.rateLimited"),
                Triple(HttpStatusCode.ServiceUnavailable, "unavailable", "error.service"),
            )) {
                f.handler = { jsonResponse("""{"detail":"$code"}""", status) }
                f.enter(); f.controller.connectWithCode(); runCurrent()
                assertEquals(expected, f.controller.state.value.pairingError)
                assertEquals("abcd-efgh", f.controller.state.value.pairingCode)
                assertFalse(f.controller.state.value.connecting)
                assertFalse(f.controller.state.value.connected)
                assertNull(f.bridge.secrets["device_token"])
            }
        } finally { f.close() }
    }

    @Test fun editingCodeOrServerInvalidatesBothLateSuccessAndFailure() = runTest {
        for (editServer in listOf(false, true)) for (fails in listOf(false, true)) {
            val f = fixture()
            val release = CompletableDeferred<Unit>()
            try {
                f.handler = { request -> if (request.url.encodedPath == "/api/mobile/pair/exchange") {
                    release.await()
                    if (fails) jsonResponse("""{"detail":"invalid_pairing"}""", HttpStatusCode.Unauthorized)
                    else jsonResponse("""{"token":"late-fixture-token","device_id":"late","expires_at":1999999999}""")
                } else null }
                runCurrent(); f.enter(); f.controller.connectWithCode(); runCurrent()
                assertTrue(f.controller.state.value.connecting)
                if (editServer) f.controller.pairingServer("https://other.example") else f.controller.pairingCode("JKLM-NPQR")
                release.complete(Unit); runCurrent()
                assertFalse(f.controller.state.value.connected)
                assertFalse(f.controller.state.value.connecting)
                assertNull(f.controller.state.value.pairingError)
                assertNull(f.controller.state.value.error)
                assertNull(f.bridge.secrets["device_token"])
            } finally { f.close() }
        }
    }

    @Test fun cancelledManualExchangeCannotReplaceNewQrOwner() = runTest {
        val f = fixture(); val release = CompletableDeferred<Unit>()
        try {
            f.handler = { request -> if (request.url.host == "typed.example" && request.url.encodedPath == "/api/mobile/pair/exchange") {
                release.await(); jsonResponse("""{"token":"stale-fixture-token","device_id":"stale","expires_at":1999999999}""")
            } else null }
            runCurrent(); f.enter(server = "https://typed.example"); f.controller.connectWithCode(); runCurrent()
            f.controller.codePairing(false); f.pairNewOwner(); runCurrent()
            release.complete(Unit); runCurrent()
            assertEquals("owner_new", f.bridge.preferences["device_id"])
            assertEquals("new-device-token", f.bridge.secrets["device_token"])
            assertEquals("https://new.example", f.controller.state.value.baseUrl)
        } finally { f.close() }
    }

    @Test fun openingManualFormInvalidatesOldScannerCallback() = runTest {
        val f = fixture()
        try {
            runCurrent(); f.controller.connect()
            val scanner = f.bridge.qrResult!!
            f.enter()
            scanner("claudebot://pair?server=https%3A%2F%2Fqr.example&code=old-qr")
            runCurrent()
            assertTrue(f.requests.isEmpty())
            assertTrue(f.controller.state.value.codePairingOpen)
            assertEquals("abcd-efgh", f.controller.state.value.pairingCode)
        } finally { f.close() }
    }

    @Test fun closingFormErasesCodeAndSuppressesLateFailure() = runTest {
        val f = fixture(); val release = CompletableDeferred<Unit>()
        try {
            f.handler = { release.await(); throw IllegalStateException("Fixture offline") }
            runCurrent(); f.enter(); f.controller.connectWithCode(); runCurrent()
            f.controller.codePairing(false)
            release.complete(Unit); runCurrent()
            assertEquals("", f.controller.state.value.pairingCode)
            assertNull(f.controller.state.value.pairingError)
            assertNull(f.controller.state.value.error)
            assertFalse(f.controller.state.value.connecting)
        } finally { f.close() }
    }
}
