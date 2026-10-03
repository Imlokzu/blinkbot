package me.waveio.claudebot.platform

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** WorkData carries account identity/epoch only. Device tokens remain in the Keystore. */
class OutboxWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = NativeOutboxStore(applicationContext)
        OutboxWorkRunner().run(inputData, store, store::snapshot)
    }
}

/** Runs the same worker decisions with harmless in-memory stores/senders in tests. */
internal class OutboxWorkRunner(
    private val delivery: OutboxDelivery = OutboxDelivery(),
    private val timeoutMillis: Long = 8 * 60_000L,
) {
    suspend fun run(
        input: Data, store: OutboxDeliveryStore, snapshot: () -> OutboxIdentity?,
    ): ListenableWorker.Result {
        try {
            val identity = snapshot() ?: return ListenableWorker.Result.success()
            if (input.getString("device_id") != identity.deviceId || input.getString("epoch") != identity.epoch ||
                input.getString("server") != identity.server) return ListenableWorker.Result.success()
            return when (withTimeoutOrNull(timeoutMillis) { delivery.drain(identity, store) }) {
                DeliveryOutcome.COMPLETE, DeliveryOutcome.STALE -> ListenableWorker.Result.success()
                DeliveryOutcome.RETRY, null -> ListenableWorker.Result.retry()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // Corrupt persistence is retained for foreground recovery.
            return ListenableWorker.Result.failure()
        }
    }
}

internal object NativeOutboxWork {
    const val UNIQUE_NAME = "claudebot-outbox"

    fun schedule(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE) =
        synchronized(NativePreferenceTransactions.lock) {
            scheduleLocked(context, policy)
        }

    private fun scheduleLocked(context: Context, policy: ExistingWorkPolicy) {
        val store = NativeOutboxStore(context)
        val identity = runCatching { store.snapshot() }.getOrNull()
        val manager = WorkManager.getInstance(context)
        if (identity == null || runCatching { store.eligible(identity).isEmpty() }.getOrDefault(true)) {
            manager.cancelUniqueWork(UNIQUE_NAME)
            return
        }
        val request = request(identity)
        // Appends a follow-up if persistence changes while a drain is running.
        manager.enqueueUniqueWork(UNIQUE_NAME, policy, request)
    }

    fun request(identity: OutboxIdentity): OneTimeWorkRequest = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf("device_id" to identity.deviceId, "server" to identity.server, "epoch" to identity.epoch))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(UNIQUE_NAME)
            .build()
    fun credentialsChanged(context: Context) = synchronized(NativePreferenceTransactions.lock) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        // A delayed credential hook must not cancel a newer completed pairing
        // without restoring its eligible work. Reconcile the latest persisted tuple.
        scheduleLocked(context, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }
}
