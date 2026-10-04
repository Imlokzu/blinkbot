package me.waveio.claudebot.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.waveio.claudebot.data.replyImages
import me.waveio.claudebot.state.PreviewItem

/** Render Markdown image nodes where they occur, retaining surrounding prose. */
@Composable
internal fun ReplyMarkdown(text: String, origin: String, live: Boolean, generation: Long,
                           thumbnails: Map<String, ByteArray>, failures: Set<String>, actions: AppActions) {
    val images = remember(text, origin, live) { replyImages(text, origin, live) }
    if (images.isEmpty()) { ChatMarkdown(text); return }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var offset = 0
        for (image in images) {
            val before = text.substring(offset, image.start).trim()
            if (before.isNotEmpty()) ChatMarkdown(before)
            key(generation, image.item.cacheKey()) {
                ReplyImage(image.item, thumbnails[image.item.cacheKey()], image.item.cacheKey() in failures, actions)
            }
            offset = image.end
        }
        val after = text.substring(offset).trim()
        if (after.isNotEmpty()) ChatMarkdown(after)
    }
}

@Composable
private fun ReplyImage(item: PreviewItem, bytes: ByteArray?, failed: Boolean, actions: AppActions) {
    val p = LocalPalette.current
    val bitmap = rememberMediaBitmap(bytes, thumbnail = false, retainOnEviction = true, decodedLimit = 1024)
    LaunchedEffect(Unit) { if (bytes == null) actions.loadReplyImage(item.path, item.source) }
    Column(Modifier.widthIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        val image = bitmap
        if (image != null) {
            // Preserve portrait/landscape geometry and fit every pixel in the frame.
            Image(image, item.name.ifBlank { tr("media.photo") }, Modifier.fillMaxWidth()
                .aspectRatio(image.width.toFloat() / image.height)
                .clip(RoundedCornerShape(16.dp)).testTag("reply-image:${item.source}:${item.path}")
                .clickable(role = Role.Button) { actions.previewReplyImage(item.path, item.source) },
                contentScale = ContentScale.Fit)
        } else Column(Modifier.fillMaxWidth().heightIn(min = 140.dp).clip(RoundedCornerShape(16.dp))
            .background(p.secondary).padding(16.dp).testTag("reply-image-placeholder:${item.source}:${item.path}"),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (failed || bytes != null) {
                Text(tr("media.imageFailed"), color = p.muted, fontSize = 12.sp)
                QuietAction(tr("action.retry"), { actions.loadReplyImage(item.path, item.source, true) })
            } else {
                LoadingDots()
                Text(tr("media.imageLoading"), color = p.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
