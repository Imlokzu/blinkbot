package me.waveio.claudebot.state

enum class QueueStep { Open, Retry, SendNow, Continue, None }

/**
 * What the Queue screen says about one item, derived only from that item's
 * own state and error code. [reason] and [cancelHint] are locale keys.
 *
 * Cancel means different things by kind, which is why the hint exists:
 * - a phone-only message is deleted from this device;
 * - a waiting host job is stopped, and the host also pauses that chat's queue;
 * - a host job that already ended (failed, interrupted, unsupported) cannot
 *   be stopped any more, so Cancel only clears it from this list.
 */
data class QueueAdvice(
    val reason: String?,
    val recommended: QueueStep,
    val canRetry: Boolean,
    val cancelLabel: String,
    val cancelHint: String,
)

private val HostEnded = setOf("failed", "interrupted", "unsupported")

fun PendingRow.isLocal(): Boolean = id.startsWith("local:")

/** A host job that has already ended; it stays listed until the owner retries or clears it. */
fun PendingRow.hostEnded(): Boolean = !isLocal() && state in HostEnded

fun queueAdvice(row: PendingRow): QueueAdvice {
    if (row.isLocal()) {
        return when {
            row.error != null -> QueueAdvice(localErrorReason(row.error), if (row.error == "mobile_image_model_unavailable") QueueStep.Open else QueueStep.Retry,
                canRetry = true, cancelLabel = "input.cancel", cancelHint = "queue.cancelHint.local")
            row.declined -> QueueAdvice("queue.reason.kept", QueueStep.SendNow, canRetry = true, cancelLabel = "input.cancel", cancelHint = "queue.cancelHint.local")
            else -> QueueAdvice("queue.reason.offline", QueueStep.None, canRetry = true, cancelLabel = "input.cancel", cancelHint = "queue.cancelHint.local")
        }
    }
    return when (row.state) {
        "failed" -> QueueAdvice(hostErrorReason(row.error), if (row.error == "mobile_image_model_unavailable") QueueStep.Open else QueueStep.Retry,
            canRetry = true, cancelLabel = "queue.clear", cancelHint = "queue.cancelHint.ended")
        "interrupted" -> QueueAdvice("queue.reason.interrupted", QueueStep.Retry, canRetry = true, cancelLabel = "queue.clear", cancelHint = "queue.cancelHint.ended")
        "unsupported" -> QueueAdvice("queue.reason.unsupported", QueueStep.Open, canRetry = true, cancelLabel = "queue.clear", cancelHint = "queue.cancelHint.ended")
        "queued", "scheduled" -> if (row.paused) QueueAdvice("queue.reason.paused", QueueStep.Continue, canRetry = true, cancelLabel = "input.cancel", cancelHint = "queue.cancelHint.waiting")
            else QueueAdvice(if (row.state == "queued") "queue.reason.queued" else null, QueueStep.None, canRetry = false, cancelLabel = "input.cancel", cancelHint = "queue.cancelHint.waiting")
        "running", "stopping" -> QueueAdvice(null, QueueStep.Open, canRetry = false, cancelLabel = "input.cancel", cancelHint = "queue.cancelHint.running")
        else -> QueueAdvice(null, QueueStep.Open, canRetry = false, cancelLabel = "input.cancel", cancelHint = "queue.cancelHint.waiting")
    }
}

/** Codes the host's mobile runtime writes when a turn ends badly (mobile_api.py). */
private fun hostErrorReason(code: String?): String = when (code) {
    "mobile_image_model_unavailable" -> "queue.reason.imageModel"
    "incomplete_stream" -> "queue.reason.incomplete"
    else -> "queue.reason.failed"
}

/** The host refused the submission itself (4xx/501); the code is kept on the phone. */
private fun localErrorReason(code: String): String = when (code) {
    "mobile_image_model_unavailable" -> "queue.reason.imageModel"
    else -> "queue.reason.refused"
}
