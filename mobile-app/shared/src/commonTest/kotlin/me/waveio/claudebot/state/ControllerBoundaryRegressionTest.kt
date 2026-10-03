@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package me.waveio.claudebot.state

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import me.waveio.claudebot.platform.PickedFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Exercise public actions with deliberately reordered service acknowledgements. */
class ControllerBoundaryRegressionTest {
    private val fixtures = mutableListOf<ControllerTestFixture>()

    private fun TestScope.fixture(bridge: FakePlatformBridge = FakePlatformBridge()): ControllerTestFixture =
        ControllerTestFixture(StandardTestDispatcher(testScheduler), bridge).also { fixtures += it }

    private fun controllerTest(block: suspend TestScope.() -> Unit): TestResult = runTest(timeout = 10.seconds) {
        try { block() } finally {
            fixtures.forEach { it.close() }
            fixtures.clear()
            runCurrent()
        }
    }

    @Test fun oldOwnersDirectoryResponseCannotReplaceTheSameDirectoryAfterPairing() = controllerTest {
        val fixture = fixture()
        val oldListing = CompletableDeferred<Unit>()
        fixture.handler = { request -> if (request.url.encodedPath == "/api/workspace/list") {
            if (request.url.host == "old.example") {
                oldListing.await()
                jsonResponse("""{"entries":[{"path":"docs/old.md","name":"old.md","type":"file"}]}""")
            } else jsonResponse("""{"entries":[{"path":"docs/new.md","name":"new.md","type":"file"}]}""")
        } else null }
        runCurrent()
        fixture.controller.openDirectory("docs")
        runCurrent()
        fixture.controller.disconnect()
        fixture.pairNewOwner()
        runCurrent()
        fixture.controller.openDirectory("docs")
        runCurrent()
        assertEquals("new.md", fixture.controller.state.value.files.single().name)
        oldListing.complete(Unit)
        runCurrent()
        assertEquals("new.md", fixture.controller.state.value.files.single().name)
        assertFalse(fixture.controller.state.value.loading)
    }

    @Test fun returningToTheSameDirectoryCannotReviveItsOlderListing() = controllerTest {
        val fixture = fixture()
        val firstListing = CompletableDeferred<Unit>()
        var docsRequests = 0
        fixture.handler = { request -> if (request.url.encodedPath == "/api/workspace/list") {
            if (request.url.parameters["path"] == "docs" && ++docsRequests == 1) {
                firstListing.await()
                jsonResponse("""{"entries":[{"path":"docs/stale.md","name":"stale.md","type":"file"}]}""")
            } else jsonResponse("""{"entries":[{"path":"docs/latest.md","name":"latest.md","type":"file"}]}""")
        } else null }
        runCurrent()
        fixture.controller.openDirectory("docs")
        runCurrent()
        fixture.controller.openDirectory("")
        runCurrent()
        fixture.controller.openDirectory("docs")
        runCurrent()
        firstListing.complete(Unit)
        runCurrent()
        assertEquals("docs", fixture.controller.state.value.directory)
        assertEquals("latest.md", fixture.controller.state.value.files.single().name)
    }

    @Test fun staleDirectoryFailureDoesNotDismissANewerRequestsLoadingState() = controllerTest {
        val fixture = fixture()
        val staleFailure = CompletableDeferred<Unit>()
        val latestListing = CompletableDeferred<Unit>()
        fixture.handler = { request -> if (request.url.encodedPath == "/api/workspace/list") {
            if (request.url.parameters["path"] == "old") {
                staleFailure.await()
                jsonResponse("""{"detail":{"code":"directory_gone"}}""", HttpStatusCode.NotFound)
            } else {
                latestListing.await()
                jsonResponse("""{"entries":[{"path":"new/current.md","name":"current.md","type":"file"}]}""")
            }
        } else null }
        runCurrent()
        fixture.controller.openDirectory("old")
        runCurrent()
        fixture.controller.openDirectory("new")
        runCurrent()
        staleFailure.complete(Unit)
        runCurrent()
        assertNull(fixture.controller.state.value.error, "Only the current directory request may report its failure")
        assertTrue(fixture.controller.state.value.loading, "An obsolete failure must not finish the newer request")
        latestListing.complete(Unit)
        runCurrent()
        assertEquals("current.md", fixture.controller.state.value.files.single().name)
    }

    @Test fun profileLoadDoesNotOverwriteEditsTypedWhileItWasFetching() = controllerTest {
        val fixture = fixture()
        runCurrent()
        val delayedProfile = CompletableDeferred<Unit>()
        fixture.handler = { request -> if (request.url.encodedPath == "/api/setup") {
            delayedProfile.await()
            jsonResponse("""{"profile":{"name":"Server name","language":"en","persona":"calm","persona_custom":"Server persona","greeting":""}}""")
        } else null }
        fixture.controller.navigate(Screen.Personalization)
        runCurrent()
        fixture.controller.profileName("Unsaved name")
        fixture.controller.profilePersona("Unsaved persona")
        delayedProfile.complete(Unit)
        runCurrent()
        assertEquals("Unsaved name", fixture.controller.state.value.profileName)
        assertEquals("Unsaved persona", fixture.controller.state.value.profilePersona)
    }

