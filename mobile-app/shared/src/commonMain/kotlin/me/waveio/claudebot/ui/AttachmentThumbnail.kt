package me.waveio.claudebot.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.waveio.claudebot.state.DraftAttachment

@Composable
fun AttachmentThumbnail(item: DraftAttachment, bytes: ByteArray?, actions: AppActions, compact: Boolean = false) {
    LaunchedEffect(item.path, bytes) { if (bytes == null && item.mimeType.startsWith("image/")) actions.loadAttachmentThumbnail(item.path) }
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, bytes) {
        value = bytes?.let { withContext(Dispatchers.Default) { decodeImage(it, if (compact) 192 else 512) } }
    }
    val image = bitmap
    if (item.mimeType.startsWith("image/")) Box(
        Modifier.size(if (compact) 72.dp else 220.dp).clip(RoundedCornerShape(16.dp))
            .background(LocalPalette.current.secondary).clickable { actions.previewAttachment(item.path) },
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) Image(image, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Glyph("photo", tint = LocalPalette.current.muted)
            Text(item.name, color = LocalPalette.current.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
        }
    } else ActionButton(item.name, { actions.previewAttachment(item.path) }, icon = "file")
}
