package me.waveio.claudebot.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.model.markdownAnimations
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.IElementType
import org.intellij.markdown.ast.findChildOfType

private val LocalStreamTail = compositionLocalOf { false }

/** Keep the last parsed frame visible while real streamed text is being parsed. */
@Composable
fun ChatMarkdown(text: String, streaming: Boolean = false) {
    val renderedText = rememberStreamingText(text, streaming)
    // Recreate parsing state only for authoritative replacements, so retained
    // Markdown cannot show an obsolete snapshot while the replacement parses.
    key(renderedText.revision) {
        val state = rememberMarkdownState(renderedText.text, retainState = true)
        val components = remember {
            markdownComponents(
                paragraph = { StreamingMarkdownText(it, it.typography.paragraph) },
                text = { StreamingMarkdownText(it, it.typography.text, plain = true) },
                heading1 = { StreamingMarkdownText(it, it.typography.h1, MarkdownTokenTypes.ATX_CONTENT) },
                heading2 = { StreamingMarkdownText(it, it.typography.h2, MarkdownTokenTypes.ATX_CONTENT) },
                heading3 = { StreamingMarkdownText(it, it.typography.h3, MarkdownTokenTypes.ATX_CONTENT) },
                heading4 = { StreamingMarkdownText(it, it.typography.h4, MarkdownTokenTypes.ATX_CONTENT) },
                heading5 = { StreamingMarkdownText(it, it.typography.h5, MarkdownTokenTypes.ATX_CONTENT) },
                heading6 = { StreamingMarkdownText(it, it.typography.h6, MarkdownTokenTypes.ATX_CONTENT) },
                setextHeading1 = { StreamingMarkdownText(it, it.typography.h1, MarkdownTokenTypes.SETEXT_CONTENT) },
                setextHeading2 = { StreamingMarkdownText(it, it.typography.h2, MarkdownTokenTypes.SETEXT_CONTENT) },
                table = { ChatTable(it) },
            )
        }
        CompositionLocalProvider(LocalStreamTail provides renderedText.animateTail) {
            // The reveal window is bounded; only its fresh suffix drawing is softened.
            Markdown(state, modifier = Modifier.wrapContentWidth(), components = components,
                animations = markdownAnimations(animateTextSize = { this }),
                loading = { Text(renderedText.text, color = LocalPalette.current.ink) },
                error = { Text(renderedText.text, color = LocalPalette.current.ink) })
        }
    }
}

/** Keep the renderer's annotated links/styles and layout; animate only drawing. */
@Composable
private fun StreamingMarkdownText(model: MarkdownComponentModel, style: TextStyle,
                                  childType: IElementType? = null, plain: Boolean = false) {
    val settings = annotatorSettings()
    val styled = remember(model.content, model.node, style, settings, childType, plain) {
        buildAnnotatedString {
            pushStyle(style.toSpanStyle())
            if (plain) append(model.node.getUnescapedTextInNode(model.content))
            else buildMarkdownAnnotatedString(model.content, childType?.let { model.node.findChildOfType(it) } ?: model.node, settings)
            pop()
        }
    }
    val isTail = model.node.endOffset >= model.content.trimEnd().length
    val tail = rememberStreamingTail(styled.text, LocalStreamTail.current && isTail)
    MarkdownText(styled, modifier = tail.modifier.then(if (childType != null) Modifier.semantics { heading() } else Modifier),
        style = style, onTextLayout = { layout, _ -> tail.onTextLayout(layout) })
}

@Immutable
private data class TableRow(val cells: List<String>, val header: Boolean)

@Composable
private fun ChatTable(model: MarkdownComponentModel) {
    val rows = remember(model.content, model.node) {
        model.node.children.filter { it.type == GFMElementTypes.HEADER || it.type == GFMElementTypes.ROW }.map { row ->
            TableRow(row.children.filter { it.type == GFMTokenTypes.CELL }.map { cell ->
                model.content.substring(cell.startOffset, cell.endOffset).trim()
            }, row.type == GFMElementTypes.HEADER)
        }
    }
    val columns = rows.maxOfOrNull { it.cells.size } ?: return
    val colors = LocalMarkdownColors.current
    val active = LocalStreamTail.current && model.node.endOffset >= model.content.trimEnd().length
    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(colors.tableBackground)
        .horizontalScroll(rememberScrollState()).width((columns * 180).dp)) {
        rows.forEachIndexed { index, row -> key(index) { ChatTableRow(row, model.typography.table, active && index == rows.lastIndex) } }
    }
}

@Composable
private fun ChatTableRow(row: TableRow, style: TextStyle, streaming: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        row.cells.forEachIndexed { index, cell -> ChatTableCell(cell, if (row.header) style.copy(fontWeight = FontWeight.SemiBold) else style, streaming && index == row.cells.lastIndex) }
    }
    if (row.header) Hairline()
}

@Composable
private fun ChatTableCell(cell: String, style: TextStyle, streaming: Boolean) {
    val settings = annotatorSettings()
    // Previous cells have unchanged strings while a new row streams in; cache
    // their inline Markdown instead of rebuilding every cell from the full reply.
    val text = remember(cell, style, settings) { cell.buildMarkdownAnnotatedString(style, settings) }
    val tail = rememberStreamingTail(text.text, streaming)
    Text(text, style = style, maxLines = Int.MAX_VALUE, overflow = TextOverflow.Clip,
        modifier = Modifier.width(180.dp).padding(12.dp).then(tail.modifier), onTextLayout = tail.onTextLayout)
}