    @Test fun oldProfileSaveAcknowledgementCannotReplaceTheNewOwnersProfileSnapshot() = controllerTest {
        val fixture = fixture()
        val oldSave = CompletableDeferred<Unit>()
        fixture.handler = { request -> if (request.url.encodedPath == "/api/setup") {
            when {
                request.method == HttpMethod.Post && request.url.host == "old.example" -> {
                    oldSave.await()
                    jsonResponse("""{"profile":{"name":"Old saved name","language":"en","persona":"calm","persona_custom":"Old persona","greeting":"Old greeting"}}""")
                }
                request.url.host == "new.example" -> jsonResponse("""{"profile":{"name":"New owner","language":"uk","persona":"playful","persona_custom":"New persona","greeting":"New greeting"}}""")
                else -> null
            }
        } else null }
        runCurrent()
        fixture.controller.profileName("Old saved name")
        fixture.controller.saveProfile()
        runCurrent()
        fixture.controller.disconnect()
        fixture.pairNewOwner()
        runCurrent()
        oldSave.complete(Unit)
        runCurrent()
        assertEquals("New owner", fixture.controller.state.value.profileName)
        assertNull(fixture.controller.state.value.notice)
        fixture.controller.profileName("New renamed owner")
        fixture.controller.saveProfile()
        runCurrent()
        val newSave = fixture.requests.single { it.host == "new.example" && it.path == "/api/setup" && it.method == HttpMethod.Post }.body!!
        assertEquals("uk", newSave.getValue("language").jsonPrimitive.content)
        assertEquals("playful", newSave.getValue("persona").jsonPrimitive.content)
        assertEquals("New greeting", newSave.getValue("greeting").jsonPrimitive.content)
    }

    @Test fun profileSaveCapturesTheValuesAtTheTimeTheUserPressedSave() = controllerTest {
        val fixture = fixture()
        fixture.handler = { request -> if (request.url.encodedPath == "/api/setup" && request.method == HttpMethod.Post)
            jsonResponse("""{"profile":{"name":"Submitted name","language":"en","persona":"calm","persona_custom":"Submitted persona","greeting":""}}""") else null }
        runCurrent()
        fixture.controller.profileName("Submitted name")
        fixture.controller.profilePersona("Submitted persona")
        fixture.controller.saveProfile()
        fixture.controller.profileName("Later unsaved name")
        fixture.controller.profilePersona("Later unsaved persona")
        runCurrent()
        val saved = fixture.requests.single { it.path == "/api/setup" && it.method == HttpMethod.Post }.body!!
        assertEquals("Submitted name", saved.getValue("name").jsonPrimitive.content)
        assertEquals("Submitted persona", saved.getValue("persona_custom").jsonPrimitive.content)
        assertEquals("calm", saved.getValue("persona").jsonPrimitive.content, "Editing the name/custom text must retain the server's selected persona")
        assertEquals("Later unsaved name", fixture.controller.state.value.profileName)
    }

    @Test fun stalePreviewFailureCannotEndTheNewerPreviewRequest() = controllerTest {
        val fixture = fixture()
        val stalePreview = CompletableDeferred<Unit>()
        val currentPreview = CompletableDeferred<Unit>()
        fixture.handler = { request -> when (request.url.encodedPath) {
            "/api/sessions/chat_1" -> jsonResponse(attachmentHistory)
            "/api/chat/attachment-preview" -> if (request.url.parameters["url"] == "/uploads/old.txt") {
                stalePreview.await()
                jsonResponse("""{"detail":{"code":"attachment_gone"}}""", HttpStatusCode.NotFound)
            } else {
                currentPreview.await()
                jsonResponse("""{"text":"Current preview","truncated":false,"type":"text/plain","size":15}""")
            }
            else -> null
        } }
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        fixture.controller.previewAttachment("/uploads/old.txt")
        runCurrent()
        fixture.controller.previewAttachment("/uploads/current.txt")
        runCurrent()
        stalePreview.complete(Unit)
        runCurrent()
        assertNull(fixture.controller.state.value.error)
        assertEquals("current.txt", fixture.controller.state.value.previewTitle)
        assertTrue(fixture.controller.state.value.loading)
        currentPreview.complete(Unit)
        runCurrent()
        assertEquals("Current preview", fixture.controller.state.value.previewText)
    }

