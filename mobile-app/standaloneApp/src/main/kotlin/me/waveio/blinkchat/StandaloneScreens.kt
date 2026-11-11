package me.waveio.blinkchat

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.waveio.claudebot.state.AppState
import me.waveio.claudebot.state.Screen
import me.waveio.claudebot.ui.*

// :shared keeps FormContentMaxWidth internal, so the standalone forms mirror its value
// and stay on the same centered column as SettingsScreen.
private val StandaloneFormMaxWidth = 640.dp

private val ImageModelChoices = listOf("qwen-image-3.0-pro", "wan2.7-image")

/**
 * Settings and secondary screens of the standalone app, drawn with the shared components.
 * False hands the screen back to MobileShell, which owns Chat, Search and the device-only
 * Appearance, Language and Haptics pages.
 */
@Composable
fun StandaloneScreen(screen: Screen, state: AppState, controller: ChatController): Boolean {
    when (screen) {
        Screen.Profile -> ProfileSettings(state, controller)
        Screen.Models -> ModelSettings(state, controller)
        Screen.Personalization -> PersonalizationSettings(state, controller)
        Screen.Files -> WorkspaceSettings(state, controller)
        Screen.Agents, Screen.Skills -> ToolSettings(screen, state, controller)
        Screen.Queue -> AvailabilitySettings("standalone.queue.body", controller)
        Screen.Updates -> AvailabilitySettings("standalone.updates.body", controller)
        Screen.Notifications -> AvailabilitySettings("standalone.notifications.body", controller)
        else -> return false
    }
    return true
}

