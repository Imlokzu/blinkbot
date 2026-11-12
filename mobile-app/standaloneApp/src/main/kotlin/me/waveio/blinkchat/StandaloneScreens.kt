package me.waveio.blinkchat

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import java.net.URI
import java.util.UUID
import me.waveio.claudebot.state.AppState
import me.waveio.claudebot.state.Screen
import me.waveio.claudebot.ui.*

// :shared keeps FormContentMaxWidth internal, so the standalone forms mirror its value
// and stay on the same centered column as SettingsScreen.
private val StandaloneFormMaxWidth = 640.dp

private val QwenPreset = ProviderSettings()
private val CustomPreset = ProviderSettings(
    baseUrl = "https://api.example.com/v1",
    imageEndpoint = "https://api.example.com/v1/images/generations",
    imageProtocol = "openai",
)

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
    val book by controller.providerBook.collectAsState()
    val hasKey by controller.hasApiKey.collectAsState()
    val defaultName = book.account(book.defaultChat.providerId)?.name ?: book.defaultChat.providerId
    FormColumn {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 20.dp)) {
            BotMark(Modifier.size(52.dp)); Spacer(Modifier.width(13.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(tr("app.name"), color = p.ink, fontSize = 21.sp)
                Text(tr("standalone.profile.summary", "count" to book.providers.size, "provider" to defaultName, "model" to book.defaultChat.modelId),
                    color = p.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!hasKey) Text(tr("standalone.profile.setup"), color = p.accent, fontSize = 13.sp)
        SettingsError(state)
        MenuRow("bot", tr("standalone.profile.models"), { controller.navigate(Screen.Models) }, subtitle = "$defaultName · ${book.defaultChat.modelId}")
        MenuRow("settings", tr("standalone.profile.appearance"), { controller.navigate(Screen.Appearance) })
        MenuRow("edit", tr("standalone.profile.personalization"), { controller.navigate(Screen.Personalization) })
        MenuRow("skills", tr("standalone.profile.agents"), { controller.navigate(Screen.Agents) })
        MenuRow("folder", tr("standalone.profile.files"), { controller.navigate(Screen.Files) })
        Hairline()
        MenuRow("phone", tr("standalone.profile.language"), { controller.navigate(Screen.Language) })
        MenuRow("phone", tr("standalone.profile.haptics"), { controller.navigate(Screen.Haptics) })
    }
}

/** One selectable model row; the provider ID keeps duplicate raw IDs apart. */
private data class ModelChoice(val providerId: String, val providerName: String, val modelId: String, val selected: Boolean)

private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull().orEmpty().ifBlank { url }

/** Primary first, then the manual lines, deduplicated without dropping unknown IDs. */
private fun mergeModels(primary: String, extra: String): List<String> =
    (listOf(primary) + extra.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }).distinct()

private fun ProviderAccount.toDraft(): ProviderDraft = ProviderDraft(
    name = name,
    baseUrl = settings.baseUrl,
    chatModel = settings.chatModel,
    extraChat = chatModels.filterNot { it == settings.chatModel }.joinToString("\n"),
    imageModel = settings.imageModel,
    extraImage = imageModels.filterNot { it == settings.imageModel }.joinToString("\n"),
    imageEndpoint = settings.imageEndpoint,
    imageProtocol = settings.imageProtocol,
)

/** The editable form; the saved key never lives here, only a freshly typed one. */
private data class ProviderDraft(
    val name: String = "",
    val baseUrl: String = QwenPreset.baseUrl,
    val chatModel: String = QwenPreset.chatModel,
    val extraChat: String = "",
    val imageModel: String = QwenPreset.imageModel,
    val extraImage: String = "",
    val imageEndpoint: String = QwenPreset.imageEndpoint,
    val imageProtocol: String = QwenPreset.imageProtocol,
)

