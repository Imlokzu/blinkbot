@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package me.waveio.claudebot.state

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Acknowledgements may arrive after another choice, chat, or paired account. */
class ControllerChatActionsRegressionTest {
    private val fixtures = mutableListOf<ControllerTestFixture>()

    private fun TestScope.fixture(bridge: FakePlatformBridge = FakePlatformBridge()) =
        ControllerTestFixture(StandardTestDispatcher(testScheduler), bridge).also { fixtures += it }

    private fun controllerTest(block: suspend TestScope.() -> Unit): TestResult = runTest(timeout = 10.seconds) {
        try { block() } finally { fixtures.forEach { it.close() }; fixtures.clear(); runCurrent() }
    }

    private fun TestScope.open(fixture: ControllerTestFixture) {
        runCurrent(); fixture.controller.openChat("chat_1"); runCurrent()
    }

    @Test fun historyKeepsHumanAndBotReactionsNotesAndUnixTimestamps() = controllerTest {
        val fixture = fixture()
        fixture.handler = { request -> when (request.url.encodedPath) {
            "/api/sessions" -> jsonResponse("""{"sessions":[{"id":"chat_1","title":"Chat","updated":1791043200},{"id":"legacy","title":"Older"}]}""")
            "/api/sessions/chat_1" -> jsonResponse(reactionHistory)
            else -> null
        } }
        open(fixture)
        val state = fixture.controller.state.value
        assertEquals(1791043200L, state.conversations.first().updatedAt)
        assertNull(state.conversations.last().updatedAt, "Unknown dates must not be presented as today")
        assertEquals("💛", state.messages.first().reaction)
        assertEquals(1791043100L, state.messages.first().timestamp)
        assertEquals(mapOf("1" to "❤️"), state.messages.last().reactions)
        assertTrue(state.messages.last().parts.first().note)
        assertEquals(listOf("Looking it up", "Answer", "More detail"), state.messages.last().parts.map { it.text })
    }

    @Test fun reactionIsImmediateAndUsesTheServerTextBubbleIndex() = controllerTest {
        val fixture = fixture()
        val acknowledgement = CompletableDeferred<Unit>()
        fixture.handler = { request -> when (request.url.encodedPath) {
            "/api/sessions/chat_1" -> jsonResponse(reactionHistory)
            "/api/sessions/chat_1/reactions" -> { acknowledgement.await(); jsonResponse("""{"ok":true,"reactions":{"1":"👍"}}""") }
            else -> null
        } }
        open(fixture)
        fixture.controller.draft("Keep the keyboard draft")
        fixture.controller.reaction("answer_1", 1, "👍")
        assertEquals("👍", fixture.controller.state.value.messages.last().reactions["1"])
        runCurrent()
        val request = fixture.requests.single { it.path.endsWith("/reactions") }
        assertEquals("answer_1", request.body?.get("message_id")?.jsonPrimitive?.content)
        assertEquals("1", request.body?.get("bubble")?.jsonPrimitive?.content)
        assertEquals("👍", request.body?.get("emoji")?.jsonPrimitive?.content)
        acknowledgement.complete(Unit); runCurrent()
        assertEquals("Keep the keyboard draft", fixture.controller.state.value.draft)
        assertNull(fixture.controller.state.value.error)
    }

    @Test fun removingReactionSendsNullAndKeepsTheOtherBubbles() = controllerTest {
        val fixture = fixture()
        fixture.handler = { request -> when (request.url.encodedPath) {
            "/api/sessions/chat_1" -> jsonResponse(reactionHistory)
            "/api/sessions/chat_1/reactions" -> jsonResponse("""{"ok":true,"reactions":{}}""")
            else -> null
        } }
        open(fixture)
        fixture.controller.reaction("answer_1", 1, null)
        assertTrue(fixture.controller.state.value.messages.last().reactions.isEmpty())
        runCurrent()
        assertEquals(JsonNull, fixture.requests.single { it.path.endsWith("/reactions") }.body?.get("emoji"))
        assertEquals(3, fixture.controller.state.value.messages.last().parts.size)
        assertEquals("💛", fixture.controller.state.value.messages.first().reaction)
    }

