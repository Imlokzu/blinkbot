package me.waveio.claudebot.platform

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.waveio.claudebot.data.ApiFailure
import me.waveio.claudebot.data.Attachment
import me.waveio.claudebot.data.BotApi
import me.waveio.claudebot.data.MobileJob
import me.waveio.claudebot.state.OutboxItem

internal data class OutboxIdentity(
    val deviceId: String,
    val server: String,
    val token: String,
    val epoch: String,
) {
    override fun toString(): String = "OutboxIdentity(redacted)"
}

internal interface OutboxDeliveryStore {
    fun isCurrent(identity: OutboxIdentity): Boolean
    /** Only this identity's permitted, unblocked entries belong in this snapshot. */
    fun eligible(identity: OutboxIdentity): List<OutboxItem>
    /** Atomically check the identity and the exact entry before removing it. */
    fun acknowledge(identity: OutboxIdentity, item: OutboxItem): Boolean
    /**
     * Atomically block the exact entry for foreground recovery, retaining its payload.
     * A 401/403 must also block the account's other entries until foreground recovery.
     */
    fun permanentFailure(identity: OutboxIdentity, item: OutboxItem, status: Int, code: String)
}

internal enum class DeliveryOutcome { COMPLETE, RETRY, STALE }

/** Mirrors the common API so tests exercise payload mapping without a network client. */
internal interface OutboxDeliveryApi {
    suspend fun submitMessage(
        clientId: String, sessionId: String, message: String,
        attachments: List<Attachment>, model: String, reasoningEffort: String,
        delivery: String, scheduledAt: String?,
    ): MobileJob

    suspend fun forkMessage(
        sessionId: String, messageId: String, action: String, clientId: String,
        message: String?, model: String, effort: String,
    ): MobileJob

    fun close()
}

private class BotApiDelivery(private val api: BotApi) : OutboxDeliveryApi {
    override suspend fun submitMessage(
        clientId: String, sessionId: String, message: String,
        attachments: List<Attachment>, model: String, reasoningEffort: String,
        delivery: String, scheduledAt: String?,
    ): MobileJob = api.submitMessage(
        clientId, sessionId, message, attachments, model, reasoningEffort, delivery, scheduledAt,
    )

    override suspend fun forkMessage(
        sessionId: String, messageId: String, action: String, clientId: String,
        message: String?, model: String, effort: String,
    ): MobileJob = api.forkMessage(sessionId, messageId, action, clientId, message, model, effort)

    override fun close() = api.close()
}

internal class OutboxDelivery(
    private val apiFactory: (OutboxIdentity) -> OutboxDeliveryApi = {
        BotApiDelivery(BotApi(it.server, it.token))
    },
) {
    suspend fun drain(identity: OutboxIdentity, store: OutboxDeliveryStore): DeliveryOutcome {
        currentCoroutineContext().ensureActive()
        if (!store.isCurrent(identity)) return DeliveryOutcome.STALE
        // Never consume a live mutable list: foreground writes must wait for a later run.
        val batch = store.eligible(identity).filter { it.lastError == null && !it.deliveryDeclined }.take(MAX_BATCH_SIZE).map {
            it.copy(attachments = it.attachments.toList())
        }
        var api: OutboxDeliveryApi? = null
        var retry = false
        try {
            for (item in batch) {
                currentCoroutineContext().ensureActive()
                if (!store.isCurrent(identity)) return DeliveryOutcome.STALE
                if (store.eligible(identity).none { it == item && it.lastError == null && !it.deliveryDeclined }) continue
                try {
                    validate(item)
                    val sender = api ?: apiFactory(identity).also { api = it }
                    // The factory may race with logout. Its client is always bound to
                    // the captured server/token, never to mutable account settings.
                    currentCoroutineContext().ensureActive()
                    if (!store.isCurrent(identity)) return DeliveryOutcome.STALE
                    if (store.eligible(identity).none { it == item && it.lastError == null && !it.deliveryDeclined }) continue
                    val forkMessageId = item.forkMessageId
                    val forkAction = item.forkAction
                    val job = if (forkMessageId != null && forkAction != null) {
                        sender.forkMessage(
                            item.sessionId, forkMessageId, forkAction, item.clientId,
                            item.message.takeIf { forkAction == "edit" }, item.model, item.effort,
                        )
                    } else {
                        sender.submitMessage(
                            item.clientId, item.sessionId, item.message,
                            item.attachments.map { Attachment(it.path, it.name, it.mimeType, it.size) },
                            item.model, item.effort, item.delivery, item.scheduledAt,
                        )
                    }
                    currentCoroutineContext().ensureActive()
                    if (!store.isCurrent(identity)) return DeliveryOutcome.STALE
                    if (job.id.isBlank() || job.clientId != item.clientId) {
                        throw ApiFailure(200, "invalid_ack")
                    }
                    if (!store.acknowledge(identity, item)) {
                        if (!store.isCurrent(identity)) return DeliveryOutcome.STALE
                        store.permanentFailure(identity, item, 200, "invalid_ack")
                    }
                } catch (failure: ApiFailure) {
                    currentCoroutineContext().ensureActive()
                    if (!store.isCurrent(identity)) return DeliveryOutcome.STALE
                    if (isRetryable(failure)) {
                        retry = true
                    } else {
                        val code = if (failure.status in 200..299 && failure.code == "invalid_response") {
                            "invalid_ack"
                        } else failure.code
                        store.permanentFailure(identity, item, failure.status, code)
                        // Authorization failures apply to the whole account. Only
                        // foreground recovery should resume it, avoiding a retry storm.
                        if (failure.status == 401 || failure.status == 403) {
                            return if (store.isCurrent(identity)) DeliveryOutcome.COMPLETE else DeliveryOutcome.STALE
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (!store.isCurrent(identity)) return DeliveryOutcome.STALE
            val remaining = store.eligible(identity).any { it.lastError == null && !it.deliveryDeclined }
            if (!store.isCurrent(identity)) return DeliveryOutcome.STALE
            return if (retry || remaining) DeliveryOutcome.RETRY else DeliveryOutcome.COMPLETE
        } finally {
            api?.close()
        }
    }

    private fun validate(item: OutboxItem) {
        if (item.clientId.isBlank()) throw ApiFailure(0, "invalid_client_id")
        if (item.delivery !in listOf("queue", "steer")) throw ApiFailure(0, "invalid_delivery")
        val hasFork = item.forkMessageId != null || item.forkAction != null
        if (!hasFork) return
        if (item.forkMessageId.isNullOrBlank() || item.forkAction !in listOf("edit", "regenerate") ||
            item.sessionId.isBlank()) {
            throw ApiFailure(0, "invalid_fork")
        }
        // The current common fork adapter cannot carry these fields. Retain
        // unsupported work instead of submitting a different request silently.
        if (item.attachments.isNotEmpty()) throw ApiFailure(0, "unsupported_fork_attachments")
        if (item.delivery != "queue") throw ApiFailure(0, "unsupported_fork_delivery")
        if (item.scheduledAt != null) throw ApiFailure(0, "unsupported_fork_schedule")
    }

    private fun isRetryable(failure: ApiFailure): Boolean =
        (failure.status == 0 && failure.code == "network_error") ||
            failure.status in listOf(408, 425, 429) ||
            (failure.status in 500..599 && failure.status != 501)

    private companion object {
        const val MAX_BATCH_SIZE = 50
    }
}
