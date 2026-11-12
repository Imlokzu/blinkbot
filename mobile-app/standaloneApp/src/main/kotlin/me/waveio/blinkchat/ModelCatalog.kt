package me.waveio.blinkchat

import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** The wire protocol a provider speaks for chat. Stored by value, so these strings never change. */
object ApiLayer {
    const val OPENAI_CHAT = "openai"
    const val ANTHROPIC = "anthropic"
    const val RESPONSES = "responses"
    val all = listOf(ANTHROPIC, OPENAI_CHAT, RESPONSES)
}

/** How a provider generates images; [NONE] makes a text-only provider. */
object ImageLayer {
    const val QWEN = "qwen"
    const val OPENAI = "openai"
    const val NONE = "none"
    val all = listOf(OPENAI, QWEN, NONE)
}

/** Effort stops the shared picker can label; anything else a catalog reports is ignored. */
val KNOWN_EFFORTS = listOf("minimal", "low", "medium", "high", "xhigh", "max")

/**
 * What the endpoint told us about one model. Every field except [id] is optional: null means
 * the catalog did not say, never "unsupported". [efforts] only lists levels the provider
 * published (or a documented family default, see [knownEfforts]).
 */
@Serializable
data class ModelInfo(
    val id: String,
    val label: String = "",
    val context: Long? = null,
    val maxOutput: Long? = null,
    val vision: Boolean? = null,
    val text: Boolean = true,
    val image: Boolean = false,
    val efforts: List<String> = emptyList(),
    val effortDefault: String = "",
)

/** What a connection probe found at one endpoint. */
data class Discovery(
    val baseUrl: String,
    /** Layers the endpoint answered on, best first. Empty when only the catalog answered. */
    val apis: List<String>,
    val recommended: String,
    val models: List<ModelInfo>,
    val imageProtocol: String,
    val imageEndpoint: String,
    val name: String,
)

private const val MAX_LABEL = 120
private val NON_CHAT = Regex("(^|[-_/:.])(embed|embedding|embeddings|tts|whisper|transcribe|transcription|moderation|rerank|reranker|audio|realtime|speech|search-preview)([-_/:.]|$)|text-embedding")
private val IMAGE_ONLY = Regex("(^|/)(gpt-image|dall-e|chatgpt-image|qwen-image|wanx?[0-9.]*-(t2i|i2i|image)|flux|imagen|stable-diffusion|sdxl|sd3|seedream|recraft|ideogram|kolors|cogview|hidream|playground-v)", RegexOption.IGNORE_CASE)

internal object ModelCatalog {
    /** Reads one entry from an OpenAI-, Anthropic- or OpenRouter-shaped `/models` listing. */
    fun parse(item: JsonObject, host: String): ModelInfo? {
        val id = item.string("id")?.trim() ?: return null
        if (id.isBlank() || id.length > 256 || id.any(Char::isISOControl)) return null
        val label = (item.string("display_name") ?: item.string("name"))
            ?.filterNot(Char::isISOControl)?.trim()?.take(MAX_LABEL)?.takeIf { it.isNotEmpty() && it != id }.orEmpty()
        val top = item["top_provider"] as? JsonObject
        val limits = item["limits"] as? JsonObject
        val context = positive(item, "max_input_tokens", "context_length", "context_window", "max_context_length",
            "max_model_len", "input_token_limit", "inputTokenLimit")
            ?: top?.let { positive(it, "context_length") } ?: limits?.let { positive(it, "context", "max_input_tokens") }
        val maxOutput = positive(item, "max_tokens", "max_output_tokens", "max_completion_tokens", "output_token_limit", "outputTokenLimit")
            ?: top?.let { positive(it, "max_completion_tokens") } ?: limits?.let { positive(it, "output", "max_output_tokens") }

        val architecture = item["architecture"] as? JsonObject
        val modalities = item["modalities"] as? JsonObject
        val inputs = strings(architecture?.get("input_modalities")) + strings(modalities?.get("input")) + strings(item["input_modalities"])
        val outputs = strings(architecture?.get("output_modalities")) + strings(modalities?.get("output")) + strings(item["output_modalities"])
        val capabilities = item["capabilities"] as? JsonObject
        val vision = when {
            capabilities != null && capabilities["image_input"] is JsonObject -> supported(capabilities["image_input"])
            capabilities?.get("vision") is JsonPrimitive -> (capabilities["vision"] as JsonPrimitive).booleanOrNull
            item["supports_vision"] is JsonPrimitive -> (item["supports_vision"] as JsonPrimitive).booleanOrNull
            inputs.isNotEmpty() -> "image" in inputs
            else -> null
        }
        val typeImage = item.string("type")?.lowercase() in setOf("image", "image_generation", "images")
        val image = "image" in outputs || typeImage || IMAGE_ONLY.containsMatchIn(id)
        val text = when {
            outputs.isNotEmpty() -> "text" in outputs
            typeImage || IMAGE_ONLY.containsMatchIn(id) -> false
            else -> !NON_CHAT.containsMatchIn(id.lowercase())
        }

        val published = publishedEfforts(item, capabilities)
        val efforts = published ?: knownEfforts(id, host, item)
        val default = item.string("default_reasoning_effort")
            ?: (item["reasoning"] as? JsonObject)?.string("default_effort")
            ?: defaultEffort(id, efforts)
        return ModelInfo(id, label, context, maxOutput, vision, text, image, efforts,
            default.takeIf { it in efforts }.orEmpty())
    }

