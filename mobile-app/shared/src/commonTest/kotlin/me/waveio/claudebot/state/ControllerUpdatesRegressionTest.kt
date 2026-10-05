@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package me.waveio.claudebot.state

import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import kotlin.test.*

class ControllerUpdatesRegressionTest {
    private fun TestScope.fixture(): ControllerTestFixture = ControllerTestFixture(StandardTestDispatcher(testScheduler),
        FakePlatformBridge().apply { appVersionCode = 15; appVersionName = "0.4.8" })

    @Test fun updatesPageRemainsReachableAndDoesNotClaimAnUnpublishedServerIsCurrent() = runTest {
        val f = fixture()
        try {
            runCurrent()
            f.controller.navigate(Screen.Updates); runCurrent()
            assertEquals(Screen.Updates, f.controller.state.value.screen)
            assertEquals("0.4.8", f.controller.state.value.installedVersion)
            assertEquals("update.noRelease", f.controller.state.value.updateStatus)
            assertFalse(f.controller.state.value.updateChecking)
            assertNull(f.controller.state.value.update)
        } finally { f.close() }
    }

    @Test fun dismissedAvailableUpdateCanBeInstalledFromThePageAndChecksCannotOverlap() = runTest {
        val f = fixture(); val gate = CompletableDeferred<Unit>(); var checks = 0
        try {
            f.handler = { request -> when(request.url.encodedPath) {
                "/api/mobile/capabilities" -> { checks++; if(checks > 1) gate.await(); jsonResponse(available) }
                "/api/mobile/update/download" -> respond(byteArrayOf(1,2,3), headers = headersOf(HttpHeaders.ContentType,"application/vnd.android.package-archive"))
                else -> null
            } }
            f.bridge.packageDigest = "a".repeat(64)
            runCurrent()
            assertTrue(f.controller.state.value.updatePromptOpen)
            f.controller.dismissUpdate()
            assertFalse(f.controller.state.value.updatePromptOpen)
            assertNotNull(f.controller.state.value.update)
            f.controller.navigate(Screen.Updates); runCurrent()
            repeat(4) { f.controller.checkForUpdate() }; runCurrent()
            assertEquals(2, checks)
            assertTrue(f.controller.state.value.updateChecking)
            gate.complete(Unit); runCurrent()
            assertFalse(f.controller.state.value.updatePromptOpen)
            f.controller.installUpdate(); f.controller.installUpdate(); runCurrent()
            assertEquals(1,f.bridge.installedPackages.size)
            assertEquals("update.installerOpened", f.controller.state.value.updateStatus)
            assertFalse(f.controller.state.value.updateInstalling)
            f.bridge.packageAccepted = false
            f.controller.installUpdate();runCurrent()
            assertEquals("update.failed",f.controller.state.value.updateError)
            assertFalse(f.controller.state.value.updateInstalling)
        } finally { f.close() }
    }

    @Test fun failedCheckIsVisibleWithoutAnUpdateAndCanBeRetried() = runTest {
        val f = fixture(); var failure = false
        try {
            f.handler = { request -> if(request.url.encodedPath == "/api/mobile/capabilities") {
                if(failure) throw IllegalStateException("Fixture offline")
                jsonResponse("""{"update":{"available":false,"version_name":"0.4.8","version_code":15}}""")
            } else null }
            runCurrent(); failure = true
            f.controller.navigate(Screen.Updates); runCurrent()
            assertEquals("update.checkFailed",f.controller.state.value.updateError)
            assertNull(f.controller.state.value.update)
            assertFalse(f.controller.state.value.updateChecking)
            failure = false;f.controller.checkForUpdate();runCurrent()
            assertNull(f.controller.state.value.updateError)
            assertEquals("update.current",f.controller.state.value.updateStatus)
        } finally { f.close() }
    }

    @Test fun aPreviousAccountsDelayedCheckCannotReplaceTheNewAccountsUpdateState() = runTest {
        val f = fixture();val gate = CompletableDeferred<Unit>();var waiting = false
        try {
            f.handler = { request -> if(request.url.encodedPath == "/api/mobile/capabilities" && request.url.host == "old.example" && waiting) {
                gate.await();jsonResponse(available)
            } else null }
            runCurrent();waiting = true
            f.controller.checkForUpdate();runCurrent()
            f.controller.disconnect();f.pairNewOwner();runCurrent()
            gate.complete(Unit);runCurrent()
            assertEquals("https://new.example",f.controller.state.value.baseUrl)
            assertNull(f.controller.state.value.update)
            assertFalse(f.controller.state.value.updateChecking)
            assertFalse(f.controller.state.value.updatePromptOpen)
        } finally { f.close() }
    }

    @Test fun anOldAccountsCompletedDownloadCannotOpenTheInstallerAfterPairing() = runTest {
        val f = fixture();val gate = CompletableDeferred<Unit>()
        try {
            f.handler = { request -> when {
                request.url.encodedPath == "/api/mobile/capabilities" && request.url.host == "old.example" -> jsonResponse(available)
                request.url.encodedPath == "/api/mobile/update/download" -> { gate.await();respond(byteArrayOf(1,2,3)) }
                else -> null
            } }
            f.bridge.packageDigest = "a".repeat(64)
            runCurrent();f.controller.installUpdate();runCurrent()
            f.controller.disconnect();f.pairNewOwner();runCurrent()
            gate.complete(Unit);runCurrent()
            assertTrue(f.bridge.installedPackages.isEmpty())
            assertFalse(f.controller.state.value.updateInstalling)
        } finally { f.close() }
    }

    companion object {
        private val available = """{"update":{"available":true,"version_name":"0.4.9","version_code":16,"changelog":["Live bubbles"],"url":"https://old.example/api/mobile/update/download","sha256":"${"a".repeat(64)}"}}"""
    }
}
