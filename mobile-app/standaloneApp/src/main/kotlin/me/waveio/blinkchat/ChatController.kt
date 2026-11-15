package me.waveio.blinkchat

import java.io.Closeable
import java.time.ZonedDateTime
import java.util.UUID
import io.ktor.client.HttpClient
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.waveio.claudebot.platform.PlatformBridge
import me.waveio.claudebot.platform.PickedFile
import me.waveio.claudebot.state.*
import me.waveio.claudebot.ui.thumbnailBytes

/** All conversations belong to this app; no Blink host credential or endpoint is used. */
class ChatController(
    private val platform: PlatformBridge,
    private val storage: ChatStorage,
    private val provider: ProviderClient = ProviderClient(),
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : LocalActions(), Closeable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val diskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var webToolHttpClient: HttpClient? = null
    /** One hardened client is shared by every web tool call for the controller's lifetime. */
    private fun toolClient(): HttpClient = webToolHttpClient ?: webToolClient().also { webToolHttpClient = it }
    private val mutable = MutableStateFlow(AppState(connected = true, loading = true))
    val state = mutable.asStateFlow()
    private val config = MutableStateFlow(ProviderSettings())
    val settings = config.asStateFlow()
    private val books = MutableStateFlow(ProviderBook.legacy(ProviderSettings()))
    val providerBook = books.asStateFlow()
    private val keyedProviders = MutableStateFlow<Set<String>>(emptySet())
    val providerKeys = keyedProviders.asStateFlow()
    private val savingProvider = MutableStateFlow(false)
    val providerSaving = savingProvider.asStateFlow()
    private val discoveringProvider = MutableStateFlow(false)
    val providerDiscovering = discoveringProvider.asStateFlow()
    private var discoveryRequest: Job? = null
    private var newChatModel = books.value.defaultChat
    private var newImageModel: ModelSelection? = books.value.defaultImage
    private var newChatEffort = ""
    private val effortProbes = mutableSetOf<String>()
    /** Live phase per reply ID: `sending`, `thinking` or `tool`; absent once text streams. */
    private val replyStatus = mutableMapOf<String, String>()
    private fun setStatus(replyId: String, status: String?) {
        if (replyStatus[replyId] == status) return
        if (status == null) replyStatus.remove(replyId) else replyStatus[replyId] = status
        publish()
    }
    private val keyPresent = MutableStateFlow(false)
    val hasApiKey = keyPresent.asStateFlow()
    private val generating = MutableStateFlow(false)
    val imageMode = generating.asStateFlow()
    private val paths = MutableStateFlow<List<String>>(emptyList())
    val workspaceFiles = paths.asStateFlow()
    private val note = MutableStateFlow("")
    val workspaceText = note.asStateFlow()
    private val notePath = MutableStateFlow("")
    val workspacePath = notePath.asStateFlow()
    private val memoryList = MutableStateFlow<List<StoredMemory>>(emptyList())
    val memories = memoryList.asStateFlow()
    private val dirtyNote = MutableStateFlow(false)
    val workspaceDirty = dirtyNote.asStateFlow()
    private var chats = emptyList<StoredChat>()
    private var request: Job? = null
    private var modelRequest: Job? = null
    private var activeSession: String? = null
    @Volatile private var storageReady = false
    private var workspaceVersion = 0L
    private var draftEpoch = 0L
    private var noteDebounce: Job? = null
    private var workspaceRecoveryReadable = true
    private val settingsLock = Mutex()
    private var providerEditorOwner: String? = null
    private var providerEditorBack: (() -> Unit)? = null
    private var providerEditorLeave: ((() -> Unit) -> Unit)? = null
    private val writes = Channel<List<StoredChat>>(Channel.CONFLATED)
    private val writer: Job

    init {
        writer = diskScope.launch {
            for (snapshot in writes) {
                try { withContext(Dispatchers.IO) { storage.saveChats(snapshot) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { fail("standalone.error.storage") }
            }
        }
        scope.launch {
            try {
                val restored = withContext(Dispatchers.IO) {
                    val book = storage.loadProviderBook()
                    Triple(book, storage.loadChats(), book.providers.filter { !storage.readProviderApiKey(it.id).isNullOrBlank() }.map { it.id }.toSet())
                }
                books.value = restored.first
                newChatModel = restored.first.defaultChat
                newImageModel = restored.first.defaultImage
                keyedProviders.value = restored.third
                syncProviderSettings()
                chats = restored.second.map { c -> c.copy(imageModel = c.imageModel.ifBlank {
                    restored.first.account(c.imageProviderId)?.takeIf { it.images }?.settings?.imageModel.orEmpty()
                }, messages = c.messages
                    .filterNot { it.role == "assistant" && it.text.isBlank() && it.attachments.isEmpty() && it.steps.isEmpty() }
                    .map { m -> m.copy(steps = m.steps.map { if (it.status == "running") it.copy(status = "interrupted") else it }) }) }
                // Memories restore alongside chats; a corrupt document surfaces, never resets.
                memoryList.value = withContext(Dispatchers.IO) { storage.loadMemories() }
                var recoveredPreference = false
                val prefs = runCatching { platform.readPreference("preferences.v1")?.let { json.decodeFromString<Preferences>(it) } ?: Preferences() }
                    .onFailure { recoveredPreference = true }.getOrDefault(Preferences())
                val draft = runCatching { loadDraft("") }.onFailure { recoveredPreference = true }.getOrDefault(StoredDraft())
                val workspace = runCatching { platform.readPreference("workspace.draft.v1")?.let { json.decodeFromString<WorkspaceDraft>(it) } }
                    .onFailure { recoveredPreference = true; workspaceRecoveryReadable = false }.getOrNull()
                workspace?.let { recovery ->
                    notePath.value = recovery.path; note.value = recovery.text; dirtyNote.value = recovery.dirty
                }
                val wallpaper = withContext(Dispatchers.IO) {
                    platform.readPreference("chat.wallpaper")?.let { runCatching { storage.mediaBytes(it) }.getOrNull() }
                }
                storageReady = true
                update { it.copy(preferences = prefs, draft = draft.text, attachments = draft.attachments.map { m -> DraftAttachment(m.path,m.name,m.mimeType,m.size) }, loading = false, screen = if (keyPresent.value) Screen.Chat else Screen.Profile,
                    selectedModel = config.value.chatModel, baseUrl = config.value.baseUrl, customWallpaper = wallpaper,
                    installedVersion = platform.appVersionName, error = if (recoveredPreference) "standalone.error.storage" else null) }
                publish()
                refreshWorkspace()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { update { it.copy(loading = false, screen = Screen.Profile, error = "standalone.error.storage") } }
        }
    }

    private fun update(block: (AppState) -> AppState) { mutable.update(block) }
    private fun fail(key: String) { update { it.copy(error = key, notice = null) } }
    override fun unavailable() = fail("standalone.unavailable")
    private fun id() = UUID.randomUUID().toString()
    private fun persist() { if (storageReady) writes.trySend(chats.toList()) }
    private fun draftKey(session: String) = "draft.v1.${session.ifBlank { "new" }}"
    private fun loadDraft(session: String): StoredDraft = platform.readPreference(draftKey(session))?.let { json.decodeFromString<StoredDraft>(it) }
        ?: StoredDraft(if (session.isEmpty()) platform.readPreference("draft.new").orEmpty() else "")
    private fun saveDraft(session: String = state.value.sessionId, value: StoredDraft = StoredDraft(state.value.draft,
        state.value.attachments.map { StoredMedia(it.path,it.name,it.mimeType,it.size) }, state.value.editingMessageId)) {
        runCatching { platform.writePreference(draftKey(session), json.encodeToString(value)); platform.writePreference("draft.new", null) }
            .onFailure { fail("standalone.error.storage") }
    }
    private fun current() = chats.firstOrNull { it.id == state.value.sessionId }
    private fun chatSelection(chat: StoredChat? = current()) = chat?.let { ModelSelection(it.providerId,it.model) } ?: newChatModel
    /** Null when neither the chat nor the saved defaults name an image model. */
    private fun imageSelection(chat: StoredChat? = current()): ModelSelection? =
        chat?.takeIf { it.imageModel.isNotBlank() }?.let { ModelSelection(it.imageProviderId,it.imageModel) } ?: newImageModel
    private fun info(selection: ModelSelection?): ModelInfo? = selection?.let { books.value.account(it.providerId)?.info(it.modelId) }
    /** The stop the picker shows: the chat's explicit choice, else the model's published default. */
    private fun effortFor(chat: StoredChat?): Pair<String, ModelInfo?> {
        val model = info(chatSelection(chat))
        val chosen = chat?.effort ?: newChatEffort
        val levels = model?.efforts.orEmpty()
        return (chosen.takeIf { it in levels } ?: model?.effortDefault?.takeIf { it in levels } ?: "none") to model
    }
    /** Only an explicit, still-published choice is sent; otherwise the provider default applies. */
    private fun requestEffort(chat: StoredChat?): String? {
        val levels = info(chatSelection(chat))?.efforts.orEmpty()
        return (chat?.effort ?: newChatEffort).takeIf { it.isNotBlank() && it in levels }
    }
    private fun syncProviderSettings() {
        val book = books.value
        val account = book.account(book.defaultChat.providerId) ?: return
        config.value = account.settings.copy(chatModel = book.defaultChat.modelId, systemPrompt = book.systemPrompt, toolsEnabled = book.toolsEnabled)
        keyPresent.value = book.defaultChat.providerId in keyedProviders.value
    }
    private fun publish() {
        val chat = current()
        val book = books.value
        val selectedChat = chatSelection(chat)
        val selectedImage = imageSelection(chat)
        fun row(account: ProviderAccount, model: String, image: Boolean): ModelRow {
            val meta = account.info(model)
            val host = runCatching { java.net.URI(account.settings.baseUrl).host }.getOrNull().orEmpty()
            return ModelRow(ModelSelection(account.id,model).pickerId(image), meta?.label?.ifBlank { null } ?: model, account.name,
                ModelCatalog.brand(model, host),
                efforts = if (image) emptyList() else meta?.efforts.orEmpty(),
                vision = if (image) null else meta?.vision,
                context = if (image) null else meta?.context,
                effortDefault = if (image) "" else meta?.effortDefault.orEmpty())
        }
        val models = book.providers.flatMap { account ->
            val chatIds = account.chatModels + listOfNotNull(selectedChat.modelId.takeIf { selectedChat.providerId == account.id })
            val imageIds = account.imageModels + listOfNotNull(selectedImage?.modelId?.takeIf { selectedImage.providerId == account.id })
            chatIds.distinct().map { row(account, it, false) } + imageIds.distinct().map { row(account, it, true) }
        } + listOfNotNull(selectedChat to false, selectedImage?.let { it to true }).filter { book.account(it.first.providerId) == null }.map { (selection,image) ->
            ModelRow(selection.pickerId(image), selection.modelId, selection.providerId, "", available = false)
        }
        val (effort, _) = effortFor(chat)
        update { it.copy(
            conversations = chats.sortedByDescending(StoredChat::updatedAt).map { c -> ConversationRow(c.id, c.title, c.updatedAt, c.messages.lastOrNull()?.text.orEmpty().take(160)) },
            messages = chat?.messages.orEmpty().map { m -> MessageRow(m.id, m.role, m.text, model = m.model, timestamp = m.timestamp,
                live = request?.isActive == true && activeSession == chat?.id && m == chat?.messages?.lastOrNull() && m.role == "assistant",
                reactions = m.reactions, steps = m.steps.map { step -> ActivityRow(step.id, step.name, status = step.status) },
                attachments = m.attachments.map { media -> DraftAttachment(media.path, media.name, media.mimeType, media.size) },
                reasoning = m.reasoning, reasoningSeconds = m.reasoningMillis?.let { ((it + 500) / 1000).toInt().coerceAtLeast(1) },
                status = replyStatus[m.id].orEmpty()) },
            models = models,
            selectedModel = if (generating.value && selectedImage != null) selectedImage.pickerId(true) else selectedChat.pickerId(),
            effort = if (generating.value) "none" else effort,
            busy = request?.isActive == true && activeSession == it.sessionId,
        ) }
    }
    private fun changeChat(session: String, transform: (StoredChat) -> StoredChat) {
        chats = chats.map { if (it.id == session) transform(it) else it }
        publish()
    }
    private fun append(session: String, message: StoredMessage) {
        changeChat(session) { it.copy(updatedAt = platform.nowMillis() / 1000, messages = it.messages + message) }
        persist()
    }
    private fun updateReply(session: String, messageId: String, transform: (StoredMessage) -> StoredMessage) {
        changeChat(session) { c -> c.copy(messages = c.messages.map { if (it.id == messageId) transform(it) else it }) }
    }

    /** All book edits and key updates share one lock and one persistent transaction. */
    private fun changeProviders(newKeys: Map<String, String?> = emptyMap(), onSaved: () -> Unit = {}, transform: (ProviderBook) -> ProviderBook) {
        if (!storageReady) { fail("standalone.error.storage"); return }
        if (request?.isActive == true) { fail("standalone.error.busy"); return }
        scope.launch {
            try {
                settingsLock.withLock {
                    savingProvider.value = true
                    try {
                        if (request?.isActive == true) throw ProviderFailure("standalone.error.busy")
                        val previous = books.value
                        val updated = transform(previous)
                        validateProviderBook(updated)
                        modelRequest?.cancel()
                        val keys = withContext(Dispatchers.IO) {
                            storage.saveProviderBook(updated,newKeys)
                            updated.providers.filter { !storage.readProviderApiKey(it.id).isNullOrBlank() }.map { it.id }.toSet()
                        }
                        books.value = updated
                        keyedProviders.value = keys
                        if (newChatModel == previous.defaultChat || updated.account(newChatModel.providerId)?.chatModels?.contains(newChatModel.modelId) != true) newChatModel = updated.defaultChat
                        val image = newImageModel
                        if (image == previous.defaultImage || image == null || updated.account(image.providerId)?.imageModels?.contains(image.modelId) != true) newImageModel = updated.defaultImage
                        syncProviderSettings()
                        update { it.copy(baseUrl = config.value.baseUrl, error = null, notice = "standalone.saved") }
                        publish()
                        onSaved()
                    } finally { savingProvider.value = false }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fail((error as? ProviderFailure)?.key ?: "standalone.error.storage") }
        }
    }

    fun saveAccount(account: ProviderAccount, newKey: String?, onSaved: () -> Unit = {}) {
        if (newKey != null && (newKey.length > 8192 || newKey.any(Char::isISOControl))) { fail("standalone.error.settings"); return }
        val settings = account.settings
        val chatIds = (listOf(settings.chatModel) + account.chatModels).map(String::trim).filter(String::isNotEmpty).distinct()
        val imageIds = if (settings.imageProtocol == ImageLayer.NONE) emptyList()
            else (listOf(settings.imageModel) + account.imageModels).map(String::trim).filter(String::isNotEmpty).distinct()
        val normalized = account.copy(name = account.name.trim(), chatModels = chatIds, imageModels = imageIds,
            settings = settings.copy(imageModel = if (imageIds.isEmpty()) "" else settings.imageModel.trim(),
                imageEndpoint = if (imageIds.isEmpty()) "" else settings.imageEndpoint.trim(),
                imageProtocol = if (imageIds.isEmpty()) ImageLayer.NONE else settings.imageProtocol),
            models = account.models.filter { it.id in chatIds || it.id in imageIds }.take(1024))
        changeProviders(if (newKey == null) emptyMap() else mapOf(account.id to newKey.trim()), onSaved) { book ->
            val profiles = if (book.account(account.id) == null) book.providers + normalized else book.providers.map { if (it.id == account.id) normalized else it }
            withValidDefaults(book.copy(providers = profiles), preferred = normalized)
        }
    }

    fun deleteProvider(id: String, onDeleted: () -> Unit = {}) = changeProviders(onSaved = onDeleted) { book ->
        if (book.account(id) == null) throw ProviderFailure("standalone.providers.missing")
        val remaining = book.providers.filterNot { it.id == id }
        if (remaining.isEmpty()) throw ProviderFailure("standalone.providers.last")
        withValidDefaults(book.copy(providers = remaining))
    }

    /**
     * Keeps defaults that still resolve. A broken chat default moves to [preferred] (the provider
     * just saved) or the first provider that chats; a broken or missing image default moves to the
     * first provider that generates images, or becomes null when none does.
     */
    private fun withValidDefaults(book: ProviderBook, preferred: ProviderAccount? = null): ProviderBook {
        fun chatOk(selection: ModelSelection) = book.account(selection.providerId)?.chatModels?.contains(selection.modelId) == true
        fun imageOk(selection: ModelSelection) = book.account(selection.providerId)?.imageModels?.contains(selection.modelId) == true
        val chat = book.defaultChat.takeIf(::chatOk)
            ?: (listOfNotNull(preferred) + book.providers).firstOrNull { it.chats }?.let { ModelSelection(it.id, it.settings.chatModel) }
            ?: throw ProviderFailure("standalone.providers.noChat")
        val image = book.defaultImage?.takeIf(::imageOk)
            ?: (listOfNotNull(preferred) + book.providers).firstOrNull { it.images }?.let { ModelSelection(it.id, it.settings.imageModel) }
        return book.copy(defaultChat = chat, defaultImage = image)
    }

    /**
     * Probes a typed endpoint for the setup screen. A saved key is reused only for the same
     * origin it was saved for, so editing the URL can never send it to a different host.
     */
    fun discoverProvider(providerId: String, url: String, typedKey: String, onFound: (Discovery) -> Unit) {
        discoveryRequest?.cancel()
        discoveryRequest = scope.launch {
            discoveringProvider.value = true
            update { it.copy(error = null, notice = null) }
            try {
                val key = typedKey.trim().ifBlank {
                    val saved = books.value.account(providerId)
                    val sameOrigin = saved != null && origin(saved.settings.baseUrl) != null &&
                        ModelCatalog.candidateBases(url).any { origin(it) == origin(saved.settings.baseUrl) }
                    if (sameOrigin) withContext(Dispatchers.IO) { storage.readProviderApiKey(providerId) }.orEmpty() else ""
                }
                if (key.isBlank()) throw ProviderFailure("standalone.error.key")
                onFound(provider.discover(url, key))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fail((error as? ProviderFailure)?.key ?: "standalone.error.network") }
            finally { if (discoveryRequest === currentCoroutineContext()[Job]) discoveringProvider.value = false }
        }
    }
    fun cancelDiscovery() { discoveryRequest?.cancel(); discoveringProvider.value = false }
    private fun origin(url: String): String? = runCatching { java.net.URI(url).let { "${it.scheme}://${it.host}:${it.port}" } }.getOrNull()

    fun setDefaultModel(selection: ModelSelection, image: Boolean) = changeProviders { book ->
        if (image) book.copy(defaultImage = selection) else book.copy(defaultChat = selection)
    }
    fun clearDefaultImage() = changeProviders { it.copy(defaultImage = null) }

    fun savePersonalization(prompt: String) = changeProviders { it.copy(systemPrompt = prompt) }
    fun setToolsEnabled(enabled: Boolean) = changeProviders { it.copy(toolsEnabled = enabled) }
    fun setMemoryEnabled(enabled: Boolean) = changeProviders { it.copy(memoryEnabled = enabled) }

    /** Personalization remains global; this also preserves the original single-provider API. */
    fun saveProvider(value: ProviderSettings, newKey: String?, onSaved: () -> Unit = {}) {
        val targetId = books.value.defaultChat.providerId
        if (newKey != null && (newKey.length > 8192 || newKey.any(Char::isISOControl))) { fail("standalone.error.settings"); return }
        changeProviders(if (newKey == null) emptyMap() else mapOf(targetId to newKey.trim()),onSaved) { book ->
            val account = book.account(targetId) ?: throw ProviderFailure("standalone.providers.missing")
            val images = value.imageProtocol != ImageLayer.NONE && value.imageModel.isNotBlank()
            val updated = account.copy(settings = value,chatModels = (account.chatModels + value.chatModel).filter(String::isNotBlank).distinct(),
                imageModels = if (images) (account.imageModels + value.imageModel).distinct() else emptyList())
            withValidDefaults(book.copy(providers = book.providers.map { if (it.id == targetId) updated else it },
                defaultChat = ModelSelection(targetId,value.chatModel),
                defaultImage = if (book.defaultImage?.providerId == targetId && images && value.imageModel != account.settings.imageModel) ModelSelection(targetId,value.imageModel) else book.defaultImage,
                systemPrompt = value.systemPrompt,toolsEnabled = value.toolsEnabled))
        }
    }

    fun refreshModels() = refreshProviderModels(books.value.defaultChat.providerId)

    fun refreshProviderModels(providerId: String) {
        if (settingsLock.isLocked) { fail("standalone.error.busy"); return }
        modelRequest?.cancel()
        val account = books.value.account(providerId) ?: run { fail("standalone.providers.missing"); return }
        modelRequest = scope.launch {
            update { it.copy(modelsLoading = true, error = null) }
            try {
                val key = withContext(Dispatchers.IO) { storage.readProviderApiKey(providerId) }.orEmpty()
                if (key.isBlank()) throw ProviderFailure("standalone.error.key")
                val result = provider.catalog(account.settings, key)
                settingsLock.withLock {
                    // Ignore a catalog whose account changed or was removed while it loaded.
                    if (books.value.account(providerId) != account) return@withLock
                    val next = books.value.copy(providers = books.value.providers.map {
                        if (it.id != providerId) it else {
                            val chatIds = if (it.chats) (it.chatModels + result.filter(ModelInfo::text).map(ModelInfo::id)).distinct().take(512) else it.chatModels
                            val imageIds = if (it.images) (it.imageModels + result.filter(ModelInfo::image).map(ModelInfo::id)).distinct().take(512) else it.imageModels
                            it.copy(chatModels = chatIds, imageModels = imageIds,
                                models = ModelCatalog.merge(result, it.models).filter { m -> m.id in chatIds || m.id in imageIds }.take(1024))
                        }
                    })
                    withContext(Dispatchers.IO) { storage.saveProviderBook(next) }
                    books.value = next
                    publish()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fail((error as? ProviderFailure)?.key ?: "standalone.error.network") }
            finally { if (modelRequest === currentCoroutineContext()[Job]) update { it.copy(modelsLoading = false) } }
        }
    }

    fun providerEditorNavigation(owner: String, back: () -> Unit, leave: (() -> Unit) -> Unit) {
        providerEditorOwner = owner; providerEditorBack = back; providerEditorLeave = leave
    }
    fun clearProviderEditorNavigation(owner: String) {
        if (providerEditorOwner == owner) { providerEditorOwner = null; providerEditorBack = null; providerEditorLeave = null }
    }
    private fun guardProviderExit(action: () -> Unit): Boolean {
        val guard = providerEditorLeave?.takeIf { state.value.screen == Screen.Models } ?: return false
        guard {
            providerEditorOwner?.let(::clearProviderEditorNavigation)
            action()
        }
        return true
    }
    override fun navigate(screen: Screen) {
        if (screen != Screen.Models && state.value.screen == Screen.Models) {
            if (screen == Screen.Profile && providerEditorBack != null) { providerEditorBack?.invoke(); return }
            if (guardProviderExit { navigate(screen) }) return
        }
        update { it.copy(screen = screen, menuOpen = false, modelPickerOpen = false, attachmentPickerOpen = false, error = null) }
    }
    override fun menu(open: Boolean) { update { it.copy(menuOpen = open) } }
    override fun modelPicker(open: Boolean) {
        update { it.copy(modelPickerOpen = open) }
        if (open && !generating.value) ensureEfforts(chatSelection())
    }

    /**
     * Learns a model's effort levels from its endpoint when no catalog published them, so
     * providers such as QwenCloud or LiteLLM still get a working effort slider. One probe per
     * model; the result is saved with the provider and never replaces a published list.
     */
    private fun ensureEfforts(selection: ModelSelection) {
        val account = books.value.account(selection.providerId) ?: return
        if (selection.modelId !in account.chatModels) return
        val source = account.info(selection.modelId)?.effortSource
        if (source == EffortSource.CATALOG || source == EffortSource.PROBE) return
        val tag = account.id + "\u0000" + selection.modelId
        if (!effortProbes.add(tag)) return
        scope.launch {
            try {
                val key = withContext(Dispatchers.IO) { storage.readProviderApiKey(account.id) }.orEmpty()
                if (key.isBlank()) return@launch
                val levels = provider.probeEfforts(account.settings, key, selection.modelId) ?: return@launch
                settingsLock.withLock {
                    val current = books.value.account(account.id) ?: return@withLock
                    // A provider edited meanwhile may point at another endpoint.
                    if (current.settings.baseUrl != account.settings.baseUrl || current.settings.api != account.settings.api) return@withLock
                    val base = current.info(selection.modelId) ?: ModelInfo(selection.modelId)
                    val probed = base.copy(efforts = levels, effortDefault = ModelCatalog.defaultEffort(selection.modelId, levels), effortSource = EffortSource.PROBE)
                    val models = (current.models.filterNot { it.id == selection.modelId } + probed).takeLast(1024)
                    val next = books.value.copy(providers = books.value.providers.map { if (it.id == current.id) it.copy(models = models) else it })
                    withContext(Dispatchers.IO) { storage.saveProviderBook(next) }
                    books.value = next
                    publish()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The slider keeps its previous state; the next open retries. */ }
            finally { effortProbes.remove(tag) }
        }
    }
    override fun attachments(open: Boolean) { update { it.copy(attachmentPickerOpen = open) } }
    override fun sendModes(open: Boolean) { update { it.copy(sendModeOpen = open) } }
    override fun draft(value: String) {
        update { it.copy(draft = value) }
        saveDraft()
    }
    override fun newChat() {
        if (guardProviderExit(::newChat)) return
        saveDraft(); ++draftEpoch
        val restored = runCatching { loadDraft("") }.getOrDefault(StoredDraft())
        generating.value = false
        newChatModel = books.value.defaultChat
        newImageModel = books.value.defaultImage
        newChatEffort = ""
        update { it.copy(sessionId = "", messages = emptyList(), draft = restored.text, attachments = restored.attachments.map { m -> DraftAttachment(m.path,m.name,m.mimeType,m.size) }, editingMessageId = restored.editingMessageId,
            screen = Screen.Chat, menuOpen = false, modelPickerOpen = false, busy = false) }
        publish()
    }
    override fun openChat(id: String) {
        if (guardProviderExit { openChat(id) }) return
        val target = chats.firstOrNull { it.id == id } ?: return
        saveDraft(); ++draftEpoch
        val restored = runCatching { loadDraft(id) }.getOrDefault(StoredDraft())
        generating.value = target.imageMode
        update { it.copy(sessionId = id, screen = Screen.Chat, menuOpen = false, draft = restored.text, attachments = restored.attachments.map { m -> DraftAttachment(m.path,m.name,m.mimeType,m.size) }, editingMessageId = restored.editingMessageId) }
        publish()
    }
    override fun renameChat(id: String, title: String) { changeChat(id) { it.copy(title = title.trim().take(160)) }; persist() }
    override fun deleteChat(id: String) {
        if (activeSession == id) stop()
        chats = chats.filterNot { it.id == id }
        if (state.value.sessionId == id) newChat()
        publish(); persist()
    }
    override fun search(value: String) { update { it.copy(search = value) } }
    override fun selectModel(id: String) {
        val parsed = parseModelSelection(id) ?: run { fail("standalone.error.settings"); return }
        val (selection,image) = parsed
        val account = books.value.account(selection.providerId) ?: run { fail("standalone.providers.missing"); return }
        if (selection.modelId !in (if (image) account.imageModels else account.chatModels) &&
            selection != (if (image) imageSelection() else chatSelection())) { fail("standalone.error.settings"); return }
        generating.value = image
        val chat = current()
        if (chat != null) {
            changeChat(chat.id) { if (image) it.copy(imageProviderId = selection.providerId,imageModel = selection.modelId,imageMode = true)
                else it.copy(providerId = selection.providerId,model = selection.modelId,imageMode = false,
                    // A different model may publish different stops; its default applies until one is chosen.
                    effort = if (it.providerId == selection.providerId && it.model == selection.modelId) it.effort else "") }
            persist()
        } else if (image) newImageModel = selection else { if (selection != newChatModel) newChatEffort = ""; newChatModel = selection }
        // The picker stays on the effort page for chat models; levels may still be arriving.
        if (image) update { it.copy(modelPickerOpen = false) } else ensureEfforts(selection)
        publish()
    }
    override fun selectEffort(value: String) {
        val chat = current()
        if (value !in info(chatSelection(chat))?.efforts.orEmpty()) return
        if (chat != null) { changeChat(chat.id) { it.copy(effort = value) }; persist() } else newChatEffort = value
        publish()
    }
    fun setImageMode(enabled: Boolean) {
        if (enabled && imageSelection() == null) { navigate(Screen.Models); fail("standalone.image.none"); return }
        generating.value = enabled
        current()?.let { chat -> changeChat(chat.id) { it.copy(imageMode = enabled) }; persist() }
        publish()
    }
    fun createImage() { setImageMode(true); navigate(Screen.Chat) }

    override fun send(delivery: String, scheduledAt: String?) {
        if (scheduledAt != null) { unavailable(); return }
        if (!storageReady) { fail("standalone.error.storage"); return }
        if (request?.isActive == true || settingsLock.isLocked) { fail("standalone.error.busy"); return }
        val snapshot = state.value
        if (snapshot.draft.isBlank() && snapshot.attachments.isEmpty()) return
        val target = (if (generating.value) imageSelection() else chatSelection())
            ?: run { navigate(Screen.Models); fail("standalone.image.none"); return }
        if (books.value.account(target.providerId) == null) { fail("standalone.providers.missing"); return }
        if (target.providerId !in keyedProviders.value) { navigate(Screen.Models); fail("standalone.error.key"); return }
        if (generating.value && (snapshot.draft.isBlank() || snapshot.attachments.isNotEmpty())) { fail("standalone.image.prompt"); return }
        ++draftEpoch
        val existing = current()
        // Editing creates a branch, retaining the original turn and every later reply.
        val chat = when {
            existing == null -> StoredChat(id(), snapshot.draft.trim().take(70).ifBlank { snapshot.attachments.first().name }, newChatModel.modelId, platform.nowMillis()/1000,
                imageMode = generating.value, providerId = newChatModel.providerId, imageProviderId = newImageModel?.providerId.orEmpty(),
                imageModel = newImageModel?.modelId.orEmpty(), effort = newChatEffort)
            snapshot.editingMessageId != null -> existing.copy(id = id(), updatedAt = platform.nowMillis()/1000,
                messages = existing.messages.takeWhile { it.id != snapshot.editingMessageId })
            else -> existing
        }
        if (chat !== existing) chats = chats + chat
        update { it.copy(sessionId = chat.id) }
        val session = chat.id
        val imageRequest = generating.value
        val user = StoredMessage(id(), "user", snapshot.draft, attachments = snapshot.attachments.map { StoredMedia(it.path, it.name, it.mimeType, it.size) }, timestamp = platform.nowMillis()/1000, providerId = target.providerId)
        saveDraft(snapshot.sessionId, StoredDraft())
        append(session, user)
        update { it.copy(attachments = emptyList(), editingMessageId = null, sendModeOpen = false, error = null) }
        draft("")
        launchReply(session, chatSelection(chat), imageSelection(chat), imageRequest, user.text, requestEffort(chat))
    }

    private fun launchReply(session: String, textModel: ModelSelection, imageModel: ModelSelection?, imageRequest: Boolean, prompt: String, effort: String? = null) {
        val book = books.value
        val target = (if (imageRequest) imageModel else textModel) ?: run { fail("standalone.image.none"); return }
        val account = book.account(target.providerId) ?: run { fail("standalone.providers.missing"); return }
        val saved = account.settings.copy(chatModel = textModel.modelId, systemPrompt = book.systemPrompt, toolsEnabled = book.toolsEnabled)
        val maxOutput = account.info(textModel.modelId)?.maxOutput
        val summaries = saved.api == ApiLayer.ANTHROPIC && account.info(textModel.modelId)?.adaptiveThinking == true
        val imageAccount = imageModel?.let { book.account(it.providerId) }?.takeIf { it.images }
        val imageSettings = imageAccount?.settings?.copy(imageModel = imageModel.modelId)
        // The image tool is offered only when this chat can actually reach an image model.
        val tools = if (!saved.toolsEnabled) emptyList()
            else ChatTools.definitions.filter { imageSettings != null || (it["function"] as? JsonObject)?.get("name")?.jsonPrimitive?.content != "generate_image" }
        activeSession = session
        val replyId = id()
        append(session, StoredMessage(replyId, "assistant", "", timestamp = platform.nowMillis()/1000, kind = if (imageRequest) "image" else "text", providerId = target.providerId))
        replyStatus[replyId] = "sending"
        request = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val key = withContext(Dispatchers.IO) { storage.readProviderApiKey(target.providerId) }.orEmpty()
                if (key.isBlank()) throw ProviderFailure("standalone.error.key")
                if (imageRequest) {
                    val image = provider.generateImage(imageSettings ?: throw ProviderFailure("standalone.providers.missing"), key, prompt)
                    val media = withContext(Dispatchers.IO) { storage.saveMedia(image.bytes, "image.png", image.mimeType) }
                    updateReply(session, replyId) { it.copy(model = target.modelId, attachments = listOf(media)) }
                } else {
                    val history = withContext(Dispatchers.IO) {
                        chats.first { it.id == session }.messages.filter { it.id != replyId && !(it.role == "assistant" && it.text.isBlank() && it.attachments.isEmpty()) }.map { m ->
                            ApiMessage(m.role, m.text, if (m.role == "user") m.attachments.filter { it.mimeType.startsWith("image/") }.map {
                                "data:${it.mimeType};base64," + Base64.encodeToString(storage.mediaBytes(it.path), Base64.NO_WRAP)
                            } else emptyList())
                        }
                    }.toMutableList()
                    val systemPrompt = buildString {
                        if (saved.systemPrompt.isNotBlank()) append(saved.systemPrompt)
                        if (book.memoryEnabled) MemoryStore.contextBlock(memoryList.value)?.let {
                            if (isNotEmpty()) append("\n\n")
                            append(it)
                        }
                    }
                    if (systemPrompt.isNotBlank()) history.add(0, ApiMessage("system", systemPrompt))
                    var complete = false
                    var count = 0
                    val resultMedia = mutableListOf<StoredMedia>()
                    val seenCalls = mutableSetOf<String>()
                    // Reasoning from earlier tool rounds stays above the new round's thinking.
                    var priorReasoning = ""
                    var thinkingMillis = 0L
                    for (round in 0 until 6) {
                        var thinkingSince: Long? = null
                        fun stopThinking() {
                            thinkingSince?.let { started -> thinkingMillis += platform.nowMillis() - started }
                            thinkingSince = null
                        }
                        val reply = provider.chat(saved, key, history, tools, effort, maxOutput, summaries,
                            onOpen = { setStatus(replyId, "thinking") },
                            onReasoning = { thinking ->
                                if (thinkingSince == null) thinkingSince = platform.nowMillis()
                                val combined = listOf(priorReasoning, thinking).filter(String::isNotBlank).joinToString("\n\n")
                                updateReply(session, replyId) { it.copy(reasoning = combined) }
                            }) { text ->
                            stopThinking()
                            if (replyStatus.containsKey(replyId)) setStatus(replyId, null)
                            updateReply(session, replyId) { it.copy(text = text) }
                        }
                        stopThinking()
                        priorReasoning = chats.firstOrNull { it.id == session }?.messages?.firstOrNull { it.id == replyId }?.reasoning.orEmpty()
                        if (priorReasoning.isNotBlank()) updateReply(session, replyId) { it.copy(reasoningMillis = thinkingMillis) }
                        updateReply(session, replyId) { it.copy(text = reply.text, model = reply.model.ifBlank { saved.chatModel }) }
                        if (reply.toolCalls.isEmpty()) { complete = true; break }
                        if (!saved.toolsEnabled) throw ProviderFailure("standalone.tools.disabled")
                        history += ApiMessage("assistant", reply.text, toolCalls = reply.toolCalls, native = reply.native)
                        setStatus(replyId, "tool")
                        for (call in reply.toolCalls) {
                            if (++count > 12 || !seenCalls.add(call.id)) throw ProviderFailure("standalone.tool.limit")
                            updateReply(session, replyId) { it.copy(steps = it.steps + StoredStep(call.id, call.name, "running")) }
                            val result = executeTool(call, imageAccount?.id, imageSettings, resultMedia)
                            updateReply(session, replyId) { it.copy(steps = it.steps.map { step -> if (step.id == call.id) step.copy(status = if (result.startsWith("{\"error\":")) "error" else "done") else step }) }
                            if (resultMedia.isNotEmpty()) updateReply(session, replyId) { it.copy(attachments = resultMedia.toList()) }
                            history += ApiMessage("tool", result, toolCallId = call.id)
                        }
                        // The next round is a new request; its phase starts over.
                        setStatus(replyId, "sending")
                    }
                    if (!complete) throw ProviderFailure("standalone.tool.limit")
                    // The reviewer is independent of toolsEnabled: memory is its own feature.
                    if (book.memoryEnabled) scheduleMemoryReview(session, target, key, saved, effort)
                }
            } catch (cancelled: CancellationException) {
                fail("standalone.stopped")
                throw cancelled
            } catch (error: Exception) {
                fail((error as? ProviderFailure)?.key ?: "standalone.error.network")
            } finally {
                replyStatus.remove(replyId)
                if (request === currentCoroutineContext()[Job]) {
                    activeSession = null
                    request = null
                }
                // Preserve completed partial output on Stop or a failed connection.
                changeChat(session) { c -> c.copy(messages = c.messages.filterNot { it.id == replyId && it.text.isBlank() && it.attachments.isEmpty() && it.steps.isEmpty() }
                    .map { message -> if (message.id == replyId) message.copy(steps = message.steps.map { step -> if (step.status == "running") step.copy(status = "error") else step }) else message }) }
                persist(); publish()
            }
        }
        request?.start(); publish()
    }

    private fun persistMemories(updated: List<StoredMemory>) {
        memoryList.value = updated
        diskScope.launch {
            try { withContext(Dispatchers.IO) { storage.saveMemories(updated) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail("standalone.error.storage") }
        }
    }

    /**
     * Background reviewer after a completed reply (Open WebUI's cadence): every Nth user
     * turn one extra model call decides whether facts changed. It never blocks the reply,
     * and any failure is silent — a wrong or broken review must cost the user nothing.
     */
    private fun scheduleMemoryReview(session: String, selection: ModelSelection, apiKey: String, settings: ProviderSettings, effort: String?) {
        val chat = chats.firstOrNull { it.id == session } ?: return
        val userTurns = chat.messages.count { it.role == "user" && it.text.isNotBlank() }
        if (userTurns == 0 || userTurns % MemoryLimits.REVIEW_INTERVAL_TURNS != 0) return
        val account = books.value.account(selection.providerId) ?: return
        val transcript = reviewTranscript(chat.messages)
        val existing = memoryList.value
        scope.launch {
            try {
                val review = provider.chat(settings.copy(chatModel = selection.modelId), apiKey,
                    listOf(
                        ApiMessage("system", MEMORY_REVIEWER_PROMPT),
                        ApiMessage("user", memoryReviewTask(existing, transcript)),
                    ),
                    effort = effort.takeIf { account.info(selection.modelId)?.efforts.orEmpty().isNotEmpty() },
                    maxOutput = 1024,
                )
                val operations = parseMemoryOperations(review.text)
                if (operations.isEmpty()) return@launch
                val updated = applyMemoryOperations(existing, operations, platform.nowMillis() / 1000)
                if (updated != existing) persistMemories(updated)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* a failed review is invisible to the user */ }
        }
    }

    private suspend fun executeTool(call: ApiToolCall, imageProviderId: String?, imageSettings: ProviderSettings?, images: MutableList<StoredMedia>): String {
        try {
            if (call.arguments.length > 600_000) throw ProviderFailure("standalone.error.limit")
            validateJsonNesting(call.arguments)
            val args = json.parseToJsonElement(call.arguments).jsonObject
            fun arg(name: String) = args[name]?.jsonPrimitive?.contentOrNull ?: throw ProviderFailure("standalone.error.tool")
            return when (call.name) {
                "current_time" -> ZonedDateTime.now().toString()
                "web_search" -> encodeResults(WebSearch.search(toolClient(), arg("query")), json)
                "read_url" -> UrlFetcher.fetch(toolClient(), arg("url"))
                "remember" -> {
                    val (updated, saved) = MemoryStore.remember(memoryList.value, arg("fact"), platform.nowMillis() / 1000)
                    if (saved) persistMemories(updated)
                    """{"saved":$saved}"""
                }
                "forget" -> {
                    val updated = MemoryStore.forget(memoryList.value, arg("fact"))
                    val forgot = updated.size < memoryList.value.size
                    if (forgot) persistMemories(updated)
                    """{"forgot":$forgot}"""
                }
                "list_files" -> withContext(Dispatchers.IO) { json.encodeToString(storage.listWorkspace()) }
                "read_file" -> withContext(Dispatchers.IO) { storage.readWorkspace(arg("path")) }
                "write_file" -> withContext(Dispatchers.IO) { storage.writeWorkspace(arg("path"), arg("content")); "{\"saved\":true}" }.also { refreshWorkspace() }
                "generate_image" -> {
                    if (images.size >= 4) throw ProviderFailure("standalone.error.limit")
                    if (imageProviderId == null || imageSettings == null) throw ProviderFailure("standalone.providers.missing")
                    val imageKey = withContext(Dispatchers.IO) { storage.readProviderApiKey(imageProviderId) }.orEmpty()
                    if (imageKey.isBlank()) throw ProviderFailure("standalone.error.key")
                    val image = provider.generateImage(imageSettings, imageKey, arg("prompt"))
                    val media = withContext(Dispatchers.IO) { storage.saveMedia(image.bytes, "image.png", image.mimeType) }
                    images += media
                    "{\"created\":true,\"displayed_in_chat\":true}"
                }
                else -> throw ProviderFailure("standalone.error.tool")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val code = (error as? ProviderFailure)?.key ?: "standalone.error.tool"
            return buildJsonObject { put("error", code) }.toString()
        }
    }

    override fun stop() { request?.cancel() }
    override fun regenerate(id: String) {
        if (!storageReady) { fail("standalone.error.storage"); return }
        if (request?.isActive == true || settingsLock.isLocked) { fail("standalone.error.busy"); return }
        val chat = current() ?: return
        val index = chat.messages.indexOfFirst { it.id == id && it.role == "assistant" }
        if (index < 0) return
        val original = chat.messages[index]
        val image = original.kind == "image"
        val selected = if (image) ModelSelection(original.providerId,original.model.ifBlank { chat.imageModel }) else chatSelection(chat)
        if (image && books.value.account(selected.providerId)?.images != true) { fail("standalone.image.none"); return }
        if (books.value.account(selected.providerId) == null) { fail("standalone.providers.missing"); return }
        if (selected.providerId !in keyedProviders.value) { fail("standalone.error.key"); return }
        val branch = chat.copy(id = id(), updatedAt = platform.nowMillis()/1000, messages = chat.messages.take(index), imageMode = image,
            imageProviderId = if (image) selected.providerId else chat.imageProviderId, imageModel = if (image) selected.modelId else chat.imageModel)
        saveDraft(); ++draftEpoch
        chats = chats + branch
        update { it.copy(sessionId = branch.id, draft = "", attachments = emptyList(), editingMessageId = null) }
        generating.value = image
        persist()
        launchReply(branch.id, chatSelection(branch), imageSelection(branch), image,
            branch.messages.lastOrNull { it.role == "user" }?.text.orEmpty(), requestEffort(branch))
    }
    override fun editMessage(id: String) {
        if (request?.isActive == true) return
        val message = current()?.messages?.firstOrNull { it.id == id && it.role == "user" } ?: return
        update { it.copy(editingMessageId = id, draft = message.text, attachments = message.attachments.map { m -> DraftAttachment(m.path,m.name,m.mimeType,m.size) }) }
        saveDraft()
    }
    override fun cancelEdit() { update { it.copy(editingMessageId = null, draft = "", attachments = emptyList()) }; saveDraft() }
    override fun reaction(id: String, bubbleIndex: Int, emoji: String?) {
        val chat = current() ?: return
        updateReply(chat.id, id) { it.copy(reactions = if (emoji == null) it.reactions - bubbleIndex.toString() else it.reactions + (bubbleIndex.toString() to emoji)) }; persist()
    }

    override fun pickFile(kind: String) {
        val epoch = draftEpoch
        platform.pickFiles(kind) { files ->
            scope.launch {
                try {
                    if (kind != "wallpaper" && epoch != draftEpoch) return@launch
                    if (kind != "wallpaper" && state.value.attachments.size + files.size > 4) throw ProviderFailure("standalone.error.limit")
                    val media = withContext(Dispatchers.IO) {
                        files.take(4).map { file ->
                            if (!file.mimeType.startsWith("image/")) throw ProviderFailure("standalone.error.image")
                            val image = normalizeImage(file)
                            storage.saveMedia(image.bytes, file.name, image.mimeType)
                        }
                    }
                    if (kind == "wallpaper") {
                        media.firstOrNull()?.let { wallpaper ->
                            val bytes = withContext(Dispatchers.IO) { storage.mediaBytes(wallpaper.path) }
                            platform.writePreference("chat.wallpaper", wallpaper.path)
                            update { it.copy(customWallpaper = bytes) }
                        }
                    } else {
                        if (epoch != draftEpoch) return@launch
                        if (state.value.attachments.size + media.size > 4) throw ProviderFailure("standalone.error.limit")
                        update { it.copy(attachments = it.attachments + media.map { m -> DraftAttachment(m.path,m.name,m.mimeType,m.size) }) }
                        saveDraft()
                        media.forEach { loadAttachmentThumbnail(it.path) }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { fail((error as? ProviderFailure)?.key ?: "standalone.error.image") }
            }
        }
    }
    private fun normalizeImage(file: PickedFile): GeneratedImage {
        if (file.bytes.size > 20 * 1024 * 1024) throw ProviderFailure("standalone.error.limit")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(file.bytes, 0, file.bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 100_000_000) throw ProviderFailure("standalone.error.image")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(file.bytes, 0, file.bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: throw ProviderFailure("standalone.error.image")
        return try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            GeneratedImage(out.toByteArray(), "image/jpeg")
        } finally { bitmap.recycle() }
    }
    override fun removeAttachment(path: String) { update { it.copy(attachments = it.attachments.filterNot { a -> a.path == path }) }; saveDraft() }
    override fun loadAttachmentThumbnail(path: String) {
        if (path in state.value.attachmentThumbnails) return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { thumbnailBytes(storage.mediaBytes(path)) ?: throw ProviderFailure("standalone.error.image") } }.onSuccess { bytes ->
                update { it.copy(attachmentThumbnails = (it.attachmentThumbnails + (path to bytes)).entries.toList().takeLast(24).associate { e -> e.toPair() }) }
            }
        }
    }
    override fun previewAttachment(path: String) {
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { storage.mediaBytes(path) }
                val media = (state.value.attachments + state.value.messages.flatMap { it.attachments }).firstOrNull { it.path == path }
                update { it.copy(previewTitle = media?.name ?: path, previewPath = path, previewSource = "upload", previewBytes = bytes, previewMimeType = media?.mimeType ?: "image/png") }
            } catch (_: Exception) { fail("standalone.error.storage") }
        }
    }
    override fun closePreview() { update { it.copy(previewTitle = null, previewPath = null, previewBytes = null) } }
    override fun savePreview() { previewFile()?.let { platform.saveFile(it) { okay -> if (!okay) fail("standalone.failed") } } }
    override fun sharePreview() { previewFile()?.let { platform.shareFile(it) { okay -> if (!okay) fail("standalone.failed") } } }
    private fun previewFile(): PickedFile? = state.value.previewBytes?.let { PickedFile(state.value.previewTitle ?: "image.png",state.value.previewMimeType,it) }
    override fun loadReplyImage(path: String, source: String, retry: Boolean) = loadAttachmentThumbnail(path)
    override fun previewReplyImage(path: String, source: String) = previewAttachment(path)

    override fun copyContent(text: String) = platform.copyText(text)
    override fun shareContent(text: String) = platform.shareText(text)
    override fun copyMessage(id: String) { current()?.messages?.firstOrNull { it.id == id }?.let { copyContent(it.text) } }
    override fun shareMessage(id: String) { current()?.messages?.firstOrNull { it.id == id }?.let { shareContent(it.text) } }
    override fun openLink(url: String) { platform.openExternalUrl(url) }
    override fun preferences(value: Preferences) {
        runCatching { platform.writePreference("preferences.v1", json.encodeToString(value)) }.onSuccess { update { it.copy(preferences = value) } }.onFailure { fail("standalone.error.storage") }
    }
    override fun resetWallpaper() { platform.writePreference("chat.wallpaper", null); update { it.copy(customWallpaper = null) } }
    override fun dismissNotice() { update { it.copy(error = null, notice = null) } }
    override fun refresh() { refreshModels(); refreshWorkspace() }
    fun refreshWorkspace() { scope.launch { runCatching { withContext(Dispatchers.IO) { storage.listWorkspace() } }.onSuccess { paths.value = it }.onFailure { fail("standalone.error.storage") } } }
    fun openWorkspace(path: String) {
        val version = ++workspaceVersion
        scope.launch {
            try {
                val content = withContext(Dispatchers.IO) { storage.readWorkspace(path) }
                if (version == workspaceVersion) { notePath.value = path; note.value = content; dirtyNote.value = false; workspaceRecoveryReadable = true; persistWorkspaceDraft() }
            } catch (_: Exception) { fail("standalone.error.storage") }
        }
    }
    fun editWorkspace(value: String) { ++workspaceVersion; note.value = value; dirtyNote.value = true; workspaceRecoveryReadable = true; persistWorkspaceDraft() }
    fun editWorkspacePath(value: String) { ++workspaceVersion; notePath.value = value; dirtyNote.value = true; workspaceRecoveryReadable = true; persistWorkspaceDraft() }
    fun newWorkspace() { ++workspaceVersion; notePath.value = ""; note.value = ""; dirtyNote.value = false; workspaceRecoveryReadable = true; persistWorkspaceDraft() }
    private fun persistWorkspaceDraft() {
        noteDebounce?.cancel()
        val value = json.encodeToString(WorkspaceDraft(notePath.value,note.value,dirtyNote.value))
        noteDebounce = scope.launch {
            delay(250)
            try { withContext(Dispatchers.IO) { platform.writePreference("workspace.draft.v1", value) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail("standalone.error.storage") }
        }
    }
    fun saveWorkspace(path: String) {
        val content = note.value
        scope.launch {
            try {
                withContext(Dispatchers.IO) { storage.writeWorkspace(path, content) }
                if (note.value == content && notePath.value == path) { dirtyNote.value = false; persistWorkspaceDraft() }
                refreshWorkspace(); update { it.copy(error = null, notice = "standalone.workspace.saved") }
            } catch (error: Exception) { fail((error as? ProviderFailure)?.key ?: "standalone.error.storage") }
        }
    }
    override fun close() {
        providerEditorOwner?.let(::clearProviderEditorNavigation)
        scope.cancel(); writes.close(); provider.close(); webToolHttpClient?.close()
        // onDestroy must not discard a queued final snapshot (for example after Stop).
        val snapshot = chats.toList()
        runBlocking(Dispatchers.IO) {
            writer.join()
            if (storageReady) runCatching { storage.saveChats(snapshot) }
            if (workspaceRecoveryReadable) runCatching { platform.writePreference("workspace.draft.v1",json.encodeToString(WorkspaceDraft(notePath.value,note.value,dirtyNote.value))) }
        }
        diskScope.cancel()
    }
}
