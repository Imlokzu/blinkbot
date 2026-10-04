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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.rememberMarkdownState
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/** Keep the last parsed frame visible while real streamed text is being parsed. */
@Composable
fun ChatMarkdown(text: String) {
    val state = rememberMarkdownState(text, retainState = true)
    val components = remember {
        markdownComponents(table = { ChatTable(it) })
    }
    Markdown(state, modifier = Modifier.fillMaxWidth(), components = components,
        loading = { Text(text, color = LocalPalette.current.ink) },
        error = { Text(text, color = LocalPalette.current.ink) })
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
    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(colors.tableBackground)
        .horizontalScroll(rememberScrollState()).width((columns * 180).dp)) {
        rows.forEachIndexed { index, row -> key(index) { ChatTableRow(row, model.typography.table) } }
    }
}

@Composable
private fun ChatTableRow(row: TableRow, style: TextStyle) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        row.cells.forEach { cell -> ChatTableCell(cell, if (row.header) style.copy(fontWeight = FontWeight.SemiBold) else style) }
    }
    if (row.header) Hairline()
}

@Composable
private fun ChatTableCell(cell: String, style: TextStyle) {
    val settings = annotatorSettings()
    // Previous cells have unchanged strings while a new row streams in; cache
    // their inline Markdown instead of rebuilding every cell from the full reply.
    val text = remember(cell, style, settings) { cell.buildMarkdownAnnotatedString(style, settings) }
    Text(text, style = style, maxLines = Int.MAX_VALUE, overflow = TextOverflow.Clip,
        modifier = Modifier.width(180.dp).padding(12.dp))
}