    /** Explicit per-model levels, when the catalog publishes them. Null means "not published". */
    private fun publishedEfforts(item: JsonObject, capabilities: JsonObject?): List<String>? {
        // Anthropic: capabilities.effort = {supported, low:{supported}, ..., max:{supported}}.
        (capabilities?.get("effort") as? JsonObject)?.let { effort ->
            if (supported(effort) == false) return emptyList()
            return KNOWN_EFFORTS.filter { supported(effort[it]) == true }
        }
        for (key in listOf("supported_reasoning_efforts", "reasoning_efforts", "reasoning_effort_levels", "supported_reasoning_levels")) {
            val values = item[key] as? JsonArray ?: (capabilities?.get(key) as? JsonArray) ?: continue
            val levels = values.mapNotNull { value ->
                (value as? JsonPrimitive)?.contentOrNull ?: (value as? JsonObject)?.let { it.string("effort") ?: it.string("id") ?: it.string("name") }
            }.map { it.lowercase() }
            return KNOWN_EFFORTS.filter { it in levels }
        }
        ((item["reasoning"] as? JsonObject)?.get("efforts") as? JsonArray)?.let { values ->
            val levels = strings(values)
            return KNOWN_EFFORTS.filter { it in levels }
        }
        return null
    }

    /**
     * Documented levels for catalogs that only flag reasoning support. OpenRouter marks
     * reasoning models in `supported_parameters`; api.openai.com publishes nothing, so its
     * reasoning families get the three levels every one of them accepts.
     */
    private fun knownEfforts(id: String, host: String, item: JsonObject): List<String> {
        val parameters = strings(item["supported_parameters"])
        if ("reasoning_effort" in parameters || "reasoning" in parameters) return listOf("low", "medium", "high")
        if (host == "api.openai.com") {
            val bare = id.lowercase()
            if (Regex("^(o[1-9]|gpt-5)").containsMatchIn(bare) && !NON_CHAT.containsMatchIn(bare) && !bare.contains("chat-latest"))
                return listOf("low", "medium", "high")
        }
        return emptyList()
    }

    /** The level a provider applies when the request names none, so the picker shows the real state. */
    private fun defaultEffort(id: String, efforts: List<String>): String {
        if (efforts.isEmpty()) return ""
        val bare = id.lowercase()
        if (bare.startsWith("claude-")) {
            return if (bare.startsWith("claude-opus-5-5") || bare.startsWith("claude-haiku-5-5")) "medium" else "high"
        }
        return if ("medium" in efforts) "medium" else ""
    }

    /** Text models first, then image models; within each the provider's order is kept. */
    fun merge(primary: List<ModelInfo>, secondary: List<ModelInfo>): List<ModelInfo> {
        val byId = LinkedHashMap<String, ModelInfo>()
        for (model in primary) byId.putIfAbsent(model.id, model)
        for (model in secondary) {
            val existing = byId[model.id]
            byId[model.id] = if (existing == null) model else existing.copy(
                label = existing.label.ifBlank { model.label },
                context = existing.context ?: model.context,
                maxOutput = existing.maxOutput ?: model.maxOutput,
                vision = existing.vision ?: model.vision,
                efforts = existing.efforts.ifEmpty { model.efforts },
                effortDefault = existing.effortDefault.ifBlank { model.effortDefault },
            )
        }
        return byId.values.toList()
    }