@Composable
private fun ModelSettings(state: AppState, controller: ChatController) {
    val book by controller.providerBook.collectAsState()
    val keys by controller.providerKeys.collectAsState()
    val saving by controller.providerSaving.collectAsState()
    var editingId by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    // null = closed, false = default chat picker, true = default image picker.
    var pickDefault by remember { mutableStateOf<Boolean?>(null) }

    val openId = editingId
    if (openId != null) {
        ProviderEditor(openId, creating, book, keys, saving, state, controller) { editingId = null; creating = false }
        return
    }

    val p = LocalPalette.current
    val chatName = book.account(book.defaultChat.providerId)?.name ?: book.defaultChat.providerId
    val imageName = book.account(book.defaultImage.providerId)?.name ?: book.defaultImage.providerId
    FormColumn {
        Text(tr("standalone.models.defaultsHelp"), color = p.muted, fontSize = 13.sp)
        MenuRow("chat", tr("standalone.models.defaultChat"), { pickDefault = false },
            subtitle = "$chatName · ${book.defaultChat.modelId}")
        MenuRow("photo", tr("standalone.models.defaultImage"), { pickDefault = true },
            subtitle = "$imageName · ${book.defaultImage.modelId}")
        Hairline()
        Text(tr("standalone.models.providers"), color = p.ink)
        Text(tr("standalone.models.providerCount", "count" to book.providers.size), color = p.muted, fontSize = 12.sp)
        book.providers.forEach { account ->
            val keyLabel = tr(if (account.id in keys) "standalone.models.keyPresent" else "standalone.models.keyAbsent")
            MenuRow("bot", account.name, { editingId = account.id; creating = false },
                subtitle = "${hostOf(account.settings.baseUrl)} · $keyLabel", enabled = !saving)
        }
        ActionButton(tr("standalone.models.addProvider"), { editingId = UUID.randomUUID().toString(); creating = true },
            Modifier.fillMaxWidth().testTag("provider-add"), icon = "new", enabled = !saving)
        SettingsError(state)
    }

    pickDefault?.let { image ->
        val current = if (image) book.defaultImage else book.defaultChat
        val entries = book.providers.flatMap { account ->
            (if (image) account.imageModels else account.chatModels).map { model ->
                ModelChoice(account.id, account.name, model, current.providerId == account.id && current.modelId == model)
            }
        }
        ModelSearchDialog(
            title = tr(if (image) "standalone.models.chooseImageDefault" else "standalone.models.chooseChatDefault"),
            image = image, entries = entries,
            onPick = { controller.setDefaultModel(ModelSelection(it.providerId, it.modelId), image); pickDefault = null },
            onDismiss = { pickDefault = null },
        )
    }
}