    @Test fun closedPreviewIgnoresItsLateTextResponse() = controllerTest {
        val fixture = fixture()
        val delayedPreview = CompletableDeferred<Unit>()
        fixture.handler = { request -> when (request.url.encodedPath) {
            "/api/sessions/chat_1" -> jsonResponse(attachmentHistory)
            "/api/chat/attachment-preview" -> {
                delayedPreview.await()
                jsonResponse("""{"text":"Late preview","truncated":false,"type":"text/plain","size":12}""")
            }
            else -> null
        } }
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        fixture.controller.previewAttachment("/uploads/old.txt")
        runCurrent()
        fixture.controller.closePreview()
        delayedPreview.complete(Unit)
        runCurrent()
        assertNull(fixture.controller.state.value.previewTitle)
        assertEquals("", fixture.controller.state.value.previewText)
        assertFalse(fixture.controller.state.value.loading)
    }

    @Test fun cancelledEditRestoresTheOriginalDraftBeforeSwitchingChats() = controllerTest {
        val bridge = FakePlatformBridge()
        bridge.preferences["owner_old.draft.chat_2"] = "Second chat draft"
        val fixture = fixture(bridge)
        fixture.handler = { request -> if (request.url.encodedPath == "/api/sessions/chat_1") jsonResponse(editableHistory) else null }
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        fixture.controller.draft("First chat original draft")
        fixture.controller.editMessage("user_1")
        fixture.controller.draft("Edited historical message")
        fixture.controller.openChat("chat_2")
        runCurrent()
        assertNull(fixture.controller.state.value.editingMessageId)
        assertEquals("Second chat draft", fixture.controller.state.value.draft)
        assertEquals("First chat original draft", bridge.preferences["owner_old.draft.chat_1"])
        fixture.controller.openChat("chat_1")
        runCurrent()
        assertEquals("First chat original draft", fixture.controller.state.value.draft)
    }

    @Test fun newChatDoesNotCarryAHistoryForkFromThePreviousChat() = controllerTest {
        val bridge = FakePlatformBridge()
        bridge.preferences["owner_old.draft.new"] = "Fresh conversation draft"
        val fixture = fixture(bridge)
        fixture.handler = { request -> when {
            request.url.encodedPath == "/api/sessions/chat_1" -> jsonResponse(editableHistory)
            request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Post ->
                jsonResponse("""{"id":"fresh_job","session_id":"local_1","state":"queued"}""")
            else -> null
        } }
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        fixture.controller.draft("Existing draft")
        fixture.controller.editMessage("user_1")
        fixture.controller.newChat()
        assertNull(fixture.controller.state.value.editingMessageId)
        assertEquals("Fresh conversation draft", fixture.controller.state.value.draft)
        assertEquals("Existing draft", bridge.preferences["owner_old.draft.chat_1"])
        fixture.controller.send()
        runCurrent()
        assertEquals(1, fixture.requests.count { it.path == "/api/mobile/messages" && it.method == HttpMethod.Post })
        assertFalse(fixture.requests.any { it.path.endsWith("/fork") })
    }

    @Test fun switchingChatsCancelsDictationAndDiscardsItsLateNativeResult() = controllerTest {
        val fixture = fixture()
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        fixture.controller.startDictation()
        val oldResult = assertNotNull(fixture.bridge.recordingResult)
        fixture.controller.openChat("chat_2")
        runCurrent()
        fixture.controller.draft("Second conversation text")
        oldResult(PickedFile("recording.webm", "audio/webm", byteArrayOf(1, 2, 3)))
        runCurrent()
        assertFalse(fixture.controller.state.value.dictationOpen)
        assertFalse(fixture.controller.state.value.recording)
        assertEquals("Second conversation text", fixture.controller.state.value.draft)
        assertFalse(fixture.requests.any { it.path.startsWith("/api/asr") })
    }

    @Test fun switchingChatsClearsUploadBusyAndIgnoresItsLateAttachment() = controllerTest {
        val fixture = fixture()
        val uploaded = CompletableDeferred<Unit>()
        fixture.handler = { request -> if (request.url.encodedPath == "/api/chat/upload") {
            uploaded.await()
            jsonResponse("""{"url":"/uploads/old.txt","name":"old.txt","type":"text/plain","size":3}""")
        } else null }
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        fixture.controller.pickFile("document")
        fixture.bridge.pickedResult!!(PickedFile("old.txt", "text/plain", byteArrayOf(1, 2, 3)))
        runCurrent()
        assertTrue(fixture.controller.state.value.uploading)
        fixture.controller.openChat("chat_2")
        runCurrent()
        assertFalse(fixture.controller.state.value.uploading)
        uploaded.complete(Unit)
        runCurrent()
        assertTrue(fixture.controller.state.value.attachments.isEmpty())
    }

    @Test fun rejectedSendPersistsItsRestoredComposerDraft() = controllerTest {
        val fixture = fixture()
        fixture.handler = { request -> if (request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Post)
            jsonResponse("""{"detail":{"code":"invalid_message"}}""", HttpStatusCode.BadRequest) else null }
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        fixture.controller.draft("Rejected message recovery")
        fixture.controller.send()
        runCurrent()
        assertEquals("Rejected message recovery", fixture.controller.state.value.draft)
        assertEquals("Rejected message recovery", fixture.bridge.preferences["owner_old.draft.chat_1"], "Restored text must survive navigation and restart")
        val failed = persistedOutbox(fixture).single()
        assertEquals("invalid_message", failed.lastError)
        fixture.controller.newChat()
        fixture.controller.openChat("chat_1")
        runCurrent()
        assertEquals("Rejected message recovery", fixture.controller.state.value.draft)
    }

