package me.waveio.claudebot.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.waveio.claudebot.data.ApiFailure
import me.waveio.claudebot.data.Attachment
import me.waveio.claudebot.data.MobileJob
import me.waveio.claudebot.state.OutboxItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeOutboxWorkTest {
    private val identity = OutboxIdentity("fixture-device", "https://fixture.invalid", "fixture-token", "fixture-epoch")
    private val item = OutboxItem("fixture-client", "fixture-session", "Fixture", "fixture/model", "low")
    private fun input(identity: OutboxIdentity = this.identity): Data = NativeOutboxWork.request(identity).workSpec.input

    private inner class Store : OutboxDeliveryStore {
        var current: OutboxIdentity? = identity
        val entries = mutableListOf(item)
        var reads = 0
        var failures = 0
        override fun isCurrent(identity: OutboxIdentity) = identity == current
        override fun eligible(identity: OutboxIdentity): List<OutboxItem> {
            reads++
            return if (isCurrent(identity)) entries.toList() else emptyList()
        }
        override fun acknowledge(identity: OutboxIdentity, item: OutboxItem) =
            isCurrent(identity) && entries.remove(item)
        override fun permanentFailure(identity: OutboxIdentity, item: OutboxItem, status: Int, code: String) {
            if (isCurrent(identity)) failures++
        }
    }

    private inner class Sender : OutboxDeliveryApi {
        var sends = 0
        var closes = 0
        var reply: suspend () -> MobileJob = { MobileJob("fixture-job", item.sessionId, "queued", clientId = item.clientId) }
        override suspend fun submitMessage(
            clientId: String, sessionId: String, message: String, attachments: List<Attachment>,
            model: String, reasoningEffort: String, delivery: String, scheduledAt: String?,
        ): MobileJob { sends++; return reply() }
        override suspend fun forkMessage(
            sessionId: String, messageId: String, action: String, clientId: String,
            message: String?, model: String, effort: String,
        ): MobileJob = error("Fixture has no fork")
        override fun close() { closes++ }
    }

    @Test fun connectedWorkCarriesNoCredentialsOrMessageBodies() {
        val identity = OutboxIdentity("fixture-device", "https://fixture.invalid", "fixture-token", "fixture-epoch")
        val request = NativeOutboxWork.request(identity)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(30_000L, request.workSpec.backoffDelayDuration)
        assertTrue(request.tags.contains(NativeOutboxWork.UNIQUE_NAME))
        assertEquals(identity.deviceId, request.workSpec.input.getString("device_id"))
        assertEquals(identity.server, request.workSpec.input.getString("server"))
        assertEquals(identity.epoch, request.workSpec.input.getString("epoch"))
        assertEquals(setOf("device_id", "server", "epoch"), request.workSpec.input.keyValueMap.keys)
        assertFalse(request.workSpec.input.toString().contains(identity.token))
    }

    @Test fun staleOsRequestCompletesWithoutOpeningAClient() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stale = OutboxIdentity("not-the-current-device", "https://fixture.invalid", "fixture-token", "not-the-current-epoch")
        val worker = TestListenableWorkerBuilder<OutboxWorker>(context)
            .setInputData(NativeOutboxWork.request(stale).workSpec.input).build()
        assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())
    }

    @Test fun eachStaleAccountFieldCompletesWithoutReadingEntriesOrCreatingAClient() = runBlocking {
        val staleIdentities = listOf(
            identity.copy(deviceId = "other-device"), identity.copy(server = "https://other.invalid"),
            identity.copy(epoch = "other-epoch"),
        )
        val store = Store()
        val runner = OutboxWorkRunner(OutboxDelivery { error("Stale work must not create a sender") })
        for (stale in staleIdentities) {
            assertEquals(ListenableWorker.Result.success(), runner.run(input(stale), store) { store.current })
        }
        assertEquals(ListenableWorker.Result.success(), runner.run(Data.EMPTY, store) { store.current })
        assertEquals(0, store.reads)
    }

    @Test fun absentIdentityAndCorruptSnapshotNeverCreateASender() = runBlocking {
        val store = Store()
        val runner = OutboxWorkRunner(OutboxDelivery { error("No sender is permitted") })
        assertEquals(ListenableWorker.Result.success(), runner.run(input(), store) { null })
        assertEquals(ListenableWorker.Result.failure(), runner.run(input(), store) { error("Fixture storage corruption") })
        assertEquals(listOf(item), store.entries)
        assertEquals(0, store.reads)
    }

    @Test fun confirmedDeliveryCompletesWhileNetworkFailureRequestsOsRetry() = runBlocking {
        val deliveredStore = Store()
        val deliveredSender = Sender()
        assertEquals(ListenableWorker.Result.success(), OutboxWorkRunner(OutboxDelivery { deliveredSender })
            .run(input(), deliveredStore) { deliveredStore.current })
        assertTrue(deliveredStore.entries.isEmpty())
        assertEquals(1, deliveredSender.closes)
        val retryStore = Store()
        val retrySender = Sender().apply { reply = { throw ApiFailure(0, "network_error") } }
        assertEquals(ListenableWorker.Result.retry(), OutboxWorkRunner(OutboxDelivery { retrySender })
            .run(input(), retryStore) { retryStore.current })
        assertEquals(listOf(item), retryStore.entries)
        assertEquals(0, retryStore.failures)
        assertEquals(1, retrySender.closes)
    }

    @Test fun workerTimeoutClosesTheSenderRetainsThePayloadAndRequestsRetry() = runBlocking {
        withTimeout(5_000) {
            val store = Store()
            val sender = Sender().apply { reply = { CompletableDeferred<MobileJob>().await() } }
            val runner = OutboxWorkRunner(OutboxDelivery { sender }, timeoutMillis = 100)
            assertEquals(ListenableWorker.Result.retry(), runner.run(input(), store) { store.current })
            assertEquals(listOf(item), store.entries)
            assertEquals(0, store.failures)
            assertEquals(1, sender.closes)
        }
    }

    @Test fun osCancellationPropagatesInsteadOfBecomingRetryOrFailure() = runBlocking {
        withTimeout(5_000) {
            val store = Store()
            val started = CompletableDeferred<Unit>()
            val sender = Sender().apply { reply = {
                started.complete(Unit)
                CompletableDeferred<MobileJob>().await()
            } }
            val job = launch { OutboxWorkRunner(OutboxDelivery { sender }).run(input(), store) { store.current } }
            started.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals(listOf(item), store.entries)
            assertEquals(1, sender.closes)
            assertEquals(0, store.failures)
        }
    }

    @Test fun snapshotCancellationIsNeverConvertedToAWorkerFailure() = runBlocking {
        val failure = CancellationException("Fixture cancellation")
        try {
            OutboxWorkRunner().run(input(), Store()) { throw failure }
            fail("Cancellation must propagate")
        } catch (caught: CancellationException) { assertSame(failure, caught) }
    }
}
