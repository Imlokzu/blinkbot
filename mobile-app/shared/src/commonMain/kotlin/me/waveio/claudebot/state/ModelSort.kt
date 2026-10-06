package me.waveio.claudebot.state

/**
 * Order the picker by provider, then by capability so the strongest familiar
 * model sits on top. ChatGPT leads per product preference: plain versions
 * first, newer numbers above older ones within the same family.
 */
internal fun providerRank(id: String, provider: String): Int = when {
    provider.contains("chatgpt", true) || provider.contains("openai", true) -> 0
    provider.contains("regolo", true) -> 1
    provider.contains("anthropic", true) || provider.contains("claude", true) -> 2
    else -> 3
}

private val family = Regex("""(astra|luna|sol|gpt|claude|gemini|deepseek|kimi|qwen|mistral|llama|grok|minimax)""")

internal fun familyRank(id: String): Int = when (family.find(id.lowercase())?.value) {
    "astra" -> 0
    "sol" -> 1
    "luna" -> 2
    "gpt" -> 3
    "claude" -> 4
    else -> 5
}

internal fun versionRank(id: String): Double =
    Regex("""(\d+(?:\.\d+)?)""").find(id)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

internal fun tierRank(id: String): Int = when {
    id.contains("mini", true) || id.contains("nano", true) -> 2
    id.contains("lite", true) || id.contains("flash", true) || id.contains("haiku", true) -> 1
    else -> 0
}

internal val modelComparator = Comparator<ModelRow> { left, right ->
    compareValuesBy(
        left, right,
        { !it.available },
        { providerRank(it.id, it.provider) },
        { familyRank(it.id) },
        { -versionRank(it.id) },
        { tierRank(it.id) },
        { it.label.lowercase() },
    )
}