    @Test fun decliningOfflineDeliveryKeepsANewerDraftAndTheSubmittedRecovery() = controllerTest {
        val fixture = fixture()
        fixture.handler = { request -> if (request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Post)
            throw IllegalStateException("local simulated connection loss") else null }
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        fixture.controller.draft("Submitted recovery text")
        fixture.controller.send()
        runCurrent()
        assertTrue(fixture.controller.state.value.offlineQuestion)
        fixture.controller.draft("Newer unsent composer draft")
        fixture.controller.offlineDelivery(false)
        assertEquals("Newer unsent composer draft", fixture.controller.state.value.draft)
        assertEquals("Newer unsent composer draft", fixture.bridge.preferences["owner_old.draft.chat_1"])
        assertTrue(persistedOutbox(fixture).any { it.message == "Submitted recovery text" }, "Keep Draft must not discard the earlier submission when the composer is already occupied")
        assertFalse(fixture.controller.state.value.offlineQuestion)
        runCurrent()
        assertEquals(1, fixture.requests.count { it.path == "/api/mobile/messages" && it.method == HttpMethod.Post })
    }

    @Test fun decliningOneOfflineSubmissionDoesNotDeleteAnotherChatsPermanentFailure() = controllerTest {
        val bridge = FakePlatformBridge()
        val rejected = OutboxItem("rejected", "other_chat", "Permanent recovery", "regolo/model", "none", lastError = "invalid_attachment")
        bridge.preferences["owner_old.outbox.v1"] = Json.encodeToString(listOf(rejected))
        val fixture = fixture(bridge)
        fixture.handler = { request -> if (request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Post)
            throw IllegalStateException("local simulated connection loss") else null }
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        fixture.controller.draft("Temporary failure")
        fixture.controller.send()
        runCurrent()
        fixture.controller.offlineDelivery(false)
        assertTrue(persistedOutbox(fixture).any { it.clientId == "rejected" && it.lastError == "invalid_attachment" }, "Declining a new delivery must retain unrelated explicit-retry recovery")
    }

    @Test fun allowingTransientDeliveryDoesNotAutomaticallyRetryPermanentFailures() = controllerTest {
        val bridge = FakePlatformBridge()
        val rejected = OutboxItem("rejected", "other_chat", "Permanent recovery", "regolo/model", "none", lastError = "invalid_attachment")
        bridge.preferences["owner_old.outbox.v1"] = Json.encodeToString(listOf(rejected))
        val fixture = fixture(bridge)
        fixture.handler = { request -> if (request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Post)
            throw IllegalStateException("local simulated connection loss") else null }
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        fixture.controller.draft("Temporary failure")
        fixture.controller.send()
        runCurrent()
        fixture.controller.offlineDelivery(true)
        runCurrent()
        val submitted = fixture.requests.filter { it.path == "/api/mobile/messages" && it.method == HttpMethod.Post }
        assertTrue(submitted.isNotEmpty())
        assertFalse(submitted.any { it.body?.get("client_id")?.jsonPrimitive?.content == "rejected" })
        assertEquals("invalid_attachment", persistedOutbox(fixture).single { it.clientId == "rejected" }.lastError)
    }

    @Test fun declinedDeliveryStaysDeclinedInBackgroundUntilTheUserRetriesThatItem() = controllerTest {
        val fixture = fixture()
        var connectionLost = true
        fixture.handler = { request -> if (request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Post) {
            if (connectionLost) throw IllegalStateException("local simulated connection loss")
            jsonResponse("""{"id":"explicit_retry","session_id":"chat_1","state":"queued"}""")
        } else null }
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        fixture.controller.draft("Only retry on request")
        fixture.controller.send()
        runCurrent()
        fixture.controller.draft("Keep this newer draft")
        fixture.controller.offlineDelivery(false)
        val declined = persistedOutbox(fixture).single()
        assertTrue(declined.deliveryDeclined)
        fixture.bridge.foreground.value = false
        runCurrent()
        advanceTimeBy(5001)
        runCurrent()
        assertEquals(1, fixture.requests.count { it.path == "/api/mobile/messages" && it.method == HttpMethod.Post })
        fixture.bridge.foreground.value = true
        runCurrent()
        fixture.controller.offlineDelivery(true)
        runCurrent()
        assertEquals(1, fixture.requests.count { it.path == "/api/mobile/messages" && it.method == HttpMethod.Post }, "A blanket opt-in must not undo the explicit refusal for an earlier item")
        connectionLost = false
        fixture.controller.retryPending("local:${declined.clientId}")
        assertFalse(persistedOutbox(fixture).single().deliveryDeclined)
        runCurrent()
        val submissions = fixture.requests.filter { it.path == "/api/mobile/messages" && it.method == HttpMethod.Post }
        assertEquals(2, submissions.size)
        assertEquals(declined.clientId, submissions.last().body!!.getValue("client_id").jsonPrimitive.content)
        assertEquals("Only retry on request", submissions.last().body!!.getValue("message").jsonPrimitive.content)
        assertTrue(persistedOutbox(fixture).isEmpty())
        assertEquals("Keep this newer draft", fixture.controller.state.value.draft)
    }

