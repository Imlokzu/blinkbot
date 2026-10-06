package me.waveio.claudebot.ui

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/** Replace explicit math only for rendering; the message's copy/share source stays intact. */
internal data class MathSpan(val marker: String, val latex: String, val display: Boolean, val source: String)
internal data class MathMarkdown(val markdown: String, val formulas: Map<String, MathSpan>)
internal val mathMarker = Regex("\uE000[0-9a-f]+:[0-9]+\uE001")
private val unsafeMathCommands = Regex("""\\(?:newcommand|renewcommand|def|edef|gdef|xdef|let|csname|input|include|write|usepackage)\b""")

internal fun mathMarkdown(source: String): MathMarkdown {
    if ('\u0024' !in source && "\\(" !in source && "\\[" !in source) return MathMarkdown(source, emptyMap())
    val result = StringBuilder()
    val formulas = linkedMapOf<String, MathSpan>()
    val protected = protectedMathRanges(source)
    var protectedIndex = 0
    var at = 0
    fun appendThrough(end: Int) { result.append(source, at, end); at = end }
    while (at < source.length) {
        val ch = source[at]
        while (protectedIndex < protected.size && protected[protectedIndex].last < at) protectedIndex++
        val literal = protected.getOrNull(protectedIndex)
        if (literal != null && at in literal) {
            appendThrough(literal.last + 1)
            continue
        }
        if (source.startsWith("https://", at) || source.startsWith("http://", at)) {
            var end = at + 1
            while (end < source.length && !source[end].isWhitespace() && source[end] != '>') end++
            appendThrough(end)
            continue
        }
        val opener = when {
            source.startsWith("\\[", at) -> "\\["
            source.startsWith("\\(", at) -> "\\("
            source.startsWith("\u0024\u0024", at) -> "\u0024\u0024"
            ch == '\u0024' && source.getOrNull(at + 1)?.let { !it.isWhitespace() } == true -> "\u0024"
            else -> null
        }
        if (opener != null && formulas.size < 128 && '\uE000' !in source) {
            val display = opener == "\\[" || opener == "\u0024\u0024"
            val closer = when (opener) { "\\[" -> "\\]"; "\\(" -> "\\)"; else -> opener }
            val start = at + opener.length
            var close = source.indexOf(closer, start)
            while (close >= 0 && close - start <= 4096 && escapedAt(source, close)) {
                close = source.indexOf(closer, close + closer.length)
            }
            if (close - start > 4096 || opener == "\u0024" && (source.getOrNull(close + 1)?.isDigit() == true || source.getOrNull(close - 1)?.isWhitespace() == true)) close = -1
            if (!display && close >= 0 && source.substring(start, close).any { it == '\n' || it == '`' }) close = -1
            if (close >= 0) {
                val latex = source.substring(start, close)
                if (latex.isNotBlank() && (display || '\n' !in latex) && safeMath(latex)) {
                    val marker = "\uE000${latex.hashCode().toUInt().toString(16)}:${formulas.size}\uE001"
                    val span = MathSpan(marker, latex, display, source.substring(at, close + closer.length))
                    formulas[marker] = span
                    if (display) result.append("\n\n")
                    result.append(marker)
                    if (display) result.append("\n\n")
                    at = close + closer.length
                    continue
                }
                result.append(escapeMathDollars(source.substring(at, close + closer.length)))
                at = close + closer.length
                continue
            }
        }
        if (ch == '\u0024') { result.append("\\\u0024"); at++; continue }
        if (ch == '\\' && at + 1 < source.length) appendThrough(at + 2)
        else appendThrough(at + 1)
    }
    return MathMarkdown(result.toString(), formulas)
}

private fun escapedAt(text: String, at: Int): Boolean {
    var start = at
    while (start > 0 && text[start - 1] == '\\') start--
    return (at - start) % 2 == 1
}

private fun safeMath(text: String): Boolean {
    if (text.length > 4096 || '`' in text || unsafeMathCommands.containsMatchIn(text)) return false
    var depth = 0
    text.forEachIndexed { index, char ->
        if (!escapedAt(text, index)) {
            if (char == '{') depth++
            if (char == '}') depth--
            if (depth !in 0..32) return false
        }
    }
    return depth == 0
}


private fun escapeMathDollars(text: String): String = buildString {
    text.forEachIndexed { index, char ->
        if (char == '\u0024' && !escapedAt(text, index)) append('\\')
        append(char)
    }
}

/** Use the existing Markdown grammar to protect code, links and HTML exactly. */
private fun protectedMathRanges(source: String): List<IntRange> {
    val types = setOf(MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK,
        MarkdownElementTypes.CODE_SPAN, MarkdownElementTypes.LINK_DESTINATION,
        MarkdownElementTypes.LINK_TITLE, MarkdownElementTypes.AUTOLINK, MarkdownElementTypes.HTML_BLOCK)
    val stack = ArrayDeque<ASTNode>()
    stack.add(MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source))
    val ranges = mutableListOf<IntRange>()
    while (stack.isNotEmpty()) {
        val node = stack.removeLast()
        if (node.type in types || node.type.toString() == "GFM_AUTOLINK") ranges += node.startOffset until node.endOffset
        else node.children.forEach(stack::add)
    }
    return ranges.sortedBy { it.first }
}
