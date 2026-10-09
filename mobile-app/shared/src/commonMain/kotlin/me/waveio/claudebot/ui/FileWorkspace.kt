package me.waveio.claudebot.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.waveio.claudebot.data.*
import me.waveio.claudebot.state.AppState
import me.waveio.claudebot.state.FileRow

/** All native exits share one flush gate; a failed handshake keeps the draft open. */
@Stable
class WorkspaceEditorNavigation {
    val session = WorkspaceEditorSession()
    private var flushVersion = 0L
    var mounted = false
        set(value) {
            if (field && !value) {
                flushVersion++
                flushing = false
            }
            field = value
        }
    var active = true
    var flushing by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)

    fun afterFlush(action: () -> Unit) {
        if (!active || flushing) return
        if (!mounted) { action(); return }
        val version = ++flushVersion
        flushing = true
        failed = false
        session.flush { success ->
            if (version != flushVersion) return@flush
            flushVersion++
            flushing = false
            if (active) {
                failed = !success
                if (success) action()
            }
        }
    }
}

@Composable
fun rememberWorkspaceEditorNavigation(id: String): WorkspaceEditorNavigation {
    val navigation = remember(id) { WorkspaceEditorNavigation() }
    DisposableEffect(navigation) { onDispose { navigation.active = false } }
    return navigation
}

@Composable
internal fun WorkspaceDocument(
    id: String, path: String, content: String, kind: String, readOnly: Boolean,
    state: AppState, actions: AppActions, modifier: Modifier = Modifier,
    navigation: WorkspaceEditorNavigation = rememberWorkspaceEditorNavigation(id),
    flushOnExit: Boolean = !readOnly,
    onError: (String) -> Unit = {},
) {
    val language = state.preferences.language.takeUnless { it == "system" }
        ?.lowercase()?.substringBefore('-')?.substringBefore('_')?.takeIf { it == "en" || it == "uk" }
        ?: tr("locale.code")
    val palette = LocalPalette.current
    val previewing = id == "preview:${state.previewRevision}"
    val status = when {
        (if (previewing) state.previewWriteActive else state.fileWriteActive) -> "writing"
        previewing -> "saved"
        else -> state.fileSaveState
    }
    val document = WorkspaceEditorDocument(id, path, kind, content, readOnly,
        if (palette.dark) "dark" else "light", language, status)
    DisposableEffect(navigation, flushOnExit) {
        navigation.mounted = flushOnExit
        onDispose { navigation.mounted = false }
    }
    NativeWorkspaceEditor(document, navigation.session,
        onChange = { actions.editorChanged(id, it) },
        onAction = { event ->
            when (event.action) {
                "openWorkspace" -> event.path?.let { path -> navigation.afterFlush { actions.openEditorLink(id, path) } }
                "openExternal" -> event.path?.let { path -> navigation.afterFlush { actions.openLink(path) } }
                "convertMermaid" -> event.content?.let { scene -> navigation.afterFlush { actions.convertEditorMermaid(id, scene) } }
                "exportDrawing" -> event.content?.let { png -> navigation.afterFlush { actions.saveEditorDrawing(id, png) } }
                "createDrawing" -> actions.createEditorDrawing(id) { path ->
                    navigation.session.resolveAction(event.sequence, path, if (path == null) "create_failed" else null)
                }
                "retry" -> navigation.afterFlush { if (flushOnExit) actions.reloadFile() else actions.reloadPreview() }
            }
        },
        onError = onError,
        loadResource = { actions.loadEditorResource(id, it) },
        modifier = modifier,
    )
}

internal fun editorKind(path: String): String = when (workspaceFileKind(path)) {
    "markdown" -> "markdown"
    "drawing" -> "drawing"
    "mermaid" -> "mermaid"
    "html", "code" -> "code"
    else -> "text"
}

@Composable
fun FileWorkspace(state: AppState, actions: AppActions, navigation: WorkspaceEditorNavigation) {
    val positions = rememberSaveableStateHolder()
    if (state.openFile != null) WorkspaceFileEditor(state, actions, navigation)
    else positions.SaveableStateProvider(state.directory) { WorkspaceFileBrowser(state, actions) }
}

