package me.waveio.claudebot.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import me.waveio.claudebot.state.AppState
import me.waveio.claudebot.state.PreviewItem
import me.waveio.claudebot.data.WebPreviewResource

@Composable
internal fun MediaPreview(state: AppState, actions: AppActions) {
    val p = LocalPalette.current
    val items = state.previewItems.ifEmpty {
        listOf(PreviewItem(state.previewPath.orEmpty(), state.previewTitle.orEmpty(), state.previewMimeType, state.previewSource.orEmpty()))
    }
    // Keep this pager stable while the controller replaces the loaded page bytes.
    val pager = rememberPagerState(initialPage = items.indexOfFirst {
        it.path == state.previewPath && it.source == state.previewSource
    }.coerceAtLeast(0), pageCount = { items.size })
    val visibleItem = items.getOrNull(pager.currentPage)
    val imageZoom = remember(pager.settledPage, state.previewRevision) { PreviewImageZoomState() }
    val web = state.previewWeb
    val canExport = !state.loading && !state.previewWriteActive && !state.previewExporting && !state.editorExporting && !pager.isScrollInProgress &&
        (if (web != null) state.previewMimeType == "text/html" else
            pager.currentPage == pager.settledPage && visibleItem?.path == state.previewPath && visibleItem?.source == state.previewSource)
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    val workspaceText = state.previewSource == "workspace" && supportsWorkspaceTextPreview(state.previewMimeType)
    var readerError by remember(state.previewRevision) { mutableStateOf(false) }
    LaunchedEffect(pager.settledPage, items) {
        val item = items.getOrNull(pager.settledPage) ?: return@LaunchedEffect
        if (item.path != state.previewPath || item.source != state.previewSource) actions.preview(item)
    }
    fun move(delta: Int) {
        val target = (pager.currentPage + delta).coerceIn(0, items.lastIndex)
        scope.launch { if (reduced) pager.scrollToPage(target) else pager.animateScrollToPage(target) }
    }
    Dialog(actions::closePreview, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        NativeDialogChrome(p.dark)
        // A dialog has its own constraints; its size can differ from the host.
        BoxWithConstraints(Modifier.fillMaxSize().background(p.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val inset = if (maxWidth >= 600.dp) 24.dp else 0.dp
        Box(Modifier.fillMaxSize().padding(inset), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = MediaContentMaxWidth).fillMaxSize()
            .clip(RoundedCornerShape(if (inset > 0.dp) 24.dp else 0.dp)).background(p.background).testTag("media-preview")) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction("close", tr("action.close"), actions::closePreview)
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(state.previewTitle.orEmpty(), color = p.ink, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (web == null && items.size > 1) Text(tr("media.position", "index" to pager.currentPage + 1, "count" to items.size), color = p.muted, fontSize = 11.sp)
                }
                if (state.previewEditable && workspaceText) IconAction("edit", tr("files.edit"), actions::editPreview, enabled = !state.loading && !state.previewWriteActive && !state.previewExporting)
                IconAction("retry", tr("files.reloadPreview"), actions::reloadPreview, enabled = !state.loading && !state.previewWriteActive && !state.previewExporting)
            }
            if (state.previewWriteActive) Text(tr("files.stillWriting"), color = p.muted, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            if (web != null) Box(Modifier.fillMaxWidth().weight(1f).testTag("web-preview"), contentAlignment = Alignment.Center) {
                if (web.ready && web.entry != null) {
                    val revision = state.previewRevision
                    NativeWebAppPreview(web.entry, revision,
                        loadResource = { path -> actions.loadWebPreviewResource(revision, path) },
                        onError = { actions.webPreviewFailed(revision) }, modifier = Modifier.fillMaxSize())
                } else Column(Modifier.widthIn(max = CompactPanelMaxWidth).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(tr("files.buildTitle"), color = p.ink, fontSize = 20.sp)
                    Text(tr("files.buildRequired"), color = p.muted, fontSize = 14.sp)
                }
            } else {
            HorizontalPager(pager, modifier = Modifier.fillMaxWidth().weight(1f).testTag("media-pages"),
                userScrollEnabled = imageZoom.scale == 1f, key = { items[it].cacheKey() }) { index ->
                val item = items[index]
                val current = item.path == state.previewPath && item.source == state.previewSource
                val bitmap = rememberMediaBitmap(if (current) state.previewBytes else state.attachmentThumbnails[item.cacheKey()])
                Box(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    if (bitmap != null && current && index == pager.settledPage) {
                        ZoomablePreviewImage(bitmap, item.name, imageZoom, Modifier.fillMaxSize().testTag("media-image:${item.path}"))
                    } else if (bitmap != null) Image(bitmap, item.name, Modifier.fillMaxSize().testTag("media-image:${item.path}"), contentScale = ContentScale.Fit)
                    else if (current && state.loading) LoadingDots()
                    else if (current && state.previewMimeType == "image/svg+xml" && state.previewBytes != null) {
                        SvgWorkspacePreview(state.previewBytes, state.previewRevision, Modifier.fillMaxSize()) { readerError = true }
                    }
                    else if (current && workspaceText && state.previewEditable && !state.previewTruncated) {
                        WorkspaceDocument("preview:${state.previewRevision}", state.previewWorkspacePath ?: item.path,
                            state.previewText, editorKind(item.path), true, state, actions, Modifier.fillMaxSize(),
                            onError = { if (it != "resource_unavailable") readerError = true })
                    }
                    else if (current) SelectionContainer(Modifier.widthIn(max = ChatContentMaxWidth).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        if (state.previewText.isNotBlank() && (state.previewSource != "workspace" || workspaceText)) {
                            if (state.previewMimeType == "text/markdown") ChatMarkdown(state.previewText)
                            else Text(state.previewText, color = p.ink, fontSize = 15.sp)
                        }
                        else Text(tr("files.previewUnavailable"), color = p.muted)
                    }
                }
            }
            if (readerError) Text(tr("files.renderFailed"), color = p.accent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            }
            if (state.previewWebError) Text(tr("files.webFailed"), color = p.accent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            if (state.error != null) Text(tr(state.error), color = p.accent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            if (state.previewTruncated) Text(tr(if (state.previewSource == "workspace") "files.tooLarge" else "files.previewTruncated"), color = p.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            if (web?.buildable == true) ActionButton(tr(if (web.ready) "files.rebuild" else "files.build"), actions::buildPreviewProject,
                Modifier.align(Alignment.CenterHorizontally).widthIn(max = FormContentMaxWidth).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                primary = true, icon = "bot", enabled = !state.loading && !state.previewWriteActive && !state.previewExporting)
            if (web == null || state.previewMimeType == "text/html") {
            Row(Modifier.align(Alignment.CenterHorizontally).widthIn(max = FormContentMaxWidth).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (web == null && items.size > 1) IconAction("back", tr("media.previous"), { move(-1) }, enabled = pager.currentPage > 0)
                ActionButton(tr("media.save"), actions::savePreview, Modifier.weight(1f), enabled = canExport, icon = "download")
                IconAction("share", tr("media.share"), actions::sharePreview, enabled = canExport)
                if (web == null && items.size > 1) IconAction("back", tr("media.next"), { move(1) }, Modifier.graphicsLayer { rotationZ = 180f }, enabled = pager.currentPage < items.lastIndex)
            }
            }
            if (state.previewExporting) LoadingDots(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 10.dp))
        }
        }
        }
    }
}

/** A successful UTF-8 decode does not make an ASCII PDF or archive a text document. */
internal fun supportsWorkspaceTextPreview(mimeType: String): Boolean =
    mimeType.startsWith("text/") || mimeType in setOf("application/json", "application/xml", "application/octet-stream")

/** SVG runs only as an image inside the existing untrusted, network-isolated viewer. */
@Composable
private fun SvgWorkspacePreview(bytes: ByteArray, revision: Long, modifier: Modifier, onError: () -> Unit) {
    val background = if (LocalPalette.current.dark) "#13110f" else "#f5f1ea"
    val html = remember(background) {
        """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:$background}img{display:block;width:100%;height:100%;object-fit:contain}</style></head><body><img src="image.svg" alt=""></body></html>""".encodeToByteArray()
    }
    NativeWebAppPreview("index.html", revision, loadResource = { path ->
        when (path.removePrefix("/")) {
            "index.html" -> WebPreviewResource(html, "text/html")
            "image.svg" -> WebPreviewResource(bytes, "image/svg+xml")
            else -> null
        }
    }, onError = onError, modifier = modifier)
}
