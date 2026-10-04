package me.waveio.claudebot.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
        value = bytes?.let { withContext(Dispatchers.Default) { decodeImage(it) } }
    }
    val image = bitmap
    if (image != null) Image(image, item.name,
        Modifier.size(if (compact) 72.dp else 220.dp).clip(RoundedCornerShape(16.dp))
            .clickable { actions.previewAttachment(item.path) }, contentScale = ContentScale.Crop)
    else ActionButton(item.name, { actions.previewAttachment(item.path) }, icon = if (item.mimeType.startsWith("image/")) "photo" else "file")
}
