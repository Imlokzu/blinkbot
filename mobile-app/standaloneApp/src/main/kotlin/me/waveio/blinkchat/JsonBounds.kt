package me.waveio.blinkchat

/** Reject pathological nesting before a recursive JSON decoder can exhaust the stack. */
internal fun validateJsonNesting(text: String) {
    var depth = 0
    var quoted = false
    var escaped = false
    for (character in text) {
        if (quoted) {
            if (escaped) escaped = false
            else if (character == '\\') escaped = true
            else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '{', '[' -> { if (++depth > 64) throw ProviderFailure("standalone.error.response") }
            '}', ']' -> depth--
        }
    }
}
