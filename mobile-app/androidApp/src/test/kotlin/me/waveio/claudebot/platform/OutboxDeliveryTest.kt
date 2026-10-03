package me.waveio.claudebot.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.waveio.claudebot.data.ApiFailure
import me.waveio.claudebot.data.Attachment
import me.waveio.claudebot.data.MobileJob
import me.waveio.claudebot.state.DraftAttachment
import me.waveio.claudebot.state.OutboxItem
import org.junit.Assert.*
import org.junit.Test

class OutboxDeliveryTest {
    private val identity = OutboxIdentity("fixture-device", "https://fixture.invalid", "fixture-token", "epoch-1")

    private fun item(id: String = "client-1") = OutboxItem(
        clientId = id, sessionId = "session-1", message = "Fixture message",
        model = "fixture/model", effort = "low",
    )

    private fun acknowledgement(id: String) = MobileJob(
        id = "job-$id", sessionId = "server-session", state = "queued", clientId = id,
    )

    private data class Failure(val item: OutboxItem, val status: Int, val code: String)
    private data class Fork(
        val sessionId: String, val messageId: String, val action: String,
        val clientId: String, val message: String?, val model: String, val effort: String,
    )

    private inner class Store(vararg entries: OutboxItem) : OutboxDeliveryStore {
        val items = entries.toMutableList()
        val blocked = mutableSetOf<String>()
        val acknowledged = mutableListOf<OutboxItem>()
        val failures = mutableListOf<Failure>()
        var current = identity
        var accountBlocked = false
        var eligibleCalls = 0
        var beforeSnapshot: () -> Unit = {}
        var beforeAcknowledge: () -> Unit = {}
        var acceptAcknowledgement = true

        override fun isCurrent(identity: OutboxIdentity): Boolean = current == identity
        override fun eligible(identity: OutboxIdentity): List<OutboxItem> {
            eligibleCalls++
            beforeSnapshot()
            return if (!isCurrent(identity) || accountBlocked) emptyList()
            else items.filter { it.clientId !in blocked }
        }

        override fun acknowledge(identity: OutboxIdentity, item: OutboxItem): Boolean {
            beforeAcknowledge()
            if (!isCurrent(identity) || !acceptAcknowledgement || !items.remove(item)) return false
            acknowledged += item
            return true
        }

        override fun permanentFailure(identity: OutboxIdentity, item: OutboxItem, status: Int, code: String) {
            if (!isCurrent(identity) || item !in items) return
            failures += Failure(item, status, code)
            blocked += item.clientId
            if (status == 401 || status == 403) accountBlocked = true
        }
    }

    private inner class Api : OutboxDeliveryApi {
        val submitted = mutableListOf<OutboxItem>()
        val forks = mutableListOf<Fork>()
        val sentIds = mutableListOf<String>()
        var closes = 0
        var reply: suspend (String) -> MobileJob = { acknowledgement(it) }

        override suspend fun submitMessage(
            clientId: String, sessionId: String, message: String,
            attachments: List<Attachment>, model: String, reasoningEffort: String,
            delivery: String, scheduledAt: String?,
        ): MobileJob {
            sentIds += clientId
            submitted += OutboxItem(
                clientId, sessionId, message, model, reasoningEffort, delivery, scheduledAt,
                attachments.map { DraftAttachment(it.path, it.name.orEmpty(), it.mimeType.orEmpty(), it.size ?: -1) },
            )
            return reply(clientId)
        }

        override suspend fun forkMessage(
            sessionId: String, messageId: String, action: String, clientId: String,
            message: String?, model: String, effort: String,
        ): MobileJob {
            sentIds += clientId
            forks += Fork(sessionId, messageId, action, clientId, message, model, effort)
            return reply(clientId)
        }

        override fun close() { closes++ }
    }