@Composable
private fun WorkspaceFileBrowser(state: AppState, actions: AppActions) {
    val p = LocalPalette.current
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("name") }
    var creating by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    val rows = remember(state.files, query, sort) {
        val byValue: Comparator<FileRow> = when (sort) {
            "size" -> compareByDescending { it.size }
            "type" -> compareBy { workspaceFileKind(it.name) }
            else -> compareBy { it.name.lowercase() }
        }
        state.files.filter { it.name.contains(query, ignoreCase = true) }
            .sortedWith(compareByDescending<FileRow> { it.directory }.then(byValue).thenBy { it.name.lowercase() })
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("file-workspace")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                QuietAction(tr("files.root"), { actions.openDirectory("") })
                val parts = state.directory.split('/').filter { it.isNotBlank() }
                parts.forEachIndexed { index, part ->
                    Text("/", color = p.muted, fontSize = 12.sp)
                    QuietAction(part, { actions.openDirectory(parts.take(index + 1).joinToString("/")) })
                }
            }
            IconAction("retry", tr("files.refresh"), { actions.openDirectory(state.directory) }, enabled = !state.loading)
        }
        BotField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = tr("files.search"), icon = "search")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("name", "type", "size").forEach { value -> ChoicePill(tr("files.sort.$value"), sort == value, { sort = value }) }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(tr("files.newNote"), { name = ""; creating = "markdown" }, Modifier.weight(1f), icon = "new", enabled = !state.fileCreating)
            ActionButton(tr("files.newDrawing"), { name = ""; creating = "drawing" }, Modifier.weight(1f), icon = "edit", enabled = !state.fileCreating)
        }
        if (state.loading) LoadingDots(Modifier.padding(vertical = 8.dp))
        LazyColumn(Modifier.weight(1f), state = rememberLazyListState()) {
            items(rows, key = { it.path }) { file ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button) {
                    if (file.directory) actions.openDirectory(file.path) else actions.browseFile(file.path)
                }.padding(horizontal = 8.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    val kind = workspaceFileKind(file.name)
                    Glyph(if (file.directory) "folder" else when (kind) { "image" -> "photo"; "drawing", "mermaid" -> "edit"; else -> "file" },
                        tint = if (file.directory) p.accent else p.muted)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(file.name, color = p.ink, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (file.directory) tr("files.folder") else tr("files.type.$kind"), color = p.muted, fontSize = 11.sp)
                    }
                    if (!file.directory) Text(fileSize(file.size), color = p.muted, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
                }
                Hairline()
            }
            if (!state.loading && rows.isEmpty()) item {
                Text(tr(if (query.isBlank()) "files.empty" else "files.noResults"), color = p.muted, modifier = Modifier.padding(vertical = 30.dp))
            }
        }
        Text(tr("files.count", "count" to rows.size), color = p.muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp))
    }
    creating?.let { kind -> WorkspaceDialog({ if (!state.fileCreating) creating = null }) {
        Text(tr(if (kind == "drawing") "files.newDrawing" else "files.newNote"), color = p.ink, fontSize = 20.sp)
        BotField(name, { name = it }, Modifier.fillMaxWidth().padding(vertical = 14.dp), label = tr("files.name"))
        state.error?.let { Text(tr(it), color = p.accent, fontSize = 12.sp) }
        ActionButton(tr(if (state.fileCreating) "files.creating" else "files.create"), { actions.createFile(name, kind) },
            Modifier.fillMaxWidth(), primary = true, enabled = name.isNotBlank() && !state.fileCreating)
        QuietAction(tr("input.cancel"), { creating = null }, Modifier.fillMaxWidth(), enabled = !state.fileCreating)
    } }
}

@Composable
private fun fileSize(size: Long): String = when {
    size < 1024 -> tr("files.bytes", "size" to size)
    size < 1024 * 1024 -> tr("files.kilobytes", "size" to ((size + 1023) / 1024))
    else -> tr("files.megabytes", "size" to (size / 104857.6).toInt().let { "${it / 10}.${it % 10}" })
}