    /** A readable provider name from the endpoint host, e.g. "api.anthropic.com" -> "Anthropic". */
    fun suggestName(baseUrl: String): String {
        val host = runCatching { URI(baseUrl).host }.getOrNull().orEmpty().lowercase()
        KNOWN_HOSTS.entries.firstOrNull { host == it.key || host.endsWith("." + it.key) }?.let { return it.value }
        val parts = host.split('.').filter { it.isNotBlank() && it !in setOf("api", "www", "gateway", "llm", "inference") }
        val core = parts.dropLast(1).lastOrNull() ?: parts.firstOrNull() ?: return host
        return core.replaceFirstChar { it.uppercase() }
    }

    /**
     * Candidate base URLs for what the owner typed. A pasted request URL loses its endpoint
     * path; a bare origin is also tried with `/v1`. Only HTTPS is accepted, as everywhere else.
     */
    fun candidateBases(input: String): List<String> {
        var url = input.trim().trimEnd('/')
        if (url.isEmpty()) return emptyList()
        if (!url.contains("://")) url = "https://$url"
        for (suffix in listOf("/chat/completions", "/completions", "/messages", "/responses", "/models", "/images/generations")) {
            if (url.endsWith(suffix, ignoreCase = true)) { url = url.dropLast(suffix.length).trimEnd('/'); break }
        }
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        return if (Regex("/v\\d+[a-z0-9]*$").containsMatchIn(path) || path.endsWith("/compatible-mode/v1")) listOf(url)
        else listOf("$url/v1", url)
    }

    fun brand(id: String, host: String): String {
        val bare = id.lowercase().substringAfterLast('/')
        val owner = id.lowercase().substringBefore('/', "")
        return when {
            bare.startsWith("claude") || owner == "anthropic" -> "anthropic"
            bare.startsWith("gpt") || Regex("^o[1-9]").containsMatchIn(bare) || bare.startsWith("dall-e") || bare.startsWith("chatgpt") || owner == "openai" -> "openai"
            bare.startsWith("gemini") || bare.startsWith("gemma") || bare.startsWith("imagen") || owner == "google" -> "google"
            bare.startsWith("qwen") || bare.startsWith("wan") || bare.startsWith("qwq") || owner == "qwen" -> "qwen"
            bare.startsWith("deepseek") -> "deepseek"
            bare.startsWith("kimi") || bare.startsWith("moonshot") -> "moonshot"
            bare.startsWith("mistral") || bare.startsWith("codestral") || bare.startsWith("magistral") || bare.startsWith("devstral") -> "mistral"
            bare.startsWith("llama") || owner == "meta-llama" -> "meta"
            bare.startsWith("grok") || owner == "x-ai" -> "xai"
            bare.startsWith("minimax") || bare.startsWith("abab") -> "minimax"
            bare.startsWith("glm") || owner == "z-ai" -> "zhipu"
            bare.startsWith("nemotron") || owner == "nvidia" -> "nvidia"
            host.endsWith("anthropic.com") -> "anthropic"
            host.endsWith("openai.com") -> "openai"
            else -> ""
        }
    }

    private val KNOWN_HOSTS = mapOf(
        "anthropic.com" to "Anthropic",
        "openai.com" to "OpenAI",
        "openrouter.ai" to "OpenRouter",
        "qwencloudapi.com" to "QwenCloud",
        "dashscope.aliyuncs.com" to "DashScope",
        "dashscope-intl.aliyuncs.com" to "DashScope",
        "generativelanguage.googleapis.com" to "Google Gemini",
        "deepseek.com" to "DeepSeek",
        "mistral.ai" to "Mistral",
        "groq.com" to "Groq",
        "x.ai" to "xAI",
        "together.xyz" to "Together",
        "fireworks.ai" to "Fireworks",
        "moonshot.ai" to "Moonshot",
        "z.ai" to "Z.ai",
    )

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun positive(obj: JsonObject, vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
        (obj[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }?.takeIf { it in 1..100_000_000 }
    }

    private fun supported(element: JsonElement?): Boolean? = when (element) {
        is JsonObject -> (element["supported"] as? JsonPrimitive)?.booleanOrNull
        is JsonPrimitive -> element.booleanOrNull
        else -> null
    }

    private fun strings(element: JsonElement?): List<String> =
        (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull?.lowercase() }.orEmpty()
}