    @Test fun identityDiagnosticsRedactEveryAccountField() {
        assertEquals("OutboxIdentity(redacted)", identity.toString())
        listOf(identity.deviceId, identity.server, identity.token, identity.epoch).forEach {
            assertFalse(identity.toString().contains(it))
        }
        assertNotEquals(identity, identity.copy(epoch = "epoch-2"))
    }

    @Test fun submissionPreservesEveryPersistedFieldAndUsesOneCapturedClient() = runBlocking {
        val queued = item().copy(
            delivery = "steer", scheduledAt = "2026-10-04T10:00:00Z",
            attachments = listOf(DraftAttachment("/uploads/fixture.pdf", "fixture.pdf", "application/pdf", 42)),
        )
        val store = Store(queued, item("client-2").copy(sessionId = ""))
        val api = Api()
        val snapshots = mutableListOf<OutboxIdentity>()
        val engine = OutboxDelivery { snapshots += it; api }
        assertEquals(DeliveryOutcome.COMPLETE, engine.drain(identity, store))
        assertEquals(listOf(identity), snapshots)
        assertEquals(queued, api.submitted.first())
        assertEquals("", api.submitted.last().sessionId)
        assertEquals(listOf(queued, api.submitted.last()), store.acknowledged)
        assertTrue(store.items.isEmpty())
        assertEquals(1, api.closes)
    }

    @Test fun editAndRegenerateMapOnlyToTheForkEndpointWithStableIds() = runBlocking {
        val edit = item("edit-client").copy(forkMessageId = "saved-user", forkAction = "edit")
        val regenerate = item("regenerate-client").copy(forkMessageId = "saved-assistant", forkAction = "regenerate")
        val store = Store(edit, regenerate)
        val api = Api()
        assertEquals(DeliveryOutcome.COMPLETE, OutboxDelivery { api }.drain(identity, store))
        assertTrue(api.submitted.isEmpty())
        assertEquals(listOf(
            Fork(edit.sessionId, "saved-user", "edit", edit.clientId, edit.message, edit.model, edit.effort),
            Fork(regenerate.sessionId, "saved-assistant", "regenerate", regenerate.clientId, null, regenerate.model, regenerate.effort),
        ), api.forks)
        assertEquals(listOf(edit, regenerate), store.acknowledged)
    }

    @Test fun malformedAndUnsupportedForksAreRetainedAndDoNotPreventOtherDelivery() = runBlocking {
        val fork = item().copy(forkMessageId = "saved-user", forkAction = "edit")
        val cases = listOf(
            fork.copy(forkAction = null) to "invalid_fork",
            fork.copy(forkMessageId = null) to "invalid_fork",
            fork.copy(forkMessageId = " ") to "invalid_fork",
            fork.copy(forkAction = "delete") to "invalid_fork",
            fork.copy(sessionId = "") to "invalid_fork",
            fork.copy(attachments = listOf(DraftAttachment("/uploads/fixture.pdf", "fixture.pdf", "application/pdf", 42))) to "unsupported_fork_attachments",
            fork.copy(delivery = "steer") to "unsupported_fork_delivery",
            fork.copy(scheduledAt = "2026-10-04T10:00:00Z") to "unsupported_fork_schedule",
            item().copy(clientId = " ") to "invalid_client_id",
            item().copy(delivery = "invalid") to "invalid_delivery",
        )
        for ((invalid, code) in cases) {
            val other = item("other-client")
            val store = Store(invalid, other)
            val api = Api()
            assertEquals(code, DeliveryOutcome.COMPLETE, OutboxDelivery { api }.drain(identity, store))
            assertEquals(listOf(other.clientId), api.sentIds)
            assertEquals(listOf(Failure(invalid, 0, code)), store.failures)
            assertEquals(listOf(invalid), store.items)
        }
    }