    @Test fun explicitRetryClearsPermanentFailureMarkersBeforeATransientFailure() = controllerTest {
        val bridge = FakePlatformBridge()
        val rejected = OutboxItem("rejected", "chat_1", "Manual recovery", "regolo/model", "none", lastError = "invalid_attachment", deliveryDeclined = true)
        bridge.preferences["owner_old.outbox.v1"] = Json.encodeToString(listOf(rejected))
        val fixture = fixture(bridge)
        fixture.handler = { request -> if (request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Post)
            throw IllegalStateException("local simulated connection loss") else null }
        runCurrent()
        fixture.controller.retryPending("local:rejected")
        runCurrent()
        val retry = persistedOutbox(fixture).single()
        assertNull(retry.lastError)
        assertFalse(retry.deliveryDeclined)
        assertTrue(Json.decodeFromString<List<String>>(bridge.preferences.getValue("owner_old.outbox.allowed")).contains("rejected"))
        assertTrue(fixture.controller.state.value.offlineQuestion)
    }

    @Test fun foregroundSendDoesNotResurrectAnOutboxItemAcknowledgedInBackground() = controllerTest {
        val bridge = FakePlatformBridge()
        val acknowledged = OutboxItem("worker_ack", "chat_1", "Already delivered by the worker", "regolo/model", "none")
        backgroundOutbox(bridge, listOf(acknowledged), listOf(acknowledged.clientId))
        val fixture = fixture(bridge)
        fixture.handler = { request -> if (request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Post)
            throw IllegalStateException("local simulated connection loss") else null }
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        assertEquals(listOf(acknowledged), persistedOutbox(fixture))

        // The worker removes its acknowledged item after the controller has
        // captured the original queue. A new local send must respect that removal.
        backgroundOutbox(bridge, emptyList())
        fixture.controller.draft("New foreground submission")
        fixture.controller.send()
        runCurrent()
        val retained = persistedOutbox(fixture).single()
        assertEquals("New foreground submission", retained.message)
        assertFalse(retained.clientId == acknowledged.clientId)
        assertFalse(acknowledged.clientId in persistedAllowed(fixture))
        fixture.controller.navigate(Screen.Queue)
        runCurrent()
        assertFalse(fixture.controller.state.value.allPending.any { it.id == "local:${acknowledged.clientId}" })
        assertEquals(1, fixture.requests.count { it.body?.get("client_id")?.jsonPrimitive?.content == acknowledged.clientId }, "A foreground save must not retry the worker's acknowledged message")
    }

    @Test fun unrelatedForegroundSendRetainsABackgroundPermanentFailure() = controllerTest {
        val bridge = FakePlatformBridge()
        val queued = OutboxItem("worker_failed", "other_chat", "Background recovery text", "regolo/model", "none")
        backgroundOutbox(bridge, listOf(queued), listOf(queued.clientId))
        val fixture = fixture(bridge)
        fixture.handler = { request -> if (request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Post)
            throw IllegalStateException("local simulated connection loss") else null }
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()

        val failed = queued.copy(lastError = "invalid_attachment")
        backgroundOutbox(bridge, listOf(failed))
        fixture.controller.draft("Unrelated foreground submission")
        fixture.controller.send()
        runCurrent()
        assertEquals(failed, persistedOutbox(fixture).single { it.clientId == failed.clientId }, "An unchanged local snapshot must not erase the worker's permanent failure")
        assertFalse(failed.clientId in persistedAllowed(fixture))
        advanceTimeBy(5001)
        runCurrent()
        fixture.controller.navigate(Screen.Queue)
        runCurrent()
        val recovery = fixture.controller.state.value.allPending.single { it.id == "local:${failed.clientId}" }
        assertEquals("failed", recovery.state)
        assertEquals(failed.message, recovery.text)
        assertEquals(1, fixture.requests.count { it.body?.get("client_id")?.jsonPrimitive?.content == failed.clientId }, "The worker's permanent failure must stay outside automatic retry")
    }