@Composable
private fun FormColumn(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = StandaloneFormMaxWidth).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun ProfileSettings(state: AppState, controller: ChatController) {
    val p = LocalPalette.current
    val settings by controller.settings.collectAsState()
    val hasKey by controller.hasApiKey.collectAsState()
    FormColumn {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 20.dp)) {
            BotMark(Modifier.size(52.dp)); Spacer(Modifier.width(13.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(tr("app.name"), color = p.ink, fontSize = 21.sp)
                Text(settings.baseUrl.substringAfter("://").substringBefore('/'), color = p.muted, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!hasKey) Text(tr("standalone.profile.setup"), color = p.accent, fontSize = 13.sp)
        SettingsError(state)
        MenuRow("bot", tr("standalone.profile.models"), { controller.navigate(Screen.Models) }, subtitle = settings.chatModel)
        MenuRow("settings", tr("standalone.profile.appearance"), { controller.navigate(Screen.Appearance) })
        MenuRow("edit", tr("standalone.profile.personalization"), { controller.navigate(Screen.Personalization) })
        MenuRow("skills", tr("standalone.profile.agents"), { controller.navigate(Screen.Agents) })
        MenuRow("folder", tr("standalone.profile.files"), { controller.navigate(Screen.Files) })
        Hairline()
        MenuRow("phone", tr("standalone.profile.language"), { controller.navigate(Screen.Language) })
        MenuRow("phone", tr("standalone.profile.haptics"), { controller.navigate(Screen.Haptics) })
    }
}

@Composable
private fun ModelSettings(state: AppState, controller: ChatController) {
    val p = LocalPalette.current
    val settings by controller.settings.collectAsState()
    val hasKey by controller.hasApiKey.collectAsState()
    var form by remember(settings) { mutableStateOf(settings) }
    // Only the freshly typed key lives here: the saved one never enters the composition,
    // and plain remember keeps it out of the saved instance state.
    var key by remember { mutableStateOf("") }
    val presets = remember { ProviderSettings() }
    val custom = form.imageProtocol == "openai"
    FormColumn {
        Text(tr("standalone.models.help"), color = p.muted, fontSize = 13.sp)
        Text(tr("standalone.models.provider"), color = p.ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            ChoicePill(tr("standalone.models.provider.qwen"), !custom, {
                // A preset swaps the known endpoints only; the owner's model IDs and instructions survive.
                form = form.copy(baseUrl = presets.baseUrl, imageEndpoint = presets.imageEndpoint, imageProtocol = presets.imageProtocol)
            })
            ChoicePill(tr("standalone.models.provider.custom"), custom, { form = form.copy(imageProtocol = "openai") })
        }
        BotField(form.baseUrl, { form = form.copy(baseUrl = it) }, Modifier.fillMaxWidth(),
            label = tr("standalone.models.baseUrl"),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
        ApiKeyField(key, { key = it }, hasKey)
        QuietAction(tr("standalone.models.keyRemove"), { controller.saveProvider(form, "") }, Modifier.fillMaxWidth(), enabled = hasKey)
        BotField(form.chatModel, { form = form.copy(chatModel = it) }, Modifier.fillMaxWidth(),
            label = tr("standalone.models.chatModel"),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(tr("standalone.models.refresh"), controller::refreshModels, icon = "retry", enabled = !state.modelsLoading && form == settings && key.isBlank())
            if (state.modelsLoading) LoadingDots(Modifier.padding(8.dp))
        }
        // Image models are offered separately, so they cannot become the chat model by tap.
        val chatModels = state.models.filterNot { it.id.startsWith("image:") }
        if (chatModels.isEmpty()) Text(tr("standalone.models.manual"), color = p.muted, fontSize = 12.sp)
        else {
            Text(tr("standalone.models.choose"), color = p.ink)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                chatModels.forEach { model ->
                    ChoicePill(model.id, form.chatModel == model.id, { form = form.copy(chatModel = model.id) }, Modifier.widthIn(max = 300.dp))
                }
            }
        }
        BotField(form.imageModel, { form = form.copy(imageModel = it) }, Modifier.fillMaxWidth(),
            label = tr("standalone.models.imageModel"),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            ImageModelChoices.forEach { id -> ChoicePill(id, form.imageModel == id, { form = form.copy(imageModel = id) }) }
        }
        BotField(form.imageEndpoint, { form = form.copy(imageEndpoint = it) }, Modifier.fillMaxWidth(),
            label = tr("standalone.models.imageEndpoint"),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
        Text(tr("standalone.models.endpointHint"), color = p.muted, fontSize = 12.sp)
        Text(tr("standalone.models.protocol"), color = p.ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            ChoicePill(tr("standalone.models.protocol.qwen"), form.imageProtocol == "qwen", { form = form.copy(imageProtocol = "qwen") })
            ChoicePill(tr("standalone.models.protocol.openai"), form.imageProtocol == "openai", { form = form.copy(imageProtocol = "openai") })
        }
        SettingsError(state)
        ActionButton(tr("standalone.models.save"), {
            controller.saveProvider(form, key.takeIf { it.isNotEmpty() }) { key = "" }
        }, Modifier.fillMaxWidth(), primary = true, icon = "check")
    }
}

/** BotField has no masking, so the key entry is an OutlinedTextField dressed in the same palette. */
@Composable
private fun ApiKeyField(value: String, onChange: (String) -> Unit, saved: Boolean) {
    val p = LocalPalette.current
    val hint = tr(if (saved) "standalone.models.keySaved" else "standalone.models.keyMissing")
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(),
        label = { Text(tr("standalone.models.key")) },
        supportingText = { Text(hint) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        shape = RoundedCornerShape(15.dp),
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = p.ink, fontSize = 15.sp),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = p.ink, unfocusedTextColor = p.ink,
            focusedContainerColor = p.secondary, unfocusedContainerColor = p.secondary,
            cursorColor = p.accent,
            focusedBorderColor = p.ink, unfocusedBorderColor = p.line,
            focusedLabelColor = p.muted, unfocusedLabelColor = p.muted,
            focusedSupportingTextColor = p.muted, unfocusedSupportingTextColor = p.muted,
        ))
}

@Composable
private fun PersonalizationSettings(state: AppState, controller: ChatController) {
    val p = LocalPalette.current
    val settings by controller.settings.collectAsState()
    var prompt by remember(settings) { mutableStateOf(settings.systemPrompt) }
    FormColumn {
        Text(tr("standalone.personalization.help"), color = p.muted, fontSize = 13.sp)
        BotField(prompt, { prompt = it }, Modifier.fillMaxWidth().heightIn(min = 200.dp),
            label = tr("standalone.personalization.label"), singleLine = false)
        SettingsError(state)
        ActionButton(tr("standalone.personalization.save"), { controller.saveProvider(settings.copy(systemPrompt = prompt), null) },
            Modifier.fillMaxWidth(), primary = true)
    }
}

