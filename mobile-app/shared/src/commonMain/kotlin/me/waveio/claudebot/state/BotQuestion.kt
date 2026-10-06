package me.waveio.claudebot.state

import kotlinx.serialization.json.*
import me.waveio.claudebot.data.BotEvent
import me.waveio.claudebot.data.MobileJob

/** An ephemeral prompt belonging to a specific tool call and conversation. */
data class BotQuestion(
    val id: String,
    val sessionId: String,
    val jobId: String,
    val text: String,
    val options: List<String>,
    val allowCustom: Boolean,
)

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)
    ?.takeIf { it.isString }?.content

/** Both native gateway and local tools already carry their arguments in job SSE. */
internal fun botQuestion(job: MobileJob, event: BotEvent): BotQuestion? {
    if (event.event !in setOf("tool_start", "tool_progress", "tool_done", "tool_result")) return null
    val data = event.data
    val step = data["step"] as? JsonObject ?: data
    val tool = step.string("label") ?: data.string("tool") ?: return null
    if (tool.substringAfterLast("__").substringAfterLast("--").replace('-', '_') != "ask_question") return null
    if (step.string("status") in setOf("failed", "interrupted") ||
        (data["is_error"] as? JsonPrimitive)?.booleanOrNull == true) return null
    val call = step.string("id") ?: data.string("call_id") ?: return null
    if (call.isBlank()) return null
    val input = step["input"] ?: data["input"] ?: return null
    val args = input as? JsonObject ?: (input as? JsonPrimitive)?.takeIf { it.isString }
        ?.content?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return null
    val question = args.string("question")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val options = (args["options"] as? JsonArray).orEmpty().mapNotNull {
        (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content?.trim()?.takeIf(String::isNotEmpty)
    }.distinct().take(6)
    return BotQuestion("${job.sessionId}/${job.id}/$call", job.sessionId, job.id,
        question, options, (args["allow_custom"] as? JsonPrimitive)?.booleanOrNull ?: true)
}
