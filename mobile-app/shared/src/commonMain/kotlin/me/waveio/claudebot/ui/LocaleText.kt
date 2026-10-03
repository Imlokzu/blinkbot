package me.waveio.claudebot.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.waveio.claudebot.resources.Res

class LocaleText(private val values: Map<String, String>) {
    fun get(key: String, vararg arguments: Pair<String, Any>): String {
        var result = values[key] ?: key
        arguments.forEach { (name, value) -> result = result.replace("{$name}", value.toString()) }
        return result
    }
    companion object {
        suspend fun load(language: String): LocaleText {
            fun parse(bytes: ByteArray) = Json.parseToJsonElement(bytes.decodeToString()).jsonObject.mapValues { it.value.jsonPrimitive.content }
            val english = parse(Res.readBytes("files/locales/en.json"))
            val code = language.lowercase().substringBefore('-').substringBefore('_')
            val overrides = if (code != "en" && code.matches(Regex("[a-z]{2,3}"))) {
                runCatching { parse(Res.readBytes("files/locales/$code.json")) }.getOrDefault(emptyMap())
            } else emptyMap()
            return LocaleText(english + overrides)
        }
    }
}

val LocalText = staticCompositionLocalOf { LocaleText(emptyMap()) }
@Composable fun tr(key: String, vararg arguments: Pair<String, Any>): String = LocalText.current.get(key, *arguments)
