package me.waveio.claudebot.state

/**
 * Display name for a catalog entry. The host forwards OpenClaw's own names, and a
 * model it has no name for arrives as its raw id ("gpt-5.5-pro") next to
 * properly named siblings ("GPT-5.5"). Only that lowercase GPT id form is
 * rewritten; real names pass through untouched, and the id sent to the host
 * never changes.
 */
fun displayModelLabel(id: String, label: String?): String {
    val raw = label?.trim()?.takeIf { it.isNotEmpty() } ?: id.substringAfterLast('/')
    if (raw != raw.lowercase() || !raw.startsWith("gpt-")) return raw
    val parts = raw.removePrefix("gpt-").split('-').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return raw
    // "4o" keeps OpenAI's lowercase o; "oss" and plain versions are upper-cased.
    val head = parts.first().let { if (it.matches(Regex("""\d+(\.\d+)?o"""))) it else it.uppercase() }
    val tail = parts.drop(1).joinToString(" ") { part ->
        // Parameter counts read as "120B"; words read as "Pro", "Mini".
        if (part.matches(Regex("""\d+(\.\d+)?[bkm]"""))) part.uppercase()
        else part.replaceFirstChar { it.uppercaseChar() }
    }
    return if (tail.isEmpty()) "GPT-$head" else "GPT-$head $tail"
}
