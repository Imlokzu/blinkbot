@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package me.waveio.claudebot.state

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.waveio.claudebot.platform.PickedFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Encoded image bytes stay ephemeral and requests remain tied to their chat/account. */
class ControllerAttachmentCacheRegressionTest {
    private val fixtures = mutableListOf<ControllerTestFixture>()

    private fun TestScope.fixture() = ControllerTestFixture(StandardTestDispatcher(testScheduler)).also { fixtures += it }

    private fun controllerTest(block: suspend TestScope.() -> Unit): TestResult = runTest(timeout = 10.seconds) {
        try { block() } finally { fixtures.forEach { it.close() }; fixtures.clear(); runCurrent() }
    }

    private fun TestScope.open(fixture: ControllerTestFixture) {
        runCurrent(); fixture.controller.openChat("chat_1"); runCurrent()
    }

    @Test fun pickedImagePreviewsFromItsBytesAndOnlyTheManifestIsPersisted() = controllerTest {
        val fixture = fixture()
        fixture.handler = { request -> when {
            request.url.encodedPath == "/api/chat/upload" -> jsonResponse("""{"url":"/uploads/picked.png","name":"picked.png","type":"image/png","size":3}""")
            request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Post ->
                jsonResponse("""{"id":"image_job","session_id":"chat_1","client_id":"local_1","state":"queued"}""")
            else -> null
        } }
        open(fixture)
        val bytes = byteArrayOf(1, 2, 3)
        fixture.controller.pickFile("photo")
        fixture.bridge.pickedResult?.invoke(PickedFile("picked.png", "image/png", bytes))
        runCurrent()
        assertContentEquals(bytes, fixture.controller.state.value.attachmentThumbnails["/uploads/picked.png"])
        fixture.controller.previewAttachment("/uploads/picked.png")
        assertContentEquals(bytes, fixture.controller.state.value.previewBytes)
        assertFalse(fixture.controller.state.value.loading)
        assertFalse(fixture.requests.any { it.path.startsWith("/uploads/") }, "A fresh pick must not redownload for its preview")
        fixture.controller.closePreview()
        fixture.controller.send(); runCurrent()
        val state = fixture.controller.state.value
        assertTrue(state.attachments.isEmpty())
        assertEquals("/uploads/picked.png", state.messages.single { it.role == "user" }.attachments.single().path)
        assertContentEquals(bytes, state.attachmentThumbnails["/uploads/picked.png"])
        val persisted = Json.parseToJsonElement(fixture.bridge.preferences.getValue("owner_old.outbox.v1")).toString()
        assertFalse(persisted.contains("bytes") || persisted.contains("thumbnail"), "The durable outbox contains only file metadata")
        assertTrue(fixture.bridge.preferences.keys.none { it.contains("thumbnail") || it.contains("previewBytes") })
    }

    @Test fun historyImagesLoadLazilyOnceAndReuseTheCacheForPreview() = controllerTest {
        val fixture = fixture()
        val download = CompletableDeferred<Unit>()
        val bytes = byteArrayOf(4, 5, 6)
        fixture.handler = { request -> when (request.url.encodedPath) {
            "/api/sessions/chat_1" -> jsonResponse(imageHistory)
            "/uploads/history.png" -> { download.await(); respond(bytes, headers = headersOf(HttpHeaders.ContentType, "image/png")) }
            else -> null
        } }
        open(fixture)
        assertTrue(fixture.controller.state.value.attachmentThumbnails.isEmpty())
        assertFalse(fixture.requests.any { it.path.startsWith("/uploads/") })
        fixture.controller.loadAttachmentThumbnail("/uploads/history.png")
        fixture.controller.loadAttachmentThumbnail("/uploads/history.png")
        runCurrent()
        assertEquals(1, fixture.requests.count { it.path == "/uploads/history.png" })
        download.complete(Unit); runCurrent()
        assertContentEquals(bytes, fixture.controller.state.value.attachmentThumbnails["/uploads/history.png"])
        fixture.controller.previewAttachment("/uploads/history.png")
        fixture.controller.loadAttachmentThumbnail("/uploads/history.png")
        runCurrent()
        assertContentEquals(bytes, fixture.controller.state.value.previewBytes)
        assertEquals(1, fixture.requests.count { it.path == "/uploads/history.png" })
    }