    @Test fun rapidChoicesAreSerializedAndAnOlderAckCannotReplaceTheLatestChoice() = controllerTest {
        val fixture = fixture()
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        var writes = 0
        fixture.handler = { request -> when (request.url.encodedPath) {
            "/api/sessions/chat_1" -> jsonResponse(reactionHistory)
            "/api/sessions/chat_1/reactions" -> if (++writes == 1) {
                first.await(); jsonResponse("""{"ok":true,"reactions":{"1":"👍"}}""")
            } else { second.await(); jsonResponse("""{"ok":true,"reactions":{"1":"🎉"}}""") }
            else -> null
        } }
        open(fixture)
        fixture.controller.reaction("answer_1", 1, "👍"); runCurrent()
        fixture.controller.reaction("answer_1", 1, "🎉"); runCurrent()
        assertEquals(1, writes, "Server writes for a message must keep the user's order")
        first.complete(Unit); runCurrent()
        assertEquals(2, writes)
        assertEquals("🎉", fixture.controller.state.value.messages.last().reactions["1"])
        second.complete(Unit); runCurrent()
        assertEquals("🎉", fixture.controller.state.value.messages.last().reactions["1"])
    }

    @Test fun anAckForOneBubbleDoesNotClearAnotherBubblesOptimisticChoice() = controllerTest {
        val fixture = fixture()
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        var writes = 0
        fixture.handler = { request -> when (request.url.encodedPath) {
            "/api/sessions/chat_1" -> jsonResponse(reactionHistory)
            "/api/sessions/chat_1/reactions" -> if (++writes == 1) {
                first.await(); jsonResponse("""{"ok":true,"reactions":{"1":"👍"}}""")
            } else { second.await(); jsonResponse("""{"ok":true,"reactions":{"1":"👍","2":"🎉"}}""") }
            else -> null
        } }
        open(fixture)
        fixture.controller.reaction("answer_1", 1, "👍"); runCurrent()
        fixture.controller.reaction("answer_1", 2, "🎉"); runCurrent()
        first.complete(Unit); runCurrent()
        assertEquals(mapOf("1" to "👍", "2" to "🎉"), fixture.controller.state.value.messages.last().reactions)
        second.complete(Unit); runCurrent()
        assertEquals(mapOf("1" to "👍", "2" to "🎉"), fixture.controller.state.value.messages.last().reactions)
    }

    @Test fun latestFailedChoiceRestoresTheLastConfirmedReaction() = controllerTest {
        val fixture = fixture()
        val first = CompletableDeferred<Unit>()
        var writes = 0
        fixture.handler = { request -> when (request.url.encodedPath) {
            "/api/sessions/chat_1" -> jsonResponse(reactionHistory)
            "/api/sessions/chat_1/reactions" -> if (++writes == 1) {
                first.await(); jsonResponse("""{"ok":true,"reactions":{"1":"👍"}}""")
            } else jsonResponse("""{"detail":{"code":"save_failed"}}""", HttpStatusCode.InternalServerError)
            else -> null
        } }
        open(fixture)
        fixture.controller.reaction("answer_1", 1, "👍"); runCurrent()
        fixture.controller.reaction("answer_1", 1, "🎉"); runCurrent()
        first.complete(Unit); runCurrent()
        assertEquals("👍", fixture.controller.state.value.messages.last().reactions["1"])
        assertEquals("reaction.failed", fixture.controller.state.value.error)
    }