    @Test fun transientFailuresRemainEligibleAndDoNotStopTheBatch() = runBlocking {
        val cases = listOf(0, 408, 425, 429, 500, 502, 503, 504, 599)
        for (status in cases) {
            val first = item()
            val other = item("other-client")
            val store = Store(first, other)
            val api = Api().apply { reply = { id ->
                if (id == first.clientId) throw ApiFailure(status, if (status == 0) "network_error" else "fixture_failure")
                acknowledgement(id)
            } }
            assertEquals("status=$status", DeliveryOutcome.RETRY, OutboxDelivery { api }.drain(identity, store))
            assertEquals(listOf(first), store.items)
            assertEquals(listOf(other), store.acknowledged)
            assertTrue(store.failures.isEmpty())
            assertEquals(1, api.closes)
        }
    }

    @Test fun permanentFailuresAreRetainedBlockedAndSkippedOnLaterRuns() = runBlocking {
        for (status in listOf(0, 301, 400, 404, 409, 413, 422, 499, 501)) {
            val first = item()
            val other = item("other-client")
            val store = Store(first, other)
            val api = Api().apply { reply = { id ->
                if (id == first.clientId) throw ApiFailure(status, "fixture_failure")
                acknowledgement(id)
            } }
            val engine = OutboxDelivery { api }
            assertEquals("status=$status", DeliveryOutcome.COMPLETE, engine.drain(identity, store))
            assertEquals(listOf(Failure(first, status, "fixture_failure")), store.failures)
            assertEquals(listOf(first), store.items)
            assertEquals(listOf(first.clientId, other.clientId), api.sentIds)
            assertEquals(DeliveryOutcome.COMPLETE, engine.drain(identity, store))
            assertEquals(2, api.sentIds.size)
            assertEquals(1, api.closes)
        }
    }

    @Test fun authorizationAndPermissionFailuresStopTheWholeAccount() = runBlocking {
        for (status in listOf(401, 403)) {
            val first = item()
            val other = item("other-client")
            val store = Store(first, other)
            val api = Api().apply { reply = { throw ApiFailure(status, "fixture_denied") } }
            assertEquals(DeliveryOutcome.COMPLETE, OutboxDelivery { api }.drain(identity, store))
            assertEquals(listOf(first.clientId), api.sentIds)
            assertEquals(listOf(first, other), store.items)
            assertEquals(listOf(Failure(first, status, "fixture_denied")), store.failures)
            assertTrue(store.accountBlocked)
            assertEquals(1, api.closes)
        }
    }

    @Test fun malformedMissingOrMismatchedAcknowledgementsNeverRemoveItems() = runBlocking {
        val original = item()
        val replies: List<suspend (String) -> MobileJob> = listOf(
            { acknowledgement(it).copy(id = "") },
            { acknowledgement(it).copy(id = " \t") },
            { acknowledgement(it).copy(clientId = null) },
            { acknowledgement(it).copy(clientId = "another-client") },
            { throw ApiFailure(200, "invalid_response") },
            { throw ApiFailure(204, "invalid_response") },
        )
        for (reply in replies) {
            val other = item("other-client")
            val store = Store(original, other)
            val api = Api().apply { this.reply = { id -> if (id == original.clientId) reply(id) else acknowledgement(id) } }
            assertEquals(DeliveryOutcome.COMPLETE, OutboxDelivery { api }.drain(identity, store))
            assertEquals(listOf(original), store.items)
            assertEquals(listOf(other), store.acknowledged)
            assertEquals("invalid_ack", store.failures.single().code)
            assertEquals(1, api.closes)
        }
    }

    @Test fun rejectedAtomicAcknowledgementIsRetainedForForegroundRecovery() = runBlocking {
        val original = item()
        val store = Store(original).apply { acceptAcknowledgement = false }
        val api = Api()
        assertEquals(DeliveryOutcome.COMPLETE, OutboxDelivery { api }.drain(identity, store))
        assertEquals(listOf(original), store.items)
        assertEquals(listOf(Failure(original, 200, "invalid_ack")), store.failures)
        assertTrue(store.acknowledged.isEmpty())
    }

