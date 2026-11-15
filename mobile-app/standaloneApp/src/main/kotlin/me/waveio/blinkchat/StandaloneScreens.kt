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
import androidx.compose.ui.draw.clip
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
        Screen.Memory -> MemorySettings(state, controller)
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
        MenuRow("check", tr("standalone.profile.memory"), { controller.navigate(Screen.Memory) })
        MenuRow("skills", tr("standalone.profile.agents"), { controller.navigate(Screen.Agents) })
        MenuRow("folder", tr("standalone.profile.files"), { controller.navigate(Screen.Files) })
        Hairline()
        MenuRow("phone", tr("standalone.profile.language"), { controller.navigate(Screen.Language) })
        MenuRow("phone", tr("standalone.profile.haptics"), { controller.navigate(Screen.Haptics) })
    }
}

/** One selectable model row; the provider ID keeps duplicate raw IDs apart. */
private data class ModelChoice(val providerId: String, val providerName: String, val modelId: String, val selected: Boolean, val info: ModelInfo? = null)

private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull().orEmpty().ifBlank { url }

/** Manual lines, trimmed and deduplicated without dropping unknown IDs. */
private fun lines(text: String): List<String> = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

private fun tokens(value: Long): String = when {
    value >= 1_000_000 && value % 1_000_000 == 0L -> "${value / 1_000_000}M"
    value >= 1_000_000 -> "${"%.1f".format(value / 1_000_000.0)}M"
    value >= 1_000 -> "${value / 1_000}K"
    else -> value.toString()
}

/** Facts the endpoint published for one model; nothing is shown for what it did not say. */
@Composable
private fun modelFacts(info: ModelInfo?): String = listOfNotNull(
    info?.context?.let { tr("standalone.models.fact.context", "tokens" to tokens(it)) },
    info?.maxOutput?.let { tr("standalone.models.fact.output", "tokens" to tokens(it)) },
    if (info?.vision == true) tr("standalone.models.fact.vision") else null,
    info?.efforts?.takeIf { it.isNotEmpty() }?.let { levels -> tr("standalone.models.fact.effort", "levels" to levels.map { tr("model.$it") }.joinToString(" · ")) },
).joinToString("  ·  ")

@Composable
private fun layerName(api: String): String = tr("standalone.setup.api.$api")

/** Well-known endpoints; Connect still detects the protocol and reads the live catalog. */
private val SetupPresets = listOf(
    "anthropic" to "https://api.anthropic.com/v1",
    "openai" to "https://api.openai.com/v1",
    "openrouter" to "https://openrouter.ai/api/v1",
    "qwen" to "https://token-plan.maas.qwencloudapi.com/compatible-mode/v1",
)

/**
 * The editable form; the saved key never lives here, only a freshly typed one. Catalog
 * [models] come from Connect; [extraChat]/[extraImage] hold IDs typed by hand.
 */
private data class ProviderDraft(
    val name: String = "",
    val baseUrl: String = "",
    val api: String = ApiLayer.OPENAI_CHAT,
    val models: List<ModelInfo> = emptyList(),
    val chatEnabled: Boolean = true,
    val chatModel: String = "",
    val extraChat: String = "",
    val imageEnabled: Boolean = false,
    val imageProtocol: String = ImageLayer.OPENAI,
    val imageEndpoint: String = "",
    val imageModel: String = "",
    val extraImage: String = "",
) {
    val chatIds: List<String> get() = (listOf(chatModel.trim()) + models.filter { it.text }.map { it.id } + lines(extraChat)).filter(String::isNotBlank).distinct()
    val imageIds: List<String> get() = (listOf(imageModel.trim()) + models.filter { it.image }.map { it.id } + lines(extraImage)).filter(String::isNotBlank).distinct()
}

private fun ProviderAccount.toDraft(): ProviderDraft {
    val catalog = models.map { it.id }.toSet()
    return ProviderDraft(
        name = name,
        baseUrl = settings.baseUrl,
        api = settings.api,
        models = models,
        chatEnabled = chats,
        chatModel = settings.chatModel,
        extraChat = chatModels.filterNot { it == settings.chatModel || it in catalog }.joinToString("\n"),
        imageEnabled = images,
        imageProtocol = if (images) settings.imageProtocol else ImageLayer.OPENAI,
        imageEndpoint = settings.imageEndpoint,
        imageModel = settings.imageModel,
        extraImage = imageModels.filterNot { it == settings.imageModel || it in catalog }.joinToString("\n"),
    )
}

