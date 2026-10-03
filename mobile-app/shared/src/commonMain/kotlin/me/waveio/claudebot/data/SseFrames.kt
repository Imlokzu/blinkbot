package me.waveio.claudebot.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Framing is independent of transport chunks; readUTF8Line handles CRLF/LF. */
internal class SseFrames(private val json: Json) {
    private var firstLine = true
    private var id: Long? = null
    private var event = "message"
    private val data = StringBuilder()

    fun line(raw: String): BotEvent? {
        val line = if (firstLine) raw.removePrefix("\uFEFF") else raw
        firstLine = false
        if (line.isEmpty()) {
            val result = if (data.isEmpty()) null else {
                val value = try { json.parseToJsonElement(data.toString().removeSuffix("\n")) as? JsonObject }
                    catch (_: Exception) { null }
                    ?: throw ApiFailure(200, "invalid_event")
                BotEvent(id, event, value)
            }
            event = "message"
            data.clear()
            return result
        }
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        when (field) {
            "id" -> if ('\u0000' !in value) {
                id = if (value.isEmpty()) null else value.toLongOrNull()?.takeIf { it >= 0 }
                    ?: throw ApiFailure(200, "invalid_event_id")
            }
            "event" -> event = value.ifEmpty { "message" }
            "data" -> {
                if (data.length + value.length > 4 * 1024 * 1024) throw ApiFailure(200, "event_too_large")
                data.append(value).append('\n')
            }
            // retry and future fields do not change the chat event payload.
        }
        return null
    }
}
