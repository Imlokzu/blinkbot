package me.waveio.blinkchat

import java.io.Closeable
import java.time.ZonedDateTime
import java.util.UUID
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
    private val mutable = MutableStateFlow(AppState(connected = true, loading = true))
    val state = mutable.asStateFlow()
    private val config = MutableStateFlow(ProviderSettings())
    val settings = config.asStateFlow()
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
    private val dirtyNote = MutableStateFlow(false)
    val workspaceDirty = dirtyNote.asStateFlow()
    private var chats = emptyList<StoredChat>()
    private var catalog = emptyList<String>()
    private var request: Job? = null
    private var modelRequest: Job? = null
    private var activeSession: String? = null
    @Volatile private var storageReady = false
    private var workspaceVersion = 0L
    private var draftEpoch = 0L
    private var noteDebounce: Job? = null
    private var workspaceRecoveryReadable = true
    private val settingsLock = Mutex()
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
                    Triple(storage.loadSettings(), storage.loadChats(), storage.readApiKey())
                }
                config.value = restored.first
                chats = restored.second.map { c -> c.copy(messages = c.messages
                    .filterNot { it.role == "assistant" && it.text.isBlank() && it.attachments.isEmpty() && it.steps.isEmpty() }
                    .map { m -> m.copy(steps = m.steps.map { if (it.status == "running") it.copy(status = "interrupted") else it }) }) }
                keyPresent.value = !restored.third.isNullOrBlank()
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
                    installedVersion = "0.1.0", error = if (recoveredPreference) "standalone.error.storage" else null) }
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
    private fun publish() {
        val chat = current()
        val providerName = runCatching { java.net.URI(config.value.baseUrl).host }.getOrNull().orEmpty()
        val models = (catalog + config.value.chatModel + listOfNotNull(chat?.model)).filter(String::isNotBlank).distinct().map {
            ModelRow(it, it, providerName, if (it.startsWith("qwen")) "qwen" else "", vision = null)
        } + ModelRow("image:${config.value.imageModel}", config.value.imageModel, providerName, if (config.value.imageModel.startsWith("qwen") || config.value.imageModel.startsWith("wan")) "qwen" else "")
        update { it.copy(
            conversations = chats.sortedByDescending(StoredChat::updatedAt).map { c -> ConversationRow(c.id, c.title, c.updatedAt, c.messages.lastOrNull()?.text.orEmpty().take(160)) },
            messages = chat?.messages.orEmpty().map { m -> MessageRow(m.id, m.role, m.text, model = m.model, timestamp = m.timestamp,
                live = request?.isActive == true && activeSession == chat?.id && m == chat?.messages?.lastOrNull() && m.role == "assistant",
                reactions = m.reactions, steps = m.steps.map { step -> ActivityRow(step.id, step.name, status = step.status) },
                attachments = m.attachments.map { media -> DraftAttachment(media.path, media.name, media.mimeType, media.size) }) },
            models = models,
            selectedModel = if (generating.value) "image:${config.value.imageModel}" else chat?.model ?: config.value.chatModel,
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

    fun saveProvider(value: ProviderSettings, newKey: String?, onSaved: () -> Unit = {}) {
        if (!storageReady) { fail("standalone.error.storage"); return }
        if (request?.isActive == true) { fail("standalone.error.busy"); return }
        try { ProviderClient.validateSettings(value) } catch (_: Exception) { fail("standalone.error.settings"); return }
        if (newKey != null && (newKey.contains('\n') || newKey.contains('\r'))) { fail("standalone.error.settings"); return }
        scope.launch {
            try {
                settingsLock.withLock {
                if (request?.isActive == true) { fail("standalone.error.busy"); return@withLock }
                modelRequest?.cancel()
                withContext(Dispatchers.IO) {
                    storage.saveConnection(value, newKey?.trim())
                }
                if (value.baseUrl != config.value.baseUrl) { catalog = emptyList(); modelRequest?.cancel() }
                config.value = value
                keyPresent.value = withContext(Dispatchers.IO) { !storage.readApiKey().isNullOrBlank() }
                update { it.copy(baseUrl = value.baseUrl, error = null, notice = "standalone.saved") }
                publish()
                onSaved()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail("standalone.error.storage") }
        }
    }

    fun refreshModels() {
        if (settingsLock.isLocked) { fail("standalone.error.busy"); return }
        modelRequest?.cancel()
        val saved = config.value
        modelRequest = scope.launch {
            update { it.copy(modelsLoading = true, error = null) }
            try {
                val key = withContext(Dispatchers.IO) { storage.readApiKey() }.orEmpty()
                if (key.isBlank()) throw ProviderFailure("standalone.error.key")
                val result = provider.models(saved, key)
                if (saved == config.value) { catalog = result; publish() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fail((error as? ProviderFailure)?.key ?: "standalone.error.network") }
            finally { if (modelRequest === currentCoroutineContext()[Job]) update { it.copy(modelsLoading = false) } }
        }
    }

    override fun navigate(screen: Screen) { update { it.copy(screen = screen, menuOpen = false, modelPickerOpen = false, attachmentPickerOpen = false, error = null) } }
    override fun menu(open: Boolean) { update { it.copy(menuOpen = open) } }
    override fun modelPicker(open: Boolean) { update { it.copy(modelPickerOpen = open) } }
    override fun attachments(open: Boolean) { update { it.copy(attachmentPickerOpen = open) } }
    override fun sendModes(open: Boolean) { update { it.copy(sendModeOpen = open) } }
    override fun draft(value: String) {
        update { it.copy(draft = value) }
        saveDraft()
    }
    override fun newChat() {
        saveDraft(); ++draftEpoch
        val restored = runCatching { loadDraft("") }.getOrDefault(StoredDraft())
        generating.value = false
        update { it.copy(sessionId = "", messages = emptyList(), draft = restored.text, attachments = restored.attachments.map { m -> DraftAttachment(m.path,m.name,m.mimeType,m.size) }, editingMessageId = restored.editingMessageId,
            screen = Screen.Chat, menuOpen = false, modelPickerOpen = false, busy = false) }
        publish()
    }
    override fun openChat(id: String) {
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
        if (id.startsWith("image:")) setImageMode(true)
        else {
            generating.value = false
            if (current() != null) { changeChat(state.value.sessionId) { it.copy(model = id, imageMode = false) }; persist() }
            else saveProvider(config.value.copy(chatModel = id), null)
        }
        update { it.copy(modelPickerOpen = false) }; publish()
    }
    override fun selectEffort(value: String) { update { it.copy(effort = "none", modelPickerOpen = false) } }
    fun setImageMode(enabled: Boolean) {
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
        if (!keyPresent.value) { navigate(Screen.Models); fail("standalone.error.key"); return }
        if (generating.value && (snapshot.draft.isBlank() || snapshot.attachments.isNotEmpty())) { fail("standalone.image.prompt"); return }
        ++draftEpoch
        val existing = current()
        // Editing creates a branch, retaining the original turn and every later reply.
        val chat = when {
            existing == null -> StoredChat(id(), snapshot.draft.trim().take(70).ifBlank { snapshot.attachments.first().name }, config.value.chatModel, platform.nowMillis()/1000, imageMode = generating.value)
            snapshot.editingMessageId != null -> existing.copy(id = id(), updatedAt = platform.nowMillis()/1000,
                messages = existing.messages.takeWhile { it.id != snapshot.editingMessageId })
            else -> existing
        }
        if (chat !== existing) chats = chats + chat
        update { it.copy(sessionId = chat.id) }
        val session = chat.id
        val model = chat.model
        val imageRequest = generating.value
        val saved = config.value.copy(chatModel = model)
        val user = StoredMessage(id(), "user", snapshot.draft, attachments = snapshot.attachments.map { StoredMedia(it.path, it.name, it.mimeType, it.size) }, timestamp = platform.nowMillis()/1000)
        saveDraft(snapshot.sessionId, StoredDraft())
        append(session, user)
        update { it.copy(attachments = emptyList(), editingMessageId = null, sendModeOpen = false, error = null) }
        draft("")
        launchReply(session, saved, imageRequest, user.text)
    }

    private fun launchReply(session: String, saved: ProviderSettings, imageRequest: Boolean, prompt: String) {
        activeSession = session
        val replyId = id()
        append(session, StoredMessage(replyId, "assistant", "", timestamp = platform.nowMillis()/1000, kind = if (imageRequest) "image" else "text"))
        request = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val key = withContext(Dispatchers.IO) { storage.readApiKey() }.orEmpty()
                if (key.isBlank()) throw ProviderFailure("standalone.error.key")
                if (imageRequest) {
                    val image = provider.generateImage(saved, key, prompt)
                    val media = withContext(Dispatchers.IO) { storage.saveMedia(image.bytes, "image.png", image.mimeType) }
                    updateReply(session, replyId) { it.copy(model = saved.imageModel, attachments = listOf(media)) }
                } else {
                    val history = withContext(Dispatchers.IO) {
                        chats.first { it.id == session }.messages.filter { it.id != replyId && !(it.role == "assistant" && it.text.isBlank() && it.attachments.isEmpty()) }.map { m ->
                            ApiMessage(m.role, m.text, if (m.role == "user") m.attachments.filter { it.mimeType.startsWith("image/") }.map {
                                "data:${it.mimeType};base64," + Base64.encodeToString(storage.mediaBytes(it.path), Base64.NO_WRAP)
                            } else emptyList())
                        }
                    }.toMutableList()
                    if (saved.systemPrompt.isNotBlank()) history.add(0, ApiMessage("system", saved.systemPrompt))
                    var complete = false
                    var count = 0
                    val resultMedia = mutableListOf<StoredMedia>()
                    val seenCalls = mutableSetOf<String>()
                    for (round in 0 until 6) {
                        val reply = provider.chat(saved, key, history, if (saved.toolsEnabled) ChatTools.definitions else emptyList()) { text ->
                            updateReply(session, replyId) { it.copy(text = text) }
                        }
                        updateReply(session, replyId) { it.copy(text = reply.text, model = reply.model.ifBlank { saved.chatModel }) }
                        if (reply.toolCalls.isEmpty()) { complete = true; break }
                        if (!saved.toolsEnabled) throw ProviderFailure("standalone.tools.disabled")
                        history += ApiMessage("assistant", reply.text, toolCalls = reply.toolCalls)
                        for (call in reply.toolCalls) {
                            if (++count > 12 || !seenCalls.add(call.id)) throw ProviderFailure("standalone.tool.limit")
                            updateReply(session, replyId) { it.copy(steps = it.steps + StoredStep(call.id, call.name, "running")) }
                            val result = executeTool(call, saved, key, resultMedia)
                            updateReply(session, replyId) { it.copy(steps = it.steps.map { step -> if (step.id == call.id) step.copy(status = if (result.startsWith("{\"error\":")) "error" else "done") else step }) }
                            if (resultMedia.isNotEmpty()) updateReply(session, replyId) { it.copy(attachments = resultMedia.toList()) }
                            history += ApiMessage("tool", result, toolCallId = call.id)
                        }
                    }
                    if (!complete) throw ProviderFailure("standalone.tool.limit")
                }
            } catch (cancelled: CancellationException) {
                fail("standalone.stopped")
                throw cancelled
            } catch (error: Exception) {
                fail((error as? ProviderFailure)?.key ?: "standalone.error.network")
            } finally {
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

    private suspend fun executeTool(call: ApiToolCall, saved: ProviderSettings, key: String, images: MutableList<StoredMedia>): String {
        try {
            if (call.arguments.length > 600_000) throw ProviderFailure("standalone.error.limit")
            validateJsonNesting(call.arguments)
            val args = json.parseToJsonElement(call.arguments).jsonObject
            fun arg(name: String) = args[name]?.jsonPrimitive?.contentOrNull ?: throw ProviderFailure("standalone.error.tool")
            return when (call.name) {
                "current_time" -> ZonedDateTime.now().toString()
                "list_files" -> withContext(Dispatchers.IO) { json.encodeToString(storage.listWorkspace()) }
                "read_file" -> withContext(Dispatchers.IO) { storage.readWorkspace(arg("path")) }
                "write_file" -> withContext(Dispatchers.IO) { storage.writeWorkspace(arg("path"), arg("content")); "{\"saved\":true}" }.also { refreshWorkspace() }
                "generate_image" -> {
                    if (images.size >= 4) throw ProviderFailure("standalone.error.limit")
                    val image = provider.generateImage(saved, key, arg("prompt"))
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
        val branch = chat.copy(id = id(), updatedAt = platform.nowMillis()/1000, messages = chat.messages.take(index), imageMode = original.kind == "image")
        saveDraft(); ++draftEpoch
        chats = chats + branch
        update { it.copy(sessionId = branch.id, draft = "", attachments = emptyList(), editingMessageId = null) }
        val image = original.kind == "image"
        generating.value = image
        persist()
        launchReply(branch.id, config.value.copy(chatModel = chat.model, imageModel = if (image && original.model.isNotBlank()) original.model else config.value.imageModel), image,
            branch.messages.lastOrNull { it.role == "user" }?.text.orEmpty())
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
        scope.cancel(); writes.close(); provider.close()
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
