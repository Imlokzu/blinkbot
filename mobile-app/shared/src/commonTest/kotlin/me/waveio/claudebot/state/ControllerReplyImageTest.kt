@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package me.waveio.claudebot.state

import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import kotlin.test.*

class ControllerReplyImageTest {
    private val url = "https://images.example/portrait.png"
    private val history = """{"messages":[{"id":"a1","role":"assistant","content":"![Portrait](https://images.example/portrait.png)"}]}"""

    @Test fun imageFailureIsLocalAndRetryReloadsWhileDuplicateMountsShareOneRequest() = runTest {
        val fixture = ControllerTestFixture(StandardTestDispatcher(testScheduler))
        var reads = 0
        fixture.handler = { request -> when (request.url.encodedPath) {
            "/api/sessions/chat_1" -> jsonResponse(history)
            "/api/mobile/images/fetch" -> {
                assertEquals(url, request.url.parameters["url"])
                if (++reads == 1) respond("failure", HttpStatusCode.BadGateway)
                else respond(byteArrayOf(1, 2, 3), headers = headersOf(HttpHeaders.ContentType, "image/png"))
            }
            else -> null
        } }
        try {
            runCurrent(); fixture.controller.openChat("chat_1"); runCurrent()
            fixture.controller.loadReplyImage(url, "remote"); fixture.controller.loadReplyImage(url, "remote"); runCurrent()
            assertEquals(1, reads)
            assertTrue(url in fixture.controller.state.value.imageFailures)
            assertNull(fixture.controller.state.value.error, "A broken image must not report the chat as disconnected")
            fixture.controller.loadReplyImage(url, "remote", true); runCurrent()
            assertEquals(2, reads)
            assertContentEquals(byteArrayOf(1, 2, 3), fixture.controller.state.value.attachmentThumbnails[url])
            assertFalse(url in fixture.controller.state.value.imageFailures)
            fixture.controller.previewReplyImage(url, "remote"); runCurrent()
            assertEquals("remote", fixture.controller.state.value.previewSource)
            assertEquals(url, fixture.controller.state.value.previewItems.single().path)
            assertContentEquals(byteArrayOf(1, 2, 3), fixture.controller.state.value.previewBytes)
        } finally { fixture.close(); runCurrent() }
    }
    @Test fun delayedImagesCannotRepopulateAnotherChatOrOpenAPreview() = runTest {
        val fixture = ControllerTestFixture(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        fixture.handler = { request -> when (request.url.encodedPath) {
            "/api/sessions/chat_1" -> jsonResponse(history)
            "/api/mobile/images/fetch" -> { gate.await(); respond(byteArrayOf(1), headers = headersOf(HttpHeaders.ContentType, "image/png")) }
            else -> null
        } }
        try {
            runCurrent(); fixture.controller.openChat("chat_1"); runCurrent()
            fixture.controller.previewReplyImage(url, "remote"); runCurrent()
            fixture.controller.openChat("chat_2"); runCurrent()
            gate.complete(Unit); runCurrent()
            assertNull(fixture.controller.state.value.previewPath)
            assertTrue(fixture.controller.state.value.attachmentThumbnails.isEmpty())
            assertTrue(fixture.controller.state.value.imageFailures.isEmpty())
        } finally { fixture.close(); runCurrent() }
    }
    @Test fun imageActionsCannotFetchUrlsNotPresentInTheCurrentConversation() = runTest {
        val fixture = ControllerTestFixture(StandardTestDispatcher(testScheduler))
        try {
            runCurrent(); fixture.controller.openChat("chat_1"); runCurrent()
            fixture.controller.loadReplyImage(url, "remote"); fixture.controller.previewReplyImage(url, "remote"); runCurrent()
            assertTrue(fixture.requests.none { it.path.contains("images/fetch") })
            assertNull(fixture.controller.state.value.previewPath)
        } finally { fixture.close(); runCurrent() }
    }
}
