package me.waveio.claudebot.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import me.waveio.claudebot.state.PreviewItem

internal fun PreviewItem.cacheKey(): String = if (source == "workspace") "workspace:$path" else path
internal fun AppActions.preview(item: PreviewItem) {
    when (item.source) {
        "remote" -> previewReplyImage(item.path, item.source)
        "workspace" -> previewWorkFile(item.path)
        else -> previewAttachment(item.path)
    }
}

@Composable
internal fun rememberMediaBitmap(bytes: ByteArray?, thumbnail: Boolean = false, retainOnEviction: Boolean = false, decodedLimit: Int = if (thumbnail) 384 else 2048): ImageBitmap? {
    val currentBytes by rememberUpdatedState(bytes)
    val bitmap by produceState<ImageBitmap?>(null, decodedLimit, retainOnEviction) {
        snapshotFlow { currentBytes }.filter { it != null || !retainOnEviction }.collectLatest { encoded ->
            if (encoded != null) value = withContext(Dispatchers.Default) { decodeImage(encoded, decodedLimit) }
            else value = null
        }
    }
    return bitmap
}

/** A small contact sheet shares one viewer for uploaded and delivered images. */
@Composable
internal fun AttachmentGallery(items: List<PreviewItem>, thumbnails: Map<String, ByteArray>, actions: AppActions, revisions: Map<String, Int> = emptyMap()) {
    val images = items.filter { it.mimeType.startsWith("image/") }
    val files = items.filterNot { it.mimeType.startsWith("image/") }
    val p = LocalPalette.current
    if (images.isNotEmpty()) {
        val columns = images.size.coerceAtMost(3)
        Column(Modifier.widthIn(max = 340.dp).fillMaxWidth().clip(RoundedCornerShape(19.dp)).testTag("attachment-gallery"),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            images.take(6).chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    row.forEach { item -> key(item.cacheKey(), revisions[item.cacheKey()]) {
                        val bytes = thumbnails[item.cacheKey()]
                        // Keep this small decoded tile while the bounded original-
                        // byte cache evicts entries. Visible images must not keep
                        // re-downloading and evicting one another.
                        val bitmap = rememberMediaBitmap(bytes, thumbnail = true, retainOnEviction = true)
                        LaunchedEffect(Unit) {
                            if (bytes == null) {
                                if (item.source == "workspace") actions.loadWorkFileThumbnail(item.path)
                                else actions.loadAttachmentThumbnail(item.path)
                            }
                        }
                        Box(Modifier.weight(1f).aspectRatio(if (images.size == 1) 1.35f else columns.toFloat() / row.size).background(p.secondary)
                            .testTag("gallery:${item.source}:${item.path}")
                            .clickable(role = Role.Button) { actions.preview(item) }, contentAlignment = Alignment.Center) {
                            if (bitmap != null) Image(bitmap, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Glyph("photo", tint = p.muted)
                                Text(item.name, color = p.muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp))
                            }
                            if (images.size > 6 && item == images[5]) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f)), contentAlignment = Alignment.Center) {
                                Text(tr("media.more", "count" to images.size - 6), color = Color.White, fontSize = 25.sp)
                            }
                        }
                    } }
                }
            }
        }
    }
    files.forEach { item ->
        Row(Modifier.widthIn(max = 340.dp).fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(p.secondary)
            .testTag("file:${item.source}:${item.path}").clickable(role = Role.Button) {
                actions.preview(item)
            }
            .padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Glyph("file", tint = p.accent, modifier = Modifier.size(25.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, color = p.ink, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(tr("media.openFile"), color = p.muted, fontSize = 11.sp)
            }
            Glyph("down", tint = p.muted, modifier = Modifier.size(16.dp))
        }
    }
}