@Composable
private fun ProviderEditor(
    editingId: String,
    creating: Boolean,
    book: ProviderBook,
    keys: Set<String>,
    saving: Boolean,
    state: AppState,
    controller: ChatController,
    onExit: () -> Unit,
) {
    val p = LocalPalette.current
    val saved = book.account(editingId)
    var draft by remember(editingId) { mutableStateOf(saved?.toDraft() ?: ProviderDraft()) }
    var baseline by remember(editingId) { mutableStateOf(saved) }
    // Only the freshly typed key lives here: the saved one never enters the composition,
    // and plain remember keeps it out of the saved instance state.
    var key by remember(editingId) { mutableStateOf("") }
    var nameError by remember(editingId) { mutableStateOf(false) }
    var pickChat by remember(editingId) { mutableStateOf(false) }
    var pickImage by remember(editingId) { mutableStateOf(false) }
    var confirmDelete by remember(editingId) { mutableStateOf(false) }
    var confirmDiscard by remember(editingId) { mutableStateOf(false) }
    var pendingExit by remember(editingId) { mutableStateOf<(() -> Unit)?>(null) }

    // A refresh imports the catalog into the saved account; follow it only while the
    // form is clean so unrelated unsaved edits are never wiped.
    LaunchedEffect(book, editingId) {
        val current = book.account(editingId)
        if (current != null) {
            if (key.isBlank() && draft == baseline?.toDraft()) draft = current.toDraft()
            else baseline?.let { previous ->
                // Discovery can complete while a name/endpoint field is being edited.
                // Rebase its new IDs without replacing those unsaved field values.
                val addedChat = current.chatModels - previous.chatModels.toSet()
                val addedImage = current.imageModels - previous.imageModels.toSet()
                draft = draft.copy(
                    extraChat = (mergeModels(draft.chatModel,draft.extraChat) + addedChat).distinct().filterNot { it == draft.chatModel }.joinToString("\n"),
                    extraImage = (mergeModels(draft.imageModel,draft.extraImage) + addedImage).distinct().filterNot { it == draft.imageModel }.joinToString("\n"))
            }
        }
        baseline = current
    }

    val cleanDraft = baseline?.toDraft() ?: ProviderDraft()
    val dirty = key.isNotBlank() || draft != cleanDraft
    val currentDirty by rememberUpdatedState(dirty)
    val currentExit by rememberUpdatedState(onExit)
    val requestExit: (() -> Unit) -> Unit = { action ->
        if (currentDirty) { pendingExit = action; confirmDiscard = true } else action()
    }
    DisposableEffect(controller, editingId) {
        controller.providerEditorNavigation(editingId, back = { requestExit { currentExit() } }, leave = requestExit)
        onDispose { controller.clearProviderEditorNavigation(editingId) }
    }
    val hasSavedKey = editingId in keys
    val busy = saving || state.busy || state.modelsLoading
    val refreshEnabled = !creating && saved != null && !dirty && !saving && !state.modelsLoading
    val mergedChat = mergeModels(draft.chatModel.trim(), draft.extraChat).filter { it.isNotBlank() }
    val mergedImage = mergeModels(draft.imageModel.trim(), draft.extraImage).filter { it.isNotBlank() }
    val providerLabel = draft.name.ifBlank { saved?.name.orEmpty() }

    FormColumn {
        Text(tr(if (creating) "standalone.models.editorNew" else "standalone.models.editorEdit"),
            color = p.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(tr("standalone.models.help"), color = p.muted, fontSize = 13.sp)

        BotField(draft.name, { draft = draft.copy(name = it); if (it.isNotBlank()) nameError = false }, Modifier.fillMaxWidth().testTag("provider-name"),
            label = tr("standalone.models.name"), placeholder = tr("standalone.models.namePlaceholder"),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
        if (nameError) Text(tr("standalone.models.nameRequired"), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)

        Text(tr("standalone.models.preset"), color = p.ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            // A preset swaps the known endpoints only; the owner's name, model IDs and the global prompt survive.
            ChoicePill(tr("standalone.models.provider.qwen"), draft.imageProtocol == "qwen", {
                draft = draft.copy(baseUrl = QwenPreset.baseUrl, imageEndpoint = QwenPreset.imageEndpoint, imageProtocol = "qwen")
            })
            ChoicePill(tr("standalone.models.provider.custom"), draft.imageProtocol == "openai", {
                draft = draft.copy(baseUrl = CustomPreset.baseUrl, imageEndpoint = CustomPreset.imageEndpoint, imageProtocol = "openai")
            })
        }

        BotField(draft.baseUrl, { draft = draft.copy(baseUrl = it) }, Modifier.fillMaxWidth().testTag("provider-base-url"),
            label = tr("standalone.models.baseUrl"),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
        ApiKeyField(key, { key = it }, hasSavedKey)
        // Removing the key acts on the saved account, never on the unsaved endpoint form.
        QuietAction(tr("standalone.models.keyRemove"), { saved?.let { controller.saveAccount(it, "") { key = "" } } },
            Modifier.fillMaxWidth(), enabled = hasSavedKey && !busy)

        BotField(draft.chatModel, { draft = draft.copy(chatModel = it) }, Modifier.fillMaxWidth().testTag("provider-chat-model"),
            label = tr("standalone.models.chatModel"),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(tr("standalone.models.chooseDiscovered"), { pickChat = true }, icon = "search", enabled = mergedChat.isNotEmpty())
            ActionButton(tr("standalone.models.refresh"), { controller.refreshProviderModels(editingId) }, icon = "retry", enabled = refreshEnabled)
            if (state.modelsLoading) LoadingDots(Modifier.padding(8.dp))
        }
        Text(tr("standalone.models.refreshHint"), color = p.muted, fontSize = 12.sp)
        BotField(draft.extraChat, { draft = draft.copy(extraChat = it) }, Modifier.fillMaxWidth().heightIn(min = 90.dp).testTag("provider-extra-chat"),
            label = tr("standalone.models.extraChat"), singleLine = false)

        BotField(draft.imageModel, { draft = draft.copy(imageModel = it) }, Modifier.fillMaxWidth().testTag("provider-image-model"),
            label = tr("standalone.models.imageModel"),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(tr("standalone.models.chooseDiscovered"), { pickImage = true }, icon = "search", enabled = mergedImage.isNotEmpty())
        }
        BotField(draft.extraImage, { draft = draft.copy(extraImage = it) }, Modifier.fillMaxWidth().heightIn(min = 90.dp),
            label = tr("standalone.models.extraImage"), singleLine = false)

        BotField(draft.imageEndpoint, { draft = draft.copy(imageEndpoint = it) }, Modifier.fillMaxWidth().testTag("provider-image-endpoint"),
            label = tr("standalone.models.imageEndpoint"),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
        Text(tr("standalone.models.endpointHint"), color = p.muted, fontSize = 12.sp)
        Text(tr("standalone.models.protocol"), color = p.ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            ChoicePill(tr("standalone.models.protocol.qwen"), draft.imageProtocol == "qwen", { draft = draft.copy(imageProtocol = "qwen") })
            ChoicePill(tr("standalone.models.protocol.openai"), draft.imageProtocol == "openai", { draft = draft.copy(imageProtocol = "openai") })
        }

        SettingsError(state)
        ActionButton(tr("standalone.models.save"), {
            if (draft.name.isBlank()) nameError = true
            else {
                val account = ProviderAccount(
                    id = editingId,
                    name = draft.name.trim(),
                    settings = ProviderSettings(
                        baseUrl = draft.baseUrl.trim(),
                        chatModel = draft.chatModel.trim(),
                        imageModel = draft.imageModel.trim(),
                        imageEndpoint = draft.imageEndpoint.trim(),
                        imageProtocol = draft.imageProtocol,
                        systemPrompt = saved?.settings?.systemPrompt ?: book.systemPrompt,
                        toolsEnabled = saved?.settings?.toolsEnabled ?: book.toolsEnabled,
                    ),
                    chatModels = mergeModels(draft.chatModel.trim(), draft.extraChat),
                    imageModels = mergeModels(draft.imageModel.trim(), draft.extraImage),
                )
                controller.saveAccount(account, key.takeIf { it.isNotEmpty() }) { key = ""; onExit() }
            }
        }, Modifier.fillMaxWidth().testTag("provider-save"), primary = true, icon = "check", enabled = !busy)

        if (!creating) {
            if (book.providers.size <= 1) Text(tr("standalone.models.deleteLastHelp"), color = p.muted, fontSize = 12.sp)
            QuietAction(tr("standalone.models.delete"), { confirmDelete = true }, Modifier.fillMaxWidth(),
                enabled = book.providers.size > 1 && !busy)
        }
        QuietAction(tr("standalone.models.backToList"), { requestExit(onExit) }, Modifier.fillMaxWidth())
    }

    if (pickChat) ModelSearchDialog(tr("standalone.models.chooseChatModel"), false,
        mergedChat.map { ModelChoice(editingId, providerLabel, it, it == draft.chatModel.trim()) },
        { draft = draft.copy(chatModel = it.modelId); pickChat = false }, { pickChat = false })
    if (pickImage) ModelSearchDialog(tr("standalone.models.chooseImageModel"), true,
        mergedImage.map { ModelChoice(editingId, providerLabel, it, it == draft.imageModel.trim()) },
        { draft = draft.copy(imageModel = it.modelId); pickImage = false }, { pickImage = false })

    if (confirmDelete) BotDialog({ confirmDelete = false }) {
        Text(tr("standalone.models.deleteTitle"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = p.ink)
        Text(tr("standalone.models.deleteBody"), color = p.muted, modifier = Modifier.padding(vertical = 14.dp))
        ActionButton(tr("standalone.models.deleteConfirm"), {
            confirmDelete = false
            controller.deleteProvider(editingId) { key = ""; onExit() }
        }, Modifier.fillMaxWidth(), primary = true, icon = "delete", enabled = !busy)
        QuietAction(tr("standalone.models.cancel"), { confirmDelete = false }, Modifier.fillMaxWidth())
    }
    if (confirmDiscard) BotDialog({ confirmDiscard = false; pendingExit = null }) {
        Text(tr("standalone.models.discardTitle"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = p.ink)
        Text(tr("standalone.models.discardBody"), color = p.muted, modifier = Modifier.padding(vertical = 14.dp))
        ActionButton(tr("standalone.models.discard"), {
            val action = pendingExit ?: onExit
            confirmDiscard = false; pendingExit = null; action()
        }, Modifier.fillMaxWidth(), primary = true)
        QuietAction(tr("standalone.models.keepEditing"), { confirmDiscard = false; pendingExit = null }, Modifier.fillMaxWidth())
    }
}

/** Searchable, bounded picker so a large catalog never becomes hundreds of chips in the form. */
@Composable
private fun ModelSearchDialog(
    title: String,
    image: Boolean,
    entries: List<ModelChoice>,
    onPick: (ModelChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    var query by remember { mutableStateOf("") }
    val filtered = remember(entries, query) {
        if (query.isBlank()) entries
        else entries.filter { it.modelId.contains(query, true) || it.providerName.contains(query, true) }
    }
    BotDialog(onDismiss) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = p.ink)
        BotField(query, { query = it }, Modifier.fillMaxWidth().padding(top = 12.dp),
            placeholder = tr("standalone.models.searchModels"), icon = "search",
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search))
        if (filtered.isEmpty()) Text(tr("standalone.models.noModels"), color = p.muted, fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 16.dp))
        else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp).padding(top = 8.dp)) {
            itemsIndexed(filtered, key = { index, _ -> index }) { _, entry ->
                MenuRow(if (image) "photo" else "chat", entry.modelId, { onPick(entry) }, subtitle = entry.providerName,
                    trailing = { if (entry.selected) Glyph("check", modifier = Modifier.size(18.dp), tint = p.accent) })
            }
        }
        QuietAction(tr("standalone.models.close"), onDismiss, Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}

/** BotField has no masking, so the key entry is an OutlinedTextField dressed in the same palette. */
@Composable
private fun ApiKeyField(value: String, onChange: (String) -> Unit, saved: Boolean) {
    val p = LocalPalette.current
    val hint = tr(if (saved) "standalone.models.keySaved" else "standalone.models.keyMissing")
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth().testTag("provider-api-key"),
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
        ActionButton(tr("standalone.personalization.save"), { controller.savePersonalization(prompt) },
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
        BotToggle(tr("standalone.tools.enable"), settings.toolsEnabled, controller::setToolsEnabled)
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