/** Applies a fresh probe without discarding the owner's own choices where they still exist. */
private fun ProviderDraft.withDiscovery(found: Discovery): ProviderDraft {
    val textIds = found.models.filter { it.text }.map { it.id }
    val imageIds = found.models.filter { it.image }.map { it.id }
    val images = found.imageProtocol != ImageLayer.NONE && (imageIds.isNotEmpty() || lines(extraImage).isNotEmpty())
    return copy(
        name = name.ifBlank { found.name },
        baseUrl = found.baseUrl,
        api = found.recommended,
        models = found.models,
        chatEnabled = textIds.isNotEmpty() || lines(extraChat).isNotEmpty(),
        chatModel = chatModel.takeIf { it in textIds || it in lines(extraChat) } ?: textIds.firstOrNull().orEmpty(),
        imageEnabled = images,
        imageProtocol = if (found.imageProtocol == ImageLayer.NONE) imageProtocol else found.imageProtocol,
        imageEndpoint = found.imageEndpoint.ifBlank { imageEndpoint },
        imageModel = imageModel.takeIf { it in imageIds || it in lines(extraImage) } ?: imageIds.firstOrNull().orEmpty(),
    )
}

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
    val defaultImage = book.defaultImage
    val imageProviders = book.providers.any { it.images }
    FormColumn {
        Text(tr("standalone.models.defaultsHelp"), color = p.muted, fontSize = 13.sp)
        MenuRow("chat", tr("standalone.models.defaultChat"), { pickDefault = false },
            subtitle = "$chatName · ${book.defaultChat.modelId}")
        MenuRow("photo", tr("standalone.models.defaultImage"), { pickDefault = true },
            subtitle = when {
                defaultImage != null -> "${book.account(defaultImage.providerId)?.name ?: defaultImage.providerId} · ${defaultImage.modelId}"
                imageProviders -> tr("standalone.models.imageOff")
                else -> tr("standalone.models.imageNone")
            }, enabled = imageProviders)
        Hairline()
        Text(tr("standalone.models.providers"), color = p.ink)
        Text(tr("standalone.models.providerCount", "count" to book.providers.size), color = p.muted, fontSize = 12.sp)
        book.providers.forEach { account ->
            val keyLabel = tr(if (account.id in keys) "standalone.models.keyPresent" else "standalone.models.keyAbsent")
            val roles = listOfNotNull(
                if (account.chats) tr("standalone.models.role.chat") else null,
                if (account.images) tr("standalone.models.role.image") else null,
            ).joinToString(" + ")
            MenuRow("bot", account.name, { editingId = account.id; creating = false },
                subtitle = "${hostOf(account.settings.baseUrl)} · ${layerName(account.settings.api)} · $roles · $keyLabel", enabled = !saving)
        }
        ActionButton(tr("standalone.models.addProvider"), { editingId = UUID.randomUUID().toString(); creating = true },
            Modifier.fillMaxWidth().testTag("provider-add"), icon = "new", enabled = !saving)
        SettingsError(state)
    }

    pickDefault?.let { image ->
        val current = if (image) book.defaultImage else book.defaultChat
        val entries = book.providers.flatMap { account ->
            (if (image) account.imageModels else account.chatModels).map { model ->
                ModelChoice(account.id, account.name, model, current?.providerId == account.id && current.modelId == model, account.info(model))
            }
        }
        ModelSearchDialog(
            title = tr(if (image) "standalone.models.chooseImageDefault" else "standalone.models.chooseChatDefault"),
            image = image, entries = entries,
            onPick = { controller.setDefaultModel(ModelSelection(it.providerId, it.modelId), image); pickDefault = null },
            onDismiss = { pickDefault = null },
            noneLabel = if (image) tr("standalone.models.imageOffChoice") else null,
            onNone = { controller.clearDefaultImage(); pickDefault = null },
        )
    }
}

