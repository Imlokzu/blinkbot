package me.waveio.claudebot.state

/** Align trimmed server bubble labels with the exact cumulative reply text. */
internal fun snapshotBubbles(text: String, bubbles: List<String>): List<String> {
    if (bubbles.isEmpty()) return listOf(text)
    if (bubbles.joinToString("\n\n") == text) return bubbles
    val labels = bubbles.map(String::trim)
    if (labels.any(String::isEmpty)) return listOf(text)
    val starts = mutableListOf<Int>()
    var cursor = 0
    for (label in labels) {
        while (cursor < text.length && text[cursor].isWhitespace()) cursor++
        if (!text.startsWith(label, cursor)) return listOf(text)
        starts += cursor
        cursor += label.length
    }
    if (text.substring(cursor).any { !it.isWhitespace() }) return listOf(text)
    val answer = mutableListOf<String>()
    var from = 0
    for (index in 0 until labels.lastIndex) {
        val end = starts[index] + labels[index].length
        val separator = text.substring(end, starts[index + 1]).indexOf("\n\n")
        if (separator < 0) return listOf(text)
        val boundary = end + separator
        answer += text.substring(from, boundary)
        from = boundary + 2
    }
    answer += text.substring(from)
    return answer
}
