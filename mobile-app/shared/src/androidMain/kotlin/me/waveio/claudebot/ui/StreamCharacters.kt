package me.waveio.claudebot.ui

/** Classify a full Unicode code point, including supplementary combining marks. */
internal actual fun isStreamCombiningMark(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
    Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
    else -> false
}
