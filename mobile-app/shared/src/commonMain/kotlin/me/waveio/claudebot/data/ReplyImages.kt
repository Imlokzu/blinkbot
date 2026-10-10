package me.waveio.claudebot.data

import io.ktor.http.Url
import io.ktor.http.decodeURLPart
import me.waveio.claudebot.state.PreviewItem
import org.intellij.markdown.MarkdownElementTypes as M
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/** Source ranges let the UI keep prose/captions in order without printing image URLs. */
data class ReplyImage(val start: Int, val end: Int, val item: PreviewItem)

private val raster = setOf("png", "jpg", "jpeg", "webp", "gif", "avif")
private val escaped = Regex("\\\\([\\\\`*{}\\[\\]()#+.!_<>-])")
private fun String.unescapeLink() = replace(escaped, "$1").replace("&amp;", "&")
private fun ASTNode.descendant(type: org.intellij.markdown.IElementType): ASTNode? =
    children.firstOrNull { it.type == type } ?: children.firstNotNullOfOrNull { it.descendant(type) }

/** Only app-owned paths get device authentication; foreign images use the host proxy. */
fun replyImageItem(destination: String, caption: String, origin: String): PreviewItem? {
    val raw = destination.removeSurrounding("<", ">").unescapeLink()
    if (raw.isBlank() || raw.length > 8192 || raw.any { it.isISOControl() || it == '\\' }) return null
    val base = runCatching { Url(origin) }.getOrNull() ?: return null
    val url = runCatching { Url(if (raw.startsWith('/') && !raw.startsWith("//")) origin + raw else raw) }.getOrNull() ?: return null
    if (url.protocol.name !in setOf("http", "https") || url.host.isBlank() || url.user != null || url.password != null) return null
    val own = url.protocol == base.protocol && url.host.equals(base.host, true) && url.port == base.port
    val path = url.encodedPath
    val source: String
    val target: String
    if (own && path.startsWith("/uploads/")) {
        val name = path.removePrefix("/uploads/")
        if (name.isBlank() || name in setOf(".", "..") || name.any { it == '/' || it == '%' } || url.parameters.names().isNotEmpty()) return null
        source = "upload"; target = path
    } else if (own && path.startsWith("/preview/")) {
        val relative = runCatching { path.removePrefix("/preview/").decodeURLPart() }.getOrNull() ?: return null
        target = runCatching { checkedWorkspacePath(relative) }.getOrNull() ?: return null
        source = "workspace"
    } else {
        // Do not proxy this host's protected pages back through a public fetch.
        if (own || raw.startsWith('/') || !raw.contains("://")) return null
        source = "remote"; target = raw.substringBefore('#')
    }
    val filename = path.substringAfterLast('/').let { runCatching { it.decodeURLPart() }.getOrDefault(it) }
    val extension = filename.substringAfterLast('.', "").lowercase()
    val mime = if (extension in raster) workspaceMimeType(filename) else "image/*"
    return PreviewItem(target, caption.ifBlank { filename }.take(180), mime, source)
}

fun replyImages(text: String, origin: String, live: Boolean = false): List<ReplyImage> {
    if (text.isBlank()) return emptyList()
    val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(text)
    val definitions = mutableMapOf<String, String>()
    fun ASTNode.value() = text.substring(startOffset, endOffset)
    fun label(value: String) = value.removeSurrounding("[", "]").trim().lowercase().replace(Regex("\\s+"), " ")
    fun collectDefinitions(node: ASTNode) {
        if (node.type == M.LINK_DEFINITION) {
            val key = node.descendant(M.LINK_LABEL)?.value()
            val target = node.descendant(M.LINK_DESTINATION)?.value()
            if (key != null && target != null) {
                val normalized = label(key)
                if (normalized !in definitions) definitions[normalized] = target
            }
        } else node.children.forEach(::collectDefinitions)
    }
    collectDefinitions(root)
    val found = mutableListOf<ReplyImage>()
    fun visit(node: ASTNode) {
        if (node.type in setOf(M.CODE_FENCE, M.CODE_BLOCK, M.CODE_SPAN, M.HTML_BLOCK, M.LINK_DEFINITION)) {
            return
        }
        if (node.type == M.IMAGE || node.type == M.INLINE_LINK || node.type == M.FULL_REFERENCE_LINK) {
            val targetNode = node.descendant(M.IMAGE) ?: node
            val caption = targetNode.descendant(M.LINK_TEXT)?.value()?.removeSurrounding("[", "]")?.unescapeLink().orEmpty()
            val destination = targetNode.descendant(M.AUTOLINK)?.value()
                ?: targetNode.descendant(M.LINK_DESTINATION)?.value()
                ?: targetNode.descendant(M.LINK_LABEL)?.value()?.let { definitions[label(it)] }
                ?: definitions[label(caption)]
            val image = targetNode.type == M.IMAGE || destination?.substringBefore('?')?.substringBefore('#')?.substringAfterLast('.')?.lowercase() in raster
            if (image && destination != null) replyImageItem(destination, caption, origin)?.let {
                found += ReplyImage(node.startOffset, node.endOffset, it)
            }
            return
        }
        if (node.type == M.AUTOLINK || node.type == GFMTokenTypes.GFM_AUTOLINK) {
            val destination = node.value().removeSurrounding("<", ">")
            val prefix = text.substring(text.lastIndexOf('\n', (node.startOffset - 1).coerceAtLeast(0)) + 1, node.startOffset)
            val incompleteImage = prefix.lastIndexOf("![") > prefix.lastIndexOf(')')
            val ended = !live || node.endOffset < text.length || node.value().endsWith('>')
            if (!incompleteImage && ended && destination.substringBefore('?').substringBefore('#').substringAfterLast('.').lowercase() in raster)
                replyImageItem(destination, "", origin)?.let { found += ReplyImage(node.startOffset, node.endOffset, it) }
            return
        }
        node.children.forEach(::visit)
    }
    visit(root)
    return found.sortedBy { it.start }.take(24)
}