/** A labelled group inside the setup form. */
@Composable
private fun SetupSection(title: String, help: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.dp, p.line, RoundedCornerShape(18.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (help != null) Text(help, color = p.muted, fontSize = 12.sp)
        content()
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
    // Layers the last Connect proved; null until Connect runs in this editor.
    var detected by remember(editingId) { mutableStateOf<List<String>?>(null) }
    var formError by remember(editingId) { mutableStateOf<String?>(null) }
    var pickChat by remember(editingId) { mutableStateOf(false) }
    var pickImage by remember(editingId) { mutableStateOf(false) }
    var manual by remember(editingId) { mutableStateOf(saved != null && saved.models.isEmpty()) }
    var confirmDelete by remember(editingId) { mutableStateOf(false) }
    var confirmDiscard by remember(editingId) { mutableStateOf(false) }
    var pendingExit by remember(editingId) { mutableStateOf<(() -> Unit)?>(null) }
    val discovering by controller.providerDiscovering.collectAsState()

    // Follow the saved account (for example after Remove key) while the form is clean. A
    // background catalog refresh can also land during an edit: rebase only its new models so
    // the unsaved field values survive and saving does not drop what the refresh imported.
    LaunchedEffect(book, editingId) {
        val current = book.account(editingId)
        if (current != null) {
            if (key.isBlank() && draft == baseline?.toDraft()) draft = current.toDraft()
            else baseline?.let { previous ->
                val known = previous.models.map { it.id }.toSet()
                val added = current.models.filter { it.id !in known }
                val addedManual = (current.chatModels - previous.chatModels.toSet()).filter { id -> current.info(id) == null }
                if (added.isNotEmpty() || addedManual.isNotEmpty())
                    draft = draft.copy(models = ModelCatalog.merge(draft.models, added),
                        extraChat = (lines(draft.extraChat) + addedManual).distinct().joinToString("\n"))
            }
        }
        baseline = current
    }
    DisposableEffect(editingId) { onDispose { controller.cancelDiscovery() } }

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
    val busy = saving || state.busy || discovering
    val providerLabel = draft.name.ifBlank { ModelCatalog.suggestName(draft.baseUrl) }
    val connected = draft.models.isNotEmpty() || detected != null
    val connect = {
        formError = null
        controller.discoverProvider(editingId, draft.baseUrl, key) { found ->
            draft = draft.withDiscovery(found)
            detected = found.apis
            manual = manual || found.models.isEmpty()
        }
    }

    FormColumn {
        Text(tr(if (creating) "standalone.models.editorNew" else "standalone.models.editorEdit"),
            color = p.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)

        SetupSection(tr("standalone.setup.connect"), tr("standalone.setup.connectHelp")) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                SetupPresets.forEach { (id, url) ->
                    ChoicePill(tr("standalone.setup.preset.$id"), draft.baseUrl.trimEnd('/') == url, {
                        draft = draft.copy(baseUrl = url); detected = null
                    })
                }
            }
            BotField(draft.baseUrl, { draft = draft.copy(baseUrl = it); detected = null }, Modifier.fillMaxWidth().testTag("provider-base-url"),
                label = tr("standalone.setup.url"), placeholder = "https://api.example.com/v1",
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
            ApiKeyField(key, { key = it }, hasSavedKey)
            BotField(draft.name, { draft = draft.copy(name = it) }, Modifier.fillMaxWidth().testTag("provider-name"),
                label = tr("standalone.models.name"), placeholder = providerLabel.ifBlank { tr("standalone.models.namePlaceholder") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActionButton(tr(if (connected) "standalone.setup.reconnect" else "standalone.setup.detect"), connect,
                    Modifier.weight(1f).testTag("provider-connect"), primary = !connected, icon = "retry",
                    enabled = !busy && draft.baseUrl.isNotBlank() && (key.isNotBlank() || hasSavedKey))
                if (discovering) LoadingDots(Modifier.padding(start = 12.dp))
            }
            detected?.let { apis ->
                val textCount = draft.models.count { it.text }
                val imageCount = draft.models.count { it.image }
                Text(tr("standalone.setup.found", "count" to textCount, "images" to imageCount,
                    "layers" to (apis.map { layerName(it) }.joinToString(", ").ifBlank { tr("standalone.setup.layersUnknown") })),
                    color = p.ink, fontSize = 13.sp, modifier = Modifier.testTag("provider-found").semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (hasSavedKey) QuietAction(tr("standalone.models.keyRemove"), { saved?.let { controller.saveAccount(it, "") { key = "" } } },
                Modifier.fillMaxWidth(), enabled = !busy)
        }

        if (connected || saved != null) {
            SetupSection(tr("standalone.setup.layer"), tr("standalone.setup.layerHelp")) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    ApiLayer.all.forEach { api ->
                        val proven = detected?.contains(api) == true
                        ChoicePill(layerName(api) + if (proven) " ✓" else "", draft.api == api, { draft = draft.copy(api = api) },
                            Modifier.testTag("provider-api-$api"))
                    }
                }
                if (detected != null && draft.api !in detected.orEmpty()) Text(tr("standalone.setup.layerUnproven"), color = p.accent, fontSize = 12.sp)
            }

            SetupSection(tr("standalone.setup.chat")) {
                BotToggle(tr("standalone.setup.chatToggle"), draft.chatEnabled) { draft = draft.copy(chatEnabled = it) }
                if (draft.chatEnabled) {
                    val info = draft.models.firstOrNull { it.id == draft.chatModel }
                    MenuRow("chat", info?.label?.ifBlank { null } ?: draft.chatModel.ifBlank { tr("standalone.setup.chooseModel") },
                        { pickChat = true }, subtitle = listOf(draft.chatModel.takeIf { info?.label?.isNotBlank() == true }.orEmpty(), modelFacts(info))
                            .filter(String::isNotBlank).joinToString("  ·  ").ifBlank { null },
                        enabled = draft.chatIds.isNotEmpty())
                    Text(tr("standalone.setup.chatCount", "count" to draft.chatIds.size), color = p.muted, fontSize = 12.sp)
                }
            }

            SetupSection(tr("standalone.setup.images"), tr("standalone.setup.imagesHelp")) {
                BotToggle(tr("standalone.setup.imageToggle"), draft.imageEnabled) { draft = draft.copy(imageEnabled = it) }
                if (draft.imageEnabled) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        ChoicePill(tr("standalone.models.protocol.openai"), draft.imageProtocol == ImageLayer.OPENAI, {
                            draft = draft.copy(imageProtocol = ImageLayer.OPENAI, imageEndpoint = draft.imageEndpoint.ifBlank { draft.baseUrl.trimEnd('/') + "/images/generations" })
                        })
                        ChoicePill(tr("standalone.models.protocol.qwen"), draft.imageProtocol == ImageLayer.QWEN, { draft = draft.copy(imageProtocol = ImageLayer.QWEN) })
                    }
                    BotField(draft.imageEndpoint, { draft = draft.copy(imageEndpoint = it) }, Modifier.fillMaxWidth().testTag("provider-image-endpoint"),
                        label = tr("standalone.models.imageEndpoint"),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
                    MenuRow("photo", draft.imageModel.ifBlank { tr("standalone.setup.chooseModel") }, { pickImage = true },
                        subtitle = tr("standalone.setup.imageCount", "count" to draft.imageIds.size), enabled = draft.imageIds.isNotEmpty())
                } else {
                    Text(tr("standalone.setup.textOnly"), color = p.muted, fontSize = 12.sp)
                }
            }
        }

        if (manual) SetupSection(tr("standalone.setup.manual"), tr("standalone.setup.manualHelp")) {
            BotField(draft.extraChat, { draft = draft.copy(extraChat = it) }, Modifier.fillMaxWidth().heightIn(min = 90.dp).testTag("provider-extra-chat"),
                label = tr("standalone.models.extraChat"), singleLine = false)
            if (draft.imageEnabled) BotField(draft.extraImage, { draft = draft.copy(extraImage = it) }, Modifier.fillMaxWidth().heightIn(min = 90.dp),
                label = tr("standalone.models.extraImage"), singleLine = false)
        } else QuietAction(tr("standalone.setup.manualOpen"), { manual = true }, Modifier.fillMaxWidth())

        formError?.let { Text(tr(it), color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        SettingsError(state)
        ActionButton(tr("standalone.models.save"), {
            val chatModel = draft.chatModel.trim().ifBlank { if (draft.chatEnabled) draft.chatIds.firstOrNull().orEmpty() else "" }
            val imageModel = draft.imageModel.trim().ifBlank { if (draft.imageEnabled) draft.imageIds.firstOrNull().orEmpty() else "" }
            formError = when {
                draft.baseUrl.isBlank() -> "standalone.setup.needUrl"
                !draft.chatEnabled && !draft.imageEnabled -> "standalone.setup.needRole"
                draft.chatEnabled && chatModel.isBlank() -> "standalone.setup.needChatModel"
                draft.imageEnabled && (imageModel.isBlank() || draft.imageEndpoint.isBlank()) -> "standalone.setup.needImageModel"
                else -> null
            }
            if (formError == null) {
                val ready = draft.copy(chatModel = chatModel, imageModel = imageModel)
                val account = ProviderAccount(
                    id = editingId,
                    name = ready.name.trim().ifBlank { providerLabel },
                    settings = ProviderSettings(
                        baseUrl = ready.baseUrl.trim().trimEnd('/'),
                        chatModel = if (ready.chatEnabled) chatModel else "",
                        imageModel = if (ready.imageEnabled) imageModel else "",
                        imageEndpoint = if (ready.imageEnabled) ready.imageEndpoint.trim() else "",
                        imageProtocol = if (ready.imageEnabled) ready.imageProtocol else ImageLayer.NONE,
                        systemPrompt = saved?.settings?.systemPrompt ?: book.systemPrompt,
                        toolsEnabled = saved?.settings?.toolsEnabled ?: book.toolsEnabled,
                        api = ready.api,
                    ),
                    chatModels = if (ready.chatEnabled) ready.chatIds else emptyList(),
                    imageModels = if (ready.imageEnabled) ready.imageIds else emptyList(),
                    models = ready.models,
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
        draft.chatIds.map { id -> ModelChoice(editingId, providerLabel, id, id == draft.chatModel.trim(), draft.models.firstOrNull { it.id == id }) },
        { draft = draft.copy(chatModel = it.modelId); pickChat = false }, { pickChat = false })
    if (pickImage) ModelSearchDialog(tr("standalone.models.chooseImageModel"), true,
        draft.imageIds.map { id -> ModelChoice(editingId, providerLabel, id, id == draft.imageModel.trim(), draft.models.firstOrNull { it.id == id }) },
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

/**
 * Searchable, bounded picker shared by the provider form and the defaults page, so every
 * place that picks a model shows the same rows and the same published facts.
 */
@Composable
private fun ModelSearchDialog(
    title: String,
    image: Boolean,
    entries: List<ModelChoice>,
    onPick: (ModelChoice) -> Unit,
    onDismiss: () -> Unit,
    noneLabel: String? = null,
    onNone: () -> Unit = {},
) {
    val p = LocalPalette.current
    var query by remember { mutableStateOf("") }
    val filtered = remember(entries, query) {
        if (query.isBlank()) entries
        else entries.filter { it.modelId.contains(query, true) || it.providerName.contains(query, true) || it.info?.label?.contains(query, true) == true }
    }
    BotDialog(onDismiss) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = p.ink)
        BotField(query, { query = it }, Modifier.fillMaxWidth().padding(top = 12.dp),
            placeholder = tr("standalone.models.searchModels"), icon = "search",
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search))
        if (noneLabel != null) MenuRow("close", noneLabel, onNone)
        if (filtered.isEmpty()) Text(tr("standalone.models.noModels"), color = p.muted, fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 16.dp))
        else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp).padding(top = 8.dp)) {
            itemsIndexed(filtered, key = { index, _ -> index }) { _, entry ->
                val label = entry.info?.label?.ifBlank { null } ?: entry.modelId
                val subtitle = listOf(entry.providerName, entry.modelId.takeIf { it != label }.orEmpty(), modelFacts(entry.info))
                    .filter(String::isNotBlank).joinToString("  ·  ")
                MenuRow(if (image) "photo" else "chat", label, { onPick(entry) }, subtitle = subtitle,
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
private fun MemorySettings(state: AppState, controller: ChatController) {
    val p = LocalPalette.current
    val memories by controller.memories.collectAsState()
    val book by controller.providerBook.collectAsState()
    var fact by remember { mutableStateOf("") }
    var clearing by remember { mutableStateOf(false) }
    FormColumn {
        Text(tr("standalone.memory.help"), color = p.muted, fontSize = 13.sp)
        BotToggle(tr("standalone.memory.enable"), book.memoryEnabled, controller::setMemoryEnabled)
        Hairline()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BotField(fact, { fact = it }, Modifier.weight(1f), label = tr("standalone.memory.addHint"), singleLine = true)
            ActionButton(tr("standalone.memory.add"), { controller.addMemory(fact); fact = "" }, enabled = fact.isNotBlank())
        }
        SettingsError(state)
        Hairline()
        Text(tr("standalone.memory.list"), color = p.ink)
        if (memories.isEmpty()) Text(tr("standalone.memory.empty"), color = p.muted, fontSize = 13.sp)
        memories.forEach { memory ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(memory.content, color = p.ink, fontSize = 14.sp) }
                IconAction("delete", tr("standalone.memory.delete"), { controller.deleteMemory(memory.id) })
            }
            Hairline()
        }
        if (memories.isNotEmpty()) QuietAction(tr("standalone.memory.clearAll"), { clearing = true }, Modifier.fillMaxWidth())
    }
    if (clearing) {
        BotDialog({ clearing = false }) {
            Text(tr("standalone.memory.clearTitle"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = p.ink)
            Text(tr("standalone.memory.clearBody"), color = p.muted, modifier = Modifier.padding(vertical = 14.dp))
            ActionButton(tr("standalone.memory.confirm"), { clearing = false; controller.clearMemories() }, Modifier.fillMaxWidth(), primary = true)
            QuietAction(tr("standalone.memory.keep"), { clearing = false }, Modifier.fillMaxWidth())
        }
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
        ToolRow("search", "standalone.tools.search", "standalone.tools.searchHint")
        ToolRow("download", "standalone.tools.readUrl", "standalone.tools.readUrlHint")
        ToolRow("check", "standalone.tools.memory", "standalone.tools.memoryHint")
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
