package me.waveio.claudebot.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hrm.latex.renderer.Latex
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import com.mikepenz.markdown.compose.LocalMarkdownInlineContent
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.model.markdownInlineContent

internal val LocalMathSpans = compositionLocalOf<Map<String, MathSpan>> { emptyMap() }

/** Native formula layout, preserving the Markdown renderer's styles and links. */
@Composable
internal fun MathText(text: AnnotatedString, modifier: Modifier, style: TextStyle,
                      onTextLayout: (TextLayoutResult) -> Unit) {
    val spans = LocalMathSpans.current
    val matches = remember(text.text, spans) { mathMarker.findAll(text.text).filter { it.value in spans }.toList() }
    if (matches.isEmpty()) {
        MarkdownText(text, modifier = modifier, style = style, onTextLayout = { layout, _ -> onTextLayout(layout) })
        return
    }
    val palette = LocalPalette.current
    val fontSize = if (style.fontSize == TextUnit.Unspecified) 16.sp else style.fontSize
    val config = remember(fontSize, palette.ink) { LatexConfig(fontSize = fontSize, theme = LatexTheme.light(color = palette.ink)) }
    val display = spans[text.text.trim()]?.takeIf { it.display }
    if (display != null) {
        val label = tr("math.formula", "formula" to display.latex)
        Box(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp)
            .testTag("math-display").semantics { contentDescription = label }) {
            Latex(display.latex, config = config)
        }
        return
    }
    val measurer = rememberLatexMeasurer(config)
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val inline = matches.associate { match ->
            val span = spans.getValue(match.value)
            val formula = measurer.inlineContent(span.latex, config)
            val bounded = formula?.let {
                // A wide inline matrix still fits a phone/table cell and can
                // scroll within its own placeholder instead of being cut off.
                val width = with(density) { maxWidth.toSp() }
                if (!maxWidth.value.isFinite() || it.placeholder.width.value <= width.value) it
                else InlineTextContent(Placeholder(width, it.placeholder.height, it.placeholder.placeholderVerticalAlign)) {
                    Box(Modifier.horizontalScroll(rememberScrollState())) { Latex(span.latex, config = config) }
                }
            }
            match.value to bounded
        }
        val styled = buildAnnotatedString {
            var at = 0
            matches.forEach { match ->
                if (at < match.range.first) append(text.subSequence(at, match.range.first))
                val span = spans.getValue(match.value)
                val start = length
                if (inline[match.value] != null) appendInlineContent(match.value, span.latex)
                else append(span.source)
                val end = length
                text.spanStyles.filter { it.start < match.range.last + 1 && it.end > match.range.first }
                    .forEach { addStyle(it.item, start, end) }
                text.getLinkAnnotations(match.range.first, match.range.last + 1).forEach { annotation ->
                    when (val link = annotation.item) {
                        is LinkAnnotation.Url -> addLink(link, start, end)
                        is LinkAnnotation.Clickable -> addLink(link, start, end)
                    }
                }
                text.getStringAnnotations(match.range.first, match.range.last + 1)
                    .forEach { addStringAnnotation(it.tag, it.item, start, end) }
                at = match.range.last + 1
            }
            if (at < text.length) append(text.subSequence(at, text.length))
        }
        val content = inline.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
        CompositionLocalProvider(LocalMarkdownInlineContent provides markdownInlineContent(
            LocalMarkdownInlineContent.current.inlineContent + content)) {
            MarkdownText(styled, style = style, onTextLayout = { layout, _ -> onTextLayout(layout) })
        }
    }
}