    @Test fun foregroundSendPreservesANewerBackgroundOutboxItemAndItsDeliveryPermission() = controllerTest {
        val fixture = fixture()
        fixture.handler = { request -> if (request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Post)
            throw IllegalStateException("local simulated connection loss") else null }
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        val background = OutboxItem("worker_added", "other_chat", "New background queue item", "regolo/model", "low")
        backgroundOutbox(fixture.bridge, listOf(background), listOf(background.clientId))

        fixture.controller.draft("New foreground queue item")
        fixture.controller.send()
        runCurrent()
        val merged = persistedOutbox(fixture)
        assertEquals(2, merged.size)
        assertEquals(background, merged.single { it.clientId == background.clientId })
        assertEquals("New foreground queue item", merged.single { it.clientId != background.clientId }.message)
        assertTrue(background.clientId in persistedAllowed(fixture), "An unrelated local save must retain the worker's delivery permission")
        fixture.controller.navigate(Screen.Queue)
        runCurrent()
        val visible = fixture.controller.state.value.allPending.single { it.id == "local:${background.clientId}" }
        assertEquals(background.sessionId, visible.sessionId)
        assertEquals(background.message, visible.text)
    }

    @Test fun explicitForegroundCancelRemovesAnItemWhilePreservingBackgroundAdditions() = controllerTest {
        val bridge = FakePlatformBridge()
        val cancelled = OutboxItem("cancel_me", "chat_1", "Explicitly cancelled recovery", "regolo/model", "none")
        backgroundOutbox(bridge, listOf(cancelled), listOf(cancelled.clientId))
        val fixture = fixture(bridge)
        fixture.handler = { request -> if (request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Post)
            throw IllegalStateException("local simulated connection loss") else null }
        runCurrent()
        fixture.controller.navigate(Screen.Queue)
        runCurrent()
        assertTrue(fixture.controller.state.value.allPending.any { it.id == "local:${cancelled.clientId}" })

        val background = OutboxItem("worker_added", "other_chat", "Retain this background item", "regolo/model", "low")
        backgroundOutbox(bridge, listOf(cancelled.copy(lastError = "invalid_attachment"), background), listOf(background.clientId))
        fixture.controller.cancelPending("local:${cancelled.clientId}")
        runCurrent()
        assertEquals(listOf(background), persistedOutbox(fixture), "An explicit local deletion must override the worker's update to that item")
        assertEquals(setOf(background.clientId), persistedAllowed(fixture))
        assertFalse(fixture.controller.state.value.allPending.any { it.id == "local:${cancelled.clientId}" })
        assertTrue(fixture.controller.state.value.allPending.any { it.id == "local:${background.clientId}" })
    }

    @Test fun outboxStorageFailureLeavesTheComposerAndItsDraftIntact() = controllerTest {
        val fixture = fixture()
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        fixture.controller.draft("Durable unsent draft")
        fixture.bridge.beforePreferenceWrite = { key, _ -> if (key == "owner_old.outbox.v1") throw IllegalStateException("local simulated full storage") }
        fixture.controller.send()
        runCurrent()
        assertEquals("Durable unsent draft", fixture.controller.state.value.draft)
        assertEquals("Durable unsent draft", fixture.bridge.preferences["owner_old.draft.chat_1"])
        assertEquals("error.storage", fixture.controller.state.value.error)
        assertFalse(fixture.requests.any { it.path == "/api/mobile/messages" && it.method == HttpMethod.Post })
    }

    @Test fun selectingANewModelPersistsItsResetEffortBeforeTheChatIsReopened() = controllerTest {
        val fixture = fixture()
        fixture.handler = { request -> if (request.url.encodedPath == "/api/brain/models") jsonResponse(modelCatalog) else null }
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        fixture.controller.selectModel("regolo/deep")
        fixture.controller.selectEffort("high")
        fixture.controller.selectModel("regolo/fast")
        assertEquals("none", fixture.controller.state.value.effort)
        assertEquals("none", fixture.controller.state.value.preferences.chatEfforts["chat_1"], "Resetting the visible effort must reset the conversation's saved effort too")
        fixture.controller.openChat("chat_2")
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        assertEquals("regolo/fast", fixture.controller.state.value.selectedModel)
        assertEquals("none", fixture.controller.state.value.effort)
    }

    @Test fun explicitOffRemainsDistinctFromInheritingTheGatewayDefault() = controllerTest {
        val fixture = fixture()
        runCurrent()
        assertEquals(listOf("none", "off", "low"), fixture.controller.state.value.models.single().efforts)
        fixture.controller.selectEffort("off")
        assertEquals("off", fixture.controller.state.value.effort)
        fixture.controller.selectEffort("none")
        assertEquals("none", fixture.controller.state.value.effort)
    }

    @Test fun reopeningAChatNormalizesItsPreviouslySavedUnsupportedEffort() = controllerTest {
        val bridge = FakePlatformBridge()
        bridge.preferences["preferences.v1"] = Json.encodeToString(Preferences(chatModels = mapOf("chat_1" to "regolo/fast"), chatEfforts = mapOf("chat_1" to "high")))
        val fixture = fixture(bridge)
        fixture.handler = { request -> if (request.url.encodedPath == "/api/brain/models") jsonResponse(modelCatalog) else null }
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        assertEquals("regolo/fast", fixture.controller.state.value.selectedModel)
        assertEquals("none", fixture.controller.state.value.effort)
        assertEquals("none", fixture.controller.state.value.preferences.chatEfforts["chat_1"])
    }

