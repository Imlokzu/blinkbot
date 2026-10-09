package me.waveio.claudebot.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.waveio.claudebot.data.WebPreviewResource
import me.waveio.claudebot.data.WorkspaceEditorAction
import me.waveio.claudebot.data.WorkspaceEditorDocument
import me.waveio.claudebot.data.WorkspaceEditorSession

/** Bundled trusted editor. Workspace HTML remains in [NativeWebAppPreview]. */
@Composable
expect fun NativeWorkspaceEditor(
    document: WorkspaceEditorDocument,
    session: WorkspaceEditorSession,
    onChange: (String) -> Unit,
    onAction: (WorkspaceEditorAction) -> Unit,
    onError: (String) -> Unit,
    loadResource: suspend (String) -> WebPreviewResource?,
    modifier: Modifier = Modifier,
)