    @Test fun lostAcknowledgementRetriesReuseTheSameClientIdAndPayload() = runBlocking {
        val original = item().copy(attachments = listOf(DraftAttachment("/uploads/fixture.txt", "fixture.txt", "text/plain", 9)))
        val store = Store(original)
        val failed = Api().apply { reply = { throw ApiFailure(0, "network_error") } }
        val recovered = Api()
        val clients = ArrayDeque(listOf(failed, recovered))
        val engine = OutboxDelivery { clients.removeFirst() }
        assertEquals(DeliveryOutcome.RETRY, engine.drain(identity, store))
        assertEquals(DeliveryOutcome.COMPLETE, engine.drain(identity, store))
        assertEquals(listOf(original), failed.submitted)
        assertEquals(failed.submitted, recovered.submitted)
        assertEquals(1, failed.closes)
        assertEquals(1, recovered.closes)
    }

    @Test fun staleIdentityNeverReadsTheQueueOrCreatesAClient() = runBlocking {
        val store = Store(item()).apply { current = identity.copy(epoch = "epoch-2") }
        assertEquals(DeliveryOutcome.STALE, OutboxDelivery { error("No client should be created") }.drain(identity, store))
        assertEquals(0, store.eligibleCalls)
        assertTrue(store.acknowledged.isEmpty())
    }

    @Test fun accountChangeDuringSnapshotOrClientCreationPreventsAnyRequest() = runBlocking {
        val store = Store(item()).apply { beforeSnapshot = { current = identity.copy(epoch = "epoch-2") } }
        assertEquals(DeliveryOutcome.STALE, OutboxDelivery { error("Stale snapshot") }.drain(identity, store))
        val raced = Store(item())
        val api = Api()
        assertEquals(DeliveryOutcome.STALE, OutboxDelivery { captured ->
            assertEquals(identity, captured)
            raced.current = identity.copy(server = "https://other-fixture.invalid", token = "other-fixture-token")
            api
        }.drain(identity, raced))
        assertTrue(api.sentIds.isEmpty())
        assertEquals(1, api.closes)
    }

    @Test fun accountSwapWhileAwaitingReplyNeverAcknowledgesOrBlocksTheNewAccount() = runBlocking {
        withTimeout(5_000) {
            for (failure in listOf(null, ApiFailure(403, "fixture_denied"))) {
                val original = item()
                val store = Store(original, item("other-client"))
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val api = Api().apply { reply = { id ->
                    started.complete(Unit)
                    release.await()
                    if (failure != null) throw failure
                    acknowledgement(id)
                } }
                var outcome: DeliveryOutcome? = null
                val delivery = launch { outcome = OutboxDelivery { api }.drain(identity, store) }
                started.await()
                store.current = identity.copy(deviceId = "other-device", token = "other-fixture-token", epoch = "epoch-2")
                release.complete(Unit)
                delivery.join()
                assertEquals(DeliveryOutcome.STALE, outcome)
                assertEquals(listOf(original.clientId), api.sentIds)
                assertTrue(store.acknowledged.isEmpty())
                assertTrue(store.failures.isEmpty())
                assertEquals(2, store.items.size)
                assertEquals(1, api.closes)
            }
        }
    }

    @Test fun accountSwapInAtomicAcknowledgementStopsBeforeTheNextRequest() = runBlocking {
        val store = Store(item(), item("other-client")).apply {
            beforeAcknowledge = { current = identity.copy(epoch = "epoch-2") }
        }
        val api = Api()
        assertEquals(DeliveryOutcome.STALE, OutboxDelivery { api }.drain(identity, store))
        assertEquals(listOf("client-1"), api.sentIds)
        assertTrue(store.acknowledged.isEmpty())
        assertTrue(store.failures.isEmpty())
        assertEquals(1, api.closes)
    }