@Composable
private fun WorkspaceSettings(state: AppState, controller: ChatController) {
    val p = LocalPalette.current
    val files by controller.workspaceFiles.collectAsState()
    val path by controller.workspacePath.collectAsState()
    val text by controller.workspaceText.collectAsState()
    val dirty by controller.workspaceDirty.collectAsState()
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun guarded(action: () -> Unit) { if (dirty) pending = action else action() }
    FormColumn {
        Text(tr("standalone.files.help"), color = p.muted, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(tr("standalone.files.new"), { guarded { controller.newWorkspace() } }, icon = "new")
            ActionButton(tr("standalone.files.refresh"), controller::refreshWorkspace, icon = "retry")
        }
        BotField(path, controller::editWorkspacePath, Modifier.fillMaxWidth(),
            label = tr("standalone.files.name"), placeholder = tr("standalone.files.namePlaceholder"),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next))
        BotField(text, controller::editWorkspace, Modifier.fillMaxWidth().heightIn(min = 220.dp),
            label = tr("standalone.files.content"), singleLine = false)
        SettingsError(state)
        ActionButton(tr("standalone.files.save"), { controller.saveWorkspace(path) },
            Modifier.fillMaxWidth(), primary = true, enabled = path.isNotBlank())
        Hairline()
        Text(tr("standalone.files.list"), color = p.ink)
        if (files.isEmpty()) Text(tr("standalone.files.empty"), color = p.muted, fontSize = 13.sp)
        files.forEach { file ->
            MenuRow("file", file, { guarded { controller.openWorkspace(file) } })
            Hairline()
        }
        pending?.let { action ->
            BotDialog({ pending = null }) {
                Text(tr("standalone.files.discardTitle"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = p.ink)
                Text(tr("standalone.files.discardBody"), color = p.muted, modifier = Modifier.padding(vertical = 14.dp))
                ActionButton(tr("standalone.files.discard"), { pending = null; action() }, Modifier.fillMaxWidth(), primary = true)
                QuietAction(tr("standalone.files.keep"), { pending = null }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ToolSettings(screen: Screen, state: AppState, controller: ChatController) {
    val p = LocalPalette.current
    val settings by controller.settings.collectAsState()
    FormColumn {
        Text(tr(if (screen == Screen.Skills) "standalone.skills.help" else "standalone.agents.help"), color = p.muted, fontSize = 13.sp)
        BotToggle(tr("standalone.tools.enable"), settings.toolsEnabled) { controller.saveProvider(settings.copy(toolsEnabled = it), null) }
        Text(tr("standalone.tools.help"), color = p.muted, fontSize = 12.sp)
        Hairline()
        ToolRow("time", "standalone.tools.clock", "standalone.tools.clockHint")
        ToolRow("file", "standalone.tools.notes", "standalone.tools.notesHint")
        ToolRow("photo", "standalone.tools.image", "standalone.tools.imageHint")
        Hairline()
        SettingsError(state)
        ActionButton(tr("standalone.tools.createImage"), controller::createImage, Modifier.fillMaxWidth(), primary = true, icon = "photo")
    }
}

/** A tool explanation, not a control: these rows describe what the model can already do. */
@Composable
private fun ToolRow(icon: String, titleKey: String, hintKey: String) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, modifier = Modifier.size(21.dp), tint = p.ink)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tr(titleKey), color = p.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(tr(hintKey), color = p.muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun AvailabilitySettings(bodyKey: String, controller: ChatController) {
    val p = LocalPalette.current
    FormColumn {
        Text(tr(bodyKey), color = p.ink.copy(alpha = .78f), fontSize = 13.sp)
        Text(tr("standalone.scope"), color = p.muted, fontSize = 12.sp)
        ActionButton(tr("standalone.action.chat"), { controller.navigate(Screen.Chat) }, Modifier.fillMaxWidth(), primary = true, icon = "chat")
    }
}

/** Request failures stay on the page as a localized key; the raw provider text never reaches it. */
@Composable
private fun SettingsError(state: AppState) {
    val error = state.error ?: return
    Text(tr(error), color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
}