    @Test fun aLateStopAcknowledgementCannotPauseTheConversationOpenedAfterIt() = controllerTest {
        val fixture = fixture()
        val stopAcknowledged = CompletableDeferred<Unit>()
        fixture.handler = { request -> when {
            request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Get ->
                jsonResponse(if (request.url.parameters["session_id"] == "chat_1")
                    """{"messages":[{"id":"active","session_id":"chat_1","state":"running"}]}"""
                else """{"messages":[]}""")
            request.url.encodedPath == "/api/mobile/messages/active/stop" -> {
                stopAcknowledged.await()
                jsonResponse("""{"id":"active","session_id":"chat_1","state":"stopped"}""")
            }
            else -> null
        } }
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        assertEquals("active", fixture.controller.state.value.activeJobId)
        fixture.controller.stop()
        runCurrent()
        fixture.controller.openChat("chat_2")
        runCurrent()
        assertFalse(fixture.controller.state.value.queuePaused)
        stopAcknowledged.complete(Unit)
        runCurrent()
        assertEquals("chat_2", fixture.controller.state.value.sessionId)
        assertFalse(fixture.controller.state.value.queuePaused, "Stop belongs to the captured conversation, including its acknowledgement")
    }

    @Test fun retryingAServerFailureCannotDeliverItsOldOwnersMessageAfterPairing() = controllerTest {
        val fixture = fixture()
        val oldJobFetched = CompletableDeferred<Unit>()
        var retryStarted = false
        fixture.handler = { request -> when {
            request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Get -> {
                if (request.url.host == "old.example") {
                    if (retryStarted && request.url.parameters["session_id"] == "old_chat") oldJobFetched.await()
                    jsonResponse("""{"messages":[{"id":"server_failed","session_id":"old_chat","state":"failed","message":"Old owner's retry text","model":"regolo/model","reasoning_effort":"none"}]}""")
                } else jsonResponse("""{"messages":[]}""")
            }
            request.url.encodedPath == "/api/mobile/sessions/old_chat/resume" ->
                jsonResponse("""{"ok":true,"paused":false,"session_id":"old_chat"}""")
            request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Post ->
                jsonResponse("""{"id":"retried","session_id":"old_chat","state":"queued"}""")
            else -> null
        } }
        runCurrent()
        fixture.controller.navigate(Screen.Queue)
        runCurrent()
        assertTrue(fixture.controller.state.value.allPending.any { it.id == "server_failed" })
        retryStarted = true
        fixture.controller.retryPending("server_failed")
        runCurrent()
        fixture.controller.disconnect()
        fixture.pairNewOwner()
        runCurrent()
        oldJobFetched.complete(Unit)
        runCurrent()
        assertFalse(fixture.requests.any { it.host == "new.example" && it.path == "/api/mobile/sessions/old_chat/resume" }, "A server-job retry must retain its connection identity after fetching the old job")
        assertFalse(fixture.requests.any { it.host == "new.example" && it.path == "/api/mobile/messages" && it.method == HttpMethod.Post })
        assertFalse(fixture.bridge.preferences.filterKeys { it.startsWith("owner_new.") }.values.any { it.contains("Old owner's retry text") })
    }

    @Test fun unsupportedEffortCannotBeSavedOrSentForTheSelectedModel() = controllerTest {
        val fixture = fixture()
        fixture.handler = { request -> when {
            request.url.encodedPath == "/api/brain/models" -> jsonResponse(modelCatalog)
            request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Post ->
                jsonResponse("""{"id":"job","session_id":"chat_1","state":"queued"}""")
            else -> null
        } }
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        fixture.controller.selectModel("regolo/fast")
        fixture.controller.selectEffort("high")
        assertEquals("none", fixture.controller.state.value.effort)
        fixture.controller.draft("Use a supported effort")
        fixture.controller.send()
        runCurrent()
        val submitted = fixture.requests.single { it.path == "/api/mobile/messages" && it.method == HttpMethod.Post }.body!!
        assertEquals("none", submitted.getValue("reasoning_effort").jsonPrimitive.content)
    }

    @Test fun oldHistoryFailureCannotReportAnAuthErrorOnANewConnection() = controllerTest {
        val fixture = fixture()
        val oldHistory = CompletableDeferred<Unit>()
        fixture.handler = { request -> if (request.url.encodedPath == "/api/sessions/chat_1" && request.url.host == "old.example") {
            oldHistory.await()
            jsonResponse("""{"detail":{"code":"device_revoked"}}""", HttpStatusCode.Unauthorized)
        } else null }
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        fixture.controller.disconnect()
        fixture.pairNewOwner()
        runCurrent()
        assertTrue(fixture.controller.state.value.connected)
        oldHistory.complete(Unit)
        runCurrent()
        assertNull(fixture.controller.state.value.error, "A retired connection must not report authentication failures to its replacement")
        assertEquals("https://new.example", fixture.controller.state.value.baseUrl)
        assertTrue(fixture.controller.state.value.connected)
    }