    @Test fun replacementDuringReplyIsNotRemovedOrBlockedByAnOldAcknowledgement() = runBlocking {
        val original = item()
        val replacement = original.copy(message = "Changed fixture")
        val store = Store(original)
        val api = Api().apply { reply = { id ->
            store.items[0] = replacement
            acknowledgement(id)
        } }
        assertEquals(DeliveryOutcome.RETRY, OutboxDelivery { api }.drain(identity, store))
        assertEquals(listOf(replacement), store.items)
        assertTrue(store.acknowledged.isEmpty())
        assertTrue(store.failures.isEmpty())
    }

    @Test fun newlyQueuedEntriesWaitForAnotherWorkerTrigger() = runBlocking {
        val original = item()
        val added = item("added-client")
        val store = Store(original)
        val api = Api().apply { reply = { id -> store.items += added; acknowledgement(id) } }
        assertEquals(DeliveryOutcome.RETRY, OutboxDelivery { api }.drain(identity, store))
        assertEquals(listOf(original.clientId), api.sentIds)
        assertEquals(listOf(added), store.items)
        assertEquals(4, store.eligibleCalls)
    }

    @Test fun mutableAttachmentMetadataCannotChangeTheCapturedRequest() = runBlocking {
        val originalAttachment = DraftAttachment("/uploads/fixture.txt", "fixture.txt", "text/plain", 9)
        val attachments = mutableListOf(originalAttachment)
        val original = item().copy(attachments = attachments)
        val store = Store(original)
        val api = Api()
        val engine = OutboxDelivery {
            attachments[0] = originalAttachment.copy(name = "changed.txt")
            api
        }
        assertEquals(DeliveryOutcome.RETRY, engine.drain(identity, store))
        assertTrue(api.submitted.isEmpty())
        assertEquals(listOf(original), store.items)
        assertTrue(store.acknowledged.isEmpty())
        assertTrue(store.failures.isEmpty())
        assertEquals(1, api.closes)
    }

    @Test fun aRunSendsAtMostFiftyEntriesAndRequestsAWorkerRetryForTheRemainder() = runBlocking {
        val entries = (1..73).map { item("client-$it") }
        val store = Store(*entries.toTypedArray())
        val api = Api()
        assertEquals(DeliveryOutcome.RETRY, OutboxDelivery { api }.drain(identity, store))
        assertEquals(entries.take(50).map { it.clientId }, api.sentIds)
        assertEquals(entries.drop(50), store.items)
        assertEquals(102, store.eligibleCalls)
        assertEquals(1, api.closes)
    }

    @Test fun emptyQueueDoesNotAllocateAClient() = runBlocking {
        assertEquals(DeliveryOutcome.COMPLETE, OutboxDelivery { error("Empty queue") }.drain(identity, Store()))
    }

    @Test fun failedEntriesNeverSendEvenIfTheStoreReturnsThemAsEligible() = runBlocking {
        val failed = item().copy(lastError = "invalid_request")
        val store = Store(failed)
        assertEquals(DeliveryOutcome.COMPLETE, OutboxDelivery { error("Failed entry cannot open a client") }.drain(identity, store))
        assertEquals(listOf(failed), store.items)
        assertTrue(store.failures.isEmpty())
    }

    @Test fun declinedEntriesNeverSendEvenIfTheStoreReturnsThemAsEligible() = runBlocking {
        val refused = item().copy(deliveryDeclined = true)
        val store = Store(refused)
        assertEquals(DeliveryOutcome.COMPLETE, OutboxDelivery { error("Refused entry cannot open a client") }.drain(identity, store))
        assertEquals(listOf(refused), store.items)
        assertTrue(store.failures.isEmpty())
    }

    @Test fun refusalAfterBatchSnapshotPreventsTheRequest() = runBlocking {
        val original = item()
        val refused = original.copy(deliveryDeclined = true)
        val store = Store(original).apply {
            beforeSnapshot = { if (eligibleCalls == 2) items[0] = refused }
        }
        assertEquals(DeliveryOutcome.COMPLETE, OutboxDelivery { error("Refusal wins before send") }.drain(identity, store))
        assertEquals(listOf(refused), store.items)
        assertTrue(store.failures.isEmpty())
    }