    @Test fun anOldReactionFailureCannotTouchAReopenedChat() = controllerTest {
        val fixture = fixture()
        val first = CompletableDeferred<Unit>()
        fixture.handler = { request -> when (request.url.encodedPath) {
            "/api/sessions/chat_1" -> jsonResponse(reactionHistory)
            "/api/sessions/chat_1/reactions" -> { first.await(); jsonResponse("""{"detail":{"code":"save_failed"}}""", HttpStatusCode.InternalServerError) }
            else -> null
        } }
        open(fixture)
        fixture.controller.reaction("answer_1", 1, "👍"); runCurrent()
        fixture.controller.openChat("chat_2"); runCurrent()
        fixture.controller.openChat("chat_1"); runCurrent()
        first.complete(Unit); runCurrent()
        assertEquals("❤️", fixture.controller.state.value.messages.last().reactions["1"])
        assertNull(fixture.controller.state.value.error)
    }

    @Test fun oldOwnersReactionResponseCannotAffectTheSameMessageForANewOwner() = controllerTest {
        val fixture = fixture()
        val first = CompletableDeferred<Unit>()
        fixture.handler = { request -> when {
            request.url.encodedPath == "/api/sessions/chat_1" -> jsonResponse(reactionHistory)
            request.url.encodedPath.endsWith("/reactions") -> { first.await(); jsonResponse("""{"ok":true,"reactions":{"1":"👍"}}""") }
            else -> null
        } }
        open(fixture)
        fixture.controller.reaction("answer_1", 1, "👍"); runCurrent()
        fixture.controller.disconnect(); fixture.pairNewOwner(); runCurrent()
        fixture.controller.openChat("chat_1"); runCurrent()
        first.complete(Unit); runCurrent()
        assertEquals("❤️", fixture.controller.state.value.messages.last().reactions["1"])
        assertNull(fixture.controller.state.value.error)
    }

    @Test fun delayedSessionListingCannotUndoARename() = controllerTest {
        val fixture = fixture()
        runCurrent()
        val listing = CompletableDeferred<Unit>()
        fixture.handler = { request -> when (request.url.encodedPath) {
            "/api/sessions" -> { listing.await(); jsonResponse("""{"sessions":[{"id":"chat_1","title":"Stale name","updated":1791043200}]}""") }
            "/api/sessions/chat_1/rename" -> jsonResponse("""{"ok":true,"title":"Renamed"}""")
            else -> null
        } }
        fixture.controller.refresh(); runCurrent()
        fixture.controller.renameChat("chat_1", "  Renamed  "); runCurrent()
        assertEquals("Renamed", fixture.controller.state.value.conversations.single().title)
        listing.complete(Unit); runCurrent()
        assertEquals("Renamed", fixture.controller.state.value.conversations.single().title)
        val request = fixture.requests.single { it.path.endsWith("/rename") }
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("Renamed", request.body?.get("title")?.jsonPrimitive?.content)
    }

    @Test fun oldOwnersRenameAckCannotReplaceTheNewOwnersConversationTitle() = controllerTest {
        val fixture = fixture()
        val rename = CompletableDeferred<Unit>()
        fixture.handler = { request -> if (request.url.encodedPath.endsWith("/rename")) {
            rename.await(); jsonResponse("""{"ok":true,"title":"Old account name"}""")
        } else null }
        runCurrent()
        fixture.controller.renameChat("chat_1", "Old account name"); runCurrent()
        fixture.controller.disconnect(); fixture.pairNewOwner(); runCurrent()
        rename.complete(Unit); runCurrent()
        assertEquals("Chat", fixture.controller.state.value.conversations.single().title)
        assertNull(fixture.controller.state.value.error)
    }