    @Test fun aPreviousChatsDelayedHistoryFailureCannotEndTheCurrentChatsLoading() = controllerTest {
        val fixture = fixture()
        val oldHistoryFailure = CompletableDeferred<Unit>()
        val currentHistory = CompletableDeferred<Unit>()
        fixture.handler = { request -> when (request.url.encodedPath) {
            "/api/sessions/chat_1" -> {
                oldHistoryFailure.await()
                jsonResponse("""{"detail":{"code":"history_unavailable"}}""", HttpStatusCode.ServiceUnavailable)
            }
            "/api/sessions/chat_2" -> {
                currentHistory.await()
                jsonResponse("""{"messages":[{"id":"current_reply","role":"assistant","content":"Current chat history"}]}""")
            }
            else -> null
        } }
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        fixture.controller.openChat("chat_2")
        runCurrent()
        assertTrue(fixture.requests.any { it.path == "/api/sessions/chat_1" })
        assertTrue(fixture.requests.any { it.path == "/api/sessions/chat_2" })
        assertTrue(fixture.controller.state.value.loading)

        // Navigation invalidates failures as well as successful history updates.
        oldHistoryFailure.complete(Unit)
        runCurrent()
        assertEquals("chat_2", fixture.controller.state.value.sessionId)
        assertNull(fixture.controller.state.value.error, "The previous chat's failure must not appear in the current chat")
        assertTrue(fixture.controller.state.value.loading, "Only the current history request may finish loading")
        assertTrue(fixture.controller.state.value.messages.isEmpty())

        currentHistory.complete(Unit)
        runCurrent()
        assertEquals("current_reply", fixture.controller.state.value.messages.single().id)
        assertFalse(fixture.controller.state.value.loading)
        assertNull(fixture.controller.state.value.error)
    }

    @Test fun recoveredFileDraftRetainsItsOriginalBaselineAfterRemoteChanges() = controllerTest {
        val bridge = FakePlatformBridge()
        val first = fixture(bridge)
        first.handler = { request -> if (request.url.encodedPath == "/api/mobile/workspace/file")
            jsonResponse("""{"path":"notes.md","content":"Original baseline","binary":false,"revision":"r1"}""") else null }
        runCurrent()
        first.controller.openFile("notes.md")
        runCurrent()
        first.controller.fileText("Local recovery")
        first.close()
        runCurrent()

        val restored = fixture(bridge)
        restored.handler = { request -> if (request.url.encodedPath == "/api/mobile/workspace/file")
            jsonResponse("""{"path":"notes.md","content":"Changed by another device","binary":false,"revision":"r2"}""") else null }
        runCurrent()
        restored.controller.openFile("notes.md")
        runCurrent()
        restored.controller.retryFileSave()
        runCurrent()
        assertEquals("Local recovery", restored.controller.state.value.fileText)
        assertEquals("failed", restored.controller.state.value.fileSaveState)
        assertEquals("error.fileConflict", restored.controller.state.value.error)
        assertEquals("Local recovery", bridge.preferences["owner_old.file.notes.md"])
        assertFalse(restored.requests.any { it.path == "/api/mobile/workspace/file" && it.method == HttpMethod.Post }, "Restart must not adopt the changed remote file as the original baseline")
    }

    private fun persistedOutbox(fixture: ControllerTestFixture): List<OutboxItem> =
        Json.decodeFromString(fixture.bridge.preferences["owner_old.outbox.v1"].orEmpty().ifEmpty { "[]" })

    private fun persistedAllowed(fixture: ControllerTestFixture): Set<String> =
        Json.decodeFromString(fixture.bridge.preferences["owner_old.outbox.allowed"].orEmpty().ifEmpty { "[]" })

    private fun backgroundOutbox(bridge: FakePlatformBridge, items: List<OutboxItem>, allowed: List<String> = emptyList()) {
        val queueKey = "owner_old.outbox.v1"
        val allowedKey = "owner_old.outbox.allowed"
        bridge.updatePreferences(listOf(queueKey, allowedKey)) {
            mapOf(queueKey to Json.encodeToString(items), allowedKey to Json.encodeToString(allowed))
        }
    }

    private companion object {
        const val editableHistory = """{"messages":[{"id":"user_1","role":"user","content":"Historical user message"}]}"""
        const val attachmentHistory = """{"messages":[{"id":"user_1","role":"user","content":"Attached files","attachments":[{"url":"/uploads/old.txt","name":"old.txt","type":"text/plain","size":3},{"url":"/uploads/current.txt","name":"current.txt","type":"text/plain","size":3}]}]}"""
        const val modelCatalog = """{"models":[{"id":"regolo/deep","available":true,"efforts":["none","high"]},{"id":"regolo/fast","available":true,"efforts":["none","low"]}],"selected":"regolo/deep","thinking_levels":[]}"""
    }
}
