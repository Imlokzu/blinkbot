package me.waveio.blinkchat

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object MemoryLimits {
    /** One sentence each; a few hundred facts stay readable in the management screen. */
    const val MAX_FACTS = 200
    const val FACT_CHARS = 500
    /** Hard cap on the injected system-prompt block (Open WebUI's default). */
    const val CONTEXT_CHARS = 2000
    /** A background review runs after every Nth user turn; more often costs tokens for nothing. */
    const val REVIEW_INTERVAL_TURNS = 5
    const val REVIEW_TRANSCRIPT_MESSAGES = 16
    const val REVIEW_TRANSCRIPT_CHARS = 1200
}

private val memoryJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Pure memory policy so JVM tests can prove dedup, caps and ops handling without
 * storage. The controller owns persistence; nothing here touches the filesystem.
 */
object MemoryStore {
    fun remember(existing: List<StoredMemory>, fact: String, nowSeconds: Long): Pair<List<StoredMemory>, Boolean> {
        val content = cleanFact(fact) ?: return existing to false
        if (existing.any { dedupKey(it.content) == dedupKey(content) }) return existing to false
        if (existing.size >= MemoryLimits.MAX_FACTS) return existing to false
        return existing + StoredMemory(UUID.randomUUID().toString(), content, createdAt = nowSeconds, updatedAt = nowSeconds) to true
    }

    /** Substring match, because the model repeats the fact in its own words. */
    fun forget(existing: List<StoredMemory>, fact: String): List<StoredMemory> {
        val needle = fact.trim()
        if (needle.isEmpty()) return existing
        return existing.filterNot { it.content.contains(needle, ignoreCase = true) }
    }

    /** The system-prompt appendix; null when there is nothing worth injecting. */
    fun contextBlock(existing: List<StoredMemory>, charLimit: Int = MemoryLimits.CONTEXT_CHARS): String? {
        val header = "Persistent memory is available across conversations. Saved memories, if any, " +
            "are shown below. Use memory tools only when the user explicitly asks to remember or forget something."
        var used = header.length + 40
        val lines = StringBuilder()
        for (memory in existing.filterNot { it.muted }) {
            val line = "- ${memory.content}"
            if (used + line.length > charLimit) break
            lines.appendLine(line)
            used += line.length + 1
        }
        if (lines.isBlank()) return null
        return "$header\n\n# Existing memory about the user:\n$lines".trimEnd()
    }
}

/** Models add or drop trailing periods freely; dedup must not count that as a new fact. */
internal fun dedupKey(content: String): String = content.trimEnd('.', '!', '?', '…', ' ').lowercase()

internal fun cleanFact(fact: String): String? {
    val content = fact.replace(Regex("\\s+"), " ").trim()
    if (content.isEmpty() || content.length > MemoryLimits.FACT_CHARS) return null
    if (content.any(Char::isISOControl)) return null
    return content
}

/** One reviewer decision: add needs content, replace/remove need the target id. */
internal data class MemoryOperation(val action: String, val id: String?, val content: String?)

private val VALID_ACTIONS = setOf("add", "replace", "remove")

/**
 * The reviewer may wrap its JSON in prose or code fences; the outermost object wins.
 * Anything unparseable yields no operations, which is always a safe outcome.
 */
internal fun parseMemoryOperations(text: String): List<MemoryOperation> {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) return emptyList()
    val root = try { memoryJson.parseToJsonElement(text.substring(start, end + 1)).jsonObject }
        catch (_: Exception) { return emptyList() }
    val operations = try { root["operations"]?.jsonArray } catch (_: Exception) { return emptyList() } ?: return emptyList()
    return operations.mapNotNull { element ->
        val op = element as? JsonObject ?: return@mapNotNull null
        val action = op["action"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        if (action !in VALID_ACTIONS) return@mapNotNull null
        MemoryOperation(action,
            op["id"]?.jsonPrimitive?.contentOrNull,
            op["content"]?.jsonPrimitive?.contentOrNull)
    }
}

/**
 * Applies reviewer decisions: add dedups case-insensitively and respects the cap,
 * replace/remove only touch known ids, and nothing here can drop the whole store.
 */
internal fun applyMemoryOperations(
    existing: List<StoredMemory>,
    operations: List<MemoryOperation>,
    nowSeconds: Long,
): List<StoredMemory> {
    var result = existing
    for (op in operations) {
        when (op.action) {
            "add" -> {
                val content = cleanFact(op.content.orEmpty()) ?: continue
                if (result.any { dedupKey(it.content) == dedupKey(content) }) continue
                if (result.size >= MemoryLimits.MAX_FACTS) continue
                result = result + StoredMemory(UUID.randomUUID().toString(), content, createdAt = nowSeconds, updatedAt = nowSeconds)
            }
            "replace" -> {
                val content = cleanFact(op.content.orEmpty()) ?: continue
                val index = result.indexOfFirst { it.id == op.id }
                if (index < 0) continue
                result = result.toMutableList().also { it[index] = it[index].copy(content = content, updatedAt = nowSeconds) }
            }
            "remove" -> result = result.filterNot { it.id == op.id }
        }
    }
    return result
}

/** The reviewer prompt: Open WebUI's durability rules, JSON-only output. */
internal const val MEMORY_REVIEWER_PROMPT =
    "You are Blink Chat's private memory reviewer. Review the completed conversation turn and decide " +
    "whether long-term memory should change. Return only valid JSON: " +
    "{\"operations\":[{\"action\":\"add\",\"content\":\"...\"},{\"action\":\"replace\",\"id\":\"...\",\"content\":\"...\"}," +
    "{\"action\":\"remove\",\"id\":\"...\"}]} with an empty operations array when nothing should change. " +
    "Save durable facts, preferences, or instructions about the user. Do not save one-off activity, meals, " +
    "temporary mood, routine daily events, or other short-lived details unless the user explicitly asks. " +
    "Do not save secrets, credentials, transient task steps, or unsupported guesses. Prefer replace or " +
    "remove over duplicate add when an existing memory should change. Each fact is one self-contained " +
    "sentence, first person, in the user's language."

internal fun memoryReviewTask(existing: List<StoredMemory>, transcript: String): String = buildString {
    append(MEMORY_REVIEWER_PROMPT)
    if (existing.isNotEmpty()) {
        append("\n\nExisting memories:")
        for (memory in existing) append("\n- id=${memory.id} ${memory.content}")
    }
    append("\n\nConversation transcript:\n$transcript")
}

internal fun reviewTranscript(messages: List<StoredMessage>): String = messages
    .filter { it.role == "user" || it.role == "assistant" }
    .takeLast(MemoryLimits.REVIEW_TRANSCRIPT_MESSAGES)
    .joinToString("\n") { message ->
        val who = if (message.role == "user") "User" else "Assistant"
        "${who}: ${message.text.take(MemoryLimits.REVIEW_TRANSCRIPT_CHARS)}"
    }