    @Test fun deletionRequiresCancellingEveryKindOfRunnableServerWorkFirst() = controllerTest {
        for (status in listOf("queued", "scheduled", "running", "stopping")) {
            val fixture = fixture()
            fixture.handler = { request -> if (request.url.encodedPath == "/api/mobile/messages" && request.url.parameters["session_id"] == "chat_1")
                jsonResponse("""{"messages":[{"id":"waiting","session_id":"chat_1","state":"$status"}]}""") else null }
            runCurrent()
            fixture.controller.deleteChat("chat_1"); runCurrent()
            assertEquals("chat.deletePendingWork", fixture.controller.state.value.notice, status)
            assertEquals("chat_1", fixture.controller.state.value.noticeDetail)
            assertFalse(fixture.requests.any { it.method == HttpMethod.Delete }, "Deleting $status work could recreate the chat")
            assertFalse(fixture.requests.any { it.path.endsWith("/stop") }, "The guard must not silently cancel work")
        }
    }

    @Test fun deletionAlsoRequiresCancellingLocalUndeliveredWork() = controllerTest {
        val bridge = FakePlatformBridge()
        bridge.preferences["owner_old.outbox.v1"] = Json.encodeToString(listOf(OutboxItem("pending", "chat_1", "Retain me", "regolo/model", "none")))
        val fixture = fixture(bridge)
        runCurrent()
        fixture.controller.deleteChat("chat_1"); runCurrent()
        assertEquals("chat.deletePendingWork", fixture.controller.state.value.notice)
        assertFalse(fixture.requests.any { it.method == HttpMethod.Delete })
        assertEquals("Retain me", Json.decodeFromString<List<OutboxItem>>(bridge.preferences.getValue("owner_old.outbox.v1")).single().message)
    }

    @Test fun deletionClearsOnlyTheDeletedChatAndFencesNewSendsDuringItsCheck() = controllerTest {
        val fixture = fixture()
        val check = CompletableDeferred<Unit>()
        fixture.handler = { request -> when {
            request.method == HttpMethod.Delete -> jsonResponse("""{"ok":true}""")
            else -> null
        } }
        open(fixture)
        fixture.controller.draft("Unsent draft")
        fixture.handler = { request -> when {
            request.url.encodedPath == "/api/mobile/messages" && request.url.parameters["session_id"] == "chat_1" -> { check.await(); jsonResponse("""{"messages":[]}""") }
            request.method == HttpMethod.Delete -> jsonResponse("""{"ok":true}""")
            else -> null
        } }
        fixture.controller.deleteChat("chat_1"); runCurrent()
        fixture.controller.send(); runCurrent()
        assertEquals("Unsent draft", fixture.controller.state.value.draft)
        assertFalse(fixture.requests.any { it.path == "/api/mobile/messages" && it.method == HttpMethod.Post })
        check.complete(Unit); runCurrent()
        assertEquals("", fixture.controller.state.value.sessionId)
        assertTrue(fixture.controller.state.value.conversations.none { it.id == "chat_1" })
        assertNull(fixture.bridge.preferences["owner_old.draft.chat_1"])
    }

    @Test fun aDeleteAckCannotCloseAChatOpenedWhileItWasWaiting() = controllerTest {
        val fixture = fixture()
        val deletion = CompletableDeferred<Unit>()
        fixture.handler = { request -> if (request.method == HttpMethod.Delete) {
            deletion.await(); jsonResponse("""{"ok":true}""")
        } else null }
        open(fixture)
        fixture.controller.deleteChat("chat_1"); runCurrent()
        fixture.controller.openChat("chat_2"); runCurrent()
        fixture.controller.draft("Second chat draft")
        deletion.complete(Unit); runCurrent()
        assertEquals("chat_2", fixture.controller.state.value.sessionId)
        assertEquals("Second chat draft", fixture.controller.state.value.draft)
    }

    private companion object {
        const val reactionHistory = """{"messages":[{"id":"user_1","role":"user","content":"Hello","reaction":"💛","ts":1791043100},{"id":"answer_1","role":"assistant","content":"Answer\n\nMore detail","parts":[{"type":"text","text":"Looking it up","note":true},{"type":"text","text":"Answer"},{"type":"text","text":"More detail"}],"reactions":{"1":"❤️"},"ts":1791043200}]}"""
    }
}