@Composable
private fun WorkspaceFileEditor(state: AppState, actions: AppActions, navigation: WorkspaceEditorNavigation) {
    val path = state.openFile ?: return
    val p = LocalPalette.current
    val id = state.fileEditorGeneration.toString()
    var engineError by remember(id) { mutableStateOf(false) }
    var localSource by remember(id) { mutableStateOf(false) }
    var confirmSource by remember(id) { mutableStateOf(false) }
    var resolveConflict by remember(id) { mutableStateOf(false) }
    var copyName by remember(id) { mutableStateOf<String?>(null) }
    val contentReady = !state.fileLoading && (state.fileEditable || state.fileText.isNotEmpty())
    val kind = workspaceFileKind(path)
    Column(Modifier.fillMaxSize().testTag("workspace-file-editor")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            val title = when {
                !state.fileEditable -> "files.readOnly"
                kind == "markdown" -> "files.documentEditor"
                kind == "drawing" || kind == "mermaid" -> "files.drawingEditor"
                kind == "html" || kind == "code" -> "files.sourceEditor"
                else -> "files.editor"
            }
            Text(tr(title), color = p.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            val status = when {
                state.fileLoading && !state.fileRecovery -> "files.opening"
                state.fileWriteActive -> "files.waitingForWriter"
                else -> "files.${state.fileSaveState}"
            }
            Text(tr(status), color = if (!state.fileWriteActive && state.fileSaveState in setOf("failed", "conflict")) p.accent else p.muted, fontSize = 12.sp)
            if (navigation.flushing) LoadingDots(Modifier.padding(start = 8.dp))
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            QuietAction(tr(if (kind == "html") "files.previewTab" else "files.openReader"), { navigation.afterFlush { actions.previewEditedFile(id) } }, enabled = !state.fileLoading && !navigation.flushing)
            QuietAction(tr("files.saveNow"), { navigation.afterFlush(actions::retryFileSave) }, enabled = state.fileEditable && !state.fileWriteActive && !navigation.flushing)
            QuietAction(tr("files.copySource"), { navigation.afterFlush { actions.copyEditorContent(id) } }, enabled = contentReady && !navigation.flushing)
            QuietAction(tr("files.saveCopy"), { navigation.afterFlush { copyName = path.substringAfterLast('/') } }, enabled = contentReady && !state.fileCreating && !navigation.flushing)
        }
        if (state.fileRecovery) WorkspaceMessage(tr(if (state.fileStorageError) "files.memoryDraft" else "files.recovery"))
        if (state.fileStorageError) Text(tr("error.storage"), color = p.accent, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp))
        if (state.editorExporting) LoadingDots(Modifier.padding(horizontal = 16.dp, vertical = 5.dp))
        if (state.fileWriteActive) WorkspaceMessage(tr("files.stillWriting"))
        if (state.fileExternallyChanged && state.fileConflict == null) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("files.updated"), color = p.accent, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 16.dp))
            QuietAction(tr("files.reloadPreview"), { navigation.afterFlush(actions::reloadFile) })
        }
        if (state.fileConflict != null) ActionButton(tr("files.resolveConflict"), { navigation.afterFlush { resolveConflict = true } },
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
        if (state.fileLoadError != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr(state.fileLoadError), color = p.accent, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 16.dp))
            QuietAction(tr("action.retry"), { navigation.afterFlush(actions::reloadFile) })
        }
        if (navigation.failed || engineError) Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(tr("files.editorFailed"), color = p.accent, fontSize = 12.sp)
            QuietAction(tr("files.useLocalSource"), { confirmSource = true })
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                state.fileLoading && !state.fileRecovery -> LoadingDots(Modifier.align(Alignment.Center))
                localSource && state.fileEditable -> {
                    val label = tr("files.sourceEditor")
                    BasicTextField(state.fileText, { actions.editorChanged(id, it) }, Modifier.fillMaxSize().padding(16.dp)
                        .verticalScroll(rememberScrollState()).semantics { contentDescription = label },
                        readOnly = state.fileWriteActive, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = p.ink), cursorBrush = SolidColor(p.accent))
                }
                state.fileEditable || state.fileText.isNotEmpty() -> WorkspaceDocument(id, path, state.fileText, editorKind(path),
                    !state.fileEditable, state, actions, Modifier.fillMaxSize(), navigation,
                    flushOnExit = true, onError = { if (it != "resource_unavailable") engineError = true })
                else -> Text(tr("files.previewUnavailable"), color = p.muted, modifier = Modifier.padding(20.dp))
            }
        }
    }
    if (confirmSource) WorkspaceDialog({ confirmSource = false }) {
        Text(tr("files.useLocalSource"), color = p.ink, fontSize = 20.sp)
        Text(tr("files.localSourceHelp"), color = p.muted, modifier = Modifier.padding(vertical = 12.dp))
        ActionButton(tr("files.useLocalSource"), {
            localSource = true; navigation.mounted = false; navigation.failed = false; engineError = false; confirmSource = false
        }, Modifier.fillMaxWidth(), primary = true)
        QuietAction(tr("input.cancel"), { confirmSource = false }, Modifier.fillMaxWidth())
    }
    if (resolveConflict && state.fileConflict != null) WorkspaceDialog({ resolveConflict = false }) {
        var remote by remember { mutableStateOf(false) }
        val conflict = state.fileConflict
        Text(tr("files.resolveConflict"), color = p.ink, fontSize = 20.sp)
        Text(tr("files.conflictHelp"), color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ChoicePill(tr("files.localVersion"), !remote, { remote = false })
            ChoicePill(tr("files.remoteVersion"), remote, { remote = true })
        }
        val comparison = if (remote) conflict.remoteContent ?: tr("files.remoteMissing") else state.fileText
        SelectionContainer(Modifier.fillMaxWidth().heightIn(max = 170.dp).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            Text(comparison.take(8000), color = p.ink, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        if (comparison.length > 8000) Text(tr("files.shortComparison"), color = p.muted, fontSize = 12.sp)
        QuietAction(tr("files.copyVersion"), { actions.copyContent(comparison) }, Modifier.fillMaxWidth(),
            enabled = !remote || conflict.remoteContent != null)
        ActionButton(tr("files.keepLocal"), { resolveConflict = false; actions.keepLocalFile() }, Modifier.fillMaxWidth(), primary = true,
            enabled = !state.fileWriteActive && conflict.remoteContent != null)
        QuietAction(tr("files.loadRemote"), { resolveConflict = false; actions.reloadRemoteFile() }, Modifier.fillMaxWidth(), enabled = !state.fileWriteActive)
        QuietAction(tr("files.saveCopy"), { resolveConflict = false; copyName = path.substringAfterLast('/') }, Modifier.fillMaxWidth())
    }
    copyName?.let { name -> WorkspaceDialog({ if (!state.fileCreating) copyName = null }) {
        Text(tr("files.saveCopy"), color = p.ink, fontSize = 20.sp)
        BotField(name, { copyName = it }, Modifier.fillMaxWidth().padding(vertical = 12.dp), label = tr("files.name"))
        Text(tr("files.copyHelp"), color = p.muted, fontSize = 12.sp)
        state.error?.let { Text(tr(it), color = p.accent, fontSize = 12.sp) }
        ActionButton(tr(if (state.fileCreating) "files.creating" else "files.saveCopy"), { actions.saveFileCopy(name) },
            Modifier.fillMaxWidth(), primary = true, enabled = contentReady && name.isNotBlank() && !state.fileCreating)
        QuietAction(tr("input.cancel"), { copyName = null }, Modifier.fillMaxWidth(), enabled = !state.fileCreating)
    } }
}

@Composable
private fun WorkspaceMessage(text: String) = Text(text, color = LocalPalette.current.muted, fontSize = 12.sp,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp))

/** Keep every file decision reachable above a keyboard or with enlarged text. */
@Composable
private fun WorkspaceDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BotDialog(onDismiss) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), content = content)
    }
}