    @Test fun aLateImagePreviewCannotReopenAfterSwitchingChats() = controllerTest {
        val fixture = fixture()
        val download = CompletableDeferred<Unit>()
        fixture.handler = { request -> when (request.url.encodedPath) {
            "/api/sessions/chat_1" -> jsonResponse(imageHistory)
            "/uploads/history.png" -> { download.await(); respond(byteArrayOf(4, 5, 6)) }
            else -> null
        } }
        open(fixture)
        fixture.controller.previewAttachment("/uploads/history.png"); runCurrent()
        assertNotNull(fixture.controller.state.value.previewTitle)
        fixture.controller.openChat("chat_2"); runCurrent()
        download.complete(Unit); runCurrent()
        val state = fixture.controller.state.value
        assertEquals("chat_2", state.sessionId)
        assertNull(state.previewTitle)
        assertNull(state.previewBytes)
        assertTrue(state.attachmentThumbnails.isEmpty())
        assertFalse(state.loading)
    }

    @Test fun oldOwnersImageBytesCannotEnterTheNewOwnersCache() = controllerTest {
        val fixture = fixture()
        val download = CompletableDeferred<Unit>()
        fixture.handler = { request -> when (request.url.encodedPath) {
            "/api/sessions/chat_1" -> jsonResponse(imageHistory)
            "/uploads/history.png" -> { download.await(); respond(byteArrayOf(4, 5, 6)) }
            else -> null
        } }
        open(fixture)
        fixture.controller.loadAttachmentThumbnail("/uploads/history.png"); runCurrent()
        fixture.controller.disconnect(); fixture.pairNewOwner(); runCurrent()
        fixture.controller.openChat("chat_1"); runCurrent()
        download.complete(Unit); runCurrent()
        assertTrue(fixture.controller.state.value.attachmentThumbnails.isEmpty())
        assertNull(fixture.controller.state.value.previewBytes)
    }

    @Test fun removingAnUnsentAttachmentReleasesItsCachedBytes() = controllerTest {
        val fixture = fixture()
        fixture.handler = { request -> if (request.url.encodedPath == "/api/chat/upload")
            jsonResponse("""{"url":"/uploads/picked.png","name":"picked.png","type":"image/png","size":3}""") else null }
        open(fixture)
        fixture.controller.pickFile("photo")
        fixture.bridge.pickedResult?.invoke(PickedFile("picked.png", "image/png", byteArrayOf(1, 2, 3)))
        runCurrent()
        fixture.controller.removeAttachment("/uploads/picked.png")
        assertTrue(fixture.controller.state.value.attachments.isEmpty())
        assertTrue(fixture.controller.state.value.attachmentThumbnails.isEmpty())
    }

    @Test fun thumbnailCacheHasABoundedMemoryBudgetAndClearsOnNavigation() = controllerTest {
        val fixture = fixture()
        var uploads = 0
        fixture.handler = { request -> if (request.url.encodedPath == "/api/chat/upload") {
            val id = ++uploads
            jsonResponse("""{"url":"/uploads/image_$id.png","name":"image_$id.png","type":"image/png","size":6291456}""")
        } else null }
        open(fixture)
        repeat(3) {
            fixture.controller.pickFile("photo")
            fixture.bridge.pickedResult?.invoke(PickedFile("image.png", "image/png", ByteArray(6 * 1024 * 1024)))
            runCurrent()
        }
        val cache = fixture.controller.state.value.attachmentThumbnails
        assertTrue(cache.values.sumOf { it.size.toLong() } <= 16L * 1024 * 1024)
        assertFalse("/uploads/image_1.png" in cache)
        assertTrue("/uploads/image_3.png" in cache)
        fixture.controller.newChat()
        assertTrue(fixture.controller.state.value.attachmentThumbnails.isEmpty())
    }

    private companion object {
        const val imageHistory = """{"messages":[{"id":"user_1","role":"user","content":"Photo","attachments":[{"url":"/uploads/history.png","name":"history.png","type":"image/png","size":3}]}]}"""
    }
}