    @Test fun failureMarkedAfterTheBatchSnapshotIsNotDelivered() = runBlocking {
        val original = item()
        val failed = original.copy(lastError = "invalid_request")
        val store = Store(original).apply {
            beforeSnapshot = { if (eligibleCalls == 2) items[0] = failed }
        }
        assertEquals(DeliveryOutcome.COMPLETE, OutboxDelivery { error("Failure was marked before validation") }.drain(identity, store))
        assertEquals(listOf(failed), store.items)
        assertTrue(store.failures.isEmpty())
    }

    @Test fun permissionRevokedDuringClientCreationPreventsTheRequest() = runBlocking {
        val original = item()
        val store = Store(original)
        val api = Api()
        assertEquals(DeliveryOutcome.COMPLETE, OutboxDelivery {
            store.blocked += original.clientId
            api
        }.drain(identity, store))
        assertTrue(api.sentIds.isEmpty())
        assertEquals(listOf(original), store.items)
        assertEquals(1, api.closes)
    }

    @Test fun editedPendingBatchEntryIsNotValidatedOrPermanentlyBlocked() = runBlocking {
        val original = item().copy(forkMessageId = "saved-user", forkAction = "delete")
        val updated = original.copy(forkAction = "edit")
        val store = Store(original).apply {
            beforeSnapshot = { if (eligibleCalls == 2) items[0] = updated }
        }
        assertEquals(DeliveryOutcome.RETRY, OutboxDelivery { error("Changed entry waits for next worker") }.drain(identity, store))
        assertEquals(listOf(updated), store.items)
        assertTrue(store.failures.isEmpty())
    }

    @Test fun cancellationWhileAwaitingReplyPropagatesRetainsWorkAndClosesClient() = runBlocking {
        withTimeout(5_000) {
            val original = item()
            val store = Store(original)
            val started = CompletableDeferred<Unit>()
            val api = Api().apply { reply = {
                started.complete(Unit)
                CompletableDeferred<MobileJob>().await()
            } }
            val delivery = launch { OutboxDelivery { api }.drain(identity, store) }
            started.await()
            delivery.cancelAndJoin()
            assertTrue(delivery.isCancelled)
            assertEquals(listOf(original), store.items)
            assertTrue(store.acknowledged.isEmpty())
            assertTrue(store.failures.isEmpty())
            assertEquals(1, api.closes)
        }
    }

    @Test fun cancellationImmediatelyBeforeReplyPreventsAcknowledgement() = runBlocking {
        val original = item()
        val store = Store(original)
        val api = Api().apply { reply = { id ->
            currentCoroutineContext().cancel(CancellationException("Fixture cancellation"))
            acknowledgement(id)
        } }
        val delivery = launch { OutboxDelivery { api }.drain(identity, store) }
        delivery.join()
        assertTrue(delivery.isCancelled)
        assertEquals(listOf(original), store.items)
        assertTrue(store.acknowledged.isEmpty())
        assertTrue(store.failures.isEmpty())
        assertEquals(1, api.closes)
    }

    @Test fun explicitCancellationAndUnexpectedFailuresNeverBecomeHttpSuccess() = runBlocking {
        for (failure in listOf(CancellationException("Fixture cancellation"), IllegalStateException("Fixture failure"))) {
            val original = item()
            val store = Store(original)
            val api = Api().apply { reply = { throw failure } }
            try {
                OutboxDelivery { api }.drain(identity, store)
                fail("Failure must propagate")
            } catch (caught: Exception) {
                assertSame(failure, caught)
            }
            assertEquals(listOf(original), store.items)
            assertTrue(store.acknowledged.isEmpty())
            assertTrue(store.failures.isEmpty())
            assertEquals(1, api.closes)
        }
    }
}
