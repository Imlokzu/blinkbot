package me.waveio.claudebot.state

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import me.waveio.claudebot.data.*
import me.waveio.claudebot.platform.PlatformBridge
import me.waveio.claudebot.platform.PickedFile
import me.waveio.claudebot.platform.PickedFileLimits
import me.waveio.claudebot.ui.AppActions
import me.waveio.claudebot.ui.LocaleText
import me.waveio.claudebot.ui.decodeImage
import me.waveio.claudebot.ui.thumbnailBytes

private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

/** Updates are installable only when the server supplies a complete digest. */
internal fun updateChecksumMatches(expected: String?, actual: String?): Boolean {
    val expectedHex = expected?.trim()?.lowercase() ?: return false
    val actualHex = actual?.trim()?.lowercase() ?: return false
    return SHA256_HEX.matches(expectedHex) && actualHex == expectedHex
}

class AppController(private val platform: PlatformBridge, private val makeApi: (String, String) -> BotApi = { server, token -> BotApi(server, token) }, dispatcher: CoroutineDispatcher = Dispatchers.Main, private val makeThumbnail: (ByteArray) -> ByteArray? = { thumbnailBytes(it) }) : AppActions {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val uiDispatcher = dispatcher
    private val mutable = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = mutable.asStateFlow()
    private var api: BotApi? = null
    private var text: LocaleText? = null
    private val watchers = mutableMapOf<String, Job>()
    private val cursors = mutableMapOf<String, Long>()
    private val sessionStreamVersions = mutableMapOf<String, Long>()
    private val observedJobStates = mutableMapOf<String, Pair<Long, String>>()
    private val reportedJobErrors = mutableSetOf<String>()
    private val seenQuestions = mutableSetOf<String>()
    private val handledQuestions = mutableSetOf<String>()
    private var navigationVersion = 0L
    private var connectionVersion = 0L
    private var previewVersion = 0L
    private var previewLoadVersion = 0L
    private var fileReadVersion = 0L
    private var directoryVersion = 0L
    private var draftVersion = 0L
    private var recordingVersion = 0L
    private var pairingVersion = 0L
    private var sessionsVersion = 0L
    private var messageMutationVersion = 0L
    private var chatActionVersion = 0L
    private val chatActionVersions = mutableMapOf<String, Long>()
    private val chatActionLocks = mutableMapOf<String, Mutex>()
    private val deletingChats = mutableSetOf<String>()
    private val reactionLocks = mutableMapOf<String, Mutex>()
    private val reactionVersions = mutableMapOf<String, Long>()
    private val reactionBaselines = mutableMapOf<String, String?>()
    private val thumbnailLoads = mutableMapOf<String, Job>()
    private val thumbnailMisses = mutableSetOf<String>()
    private val reducedThumbnails = mutableSetOf<String>()
    private val replyImageCache = linkedMapOf<Pair<String, Boolean>, List<PreviewItem>>()
    private var uploadVersion = 0L
    private var activeUploadVersion: Long? = null
    private val presentationIds = mutableMapOf<String, String>()
    private var partialAsr: Job? = null
    private var accountScope = runCatching { platform.readPreference("device_id") }.getOrNull() ?: "unpaired"
    private var lastHaptic = 0L
    private var cachedProfile: BotProfile? = null
    private var catalogDefaultModel = ""
    private var outbox = mutableListOf<OutboxItem>()
    private val permitted = mutableSetOf<String>()
    private var outboxSnapshot = emptyList<OutboxItem>()
    private var allowedSnapshot = emptySet<String>()
    private var retrying = false
    private val fileMutex = Mutex()
    private var fileDebounce: Job? = null
    private data class FileTarget(val path: String, val session: String = "") {
        // Canonical workspace paths identify the same file across entry points;
        // retain the captured session only for transport and unresolved aliases.
        private val identitySession get() = session.takeIf { path.startsWith("session/") }.orEmpty()
        override fun equals(other: Any?) = other is FileTarget && path == other.path && identitySession == other.identitySession
        override fun hashCode() = 31 * path.hashCode() + identitySession.hashCode()
    }
    private val fileDrafts = mutableMapOf<FileTarget, String>()
    private val fileBaselines = mutableMapOf<FileTarget, String>()
    private val fileRevisions = mutableMapOf<FileTarget, String>()
    private val fileConflicts = mutableMapOf<FileTarget, FileConflict>()
    private val fileAliases = mutableMapOf<FileTarget, FileTarget>()
    private val fileEditVersions = mutableMapOf<FileTarget, Long>()
    private val filePausedForWrite = mutableSetOf<FileTarget>()
    private val fileParents = ArrayDeque<FileTarget>()
    private var editorGeneration = 0L
    private var drawingExportVersion = 0L
    private var fileReturnScreen: Screen? = null
    private fun editorTarget(): FileTarget? = state.value.openFile?.let { FileTarget(it, state.value.fileSessionId) }
    private fun fileKey(target: FileTarget, prefix: String): String = if (target.session.isBlank() || !target.path.startsWith("session/")) "$prefix.${target.path}"
        else "$prefix.session.v1:${target.session}:${target.path}"
    private var beforeEdit: String? = null
    private var profileEditVersion = 0L

    init {
        val preferences = runCatching { platform.readPreference("preferences.v1")?.let { json.decodeFromString<Preferences>(it) } }.getOrNull() ?: Preferences()
        val server = platform.readPreference("server") ?: "https://api-bot.waveio.me"
        val wallpaper = runCatching { platform.readPreference("wallpaper.v1")?.let { Base64.decode(it) } }.getOrNull()
        outbox = runCatching { readScoped("outbox.v1")?.let { json.decodeFromString<List<OutboxItem>>(it).toMutableList() } }.getOrNull() ?: mutableListOf()
        permitted += runCatching { readScoped("outbox.allowed")?.let { json.decodeFromString<List<String>>(it) } }.getOrNull().orEmpty()
        outboxSnapshot = outbox.toList()
        allowedSnapshot = permitted.toSet()
        val restoredToken = runCatching { platform.readSecret("device_token") }
        val token = restoredToken.getOrNull()
        // Publish restoration before launching work so a paired phone never flashes QR login.
        mutable.value = AppState(preferences = preferences, baseUrl = server, customWallpaper = wallpaper, draft = readScoped("draft.new").orEmpty(), selectedModel = initialModel(preferences), installedVersion = platform.appVersionName,
            updateBeta = runCatching { platform.readPreference("update_beta") == "1" }.getOrDefault(false),
            initializing = !token.isNullOrBlank(), connecting = !token.isNullOrBlank(),
            initializationError = if (restoredToken.isFailure) "startup.storage" else null)
        if (!token.isNullOrBlank()) run { establish(server, token) }
        scope.launch {
            platform.incomingPairing.filterNotNull().distinctUntilChanged().collect { payload ->
                if (!state.value.connected && !state.value.connecting) processPairing(payload)
            }
        }
        scope.launch {
            var hasBeenForeground = platform.foreground.value
            platform.foreground.drop(1).collect { foreground ->
                if (!foreground && hasBeenForeground) { permitted += outbox.filter { it.lastError == null && !it.deliveryDeclined }.map { it.clientId }; persistOutbox() }
                else if (foreground && state.value.connected && !state.value.initializing) refresh()
                if (foreground) hasBeenForeground = true
            }
        }
        scope.launch {
            while (isActive) {
                delay(5000)
                if (state.value.connected) {
                    retryOutbox()
                    val session = state.value.sessionId
                    if (session.isNotBlank() && platform.foreground.value) runCatching { loadJobs(session) }
                    if (platform.foreground.value) runCatching { loadAllJobs() }
                }
            }
        }
    }

    fun strings(value: LocaleText) { text = value }
    private fun readScoped(key: String) = platform.readPreference("$accountScope.$key")
    private fun writeScoped(key: String, value: String?) = platform.writePreference("$accountScope.$key", value)
    private fun update(block: (AppState) -> AppState) = mutable.update(block)
    private fun initialModel(p: Preferences) = if (p.defaultModelMode == "fixed") p.defaultModel else p.lastModel
    private fun modelForChat(id: String): String {
        val p = state.value.preferences
        return p.chatModels[id]?.takeIf { it.isNotBlank() }
            ?: if (p.chatModels.containsKey(id) && p.defaultModelMode != "fixed") catalogDefaultModel.ifBlank { state.value.selectedModel }
            else initialModel(p).ifBlank { catalogDefaultModel.ifBlank { state.value.selectedModel } }
    }
    private fun requireApi(): BotApi = api ?: throw ApiFailure(401, "not_connected")
    private fun parseUpdate(value: JsonObject?, defaultChannel: String): MobileUpdate? {
        if (value == null) return null
        val versionCode = value["version_code"]?.jsonPrimitive?.intOrNull ?: 0
        if (versionCode <= platform.appVersionCode ||
            value["version_name"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) return null
        return MobileUpdate(
            versionName = value["version_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            versionCode = versionCode,
            changelog = value["changelog"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            url = value["url"]?.jsonPrimitive?.contentOrNull,
            iosUrl = value["ios_url"]?.jsonPrimitive?.contentOrNull,
            sha256 = value["sha256"]?.jsonPrimitive?.contentOrNull,
            mandatory = value["mandatory"]?.jsonPrimitive?.booleanOrNull == true,
            channel = value["channel"]?.jsonPrimitive?.contentOrNull ?: defaultChannel,
        )
    }
    private fun updateFromCapabilities(capabilities: JsonObject): MobileUpdate? =
        parseUpdate(capabilities["update"]?.jsonObject, if (state.value.updateBeta) "beta" else "stable")?.takeIf {
            capabilities["update"]?.jsonObject?.get("available")?.jsonPrimitive?.booleanOrNull == true
        }
    private fun updateStatus(capabilities: JsonObject, available: MobileUpdate?): String = when {
        available != null -> "update.ready"
        capabilities["update"]?.jsonObject?.get("version_name")?.jsonPrimitive?.contentOrNull.isNullOrBlank() -> "update.noRelease"
        else -> "update.current"
    }
    private fun run(isCurrent: () -> Boolean = { true }, block: suspend () -> Unit) {
        val epoch = connectionVersion
        scope.launch {
            if (epoch != connectionVersion || !isCurrent()) return@launch
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (epoch == connectionVersion && isCurrent()) error(failure) }
        }
    }
    private fun errorKey(failure: Exception): String = when {
            failure is ApiFailure && failure.code == "workspace_binary_unavailable" -> "files.binaryUnavailable"
            failure is ApiFailure && failure.code == "workspace_file_too_large" -> "files.tooLarge"
            failure is ApiFailure && failure.code == "invalid_workspace_path" -> "files.invalidPath"
            failure is ApiFailure && failure.code == "mobile_image_model_unavailable" -> "error.imageModel"
            failure is ApiFailure && failure.status == 401 -> "error.auth"
            failure is ApiFailure && failure.status == 501 -> "error.unsupported"
            failure is ApiFailure && failure.code in listOf("invalid_pairing", "invalid_server") -> "error.qr"
            failure is ApiFailure && (failure.code.contains("revision") || failure.code == "file_conflict") -> "error.fileConflict"
            failure is ApiFailure && failure.status == 409 -> "error.busy"
            failure is ApiFailure && failure.status != 0 -> "error.service"
            else -> "error.network"
        }
    private fun error(failure: Exception) {
        val key = errorKey(failure)
        update { it.copy(error = if (it.initializing) null else key,
            initializationError = if (it.initializing) key else it.initializationError,
            initializing = false, loading = false, connecting = false, modelPickerOpen = it.modelPickerOpen || key == "error.imageModel") }
    }
    private fun feedback(answer: Boolean = false) {
        if (answer && !platform.foreground.value) return
        val p = state.value.preferences
        if (p.haptics && (!answer || p.answerHaptics) && (!answer || platform.nowMillis() - lastHaptic > 200)) {
            lastHaptic = platform.nowMillis(); platform.haptic()
        }
    }

    override fun connect() {
        if (state.value.connecting) return
        val version = ++pairingVersion
        update { it.copy(codePairingOpen = false, pairingCode = "", pairingError = null) }
        platform.scanQr { payload ->
            if (payload == null || version != pairingVersion) return@scanQr
            processPairing(payload)
        }
    }

    override fun codePairing(open: Boolean) {
        if (state.value.connected) return
        pairingVersion++
        update { it.copy(codePairingOpen = open, pairingCode = "", pairingServer = it.baseUrl,
            pairingError = null, connecting = false, error = null) }
    }

    override fun pairingCode(value: String) {
        if (!state.value.codePairingOpen || state.value.connected) return
        pairingVersion++
        update { it.copy(pairingCode = value.take(64), connecting = false, pairingError = null) }
    }

    override fun pairingServer(value: String) {
        if (!state.value.codePairingOpen || state.value.connected) return
        pairingVersion++
        update { it.copy(pairingServer = value.take(512), connecting = false, pairingError = null) }
    }

    override fun connectWithCode() {
        val current = state.value
        if (!current.codePairingOpen || current.connected || current.connecting) return
        exchangePairing(manual = true) { PairingCode.manual(current.pairingServer, current.pairingCode) }
    }

    private fun processPairing(payload: String) = exchangePairing(manual = false) { PairingCode.parse(payload) }

    private fun exchangePairing(manual: Boolean, parse: () -> PairingCode) {
        val attempt = ++pairingVersion
        val epoch = connectionVersion
        // Publish synchronously so two taps cannot redeem the same one-time code.
        update { it.copy(connecting = true, pairingError = null, error = null) }
        run(isCurrent = { attempt == pairingVersion }) {
            val pairing: PairingCode
            val credentials: PairingCredentials
            try {
                pairing = parse()
                val pairingApi = makeApi(pairing.server, "")
                credentials = try { pairingApi.exchangePairing(pairing.code, platform.deviceName, platform.platformName) }
                finally { pairingApi.close() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (attempt != pairingVersion || epoch != connectionVersion) return@run
                if (manual) {
                    val key = when {
                        failure is ApiFailure && failure.code == "invalid_server" -> "connect.invalidServer"
                        failure is ApiFailure && (failure.status == 429 || failure.code == "pairing_rate_limited") -> "connect.rateLimited"
                        failure is ApiFailure && (failure.status in listOf(400, 401, 404, 410) || failure.code == "invalid_pairing") -> "connect.invalidCode"
                        failure is ApiFailure && failure.status != 0 -> "error.service"
                        else -> "error.network"
                    }
                    update { it.copy(connecting = false, pairingError = key) }
                } else error(failure)
                return@run
            }
            if (attempt != pairingVersion || epoch != connectionVersion) return@run
            disconnect()
            accountScope = credentials.deviceId
            platform.writePreference("device_id", accountScope)
            outbox = mutableListOf(); permitted.clear()
            platform.writeSecret("device_token", credentials.token)
            platform.writePreference("server", pairing.server)
            establish(pairing.server, credentials.token)
        }
    }

    private suspend fun establish(server: String, token: String) {
        // Retry must use the origin that owns this token, even if preload fails.
        update { it.copy(baseUrl = server, connecting = true, initializing = true, initializationError = null, error = null) }
        handledQuestions.clear()
        handledQuestions += readScoped("questions.handled.v1")?.let { json.decodeFromString<Set<String>>(it) }.orEmpty()
        handledQuestions += outbox.mapNotNull { it.questionId }
        val version = ++connectionVersion
        val connection = try { makeApi(server, token) }
        catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error(failure)
            return
        }
        try {
            api?.close(); api = connection
            // Independent authenticated reads keep startup bounded by the slowest request.
            val capabilities = coroutineScope {
                val capabilities = async { connection.capabilities(platform.platformName, platform.appVersionCode) }
                launch { if (version == connectionVersion) loadCatalog() }
                launch { if (version == connectionVersion) loadSessions() }
                capabilities.await()
            }
            val updateInfo = updateFromCapabilities(capabilities)
            if (version != connectionVersion) { connection.close(); return }
            update { it.copy(baseUrl = connection.origin, connected = true, connecting = false, initializing = false, initializationError = null, error = null, update = updateInfo,
                updateChecking = false, updatePromptOpen = updateInfo != null, updateStatus = updateStatus(capabilities, updateInfo),
                steerAvailable = capabilities["steer"]?.jsonPrimitive?.booleanOrNull == true) }
            run(isCurrent = { version == connectionVersion }) { loadIntelligence(version) }
            run(isCurrent = { version == connectionVersion }) {
                try { loadProfile() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Optional profile data must not block chat. */ }
            }
            run(isCurrent = { version == connectionVersion }) { retryOutbox() }
        } catch (failure: Exception) {
            if (api === connection) api = null
            connection.close()
            if (version == connectionVersion && failure !is CancellationException) error(failure)
            if (failure is CancellationException) throw failure
        }
    }

    private suspend fun loadCatalog() {
        val version = connectionVersion
        update { it.copy(modelsLoading = true) }
        val catalog = try { requireApi().models() }
        finally { if (version == connectionVersion) update { it.copy(modelsLoading = false) } }
        if (version != connectionVersion) return
        val models = catalog.models.map { model -> ModelRow(model.id, model.label ?: model.id.substringAfterLast('/'), model.provider.orEmpty(), model.brand ?: brand(model.id), model.available != false, (listOf("none") + model.efforts.ifEmpty { catalog.efforts }).distinct(), model.vision) }.sortedWith(modelComparator)
        val initial = catalog.selected?.takeIf { it.isNotBlank() }
            ?: catalog.defaultModel?.takeIf { it.isNotBlank() }
            ?: models.firstOrNull { it.available }?.id.orEmpty()
        catalogDefaultModel = initial
        update { it.copy(models = models, selectedModel = it.selectedModel.ifBlank { initial }) }
        val current = state.value
        val normalized = supportedEffort(current.selectedModel, current.effort)
        if (normalized != current.effort) selectEffort(normalized)
    }
    private suspend fun loadIntelligence(version: Long) {
        if (version != connectionVersion) return
        update { it.copy(intelligenceLoading = true) }
        try {
            val catalog = requireApi().intelligence()
            if (version != connectionVersion) return
            val total = catalog.benchmarks.size
            val entries = catalog.models.mapValues { (_, entry) ->
                IntelligenceRow(entry.index.toFloat().coerceIn(0f, 100f), entry.scores.keys.count { key -> catalog.benchmarks.any { it.key == key } }, total, entry)
            }
            update { it.copy(intelligence = entries, intelligenceLoading = false, intelligenceBenchmarks = catalog.benchmarks, intelligenceSource = catalog.source) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (version == connectionVersion) update { it.copy(intelligence = emptyMap(), intelligenceLoading = false) }
        }
    }
    private fun brand(id: String): String = when {
        id.contains("claude", true) -> "anthropic"
        id.contains("gpt", true) || id.contains("openai", true) -> "openai"
        id.contains("gemini", true) -> "google"
        id.contains("deepseek", true) -> "deepseek"
        id.contains("kimi", true) -> "moonshot"
        id.contains("qwen", true) -> "qwen"
        id.contains("mistral", true) -> "mistral"
        else -> ""
    }
    private suspend fun loadSessions() {
        val version = connectionVersion
        val request = ++sessionsVersion
        val sessions = requireApi().listSessions().map { ConversationRow(it.id, it.title.orEmpty(), it.updatedAt) }
        if (version != connectionVersion || request != sessionsVersion) return
        val local = outbox.map { ConversationRow(it.sessionId, it.message.take(80)) }
        update { it.copy(conversations = (sessions + local).distinctBy { row -> row.id }) }
    }
    private suspend fun loadProfile() {
        val version = connectionVersion
        val edits = profileEditVersion
        val profile = requireApi().profile()
        if (version != connectionVersion) return
        cachedProfile = profile
        if (edits == profileEditVersion) update { it.copy(profileName = profile.name.orEmpty(), profilePersona = profile.personaCustom.orEmpty()) }
    }

    override fun refresh() {
        if (state.value.initializing || state.value.connecting) return
        if (!state.value.connected) {
            val token = try { platform.readSecret("device_token") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { update { it.copy(initializationError = "startup.storage") }; return }
            if (token.isNullOrBlank()) {
                update { it.copy(initializationError = null, error = null) }
                return
            }
            val server = state.value.baseUrl
            update { it.copy(initializing = true, connecting = true, initializationError = null) }
            run { establish(server, token) }
        } else {
            val version = connectionVersion
            run {
                if (state.value.screen == Screen.Skills) loadSkills() else coroutineScope {
                    launch { if (version == connectionVersion) loadSessions() }
                    launch { if (version == connectionVersion) loadCatalog() }
                    launch { if (version == connectionVersion) loadAllJobs() }
                    state.value.sessionId.takeIf { it.isNotBlank() }?.let { session -> launch { if (version == connectionVersion) loadJobs(session) } }
                }
            }
        }
    }
    override fun navigate(screen: Screen) {
        if (screen != state.value.screen && state.value.openFile != null) { fileParents.clear(); closeFile() }
        if (screen != Screen.Chat && state.value.dictationOpen) cancelDictation()
        if (screen != state.value.screen) closePreview()
        feedback(); update { it.copy(screen = screen, menuOpen = false) }
        if (screen == Screen.Files) openDirectory(state.value.directory)
        if (screen == Screen.Personalization) run { loadProfile() }
        if (screen == Screen.Queue) run { loadAllJobs() }
        if (screen == Screen.Skills) run { loadSkills() }
        if (screen == Screen.Updates) {
            update { it.copy(updatePromptOpen = false) }
            checkForUpdate()
        }
    }
    private suspend fun loadSkills() {
        val version = connectionVersion
        update { it.copy(skillsLoading = true, skillsError = false) }
        try {
            val skills = requireApi().fetchMobileSkills().sortedWith(compareByDescending<MobileSkill> { it.selectable }.thenBy { it.name })
            if (version == connectionVersion) update { it.copy(skills = skills, skillsLoading = false) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (version == connectionVersion) update { it.copy(skillsLoading = false, skillsError = true) } }
    }
    override fun useSkill(name: String) {
        val skill = state.value.skills.firstOrNull { it.name == name && it.selectable && it.invocation.isNotBlank() } ?: return
        val existing = state.value.draft
        if (skill.invocation !in existing.split(Regex("\\s+"))) draft(listOf(skill.invocation, existing).filter { it.isNotBlank() }.joinToString(" "))
        update { it.copy(screen = Screen.Chat, attachmentPickerOpen = false) }
        feedback()
    }
    override fun newChat() {
        fileParents.clear()
        if (state.value.openFile != null) closeFile()
        if (state.value.editingMessageId != null) cancelEdit()
        cancelDictation()
        clearAttachmentPreviews()
        presentationIds.clear()
        navigationVersion++; draftVersion++; activeUploadVersion = null
        val current = state.value
        writeScoped("draft.${current.sessionId.ifBlank { "new" }}", current.draft)
        update { it.copy(screen = Screen.Chat, menuOpen = false, sessionId = "", messages = emptyList(), draft = readScoped("draft.new").orEmpty(), editingMessageId = null, attachments = emptyList(), uploading = false, selectedModel = initialModel(it.preferences).ifBlank { catalogDefaultModel.ifBlank { it.selectedModel } }, effort = "none", busy = false, activeJobId = null, pending = emptyList(), queuePaused = false, loading = false) }
        feedback()
    }
    override fun openChat(id: String) {
        fileParents.clear()
        if (state.value.openFile != null) closeFile()
        if (state.value.editingMessageId != null) cancelEdit()
        cancelDictation()
        clearAttachmentPreviews()
        presentationIds.clear()
        navigationVersion++; draftVersion++; activeUploadVersion = null
        val version = navigationVersion
        val connection = api ?: return
        val epoch = connectionVersion
        val p = state.value.preferences
        val model = modelForChat(id)
        val effort = supportedEffort(model, p.chatEfforts[id] ?: "none")
        if (p.chatEfforts[id] != null && effort != p.chatEfforts[id]) preferences(p.copy(chatEfforts = p.chatEfforts + (id to effort)))
        update { it.copy(screen = Screen.Chat, menuOpen = false, sessionId = id, messages = emptyList(), draft = readScoped("draft.$id").orEmpty(), editingMessageId = null, attachments = emptyList(), uploading = false, loading = true, busy = false, activeJobId = null, pending = emptyList(), queuePaused = false, selectedModel = model, effort = effort) }
        run(isCurrent = { navigationVersion == version }) {
            val local = outbox.filter { it.sessionId == id }
            val streamVersion = sessionStreamVersions[id] ?: 0L
            val mutations = messageMutationVersion
            val messages = try { messageRows(connection.sessionHistory(id)) }
                catch (failure: ApiFailure) { if (failure.status == 404 && local.isNotEmpty()) local.map { MessageRow("u-${it.clientId}", "user", it.message) } else throw failure }
            if (epoch != connectionVersion) return@run
            if (navigationVersion != version) return@run
            update { it.copy(messages = if (streamVersion == (sessionStreamVersions[id] ?: 0L) && mutations == messageMutationVersion) messages else it.messages, loading = false) }
            loadJobs(id, reconcileTerminal = true)
        }
    }
    private fun messageRows(messages: List<ChatMessage>): List<MessageRow> {
        val root = workspaceRootFromSteps(messages.filter { it.role == "assistant" }.flatMap { it.steps })
        return messages.mapIndexed { index, message -> messageRow(message, index, root) }
    }
    private fun messageRow(message: ChatMessage, index: Int, workspaceRoot: String? = null) = MessageRow(message.id ?: "history-$index", message.role, message.text, message.bubbles, message.steps.map { ActivityRow(it.id, it.label.orEmpty(), it.detail.orEmpty(), it.status ?: "interrupted", it.input, it.result) }, message.model.orEmpty(), parts = message.parts.map { ContentPart(it.type, it.text.orEmpty(), it.ids, note = it.note == true) }, attachments = message.attachments.map { DraftAttachment(it.path, it.name.orEmpty(), it.mimeType.orEmpty(), it.size ?: 0) }, reaction = message.reaction, reactions = message.reactions, timestamp = message.timestamp,
        workFiles = if (message.role == "assistant") collectWorkFiles(message.steps, workspaceRoot) else emptyList(),
        presentationId = message.id?.let(presentationIds::get) ?: state.value.messages.firstOrNull { it.id == message.id }?.presentationId)

    override fun renameChat(id: String, title: String) {
        val connection = api ?: return
        val name = title.trim()
        if (id.isBlank() || name.isBlank() || id in deletingChats) return
        val epoch = connectionVersion
        val action = ++chatActionVersion
        chatActionVersions[id] = action
        val lock = chatActionLocks.getOrPut(id) { Mutex() }
        run(isCurrent = { chatActionVersions[id] == action }) {
            lock.withLock {
                if (epoch != connectionVersion || chatActionVersions[id] != action) return@withLock
                if (!connection.renameSession(id, name)) throw ApiFailure(500, "rename_failed")
                if (epoch != connectionVersion || chatActionVersions[id] != action) return@withLock
                sessionsVersion++
                update { it.copy(conversations = it.conversations.map { row -> if (row.id == id) row.copy(title = name) else row }) }
            }
        }
    }

    override fun deleteChat(id: String) {
        val connection = api ?: return
        if (id.isBlank() || id in deletingChats) return
        val epoch = connectionVersion
        val navigation = navigationVersion
        val action = ++chatActionVersion
        chatActionVersions[id] = action
        val lock = chatActionLocks.getOrPut(id) { Mutex() }
        deletingChats += id
        run(isCurrent = { chatActionVersions[id] == action }) {
            try {
                lock.withLock {
                    if (epoch != connectionVersion || chatActionVersions[id] != action) return@withLock
                    // Deleting history alone leaves scheduled work runnable on the host.
                    // Read fresh jobs and fence local sends until this decision finishes.
                    persistOutbox()
                    val jobs = connection.listMessages(id)
                    if (epoch != connectionVersion || chatActionVersions[id] != action) return@withLock
                    val activeHere = state.value.sessionId == id && (state.value.busy || state.value.pending.isNotEmpty() || state.value.uploading)
                    val observedActive = jobs.any { job -> (observedJobStates[job.id]?.second ?: job.state) !in terminalStates }
                    if (activeHere || observedActive || jobs.any { it.state !in terminalStates } || outbox.any { it.sessionId == id }) {
                        if (navigation == navigationVersion) update { it.copy(notice = "chat.deletePendingWork", noticeDetail = id) }
                        return@withLock
                    }
                    if (!connection.deleteSession(id)) throw ApiFailure(500, "delete_failed")
                    if (epoch != connectionVersion || chatActionVersions[id] != action) return@withLock
                    sessionsVersion++
                    preferences(state.value.preferences.copy(chatModels = state.value.preferences.chatModels - id, chatEfforts = state.value.preferences.chatEfforts - id))
                    update { it.copy(conversations = it.conversations.filterNot { row -> row.id == id }, allPending = it.allPending.filterNot { row -> row.sessionId == id },
                        notice = if (it.notice == "chat.deletePendingWork" && it.noticeDetail == id) null else it.notice,
                        noticeDetail = if (it.notice == "chat.deletePendingWork" && it.noticeDetail == id) null else it.noticeDetail) }
                    // A delayed delete response cannot flush an editor that may
                    // have received more keystrokes since the drawer action.
                    if (navigation == navigationVersion && state.value.sessionId == id && state.value.openFile == null) newChat()
                    writeScoped("draft.$id", null)
                }
            } finally {
                if (epoch == connectionVersion) deletingChats -= id
            }
        }
    }
    override fun menu(open: Boolean) { update { it.copy(menuOpen = open) } }
    override fun modelPicker(open: Boolean) { update { it.copy(modelPickerOpen = open) } }
    override fun selectModel(id: String) {
        val current = state.value
        val effort = current.effort.takeIf { it in current.models.firstOrNull { model -> model.id == id }?.efforts.orEmpty() } ?: "none"
        val p = current.preferences.copy(lastModel = id, chatModels = if (current.sessionId.isBlank()) current.preferences.chatModels else current.preferences.chatModels + (current.sessionId to id), chatEfforts = if (current.sessionId.isBlank()) current.preferences.chatEfforts else current.preferences.chatEfforts + (current.sessionId to effort))
        preferences(p); update { it.copy(selectedModel = id, effort = effort) }; feedback()
    }
    override fun selectEffort(value: String) {
        val current = state.value
        val effort = supportedEffort(current.selectedModel, value)
        if (current.sessionId.isNotBlank()) preferences(current.preferences.copy(chatEfforts = current.preferences.chatEfforts + (current.sessionId to effort)))
        update { it.copy(effort = effort) }; feedback()
    }
    private fun supportedEffort(model: String, value: String): String {
        val normalized = value
        val supported = state.value.models.firstOrNull { it.id == model }?.efforts.orEmpty()
        return normalized.takeIf { it == "none" || it in supported } ?: "none"
    }
    override fun draft(value: String) { draftVersion++; update { it.copy(draft = value) }; writeScoped("draft.${state.value.sessionId.ifBlank { "new" }}", value) }

    private fun inheritsModel(current: AppState): Boolean {
        if (current.preferences.defaultModelMode == "fixed") return false
        val saved = current.preferences.chatModels[current.sessionId]
        if (saved != null) return saved.isBlank()
        return initialModel(current.preferences).isBlank()
    }

    private fun forkAttachments(current: AppState, id: String): List<DraftAttachment> {
        val at = current.messages.indexOfFirst { it.id == id }
        if (at < 0) return emptyList()
        val target = current.messages[at]
        val human = if (target.role == "user") target
            else current.messages.take(at).lastOrNull { it.role == "user" }
        return human?.attachments.orEmpty()
    }

    private fun submissionModel(current: AppState, attachments: List<DraftAttachment>): String =
        if (inheritsModel(current) && attachments.any { it.mimeType.startsWith("image/") }) "" else current.selectedModel

    override fun send(delivery: String, scheduledAt: String?) {
        val current = state.value
        if (current.sessionId in deletingChats) { update { it.copy(notice = "chat.deletePendingWork", noticeDetail = current.sessionId) }; return }
        if (current.uploading || activeUploadVersion != null) return
        // Forks inherit their source manifest on the host, without adding a
        // second copy of those attachments to the editing composer.
        val inputAttachments = current.editingMessageId?.let { forkAttachments(current, it) } ?: current.attachments
        if (current.draft.isBlank() && inputAttachments.isEmpty()) { update { it.copy(error = "error.message") }; return }
        if (scheduledAt != null && Instant.parse(scheduledAt).toEpochMilliseconds() <= platform.nowMillis()) { update { it.copy(error = "error.pastSchedule") }; return }
        val session = current.sessionId.ifBlank { platform.newId() }
        val inherited = inheritsModel(current)
        // The displayed default is resolved for text, but image routing belongs
        // to the host unless the user explicitly picked a model for this chat.
        val model = submissionModel(current, inputAttachments)
        val item = OutboxItem(platform.newId(), session, current.draft, model, current.effort, delivery, scheduledAt, current.attachments, current.editingMessageId, if (current.editingMessageId != null) "edit" else null)
        outbox.add(item)
        try { persistOutbox() } catch (_: Exception) { outbox.remove(item); update { it.copy(error = "error.storage") }; return }
        val p = current.preferences.copy(chatModels = current.preferences.chatModels + (session to if (inherited) "" else current.selectedModel), chatEfforts = current.preferences.chatEfforts + (session to current.effort))
        preferences(p); draftVersion++
        writeScoped("draft.${current.sessionId.ifBlank { "new" }}", null)
        update { it.copy(sessionId = session, draft = "", editingMessageId = null, attachments = emptyList(), scheduling = false, sendModeOpen = false, error = null) }
        feedback()
        run { submit(item, askOnFailure = true) }
    }

    override fun answerQuestion(id: String, answer: String) {
        val current = state.value
        if (api == null || !current.connected || current.sessionId in deletingChats) return
        val question = current.questions.firstOrNull { it.id == id && it.sessionId == current.sessionId } ?: return
        val value = answer.trim()
        if (value.isEmpty() || value.length > 8000 || (!question.allowCustom && value !in question.options)) return
        // Answer as a separate queued turn, preserving the composer's draft,
        // attachments and edit target. The existing outbox owns retry safety.
        val item = OutboxItem(platform.newId(), question.sessionId, value, current.selectedModel, current.effort, "queue", questionId = id)
        outbox.add(item)
        try { persistOutbox() } catch (_: Exception) {
            outbox.remove(item)
            update { it.copy(error = "error.storage") }
            return
        }
        handledQuestions += id
        update { it.copy(questions = it.questions.filterNot { q -> q.id == id }, error = null) }
        feedback()
        run { submit(item, askOnFailure = true) }
    }

    override fun dismissQuestion(id: String) {
        if (state.value.questions.none { it.id == id && it.sessionId == state.value.sessionId }) return
        if (!rememberHandledQuestion(id)) return
        update { it.copy(questions = it.questions.filterNot { q -> q.id == id && q.sessionId == it.sessionId }) }
    }

    private fun rememberHandledQuestion(id: String): Boolean {
        val key = "$accountScope.questions.handled.v1"
        try {
            platform.updatePreferences(listOf(key)) { latest ->
                val handled = latest[key]?.let { json.decodeFromString<Set<String>>(it) }.orEmpty()
                mapOf(key to json.encodeToString(handled + id))
            }
        } catch (_: Exception) { update { it.copy(error = "error.storage") }; return false }
        handledQuestions += id
        return true
    }

    private fun receiveQuestion(job: MobileJob, event: BotEvent) {
        // Listen even while another chat is open; only the matching chat may
        // display or answer this prompt. Never restore prompts from history.
        botQuestion(job, event)?.let { question ->
            if (question.id !in handledQuestions && seenQuestions.add(question.id)) update { it.copy(questions = it.questions + question) }
        }
        val status = (event.data["state"] as? JsonPrimitive)?.contentOrNull
        if (event.event == "error" || event.event == "mobile_state" && status in setOf("stopped", "failed", "interrupted", "cancelled")) {
            update { it.copy(questions = it.questions.filterNot { q -> q.jobId == job.id }) }
        }
        if (event.event in setOf("tool_error", "tool_done", "tool_result")) {
            val step = event.data["step"] as? JsonObject
            val failed = event.event == "tool_error" || (step?.get("status") as? JsonPrimitive)?.contentOrNull == "failed" ||
                (event.data["is_error"] as? JsonPrimitive)?.booleanOrNull == true
            val call = (step?.get("id") as? JsonPrimitive)?.contentOrNull ?: (event.data["call_id"] as? JsonPrimitive)?.contentOrNull
            if (failed && call != null) update { it.copy(questions = it.questions.filterNot { q -> q.id == "${job.sessionId}/${job.id}/$call" }) }
        }
    }

    private fun persistOutbox() {
        val queueKey = "$accountScope.outbox.v1"
        val allowedKey = "$accountScope.outbox.allowed"
        val local = outbox.associateBy { it.clientId }
        val baseline = outboxSnapshot.associateBy { it.clientId }
        val allowed = permitted.toSet()
        platform.updatePreferences(listOf(queueKey, allowedKey)) { latest ->
            val remote = latest[queueKey]?.let { json.decodeFromString<List<OutboxItem>>(it) }.orEmpty().associateBy { it.clientId }.toMutableMap()
            // Only local changes win. A worker's acknowledgement or failure must
            // survive a foreground controller that still holds an older snapshot.
            (baseline.keys - local.keys).forEach(remote::remove)
            local.forEach { (id, item) -> if (baseline[id] != item) remote[id] = item }
            val latestAllowed = latest[allowedKey]?.let { json.decodeFromString<Set<String>>(it) }.orEmpty()
            val mergedAllowed = ((latestAllowed - (allowedSnapshot - allowed)) + (allowed - allowedSnapshot))
                .filter { id -> remote[id]?.let { it.lastError == null && !it.deliveryDeclined } == true }
            mapOf(queueKey to json.encodeToString(remote.values.toList()), allowedKey to json.encodeToString(mergedAllowed))
        }
        // Native persistence may also filter acknowledged IDs during the commit.
        outbox = readScoped("outbox.v1")?.let { json.decodeFromString<List<OutboxItem>>(it).toMutableList() } ?: mutableListOf()
        permitted.clear()
        permitted += readScoped("outbox.allowed")?.let { json.decodeFromString<Set<String>>(it) }.orEmpty()
        outboxSnapshot = outbox.toList()
        allowedSnapshot = permitted.toSet()
    }
    private suspend fun submit(item: OutboxItem, askOnFailure: Boolean) {
        if (item.sessionId in deletingChats) return
        // iOS preference updates can be sequential. A pending outbox answer
        // carries its question ID through a crash; persist the receipt before
        // HTTP acknowledgment can remove that durable answer from the queue.
        if (item.questionId != null && !rememberHandledQuestion(item.questionId)) return
        val version = connectionVersion
        val owner = accountScope
        val navigation = navigationVersion
        val connection = requireApi()
        try {
            val job = if (item.forkMessageId != null && item.forkAction != null)
                connection.forkMessage(item.sessionId, item.forkMessageId, item.forkAction, item.clientId, item.message.takeIf { item.forkAction == "edit" }, item.model, item.effort)
            else connection.submitMessage(item.clientId, item.sessionId, item.message, item.attachments.map { Attachment(it.path, it.name, it.mimeType, it.size) }, item.model, item.effort, item.delivery, item.scheduledAt)
            if (version != connectionVersion || owner != accountScope) return
            outbox.removeAll { it.clientId == item.clientId }; permitted.remove(item.clientId); persistOutbox()
            if (navigation == navigationVersion && state.value.sessionId == item.sessionId) {
                if (item.forkAction != null) {
                    update { it.copy(sessionId = job.sessionId, messages = emptyList(), busy = true, activeJobId = job.id) }
                } else update { it.copy(messages = if (item.scheduledAt == null) it.messages + MessageRow("u-${item.clientId}", "user", item.message, attachments = item.attachments, presentationId = "u-${item.clientId}") else it.messages,
                    busy = it.busy || job.state in setOf("running", "stopping"), activeJobId = if (job.state in setOf("running", "stopping")) job.id else it.activeJobId,
                    pending = if (job.state in setOf("queued", "scheduled")) it.pending + PendingRow(job.id, item.message, job.state, item.scheduledAt) else it.pending) }
            }
            if (job.state in terminalStates) {
                if (navigation == navigationVersion && state.value.sessionId == job.sessionId) {
                    reconcileHistory(connection, job.sessionId, version, navigation)
                }
            } else watch(job)
            if (version == connectionVersion && owner == accountScope) loadJobs(job.sessionId)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (version != connectionVersion || owner != accountScope) return
            if (failure is ApiFailure && failure.status in 400..499 || failure is ApiFailure && failure.status == 501) {
                val index = outbox.indexOfFirst { it.clientId == item.clientId }
                if (index >= 0) outbox[index] = item.copy(lastError = (failure as ApiFailure).code)
                permitted.remove(item.clientId); persistOutbox()
                if (item.questionId == null && navigation == navigationVersion && state.value.sessionId == item.sessionId) {
                    if (state.value.draft.isBlank()) draft(item.message)
                    update { it.copy(attachments = if (it.attachments.isEmpty()) item.attachments else it.attachments) }
                }
                if (navigation == navigationVersion) error(failure)
            } else if (askOnFailure && platform.foreground.value && navigation == navigationVersion) update { it.copy(offlineQuestion = true) }
            else { permitted.add(item.clientId); persistOutbox() }
        }
    }
    private suspend fun retryOutbox() {
        if (retrying) return
        persistOutbox()
        retrying = true
        val version = connectionVersion
        val owner = accountScope
        try {
            for (item in outbox.toList().filter { it.clientId in permitted }) {
                if (version != connectionVersion || owner != accountScope) break
                if (item.sessionId in deletingChats) continue
                submit(item, false)
            }
        }
        finally { retrying = false }
    }
    override fun offlineDelivery(allow: Boolean) {
        if (allow) { permitted += outbox.filter { it.lastError == null && !it.deliveryDeclined }.map { it.clientId }; persistOutbox(); run { retryOutbox() } }
        else {
            val same = outbox.lastOrNull { it.sessionId == state.value.sessionId }
            if (same != null && same.questionId == null && state.value.draft.isBlank() && same.clientId !in permitted) {
                draft(same.message); update { it.copy(attachments = same.attachments) }
                outbox.removeAll { it.clientId == same.clientId }
            }
            outbox.replaceAllInPlace { if (it.clientId !in permitted) it.copy(deliveryDeclined = true) else it }
            persistOutbox()
        }
        update { it.copy(offlineQuestion = false) }
    }

    private suspend fun loadJobs(session: String, reconcileTerminal: Boolean = false) {
        val version = connectionVersion
        val navigation = navigationVersion
        val connection = requireApi()
        val streamVersion = sessionStreamVersions[session] ?: 0L
        val jobs = connection.listMessages(session).map { job ->
            val observed = observedJobStates[job.id]
            if (observed != null && observed.first > streamVersion) job.copy(state = observed.second) else job
        }
        if (version != connectionVersion) return
        if (navigation == navigationVersion && state.value.sessionId == session) {
            val active = jobs.firstOrNull { it.state in listOf("running", "stopping") }
            val currentSnapshot = (sessionStreamVersions[session] ?: 0L) == streamVersion
            update { it.copy(pending = jobs.filter { it.state in listOf("queued", "scheduled") }.map { job -> PendingRow(job.id, job.message.orEmpty(), job.state, job.scheduledAt?.let { Instant.fromEpochMilliseconds((it * 1000).toLong()).toString() }) },
                busy = if (currentSnapshot) active != null else it.busy, activeJobId = if (currentSnapshot) active?.id else it.activeJobId,
                queuePaused = if (currentSnapshot) jobs.any { it.conversationPaused == true } else it.queuePaused) }
            val latest = jobs.lastOrNull()
            if (currentSnapshot && latest?.state == "failed" && latest.error == "mobile_image_model_unavailable" && reportedJobErrors.add(latest.id)) update { it.copy(error = "error.imageModel", modelPickerOpen = true) }
        }
        jobs.filter { it.state !in terminalStates }.forEach(::watch)
        if (reconcileTerminal && jobs.any { it.state in terminalStates } && navigation == navigationVersion && state.value.sessionId == session) {
            reconcileHistory(connection, session, version, navigation)
        }
    }
    private suspend fun reconcileHistory(connection: BotApi, session: String, epoch: Long, navigation: Long) {
        val stream = sessionStreamVersions[session] ?: 0L
        val mutations = messageMutationVersion
        val history = messageRows(connection.sessionHistory(session))
        if (epoch == connectionVersion && navigation == navigationVersion && state.value.sessionId == session && stream == (sessionStreamVersions[session] ?: 0L) && mutations == messageMutationVersion) update { it.copy(messages = history) }
    }
    private suspend fun loadAllJobs() {
        val version = connectionVersion
        val jobs = requireApi().listMessages("")
        if (version != connectionVersion) return
        val pending = jobs.filter { it.state !in setOf("completed", "stopped") }.map { job -> PendingRow(job.id, job.message.orEmpty(), job.state, job.scheduledAt?.let { Instant.fromEpochMilliseconds((it * 1000).toLong()).toString() }, job.sessionId) }
        val local = outbox.map { PendingRow("local:${it.clientId}", it.message, if (it.lastError == null) "pending" else "failed", it.scheduledAt, it.sessionId) }
        update { it.copy(allPending = pending + local) }
    }
    private fun watch(job: MobileJob) {
        if (job.state in terminalStates) return
        if (watchers[job.id]?.isActive == true) return
        val version = connectionVersion
        val connection = requireApi()
        watchers[job.id] = scope.launch {
            var terminal = false
            while (isActive && !terminal && version == connectionVersion) {
                try {
                    connection.jobEvents(job.id, cursors[job.id] ?: 0).collect { event ->
                        if (version != connectionVersion) return@collect
                        if (event.id != null && event.id <= (cursors[job.id] ?: 0L)) return@collect
                        event.id?.let { cursors[job.id] = it }
                        receiveQuestion(job, event)
                        val streamVersion = (sessionStreamVersions[job.sessionId] ?: 0L) + 1
                        sessionStreamVersions[job.sessionId] = streamVersion
                        if (event.event == "mobile_state") {
                            val status = event.data["state"]?.jsonPrimitive?.content.orEmpty()
                            val imageFailure = status == "failed" && event.data["error"]?.jsonPrimitive?.contentOrNull == "mobile_image_model_unavailable"
                            if (imageFailure) reportedJobErrors += job.id
                            observedJobStates[job.id] = streamVersion to status
                            terminal = status in terminalStates
                            if (state.value.sessionId == job.sessionId) update { current ->
                                val starts = status in listOf("running", "stopping")
                                val endsActive = terminal && current.activeJobId == job.id
                                current.copy(busy = if (starts) true else if (endsActive) false else current.busy,
                                    activeJobId = if (starts) job.id else if (endsActive) null else current.activeJobId,
                                    pending = if (starts || terminal) current.pending.filterNot { it.id == job.id } else current.pending,
                                    queuePaused = current.queuePaused || status in listOf("stopped", "failed", "interrupted"),
                                    error = if (imageFailure) "error.imageModel" else current.error,
                                    modelPickerOpen = current.modelPickerOpen || imageFailure,
                                    messages = if (terminal) current.messages.map { row -> if (row.id == "a-${job.id}") row.copy(live = false) else row } else current.messages)
                            }
                        } else if (state.value.sessionId == job.sessionId) applyEvent(job, event)
                        if (event.event == "done") {
                            val navigation = navigationVersion
                            notifyReply(job.sessionId, event.data["reply"]?.jsonPrimitive?.content.orEmpty())
                            // HTTP reconciliation must not hold up the next SSE state frame.
                            scope.launch history@{
                                try {
                                    if (version != connectionVersion) return@history
                                    loadSessions()
                                    if (version == connectionVersion && navigation == navigationVersion && state.value.sessionId == job.sessionId) {
                                        reconcileHistory(connection, job.sessionId, version, navigation)
                                    }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { /* The authoritative final row remains visible. */ }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (failure is ApiFailure && failure.status in listOf(401, 403, 404)) terminal = true
                    else delay(2000)
                }
                if (!terminal) delay(1000)
            }
        }
    }
    private fun applyEvent(job: MobileJob, event: BotEvent) {
        if (event.event == "reaction") {
            applyBotReaction(job, event.data["emoji"]?.jsonPrimitive?.contentOrNull)
            return
        }
        if (event.event !in setOf("delta", "reply_snapshot", "break", "note", "model", "tool_start", "tool_progress", "tool_done", "tool_result", "tool_error", "done", "error")) return
        val data = event.data
        val running = event.event in setOf("delta", "reply_snapshot", "note", "tool_start", "tool_progress")
        if (running) {
            val revision = sessionStreamVersions[job.sessionId] ?: 0L
            observedJobStates[job.id] = revision to "running"
        }
        val id = "a-${job.id}"
        val old = state.value.messages.firstOrNull { it.id == id } ?: MessageRow(id, "assistant", "", live = true, presentationId = id)
        var row = old
        when (event.event) {
            "delta" -> {
                val chunk = data["chunk"]?.jsonPrimitive?.content.orEmpty()
                val last = old.parts.lastOrNull()
                val parts = if (last?.type == "text" && last.noteId == null && !last.note) old.parts.dropLast(1) + last.copy(text = last.text + chunk) else old.parts + ContentPart("text", chunk)
                row = old.copy(text = old.text + chunk, parts = parts, live = true); feedback(true)
            }
            "break" -> row = old.copy(text = old.text + "\n\n", parts = old.parts + ContentPart("text"))
            "reply_snapshot" -> {
                val snapshot = data["text"]?.jsonPrimitive?.content
                    ?: data["bubbles"]?.jsonArray?.joinToString("\n\n") { it.jsonPrimitive.content }.orEmpty()
                if (snapshot.isBlank()) return
                val bubbles = data["bubbles"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                val answer = snapshotBubbles(snapshot, bubbles)
                val insertAt = old.parts.indexOfFirst { it.type == "text" && it.noteId == null && !it.note }.let { if (it < 0) old.parts.size else it }
                val prefix = old.parts.take(insertAt)
                val preserved = old.parts.drop(insertAt).filterNot { it.type == "text" && it.noteId == null && !it.note }
                row = old.copy(text = snapshot, bubbles = answer, parts = prefix + answer.map { ContentPart("text", it) } + preserved, live = true)
                feedback(true)
            }
            "note" -> {
                val noteId = data["id"]?.jsonPrimitive?.content ?: return
                val notes = data["bubbles"]?.jsonArray?.map { ContentPart("text", it.jsonPrimitive.content, noteId = noteId, note = true) }.orEmpty()
                val at = old.parts.indexOfFirst { it.noteId == noteId }
                row = old.copy(parts = if (at >= 0) old.parts.take(at) + notes + old.parts.drop(at).filterNot { it.noteId == noteId } else old.parts + notes)
                feedback(true)
            }
            "model" -> {
                val model = data["model"]?.jsonPrimitive?.content.orEmpty(); row = old.copy(model = model)
            }
            "tool_start", "tool_progress", "tool_done", "tool_result", "tool_error" -> {
                val step = data["step"] as? JsonObject ?: data
                val stepId = step["id"]?.jsonPrimitive?.content ?: data["call_id"]?.jsonPrimitive?.content ?: return
                val previous = old.steps.firstOrNull { it.id == stepId }
                val value = ActivityRow(stepId, step["label"]?.jsonPrimitive?.content ?: data["tool"]?.jsonPrimitive?.content ?: previous?.label.orEmpty(), step["detail"]?.jsonPrimitive?.content ?: previous?.detail.orEmpty(), step["status"]?.jsonPrimitive?.content ?: if (event.event == "tool_error" || data["is_error"]?.jsonPrimitive?.booleanOrNull == true) "failed" else if (event.event in listOf("tool_done", "tool_result")) "done" else previous?.status ?: "running",
                    step["input"] ?: data["input"] ?: previous?.input, step["result"] ?: data["result"] ?: previous?.result)
                val known = old.steps.any { it.id == stepId }
                val steps = if (known) old.steps.map { if (it.id == stepId) value else it } else old.steps + value
                val last = old.parts.lastOrNull()
                val parts = if (known) old.parts else if (last?.type == "steps") old.parts.dropLast(1) + last.copy(stepIds = last.stepIds + stepId) else old.parts + ContentPart("steps", stepIds = listOf(stepId))
                val files = workFiles(steps)
                invalidateWorkThumbnails(old.workFiles, files)
                row = old.copy(steps = steps, parts = parts, workFiles = files)
            }
            "done" -> {
                applyBotReaction(job, data["reaction"]?.jsonPrimitive?.contentOrNull, data["user_message_id"]?.jsonPrimitive?.contentOrNull)
                val savedAssistantId = data["assistant_message_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                if (savedAssistantId != null) rememberPresentation(savedAssistantId, old.presentationId ?: id)
                val finalSteps = (data["steps"] as? JsonArray)?.mapNotNull { runCatching { json.decodeFromJsonElement<ToolStep>(it) }.getOrNull() }
                    ?.map { ActivityRow(it.id, it.label.orEmpty(), it.detail.orEmpty(), it.status ?: "interrupted", it.input, it.result) }
                    ?: old.steps.map { if (it.status in setOf("running", "active")) it.copy(status = "interrupted") else it }
                val files = workFiles(finalSteps)
                invalidateWorkThumbnails(old.workFiles, files)
                row = old.copy(id = data["assistant_message_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: old.id,
                    text = data["reply"]?.jsonPrimitive?.content ?: old.text,
                    bubbles = data["bubbles"]?.jsonArray?.map { it.jsonPrimitive.content } ?: old.bubbles,
                    parts = data["parts"]?.jsonArray?.map { part -> val value = part.jsonObject; ContentPart(value["type"]?.jsonPrimitive?.content.orEmpty(), value["text"]?.jsonPrimitive?.content.orEmpty(), value["ids"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(), note = value["note"]?.jsonPrimitive?.booleanOrNull == true) } ?: old.parts,
                    steps = finalSteps, workFiles = files, live = false)
            }
            "error" -> {
                row = old.copy(live = false)
                val code = data["code"]?.jsonPrimitive?.contentOrNull ?: data["error"]?.jsonPrimitive?.contentOrNull
                if (code == "mobile_image_model_unavailable") reportedJobErrors += job.id
                update { it.copy(error = if (code == "mobile_image_model_unavailable") "error.imageModel" else "error.service", modelPickerOpen = it.modelPickerOpen || code == "mobile_image_model_unavailable") }
            }
        }
        update { current -> current.copy(messages = if (current.messages.any { it.id == id }) current.messages.map { if (it.id == id) row else it } else current.messages + row,
            busy = current.busy || running, activeJobId = if (running) job.id else current.activeJobId,
            pending = if (running) current.pending.filterNot { it.id == job.id } else current.pending) }
    }
    private fun applyBotReaction(job: MobileJob, emoji: String?, savedId: String? = null) {
        val optimisticId = "u-${job.clientId ?: job.id}"
        val id = savedId?.takeIf { it.isNotBlank() }
        if (id != null) rememberPresentation(id, state.value.messages.firstOrNull { it.id == optimisticId }?.presentationId ?: optimisticId)
        update { current ->
            val target = current.messages.firstOrNull { it.role == "user" && (it.id == optimisticId || (id != null && it.id == id)) }
            if (target != null) current.copy(messages = current.messages.map { if (it.id == target.id) it.copy(id = id ?: it.id, reaction = emoji?.takeIf { value -> value.isNotBlank() } ?: it.reaction) else it })
            else if (!emoji.isNullOrBlank() && job.message != null) {
                val human = MessageRow(id ?: optimisticId, "user", job.message, reaction = emoji, attachments = job.attachments.map { DraftAttachment(it.path, it.name.orEmpty(), it.mimeType.orEmpty(), it.size ?: 0) }, presentationId = optimisticId)
                val at = current.messages.indexOfFirst { it.id == "a-${job.id}" }.let { if (it < 0) current.messages.size else it }
                current.copy(messages = current.messages.take(at) + human + current.messages.drop(at))
            } else current
        }
    }

    override fun reaction(id: String, bubbleIndex: Int, emoji: String?) {
        val connection = api ?: return
        val current = state.value
        val message = current.messages.firstOrNull { it.id == id && it.role == "assistant" && !it.live } ?: return
        if (current.sessionId.isBlank() || id.startsWith("history-") || id.startsWith("a-") || emoji?.isBlank() == true) return
        val bubbles = message.parts.filter { it.type == "text" }
        val count = bubbles.size.takeIf { it > 0 } ?: message.bubbles.size.coerceAtLeast(1)
        if (bubbleIndex !in 0 until count) return
        val session = current.sessionId
        val epoch = connectionVersion
        val navigation = navigationVersion
        val key = bubbleIndex.toString()
        val messageKey = "$session/$id"
        val actionKey = "$messageKey/$key"
        val action = (reactionVersions[actionKey] ?: 0L) + 1
        reactionVersions[actionKey] = action
        if (!reactionBaselines.containsKey(actionKey)) reactionBaselines[actionKey] = message.reactions[key]
        val lock = reactionLocks.getOrPut(messageKey) { Mutex() }
        fun apply(value: String?) {
            messageMutationVersion++
            update { it.copy(messages = it.messages.map { row -> if (row.id == id) row.copy(reactions = if (value == null) row.reactions - key else row.reactions + (key to value)) else row }) }
        }
        fun currentView() = epoch == connectionVersion && navigation == navigationVersion && state.value.sessionId == session && reactionVersions[actionKey] == action
        apply(emoji)
        feedback()
        run {
            try {
                lock.withLock {
                    if (epoch != connectionVersion || reactionVersions[actionKey] != action) return@withLock
                    val saved = connection.setReaction(session, id, bubbleIndex, emoji)
                    if (epoch != connectionVersion) return@withLock
                    reactionBaselines[actionKey] = saved[key]
                    if (currentView()) apply(saved[key])
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (currentView()) { apply(reactionBaselines[actionKey]); update { it.copy(error = "reaction.failed") } }
            } finally {
                if (epoch == connectionVersion && reactionVersions[actionKey] == action) {
                    reactionVersions.remove(actionKey); reactionBaselines.remove(actionKey)
                }
            }
        }
    }
    private fun notifyReply(session: String, reply: String) {
        val p = state.value.preferences
        if (p.notifications && !platform.foreground.value) platform.notifyReply(text?.get("app.name").orEmpty(), if (p.notificationPreview) reply.take(180) else text?.get("notifications.private").orEmpty(), session)
    }
    override fun stop() {
        val id = state.value.activeJobId ?: return
        val session = state.value.sessionId
        val version = connectionVersion
        run {
            requireApi().stopMessage(id)
            if (version == connectionVersion && state.value.sessionId == session) update { it.copy(queuePaused = true) }
        }
    }
    override fun resumeQueue() {
        val session = state.value.sessionId
        val version = connectionVersion
        run {
            requireApi().resumeSession(session)
            if (version != connectionVersion) return@run
            if (state.value.sessionId == session) update { it.copy(queuePaused = false) }
            loadJobs(session)
        }
    }
    override fun cancelPending(id: String) {
        if (id.startsWith("local:")) { val clientId = id.removePrefix("local:"); outbox.removeAll { it.clientId == clientId }; permitted.remove(clientId); persistOutbox(); run { loadAllJobs() } }
        else run { requireApi().stopMessage(id); loadAllJobs(); state.value.sessionId.takeIf { it.isNotBlank() }?.let { loadJobs(it) } }
    }
    override fun retryPending(id: String) {
        persistOutbox()
        if (id.startsWith("local:")) {
            val clientId = id.removePrefix("local:")
            outbox.firstOrNull { it.clientId == clientId }?.let { original ->
                val model = if (original.lastError == "mobile_image_model_unavailable") state.value.preferences.chatModels[original.sessionId]?.takeIf { it.isNotBlank() } ?: original.model else original.model
                val item = original.copy(model = model, effort = if (model != original.model) supportedEffort(model, original.effort) else original.effort, lastError = null, deliveryDeclined = false)
                outbox.replaceAllInPlace { if (it.clientId == clientId) item else it }
                permitted.add(clientId); persistOutbox(); run { submit(item, true); loadAllJobs() }
            }
        } else state.value.allPending.firstOrNull { it.id == id }?.let { pending ->
            val connection = api ?: return
            val epoch = connectionVersion
            run {
            if (pending.state in setOf("failed", "interrupted", "unsupported")) {
                val job = connection.listMessages(pending.sessionId).firstOrNull { it.id == id } ?: return@run
                if (epoch != connectionVersion) return@run
                val model = if (job.error == "mobile_image_model_unavailable") state.value.preferences.chatModels[job.sessionId]?.takeIf { it.isNotBlank() } ?: job.model.orEmpty() else job.model.orEmpty()
                val effort = job.reasoningEffort ?: "none"
                val item = OutboxItem(platform.newId(), job.sessionId, job.message.orEmpty(), model, if (model != job.model.orEmpty()) supportedEffort(model, effort) else effort, attachments = job.attachments.map { DraftAttachment(it.path, it.name.orEmpty(), it.mimeType.orEmpty(), it.size ?: 0) })
                outbox.add(item); persistOutbox()
                connection.resumeSession(job.sessionId)
                if (epoch != connectionVersion) return@run
                submit(item, true)
            } else connection.resumeSession(pending.sessionId)
            if (epoch == connectionVersion) loadAllJobs()
        } }
    }
    override fun sendModes(open: Boolean) { update { it.copy(sendModeOpen = open) } }
    override fun schedule(open: Boolean) { update { it.copy(scheduling = open) } }
    override fun attachments(open: Boolean) { update { it.copy(attachmentPickerOpen = open) } }
    override fun removeAttachment(path: String) {
        update { it.copy(attachments = it.attachments.filterNot { item -> item.path == path }, attachmentThumbnails = if (it.messages.any { message -> message.attachments.any { item -> item.path == path } }) it.attachmentThumbnails else it.attachmentThumbnails - path) }
        reducedThumbnails.retainAll(state.value.attachmentThumbnails.keys)
    }
    private fun clearAttachmentPreviews() {
        closePreview()
        thumbnailLoads.values.forEach { it.cancel() }; thumbnailLoads.clear(); thumbnailMisses.clear(); reducedThumbnails.clear()
        replyImageCache.clear()
        update { it.copy(attachmentThumbnails = emptyMap(), imageFailures = emptySet(), mediaGeneration = it.mediaGeneration + 1) }
    }
    private fun attachment(path: String): DraftAttachment? = state.value.attachments.firstOrNull { it.path == path }
        ?: state.value.messages.asSequence().flatMap { it.attachments.asSequence() }.firstOrNull { it.path == path }
    private fun workFile(path: String): WorkFile? = state.value.messages.asReversed().asSequence()
        .flatMap { it.workFiles.asSequence() }.firstOrNull { it.path == path }
    private fun workFiles(steps: List<ActivityRow>): List<WorkFile> = collectWorkFiles(steps.map {
        ToolStep(it.id, it.label, it.detail, it.status, it.input, it.result)
    }, workspaceRootFromSteps(state.value.messages.flatMap { message -> message.steps }.map {
        ToolStep(it.id, it.label, it.detail, it.status, it.input, it.result)
    }))
    private fun rememberPresentation(id: String, key: String) {
        presentationIds[id] = key
        while (presentationIds.size > 500) presentationIds.remove(presentationIds.keys.first())
    }
    private fun messageMedia(message: MessageRow): List<PreviewItem> {
        val inline = if (message.role == "assistant") message.parts.filter { it.type == "text" }.map { it.text }
            .ifEmpty { message.bubbles.ifEmpty { listOf(message.text) } }.flatMap { text ->
                val key = text to message.live
                replyImageCache.getOrPut(key) { replyImages(text, state.value.baseUrl, message.live).map { it.item } }
            } else emptyList()
        while (replyImageCache.size > 64) replyImageCache.remove(replyImageCache.keys.first())
        return (message.attachments.map { PreviewItem(it.path, it.name, it.mimeType, "upload") } +
            message.workFiles.filterNot { it.active }.map { PreviewItem(it.path, it.name, it.mimeType, "workspace") } + inline)
            .distinctBy { it.source to it.path }
    }
    private fun replyImage(path: String, source: String): PreviewItem? = state.value.messages.asSequence()
        .flatMap { messageMedia(it).asSequence() }.firstOrNull { it.path == path && it.source == source && it.mimeType.startsWith("image/") }
    private fun previewItems(path: String, source: String): List<PreviewItem> {
        val row = state.value.messages.lastOrNull { message -> messageMedia(message).any { it.path == path && it.source == source } }
        return (row?.let(::messageMedia) ?: state.value.attachments.map { PreviewItem(it.path, it.name, it.mimeType, "upload") })
            .distinctBy { it.source to it.path }
    }
    private suspend fun downloadImage(connection: BotApi, path: String, source: String, session: String): ImageDownload = when (source) {
        "remote" -> connection.fetchReplyImage(path)
        "workspace" -> ImageDownload(connection.downloadWorkspace(path, session), workspaceMimeType(path))
        else -> ImageDownload(connection.downloadAttachment(path), attachment(path)?.mimeType ?: workspaceMimeType(path))
    }
    override fun loadReplyImage(path: String, source: String, retry: Boolean) {
        if (replyImage(path, source) == null) return
        val key = if (source == "workspace") "workspace:$path" else path
        if (retry) {
            thumbnailMisses.remove(key)
            reducedThumbnails.remove(key)
            update { it.copy(imageFailures = it.imageFailures - key, attachmentThumbnails = it.attachmentThumbnails - key) }
        }
        loadThumbnail(path, source)
    }
    override fun previewReplyImage(path: String, source: String) { replyImage(path, source)?.let { preview(it) } }
    private suspend fun cacheThumbnail(path: String, bytes: ByteArray, isCurrent: () -> Boolean = { true }) {
        val epoch = connectionVersion
        val navigation = navigationVersion
        val reduced = bytes.size > MAX_THUMBNAIL_BYTES
        val thumbnail = if (reduced) {
            try { withContext(Dispatchers.Default) { makeThumbnail(bytes) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        } else bytes
        // Encoding adds a suspension point: old chats/accounts and invalidated
        // reads must never repopulate the current cache after it completes.
        if (epoch != connectionVersion || navigation != navigationVersion || !isCurrent()) return
        if (thumbnail == null || thumbnail.isEmpty() || thumbnail.size > MAX_THUMBNAIL_BYTES) {
            thumbnailMisses += path
            update { it.copy(imageFailures = it.imageFailures + path) }
            return
        }
        if (reduced) reducedThumbnails += path else reducedThumbnails -= path
        thumbnailMisses.remove(path)
        update { current ->
            val images = current.attachmentThumbnails.toMutableMap()
            images.remove(path); images[path] = thumbnail
            var size = images.values.sumOf { it.size.toLong() }
            while (size > MAX_THUMBNAIL_CACHE_BYTES || images.size > MAX_THUMBNAILS) {
                val oldest = images.keys.first()
                size -= images.remove(oldest)?.size ?: 0
            }
            current.copy(attachmentThumbnails = images, imageFailures = current.imageFailures - path)
        }
        reducedThumbnails.retainAll(state.value.attachmentThumbnails.keys)
    }
    private fun invalidateWorkThumbnails(before: List<WorkFile>, after: List<WorkFile>) {
        val keys = (after.filter { file -> before.firstOrNull { it.path == file.path } != file }.map { it.path } +
            before.filter { old -> after.none { it.path == old.path } }.map { it.path }).map { "workspace:" + it }.toSet()
        if (keys.isEmpty()) return
        keys.forEach { key -> thumbnailLoads.remove(key)?.cancel(); thumbnailMisses.remove(key); reducedThumbnails.remove(key) }
        update { it.copy(attachmentThumbnails = it.attachmentThumbnails - keys) }
        val session = state.value.sessionId
        for (draftTarget in fileDrafts.keys.toList()) {
            if (keys.none { sameWorkspaceFile(draftTarget, FileTarget(it.removePrefix("workspace:"), session)) }) continue
            val writing = after.firstOrNull { sameWorkspaceFile(draftTarget, FileTarget(it.path, session)) }?.active == true
            if (writing) filePausedForWrite += draftTarget
            else if (filePausedForWrite.remove(draftTarget)) {
                val epoch = connectionVersion
                val owner = accountScope
                run {
                    // The enclosing stream event publishes the new work-file
                    // state before a paused save checks ownership again.
                    yield()
                    if (epoch == connectionVersion && owner == accountScope && draftTarget in fileDrafts) saveFile(draftTarget)
                }
            }
        }
        editorTarget()?.let { target ->
            if (keys.any { sameWorkspaceFile(target, FileTarget(it.removePrefix("workspace:"), state.value.sessionId)) }) {
                val active = after.firstOrNull { sameWorkspaceFile(target, FileTarget(it.path, state.value.sessionId)) }?.active == true
                if (active) fileDebounce?.cancel()
                update { it.copy(fileWriteActive = active, fileExternallyChanged = true) }
            }
        }
        val preview = state.value
        val path = preview.previewPath ?: return
        val target = FileTarget(preview.previewWorkspacePath ?: path, preview.previewSessionId)
        if (preview.previewSource != "workspace" || keys.none {
                sameWorkspaceFile(target, FileTarget(it.removePrefix("workspace:"), preview.sessionId))
            }) return
        if (workspaceFileKind(path) !in setOf("drawing", "markdown", "mermaid", "text", "code")) {
            closePreview()
            return
        }
        val previous = before.firstOrNull { sameWorkspaceFile(target, FileTarget(it.path, preview.sessionId)) }
        val latest = after.firstOrNull { sameWorkspaceFile(target, FileTarget(it.path, preview.sessionId)) }
        val active = latest?.active == true
        // Keep the last complete scene visible while invalidating any older GET.
        // A failed/interrupted write must not turn partial bytes into a preview.
        previewLoadVersion++
        update { it.copy(previewWriteActive = active, previewExporting = false, loading = false) }
        if (!active && latest != null && latest.revision > (previous?.revision ?: 0)) refreshWorkspaceReader(preview)
    }

    private fun refreshWorkspaceReader(snapshot: AppState) {
        val path = snapshot.previewPath ?: return
        val connection = api ?: return
        val epoch = connectionVersion
        val navigation = navigationVersion
        val version = snapshot.previewRevision
        val load = ++previewLoadVersion
        fun current() = scope.isActive && epoch == connectionVersion && api === connection && navigation == navigationVersion &&
            version == previewVersion && load == previewLoadVersion && state.value.previewPath == path &&
            state.value.previewSessionId == snapshot.previewSessionId && state.value.previewSource == "workspace"
        scope.launch {
            if (!current()) return@launch
            try {
                val result = connection.readWorkspace(snapshot.previewWorkspacePath ?: path, snapshot.previewSessionId)
                val canonical = checkedWorkspacePath(result.path)
                if (current()) update { it.copy(previewText = result.content.takeUnless { result.binary }.orEmpty(),
                    previewWorkspacePath = canonical, previewTruncated = result.tooLarge,
                    previewEditable = !result.binary && !result.tooLarge, previewWriteActive = false,
                    previewRevision = ++previewVersion, loading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (current()) update { it.copy(error = errorKey(failure), loading = false) } }
        }
    }
    override fun loadAttachmentThumbnail(path: String) {
        if (attachment(path) == null) { loadWorkFileThumbnail(path); return }
        loadThumbnail(path, "upload")
    }
    override fun loadWorkFileThumbnail(path: String) { loadThumbnail(path, "workspace") }
    private fun loadThumbnail(path: String, source: String) {
        val key = if (source == "workspace") "workspace:" + path else path
        if (key in state.value.attachmentThumbnails || key in thumbnailMisses || thumbnailLoads[key]?.isActive == true) return
        val inline = replyImage(path, source)
        val image = inline != null || if (source == "workspace") workFile(path)?.let { it.kind == "image" && !it.active } == true
            else attachment(path)?.mimeType?.startsWith("image/") == true
        if (!image) return
        val connection = api ?: return
        val epoch = connectionVersion
        val navigation = navigationVersion
        val session = state.value.sessionId
        val revision = workFile(path)?.revision
        thumbnailLoads[key] = scope.launch {
            val load = currentCoroutineContext()[Job]
            try {
                val bytes = downloadImage(connection, path, source, session).bytes
                val exists = if (inline != null) replyImage(path, source) != null else if (source == "workspace") workFile(path)?.let { !it.active && it.revision == revision } == true else attachment(path) != null
                if (epoch == connectionVersion && navigation == navigationVersion && exists && thumbnailLoads[key] === load) {
                    cacheThumbnail(key, bytes) {
                        thumbnailLoads[key] === load && if (inline != null) replyImage(path, source) != null else if (source == "workspace") workFile(path)?.let { !it.active && it.revision == revision } == true else attachment(path) != null
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (epoch == connectionVersion && navigation == navigationVersion && thumbnailLoads[key] === load) {
                thumbnailMisses += key
                update { it.copy(imageFailures = it.imageFailures + key) }
            } }
            finally { if (epoch == connectionVersion && navigation == navigationVersion && thumbnailLoads[key] === load) thumbnailLoads.remove(key) }
        }
    }
    override fun previewAttachment(path: String) {
        val item = attachment(path)
        if (item == null) {
            val inline = replyImage(path, "upload")
            if (inline != null) preview(inline) else previewWorkFile(path)
            return
        }
        preview(PreviewItem(path, item.name, item.mimeType, "upload"))
    }
    override fun previewWorkFile(path: String) {
        val file = workFile(path)
        if (file == null) { replyImage(path, "workspace")?.let { preview(it) }; return }
        if (file.active) { update { it.copy(error = "files.stillWriting") }; return }
        preview(PreviewItem(path, file.name, file.mimeType, "workspace"))
    }
    private fun preview(item: PreviewItem, sessionOverride: String? = null, single: Boolean = false) {
        val connection = api
        if (connection == null) { update { it.copy(error = "error.auth") }; return }
        val epoch = connectionVersion
        val navigation = navigationVersion
        val session = sessionOverride ?: state.value.sessionId
        if (item.source == "workspace" && activelyWritten(FileTarget(item.path, session))) {
            update { it.copy(error = "files.stillWriting") }; return
        }
        val version = ++previewVersion
        val load = ++previewLoadVersion
        val webCandidate = item.source == "workspace" && (item.mimeType == "text/html" || '.' !in item.path.substringAfterLast('/'))
        val key = if (item.source == "workspace") "workspace:" + item.path else item.path
        update { it.copy(previewTitle = item.name, previewPath = item.path, previewSource = item.source,
            previewMimeType = item.mimeType, previewItems = if (single || webCandidate) listOf(item) else previewItems(item.path, item.source),
            previewSessionId = session, previewWorkspacePath = null, previewRevision = version, previewEditable = false, previewWriteActive = false, previewWeb = null, previewWebError = false,
            previewBytes = null, previewText = "", previewTruncated = false, previewExporting = false, loading = true, error = null) }
        if (session == state.value.sessionId && item.mimeType.startsWith("image/") && item.source != "remote" && key !in reducedThumbnails) state.value.attachmentThumbnails[key]?.let { bytes ->
            update { it.copy(previewBytes = bytes, loading = false) }; return
        }
        fun current() = version == previewVersion && load == previewLoadVersion && epoch == connectionVersion && navigation == navigationVersion
        run(isCurrent = ::current) {
            if (webCandidate) {
                val web = connection.webPreview(item.path, session)
                if (web.projectPath.isNotBlank()) checkedWorkspacePath(web.projectPath)
                if (web.root.isNotBlank()) checkedWorkspacePath(web.root)
                if (web.ready) checkedWorkspacePath(web.entry ?: throw ApiFailure(200, "invalid_response"))
                if (web.kind == "web") {
                    if (current()) update { it.copy(previewWeb = web, loading = false,
                        previewEditable = item.mimeType == "text/html") }
                } else {
                    val file = connection.readWorkspace(item.path, session)
                    val canonical = checkedWorkspacePath(file.path)
                    if (current()) update { it.copy(previewText = file.content.takeUnless { file.binary }.orEmpty(), loading = false, previewWorkspacePath = canonical,
                        previewTruncated = file.tooLarge, previewEditable = !file.binary && !file.tooLarge) }
                }
            } else if (item.mimeType.startsWith("image/")) {
                val download = downloadImage(connection, item.path, item.source, session)
                val bytes = download.bytes
                if (current()) {
                    if (session == state.value.sessionId) cacheThumbnail(key, bytes, ::current)
                    if (current()) update { it.copy(previewBytes = bytes, previewMimeType = download.mimeType, loading = false) }
                }
            } else if (item.source == "workspace") {
                val result = connection.readWorkspace(item.path, session)
                val canonical = checkedWorkspacePath(result.path)
                if (current()) update { it.copy(previewText = result.content.takeUnless { result.binary }.orEmpty(), loading = false, previewWorkspacePath = canonical,
                    previewTruncated = result.tooLarge, previewEditable = !result.binary && !result.tooLarge) }
            } else {
                val result = connection.attachmentPreview(item.path)
                if (current()) update { it.copy(previewText = result.text, previewTruncated = result.truncated, loading = false) }
            }
        }
    }
    override fun closePreview() {
        previewVersion++
        update { it.copy(previewTitle = null, previewPath = null, previewSource = null, previewMimeType = "",
            previewItems = emptyList(), previewBytes = null, previewText = "", previewSessionId = "", previewWorkspacePath = null, previewEditable = false, previewWriteActive = false,
            previewWeb = null, previewWebError = false, previewTruncated = false, previewExporting = false, loading = false) }
    }
    override fun reloadPreview() {
        val current = state.value
        if (current.previewWriteActive) { update { it.copy(error = "files.stillWriting") }; return }
        val path = current.previewPath ?: return
        preview(PreviewItem(path, current.previewTitle.orEmpty(), current.previewMimeType, current.previewSource ?: return),
            current.previewSessionId, single = current.previewItems.size <= 1)
    }
    override fun editPreview() {
        val current = state.value
        if (!current.previewEditable || current.previewSource != "workspace" || current.loading || current.previewWriteActive) return
        val target = FileTarget(current.previewPath ?: return, current.previewSessionId)
        editorTarget()?.takeIf { it != target }?.let { if (fileParents.size < 8) fileParents.addLast(it) }
        closePreview()
        openEditor(target)
    }
    override suspend fun loadWebPreviewResource(revision: Long, path: String): WebPreviewResource? = withContext(uiDispatcher) {
        val current = state.value
        val web = current.previewWeb?.takeIf { it.ready } ?: return@withContext null
        if (!scope.isActive || revision != previewVersion || current.previewTitle == null) return@withContext null
        val connection = api ?: return@withContext null
        val epoch = connectionVersion
        val result = connection.webPreviewResource(web.root, path.removePrefix("/"), web.entry ?: return@withContext null, current.previewSessionId)
        result.takeIf { scope.isActive && epoch == connectionVersion && revision == previewVersion && api === connection }
    }
    override fun webPreviewFailed(revision: Long) {
        scope.launch { if (revision == previewVersion && state.value.previewWeb != null) update { it.copy(previewWebError = true) } }
    }
    override fun buildPreviewProject() {
        val current = state.value
        val web = current.previewWeb?.takeIf { it.buildable } ?: return
        if (current.loading || api == null) return
        val message = text?.get("files.buildPrompt", "path" to web.projectPath.ifBlank { "." }) ?: return
        val session = current.previewSessionId.ifBlank { current.sessionId.ifBlank { platform.newId() } }
        val model = if (session == current.sessionId || current.previewSessionId.isBlank()) current.selectedModel else modelForChat(session)
        val effort = if (session == current.sessionId) current.effort else supportedEffort(model, current.preferences.chatEfforts[session] ?: "none")
        val item = OutboxItem(platform.newId(), session, message, model, effort)
        outbox.add(item)
        try { persistOutbox() } catch (_: Exception) { outbox.remove(item); update { it.copy(error = "error.storage") }; return }
        // A build action is its own chat request; never replace the user's draft.
        if (current.sessionId.isBlank() && current.previewSessionId.isBlank()) {
            writeScoped("draft.$session", current.draft)
            writeScoped("draft.new", null)
        }
        closePreview()
        if (session != current.sessionId && current.previewSessionId.isNotBlank()) openChat(session)
        else update { it.copy(sessionId = session, screen = Screen.Chat, menuOpen = false) }
        run { submit(item, askOnFailure = false) }
    }
    override fun savePreview() { exportPreview(share = false) }
    override fun sharePreview() { exportPreview(share = true) }
    private fun exportPreview(share: Boolean) {
        val snapshot = state.value
        val path = snapshot.previewPath ?: return
        val source = snapshot.previewSource ?: return
        if (snapshot.previewWriteActive) { update { it.copy(error = "files.stillWriting") }; return }
        if (snapshot.previewExporting || snapshot.editorExporting) return
        val connection = api ?: return
        val version = previewVersion
        val load = previewLoadVersion
        val epoch = connectionVersion
        val navigation = navigationVersion
        val session = snapshot.previewSessionId
        fun current() = version == previewVersion && load == previewLoadVersion && epoch == connectionVersion && navigation == navigationVersion &&
            state.value.previewPath == path && state.value.previewSource == source
        update { it.copy(previewExporting = true, error = null) }
        run(isCurrent = ::current) {
            try {
                // Extracted/truncated preview text is never substituted for the file.
                val download = downloadImage(connection, path, source, session)
                val bytes = download.bytes
                if (!current()) return@run
                val mime = download.mimeType.takeUnless { it == "image/*" }.orEmpty().ifBlank { snapshot.previewMimeType.ifBlank { "application/octet-stream" } }
                val extension = when (mime) {
                    "image/jpeg" -> ".jpg"; "image/png" -> ".png"; "image/gif" -> ".gif"
                    "image/webp" -> ".webp"; "image/avif" -> ".avif"; else -> ""
                }
                val name = snapshot.previewTitle?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/')
                val exportedName = if (source == "remote" && extension.isNotEmpty() && !name.endsWith(extension, true)) name + extension else name
                val file = PickedFile(exportedName, mime, bytes)
                var completed = false
                val result: (Boolean) -> Unit = { success ->
                    scope.launch {
                        if (current() && !completed) {
                            completed = true
                            update { it.copy(previewExporting = false, notice = if (success && !share) "files.saved" else null,
                                error = if (!success) (if (share) "files.shareIncomplete" else "files.saveIncomplete") else null) }
                        }
                    }
                }
                if (share) platform.shareFile(file, result) else platform.saveFile(file, result)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (current()) { update { it.copy(previewExporting = false) }; error(failure) }
            }
        }
    }

    override fun pickFile(kind: String) {
        if (state.value.uploading || activeUploadVersion != null) return
        val navigation = navigationVersion
        val epoch = connectionVersion
        val selection = ++uploadVersion
        var delivered = false
        val picked: (List<PickedFile>) -> Unit = picked@ { files ->
            if (delivered || navigation != navigationVersion || epoch != connectionVersion || selection != uploadVersion) return@picked
            delivered = true
            if (files.isEmpty()) return@picked
            if (kind == "wallpaper") {
                val file = files.first()
                if (decodeImage(file.bytes) == null) { update { it.copy(error = "error.image") }; return@picked }
                platform.writePreference("wallpaper.v1", Base64.encode(file.bytes)); update { it.copy(customWallpaper = file.bytes) }
                return@picked
            }
            fun current() = navigation == navigationVersion && epoch == connectionVersion && selection == uploadVersion
            // Count and aggregate byte limits also apply to fallback/custom bridges.
            var byteCount = 0L
            val accepted = files.take(PickedFileLimits.MAX_SELECTION).filter { file ->
                val fits = file.bytes.isNotEmpty() && byteCount + file.bytes.size <= PickedFileLimits.MAX_BYTES
                if (fits) byteCount += file.bytes.size
                fits
            }
            if (accepted.isNotEmpty()) activeUploadVersion = selection
            update { it.copy(uploading = accepted.isNotEmpty(), error = if (accepted.size != files.size) "attachments.selectionLimit" else null) }
            run(isCurrent = ::current) {
                var failure: Exception? = null
                try {
                    val connection = requireApi()
                    for (file in accepted) {
                        if (!current()) break
                        try {
                            val attachment = connection.upload(file.name, file.bytes, file.mimeType)
                            if (!current()) break
                            if ((attachment.mimeType ?: file.mimeType).startsWith("image/")) cacheThumbnail(attachment.path, file.bytes, ::current)
                            if (!current()) break
                            update { it.copy(attachments = it.attachments.filterNot { existing -> existing.path == attachment.path } +
                                DraftAttachment(attachment.path, attachment.name ?: file.name, attachment.mimeType ?: file.mimeType, attachment.size ?: file.bytes.size.toLong())) }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (problem: Exception) { failure = problem }
                    }
                    if (current() && failure != null) error(failure)
                } finally {
                    if (activeUploadVersion == selection) activeUploadVersion = null
                    if (current()) update { it.copy(uploading = false) }
                }
            }
        }
        if (kind in setOf("photo", "document")) platform.pickFiles(kind, picked)
        else platform.pickFile(kind) { picked(listOfNotNull(it)) }
    }
    override fun startDictation() {
        val connection = api ?: return
        val version = ++recordingVersion
        update { it.copy(dictationOpen = true, recording = true, transcript = "", amplitude = 0f) }
        platform.startRecording(onAmplitude = { amplitude -> if (recordingVersion == version) update { it.copy(amplitude = amplitude) } }, onResult = { file ->
            if (recordingVersion != version) return@startRecording
            if (file == null) { update { it.copy(recording = false, transcribing = false, dictationOpen = false, error = "error.recording") }; return@startRecording }
            run(isCurrent = { recordingVersion == version }) {
                update { it.copy(recording = false, transcribing = true, amplitude = 0f) }
                try {
                    val result = connection.transcribe(file.name, file.bytes, file.mimeType)
                    if (recordingVersion == version) {
                        if (result.text.isBlank()) update { it.copy(transcribing = false, error = "error.emptyAudio") }
                        else { update { it.copy(transcript = result.text, transcribing = false) }; useTranscript() }
                    }
                } catch (failure: Exception) { if (recordingVersion == version) update { it.copy(transcribing = false) }; throw failure }
            }
        }, onPartial = { file ->
            if (recordingVersion == version && state.value.recording && partialAsr?.isActive != true) {
                partialAsr = scope.launch {
                    if (recordingVersion != version) return@launch
                    runCatching { connection.transcribe(file.name, file.bytes, file.mimeType, partial = true) }.getOrNull()?.let { result ->
                        if (recordingVersion == version && state.value.recording) update { it.copy(transcript = result.text) }
                    }
                }
            }
        })
    }
    override fun stopDictation() { partialAsr?.cancel(); update { it.copy(recording = false, transcribing = true) }; platform.stopRecording() }
    override fun cancelDictation() { recordingVersion++; partialAsr?.cancel(); platform.cancelRecording(); update { it.copy(dictationOpen = false, recording = false, transcribing = false, amplitude = 0f) } }
    override fun useTranscript() {
        val transcript = state.value.transcript
        if (transcript.isNotBlank()) draft(listOf(state.value.draft, transcript).filter { it.isNotBlank() }.joinToString(" "))
        update { it.copy(dictationOpen = false, transcript = "") }
    }

    override fun copyContent(text: String) {
        try { platform.copyText(text) }
        catch (_: Exception) {
            update { it.copy(error = "error.copyFailed", notice = it.notice.takeUnless { notice -> notice == "chat.copied" }) }
            return
        }
        feedback()
        update { it.copy(notice = "chat.copied", error = it.error.takeUnless { error -> error == "error.copyFailed" }) }
    }
    override fun shareContent(text: String) { platform.shareText(text) }
    override fun openLink(url: String) {
        try {
            val current = state.value
            val inWorkspacePreview = current.previewTitle != null && current.previewSource == "workspace"
            val target = fileLinkTarget(url, current.baseUrl,
                if (inWorkspacePreview) current.previewSessionId else current.sessionId,
                (current.previewWorkspacePath ?: current.previewPath).takeIf { inWorkspacePreview })
            if (target != null) {
                if (target.source == "workspace" && target.sessionId == current.sessionId && workFile(target.path)?.active == true) {
                    update { it.copy(error = "files.stillWriting") }; return
                }
                val known = attachment(target.path)
                preview(PreviewItem(target.path, known?.name ?: target.path.substringAfterLast('/'),
                    known?.mimeType ?: workspaceMimeType(target.path), target.source), target.sessionId, single = true)
            } else {
                val external = io.ktor.http.Url(url)
                if (external.protocol.name == "https" && external.user == null && external.password == null && url.none(Char::isISOControl))
                    platform.openExternalUrl(url)
            }
        } catch (failure: Exception) { error(failure) }
    }
    override fun copyMessage(id: String) { state.value.messages.firstOrNull { it.id == id }?.let { platform.copyText(it.text); update { current -> current.copy(notice = "chat.copied") } } }
    override fun shareMessage(id: String) { state.value.messages.firstOrNull { it.id == id }?.let { platform.shareText(it.text) } }
    override fun editMessage(id: String) {
        val message = state.value.messages.firstOrNull { it.id == id && it.role == "user" } ?: return
        beforeEdit = state.value.draft
        draft(message.text)
        update { it.copy(editingMessageId = id) }
    }
    override fun cancelEdit() { draft(beforeEdit.orEmpty()); beforeEdit = null; update { it.copy(editingMessageId = null) } }
    override fun regenerate(id: String) {
        val current = state.value
        if (current.sessionId in deletingChats) { update { it.copy(notice = "chat.deletePendingWork", noticeDetail = current.sessionId) }; return }
        val item = OutboxItem(platform.newId(), current.sessionId, "", submissionModel(current, forkAttachments(current, id)), current.effort, forkMessageId = id, forkAction = "regenerate")
        outbox.add(item); persistOutbox(); run { submit(item, true) }
    }
    override fun search(value: String) { update { it.copy(search = value) } }

    override fun openDirectory(path: String) {
        if (path.isNotEmpty()) try { checkedWorkspacePath(path) } catch (failure: ApiFailure) { error(failure); return }
        val closing = editorTarget()
        fileReturnScreen = null
        fileParents.clear()
        if (closing != null && closing in fileDrafts) run { saveFile(closing) }
        val connection = api ?: return
        val epoch = connectionVersion
        val version = ++directoryVersion
        fileReadVersion++
        update { it.copy(directory = path, files = emptyList(), openFile = null, fileSessionId = "",
            fileEditorGeneration = ++editorGeneration, fileConflict = null, fileLoadError = null, fileStorageError = false, fileLoading = false, loading = true) }
        run(isCurrent = { version == directoryVersion }) {
            val files = connection.listWorkspace(path).map { FileRow(it.path, it.name, it.isDirectory, it.size ?: 0) }
            if (epoch == connectionVersion && version == directoryVersion) update { it.copy(files = files, loading = false) }
        }
    }
    override fun browseFile(path: String) {
        try { checkedWorkspacePath(path) } catch (failure: ApiFailure) { error(failure); return }
        if (activelyWritten(FileTarget(path))) { update { it.copy(error = "files.stillWriting") }; return }
        preview(PreviewItem(path, path.substringAfterLast('/'), workspaceMimeType(path), "workspace"), sessionOverride = "", single = true)
    }
    override fun openFile(path: String) {
        fileParents.clear()
        openEditor(FileTarget(path))
    }
    /** Match the backend's session aliases for activity checks, never for transport. */
    private fun workspaceIdentity(target: FileTarget): String? {
        val resolved = knownFileTarget(target)
        if (!resolved.path.startsWith("session/")) return resolved.path
        val session = resolved.session.trim()
        val slug = when {
            session.isEmpty() -> "default"
            Regex("[A-Za-z0-9_-]{1,40}").matches(session) -> session
            else -> platform.sha256(session.encodeToByteArray())?.takeIf { SHA256_HEX.matches(it) }?.take(16) ?: return null
        }
        return "sessions/$slug/" + resolved.path.removePrefix("session/")
    }
    private fun sameWorkspaceFile(first: FileTarget, second: FileTarget): Boolean = first == second ||
        workspaceIdentity(first)?.let { it == workspaceIdentity(second) } == true
    private fun activelyWritten(target: FileTarget): Boolean = state.value.messages.asReversed().asSequence()
        .flatMap { it.workFiles.asSequence() }
        .firstOrNull { sameWorkspaceFile(target, FileTarget(it.path, state.value.sessionId)) }?.active == true

    /** Recovery belongs to a verified canonical path or a session-specific alias. */
    private fun knownFileTarget(target: FileTarget): FileTarget = fileAliases[target] ?: runCatching {
        readScoped(fileKey(target, "fileAlias"))?.let { FileTarget(checkedWorkspacePath(it), target.session) }
    }.getOrNull() ?: target

    private fun recoveredDraft(target: FileTarget): String? = fileDrafts[target] ?: runCatching {
        readScoped(fileKey(target, "file"))
    }.getOrNull()?.also { value ->
        fileDrafts[target] = value
        fileBaselines[target] = runCatching { readScoped(fileKey(target, "fileBaseline")) }.getOrNull().orEmpty()
    }

    /** A full disk never turns a keystroke into a lost in-memory draft. */
    private fun persistFile(target: FileTarget, content: String?, baseline: String?): Boolean = try {
        if (content == null) {
            writeScoped(fileKey(target, "file"), null)
            writeScoped(fileKey(target, "fileBaseline"), null)
        } else {
            writeScoped(fileKey(target, "fileBaseline"), baseline)
            writeScoped(fileKey(target, "file"), content)
        }
        if (editorTarget()?.let { sameWorkspaceFile(it, target) } == true) update { it.copy(fileStorageError = false) }
        true
    } catch (_: Exception) {
        if (editorTarget()?.let { sameWorkspaceFile(it, target) } == true) update {
            it.copy(error = "error.storage", fileStorageError = true, fileRecovery = true)
        }
        false
    }

    private fun openEditor(requested: FileTarget) {
        try { checkedWorkspacePath(requested.path) } catch (failure: ApiFailure) { error(failure); return }
        val target = knownFileTarget(requested)
        val initialRecoveryTarget = if (recoveredDraft(target) != null) target else requested
        val recovered = recoveredDraft(initialRecoveryTarget)
        val writing = activelyWritten(target)
        if (writing && recovered == null) { update { it.copy(error = "files.stillWriting") }; return }
        val connection = api ?: return
        val epoch = connectionVersion
        val readVersion = ++fileReadVersion
        val closing = editorTarget()
        if (closing != null && closing != target && closing in fileDrafts) run { saveFile(closing) }
        if (state.value.screen != Screen.Files) fileReturnScreen = state.value.screen
        update { it.copy(screen = Screen.Files, menuOpen = false, openFile = target.path, fileSessionId = target.session,
            fileEditorGeneration = ++editorGeneration, loading = recovered == null, fileLoading = recovered == null, fileText = recovered.orEmpty(),
            fileEditable = recovered != null, fileSaveState = if (recovered == null) "saved" else "failed",
            fileConflict = fileConflicts[target], fileRecovery = recovered != null, fileLoadError = null, fileStorageError = false,
            fileWriteActive = writing, fileExternallyChanged = writing, error = null) }
        if (recovered != null) persistFile(initialRecoveryTarget, recovered, fileBaselines[initialRecoveryTarget])
        if (writing) {
            filePausedForWrite += target
            return
        }
        scope.launch {
            try {
                val file = connection.readMobileWorkspace(target.path, target.session)
                if (epoch != connectionVersion || readVersion != fileReadVersion) return@launch
                val resolved = FileTarget(checkedWorkspacePath(file.path), target.session)
                file.revision?.let { fileRevisions[resolved] = it }
                // Read recovery again after transport: the owner may have typed while offline.
                val recoveryTarget = listOf(target, resolved, requested).distinct().firstOrNull { recoveredDraft(it) != null }
                val latest = recoveryTarget?.let { fileDrafts[it] }
                fileBaselines[resolved] = recoveryTarget?.let { fileBaselines[it] }.orEmpty().takeIf { latest != null } ?: file.content
                if (latest != null) {
                    fileDrafts[resolved] = latest
                    if (recoveryTarget != null && filePausedForWrite.remove(recoveryTarget)) filePausedForWrite += resolved
                    recoveryTarget?.let { fileEditVersions[resolved] = maxOf(fileEditVersions[resolved] ?: 0, fileEditVersions[it] ?: 0) }
                    if (recoveryTarget != resolved && persistFile(resolved, latest, fileBaselines[resolved])) {
                        recoveryTarget?.let {
                            fileDrafts.remove(it)
                            fileBaselines.remove(it)
                            persistFile(it, null, null)
                        }
                    }
                }
                if (requested != resolved) {
                    fileAliases[requested] = resolved
                    runCatching { writeScoped(fileKey(requested, "fileAlias"), resolved.path) }
                }
                val conflict = if (latest != null && (file.binary || file.tooLarge ||
                    fileBaselines[resolved] != file.content && latest != file.content)) {
                    FileConflict(latest, file.content.takeUnless { file.binary || file.tooLarge }, file.revision)
                } else null
                if (conflict != null) fileConflicts[resolved] = conflict else fileConflicts.remove(resolved)
                if (editorTarget() == target) update { it.copy(openFile = resolved.path, loading = false, fileLoading = false,
                    fileText = latest ?: file.content, fileEditable = latest != null || !file.binary && !file.tooLarge,
                    fileSaveState = if (latest != null) "failed" else "saved", fileConflict = conflict,
                    fileRecovery = latest != null, fileLoadError = when {
                        file.tooLarge -> "files.tooLarge"
                        file.binary -> "files.binaryUnavailable"
                        else -> null
                    }) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (epoch == connectionVersion && readVersion == fileReadVersion) update { it.copy(
                    loading = false, fileLoading = false, fileLoadError = errorKey(failure), fileSaveState = if (target in fileDrafts) "failed" else it.fileSaveState) }
            }
        }
    }
    override fun closeFile() {
        fileReadVersion++
        val target = editorTarget()
        val parent = fileParents.removeLastOrNull()
        if (parent != null) {
            openEditor(parent)
            return
        }
        val back = fileReturnScreen
        fileReturnScreen = null
        update { it.copy(openFile = null, fileSessionId = "", fileEditorGeneration = ++editorGeneration,
            fileConflict = null, fileLoadError = null, fileStorageError = false, fileLoading = false, screen = back ?: it.screen,
            loading = if (target != null) false else it.loading) }
        if (target != null && target in fileDrafts) run { saveFile(target) }
    }
    override fun editorChanged(id: String, content: String) {
        if (!scope.isActive || id != state.value.fileEditorGeneration.toString()) return
        changeFileText(content, preserveQueuedChange = true)
    }
    override fun copyEditorContent(id: String) {
        if (documentTarget(id) != editorTarget() || editorTarget() == null || id != state.value.fileEditorGeneration.toString()) return
        copyContent(state.value.fileText)
    }
    override fun previewEditedFile(id: String) {
        val target = editorTarget() ?: return
        if (id != state.value.fileEditorGeneration.toString() || state.value.fileLoading || state.value.fileWriteActive) return
        val epoch = connectionVersion
        scope.launch {
            if (target in fileDrafts) saveFile(target)
            if (epoch != connectionVersion || documentTarget(id) != target ||
                id != state.value.fileEditorGeneration.toString() || target in fileDrafts || target in fileConflicts ||
                state.value.fileSaveState != "saved" || fileBaselines[target] != state.value.fileText) return@launch
            preview(PreviewItem(target.path, target.path.substringAfterLast('/'), workspaceMimeType(target.path), "workspace"),
                sessionOverride = target.session, single = true)
        }
    }
    override fun fileText(value: String) = changeFileText(value, preserveQueuedChange = false)
    private fun changeFileText(value: String, preserveQueuedChange: Boolean) {
        if (!scope.isActive) return
        val target = editorTarget() ?: return
        val current = state.value
        val writing = current.fileWriteActive || activelyWritten(target)
        if (!current.fileEditable || writing && !preserveQueuedChange) return
        if (value == current.fileText) return
        if (value.length > 2_000_000 || value.encodeToByteArray().size > 2_000_000) {
            update { it.copy(error = "files.tooLarge") }; return
        }
        fileDrafts[target] = value
        fileEditVersions[target] = (fileEditVersions[target] ?: 0) + 1
        val conflict = fileConflicts[target]?.copy(localContent = value)
        if (conflict != null) fileConflicts[target] = conflict
        update { it.copy(fileText = value, fileSaveState = if (conflict == null && !writing) "saving" else "failed",
            fileConflict = conflict, fileRecovery = it.fileRecovery || writing) }
        persistFile(target, value, fileBaselines[target])
        fileDebounce?.cancel()
        if (writing) filePausedForWrite += target
        else if (conflict == null) fileDebounce = scope.launch { delay(650); run { saveFile(target) } }
    }

    private suspend fun latestFile(connection: BotApi, target: FileTarget): WorkspaceFile = try {
        connection.readMobileWorkspace(target.path, target.session)
    } catch (failure: ApiFailure) {
        if (failure.status != 404) throw failure
        WorkspaceFile(target.path, "", false, revision = "missing")
    }

    private fun retainConflict(target: FileTarget, content: String, remote: WorkspaceFile?) {
        val conflict = FileConflict(content, remote?.content?.takeUnless { remote.binary || remote.tooLarge || remote.revision == "missing" }, remote?.revision)
        fileConflicts[target] = conflict
        if (editorTarget() == target) update { it.copy(fileConflict = conflict, fileSaveState = "failed", fileRecovery = true) }
    }

    private suspend fun saveFile(target: FileTarget, keepLocalGeneration: Long? = null) {
        val connection = api ?: return
        val owner = accountScope
        val version = connectionVersion
        fileMutex.withLock {
            var replaceRemote = keepLocalGeneration != null
            if (replaceRemote && (editorTarget() != target || state.value.fileEditorGeneration != keepLocalGeneration)) return
            if (!replaceRemote && target in fileConflicts) return
            try {
                while (target in fileDrafts && owner == accountScope && version == connectionVersion) {
                    if (activelyWritten(target) || editorTarget() == target && state.value.fileWriteActive) return
                    val content = fileDrafts.getValue(target)
                    val remote = latestFile(connection, target)
                    if (owner != accountScope || version != connectionVersion) return
                    if (activelyWritten(target) || editorTarget() == target && state.value.fileWriteActive) return
                    if (replaceRemote && (editorTarget() != target || state.value.fileEditorGeneration != keepLocalGeneration)) return
                    if (remote.binary || remote.tooLarge || (!replaceRemote &&
                        (remote.revision == "missing" || fileBaselines[target] != remote.content && remote.content != content))) {
                        retainConflict(target, content, remote)
                        return
                    }
                    val revision = remote.revision ?: throw ApiFailure(501, "revision_unavailable")
                    val result = connection.writeMobileWorkspace(target.path, content, revision, target.session)
                    if (!result.ok) throw ApiFailure(500, "workspace_write_failed")
                    if (owner != accountScope || version != connectionVersion) return
                    replaceRemote = false
                    fileConflicts.remove(target)
                    result.revision?.let { fileRevisions[target] = it }
                    fileBaselines[target] = content
                    if (fileDrafts[target] == content) {
                        if (!persistFile(target, null, null)) {
                            if (editorTarget() == target) update { it.copy(fileSaveState = "failed") }
                            return
                        }
                        fileDrafts.remove(target)
                    } else persistFile(target, fileDrafts[target], content)
                }
                if (editorTarget() == target) update { it.copy(fileSaveState = "saved", fileConflict = null,
                    fileRecovery = false, fileLoadError = null, fileExternallyChanged = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (owner == accountScope && version == connectionVersion) {
                    if (failure is ApiFailure && failure.status == 409) {
                        val remote = try { latestFile(connection, target) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                        if (owner == accountScope && version == connectionVersion) fileDrafts[target]?.let { retainConflict(target, it, remote) }
                    } else if (editorTarget() == target) {
                        update { it.copy(fileSaveState = "failed", fileLoadError = errorKey(failure)) }
                    }
                }
            }
        }
    }
    override fun retryFileSave() { editorTarget()?.let { target ->
        if (target in fileConflicts) update { it.copy(error = "error.fileConflict") }
        else run { saveFile(target) }
    } }
    override fun reloadFile() { editorTarget()?.let { openEditor(it) } }

    /** This is the only reload action that discards local content, after an explicit tap. */
    override fun reloadRemoteFile() {
        val target = editorTarget() ?: return
        if (state.value.fileWriteActive || activelyWritten(target)) return
        val connection = api ?: return
        val epoch = connectionVersion
        val generation = state.value.fileEditorGeneration
        val editVersion = fileEditVersions[target] ?: 0
        fileReadVersion++
        fileDebounce?.cancel()
        scope.launch {
            fileMutex.withLock {
                try {
                    val remote = connection.readMobileWorkspace(target.path, target.session)
                    if (epoch != connectionVersion || editorTarget() != target || generation != state.value.fileEditorGeneration) return@withLock
                    if (state.value.fileWriteActive || activelyWritten(target)) return@withLock
                    if ((fileEditVersions[target] ?: 0) != editVersion) {
                        retainConflict(target, fileDrafts[target] ?: state.value.fileText, remote)
                        return@withLock
                    }
                    if (remote.binary || remote.tooLarge) {
                        update { it.copy(fileLoadError = if (remote.binary) "files.binaryUnavailable" else "files.tooLarge") }
                        return@withLock
                    }
                    if (!persistFile(target, null, null)) return@withLock
                    fileDrafts.remove(target)
                    fileConflicts.remove(target)
                    fileBaselines[target] = remote.content
                    remote.revision?.let { fileRevisions[target] = it }
                    update { it.copy(fileText = remote.content, fileEditorGeneration = ++editorGeneration,
                        fileEditable = true, fileSaveState = "saved", fileConflict = null, fileRecovery = false,
                        fileLoadError = null, fileExternallyChanged = false, error = null) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (epoch == connectionVersion && generation == state.value.fileEditorGeneration) update { it.copy(fileLoadError = errorKey(failure)) }
                }
            }
        }
    }
    override fun keepLocalFile() {
        val target = editorTarget() ?: return
        if (target !in fileConflicts || state.value.fileWriteActive) return
        val generation = state.value.fileEditorGeneration
        update { it.copy(fileSaveState = "saving", error = null) }
        run { saveFile(target, keepLocalGeneration = generation) }
    }

    private fun newFileName(value: String, kind: String): String {
        if (value.isBlank() || value != value.trim() || value.length > 180 || '/' in value || value.startsWith('.'))
            throw ApiFailure(0, "invalid_workspace_path")
        val name = when (kind) {
            "markdown" -> if (value.endsWith(".md", true) || value.endsWith(".markdown", true)) value else "$value.md"
            "drawing" -> if (value.endsWith(".excalidraw", true) || value.endsWith(".excalidraw.json", true)) value else "$value.excalidraw"
            else -> value
        }
        return checkedWorkspacePath(name)
    }
    private fun emptyDrawing() = """{"type":"excalidraw","version":2,"source":"blink","elements":[],"appState":{},"files":{}}"""

    private fun createWorkspaceFile(target: FileTarget, content: String, current: () -> Boolean, complete: (String?) -> Unit) {
        val connection = api ?: kotlin.run { complete(null); return }
        val epoch = connectionVersion
        if (state.value.fileCreating) { complete(null); return }
        update { it.copy(fileCreating = true, error = null) }
        scope.launch {
            var resultPath: String? = null
            try {
                val result = connection.writeMobileWorkspace(target.path, content, "missing", target.session)
                if (!result.ok) throw ApiFailure(500, "workspace_write_failed")
                resultPath = checkedWorkspacePath(result.path)
                if (epoch == connectionVersion && current() && resultPath.substringBeforeLast('/', "") == state.value.directory) {
                    val row = FileRow(resultPath, resultPath.substringAfterLast('/'), false, content.encodeToByteArray().size.toLong())
                    update { it.copy(files = (it.files.filterNot { file -> file.path == resultPath } + row)
                        .sortedWith(compareByDescending<FileRow> { file -> file.directory }.thenBy { file -> file.name.lowercase() })) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (epoch == connectionVersion && current()) update { it.copy(error = if (failure is ApiFailure && failure.status == 409) "files.alreadyExists" else errorKey(failure)) }
            } finally {
                if (epoch == connectionVersion) update { it.copy(fileCreating = false) }
                complete(resultPath.takeIf { epoch == connectionVersion && current() })
            }
        }
    }
    override fun createFile(name: String, kind: String) {
        if (kind !in setOf("markdown", "drawing")) return
        val folder = state.value.directory
        val directory = directoryVersion
        val navigation = navigationVersion
        val generation = state.value.fileEditorGeneration
        val screen = state.value.screen
        val path = try { checkedWorkspacePath(listOf(folder, newFileName(name, kind)).filter { it.isNotEmpty() }.joinToString("/")) }
            catch (_: ApiFailure) { update { it.copy(error = "files.invalidName") }; return }
        createWorkspaceFile(FileTarget(path), if (kind == "drawing") emptyDrawing() else "",
            current = { directory == directoryVersion && navigation == navigationVersion &&
                generation == state.value.fileEditorGeneration && screen == state.value.screen }) { created ->
            if (created != null) openFile(created)
        }
    }
    override fun saveFileCopy(name: String) {
        val target = editorTarget() ?: return
        val generation = state.value.fileEditorGeneration
        val path = try { checkedWorkspacePath(listOf(target.path.substringBeforeLast('/', ""), newFileName(name, "copy")).filter { it.isNotEmpty() }.joinToString("/")) }
            catch (_: ApiFailure) { update { it.copy(error = "files.invalidName") }; return }
        val content = state.value.fileText
        createWorkspaceFile(FileTarget(path, target.session), content,
            current = { generation == state.value.fileEditorGeneration && editorTarget() == target }) { created ->
            if (created != null) openEditor(FileTarget(created, target.session))
        }
    }

    private fun documentTarget(id: String): FileTarget? {
        if (!scope.isActive) return null
        val current = state.value
        return when {
            id == current.fileEditorGeneration.toString() -> editorTarget()
            id == "preview:${current.previewRevision}" && current.previewSource == "workspace" && current.previewTitle != null ->
                (current.previewWorkspacePath ?: current.previewPath)?.let { FileTarget(it, current.previewSessionId) }
            else -> null
        }
    }
    override fun saveEditorDrawing(id: String, content: String) {
        val target = documentTarget(id) ?: return
        if (workspaceFileKind(target.path) !in setOf("drawing", "mermaid") || activelyWritten(target) ||
            state.value.editorExporting || state.value.previewExporting ||
            id.startsWith("preview:") && state.value.previewWriteActive ||
            !id.startsWith("preview:") && state.value.fileWriteActive) return
        val prefix = "data:image/png;base64,"
        if (content.length > WorkspaceEditorMaxContentBytes || !content.startsWith(prefix)) {
            update { it.copy(error = "files.saveIncomplete") }; return
        }
        val encoded = content.removePrefix(prefix)
        val bytes = runCatching {
            require(encoded.isNotEmpty() && encoded.length % 4 == 0 && Regex("[A-Za-z0-9+/]+={0,2}").matches(encoded))
            Base64.decode(encoded).also { require(Base64.encode(it) == encoded) }
        }.getOrNull()
        if (bytes == null || bytes.size > WorkspaceEditorMaxContentBytes || !validDrawingPng(bytes)) {
            update { it.copy(error = "files.saveIncomplete") }; return
        }
        val name = target.path.substringAfterLast('/')
        val stem = if (name.endsWith(".excalidraw.json", ignoreCase = true)) name.dropLast(".excalidraw.json".length)
            else name.substringBeforeLast('.', name)
        val file = PickedFile(stem.ifBlank { "drawing" } + ".png", "image/png", bytes)
        val epoch = connectionVersion
        val owner = accountScope
        val export = ++drawingExportVersion
        var completed = false
        fun current() = scope.isActive && epoch == connectionVersion && owner == accountScope && export == drawingExportVersion
        update { it.copy(editorExporting = true, error = null) }
        val result: (Boolean) -> Unit = { success ->
            scope.launch {
                if (current() && !completed) {
                    completed = true
                    val sameDocument = documentTarget(id) == target
                    update { it.copy(editorExporting = false,
                        notice = if (sameDocument && success) "files.saved" else it.notice,
                        error = if (sameDocument && !success) "files.saveIncomplete" else it.error) }
                }
            }
        }
        try { platform.saveFile(file, result) }
        catch (_: Exception) { result(false) }
    }

    private fun validDrawingPng(bytes: ByteArray): Boolean {
        if (bytes.size < 33 || !bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))) return false
        fun unsignedInt(offset: Int): Long = (0..3).fold(0L) { value, at -> (value shl 8) or (bytes[offset + at].toLong() and 255) }
        return unsignedInt(8) == 13L && bytes.copyOfRange(12, 16).contentEquals(byteArrayOf(73, 72, 68, 82)) &&
            unsignedInt(16) in 1L..4096L && unsignedInt(20) in 1L..4096L
    }
    override suspend fun loadEditorResource(id: String, path: String): WebPreviewResource? = withContext(uiDispatcher) {
        val target = documentTarget(id) ?: return@withContext null
        val resource = runCatching { checkedWorkspacePath(path) }.getOrNull() ?: return@withContext null
        if (activelyWritten(FileTarget(resource, target.session))) return@withContext null
        val mime = workspaceMimeType(resource)
        val drawing = workspaceFileKind(resource) == "drawing"
        if (!drawing && mime !in setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/svg+xml", "image/avif", "image/x-icon")) return@withContext null
        val connection = api ?: return@withContext null
        val epoch = connectionVersion
        val previewLoad = previewLoadVersion.takeIf { id.startsWith("preview:") }
        val bytes = if (drawing) {
            val result = connection.readMobileWorkspace(resource, target.session)
            if (result.binary || result.tooLarge) return@withContext null
            result.content.encodeToByteArray()
        } else connection.downloadWorkspace(resource, target.session)
        if (bytes.size > 8 * 1024 * 1024) return@withContext null
        if (!scope.isActive || epoch != connectionVersion || api !== connection || documentTarget(id) != target ||
            previewLoad != null && previewLoad != previewLoadVersion || activelyWritten(FileTarget(resource, target.session))) return@withContext null
        WebPreviewResource(bytes, if (drawing) "application/json" else mime)
    }
    override fun openEditorLink(id: String, path: String) {
        val target = documentTarget(id) ?: return
        // The trusted editor resolves relative Markdown links before this boundary.
        val resolved = runCatching { checkedWorkspacePath(path) }.getOrNull()
        if (resolved == null) { update { it.copy(error = "files.invalidPath") }; return }
        if (activelyWritten(FileTarget(resolved, target.session))) { update { it.copy(error = "files.stillWriting") }; return }
        if (workspaceFileKind(resolved) == "drawing") {
            editorTarget()?.let { parent -> if (fileParents.size < 8 && parent.path != resolved) fileParents.addLast(parent) }
            if (state.value.previewTitle != null) closePreview()
            openEditor(FileTarget(resolved, target.session))
        } else preview(PreviewItem(resolved, resolved.substringAfterLast('/'), workspaceMimeType(resolved), "workspace"), target.session, single = true)
    }
    override fun createEditorDrawing(id: String, onCreated: (String?) -> Unit) {
        val target = documentTarget(id)
        if (target == null || editorTarget() != target || id != state.value.fileEditorGeneration.toString() ||
            !state.value.fileEditable || state.value.fileWriteActive || workspaceFileKind(target.path) != "markdown") {
            onCreated(null); return
        }
        val path = target.path.substringBeforeLast('.') + ".drawings/" + platform.newId() + ".excalidraw"
        val destination = runCatching { checkedWorkspacePath(path) }.getOrNull() ?: kotlin.run { onCreated(null); return }
        createWorkspaceFile(FileTarget(destination, target.session), emptyDrawing(),
            current = { documentTarget(id) == target && id == state.value.fileEditorGeneration.toString() }, complete = onCreated)
    }
    override fun convertEditorMermaid(id: String, content: String) {
        val target = documentTarget(id) ?: return
        if (activelyWritten(target) || workspaceFileKind(target.path) != "mermaid" || content.length > 2_000_000 || content.encodeToByteArray().size > 2_000_000) return
        val scene = runCatching { json.parseToJsonElement(content) as? JsonObject }.getOrNull() ?: return
        if ((scene["type"] as? JsonPrimitive)?.contentOrNull != "excalidraw" || scene["elements"] !is JsonArray) return
        val path = target.path.substringBeforeLast('.') + "-" + platform.newId() + ".excalidraw"
        createWorkspaceFile(FileTarget(checkedWorkspacePath(path), target.session), content,
            current = { documentTarget(id) == target }) { created ->
            if (created != null) {
                if (state.value.previewTitle != null) closePreview()
                openEditor(FileTarget(created, target.session))
            }
        }
    }
    override fun preferences(value: Preferences) {
        try { platform.writePreference("preferences.v1", json.encodeToString(value)); update { it.copy(preferences = value) } }
        catch (_: Exception) { update { it.copy(error = "error.storage") } }
    }
    override fun resetWallpaper() { platform.writePreference("wallpaper.v1", null); update { it.copy(customWallpaper = null) } }
    override fun requestNotifications() { platform.requestNotifications { allowed -> preferences(state.value.preferences.copy(notifications = allowed)) } }
    override fun profileName(value: String) { profileEditVersion++; update { it.copy(profileName = value) } }
    override fun profilePersona(value: String) { profileEditVersion++; update { it.copy(profilePersona = value) } }
    override fun saveProfile() {
        val connection = api ?: return
        val version = connectionVersion
        val name = state.value.profileName
        val persona = state.value.profilePersona
        run {
            val base = cachedProfile ?: connection.profile()
            if (version != connectionVersion) return@run
            val saved = connection.updateProfile(base.copy(name = name, personaCustom = persona))
            if (version != connectionVersion) return@run
            cachedProfile = saved
            update { it.copy(notice = "files.saved") }
        }
    }
    override fun disconnect() {
        connectionVersion++; navigationVersion++; recordingVersion++; previewVersion++; fileReadVersion++; directoryVersion++; pairingVersion++
        partialAsr?.cancel(); fileDebounce?.cancel(); platform.cancelRecording()
        watchers.values.forEach { it.cancel() }; watchers.clear(); api?.close(); api = null
        seenQuestions.clear()
        handledQuestions.clear()
        thumbnailLoads.values.forEach { it.cancel() }; thumbnailLoads.clear(); thumbnailMisses.clear(); reducedThumbnails.clear()
        presentationIds.clear(); replyImageCache.clear(); uploadVersion++; activeUploadVersion = null
        chatActionVersions.clear(); chatActionLocks.clear(); deletingChats.clear(); reactionLocks.clear(); reactionVersions.clear(); reactionBaselines.clear()
        sessionsVersion++; messageMutationVersion++
        fileConflicts.clear(); fileAliases.clear(); fileEditVersions.clear(); filePausedForWrite.clear(); fileParents.clear(); editorGeneration++; drawingExportVersion++
        outbox.clear(); permitted.clear(); outboxSnapshot = emptyList(); allowedSnapshot = emptySet(); fileDrafts.clear(); fileBaselines.clear(); fileRevisions.clear(); cursors.clear(); sessionStreamVersions.clear(); observedJobStates.clear(); reportedJobErrors.clear(); cachedProfile = null; catalogDefaultModel = ""; beforeEdit = null; fileReturnScreen = null
        platform.writeSecret("device_token", null); update { AppState(preferences = it.preferences, baseUrl = it.baseUrl, customWallpaper = it.customWallpaper, mediaGeneration = it.mediaGeneration + 1, fileEditorGeneration = editorGeneration, installedVersion = platform.appVersionName) }
    }
    override fun dismissNotice() { update { it.copy(error = null, notice = null, noticeDetail = null) } }
    override fun dismissUpdate() { update { it.copy(updatePromptOpen = false) } }
    override fun updateBeta(enabled: Boolean) { update { it.copy(updateBeta = enabled) }; platform.writePreference("update_beta", if (enabled) "1" else "0"); checkForUpdate() }
    override fun checkForUpdate() {
        if (state.value.updateChecking || state.value.updateInstalling) return
        val connection = api ?: return
        val version = connectionVersion
        update { it.copy(updateChecking = true, updateError = null) }
        run(isCurrent = { version == connectionVersion }) {
            try {
                val capabilities = connection.capabilities(platform.platformName, platform.appVersionCode, if (state.value.updateBeta) "beta" else "stable")
                val updateInfo = updateFromCapabilities(capabilities)
                val stableLatest = parseUpdate(capabilities["update_stable"]?.jsonObject, "stable")
                val betaLatest = parseUpdate(capabilities["update_beta"]?.jsonObject, "beta")
                if (version != connectionVersion) return@run
                update { it.copy(update = updateInfo, updateStable = stableLatest, updateBetaLatest = betaLatest,
                    updateChecking = false, updateStatus = updateStatus(capabilities, updateInfo),
                    updatePromptOpen = updateInfo != null && it.screen != Screen.Updates) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (version == connectionVersion) update { it.copy(updateChecking = false, updateError = "update.checkFailed") }
            }
        }
    }
    override fun installUpdate() {
        if (state.value.updateInstalling || state.value.updateChecking) return
        val available = state.value.update ?: return
        val target = if (platform.platformName == "ios") available.iosUrl ?: available.url else available.url
        if (target.isNullOrBlank()) { update { it.copy(updateError = "update.unavailable") }; return }
        if (platform.platformName == "ios") {
            platform.openExternalUrl(target)
            return
        }
        val connection = api ?: return
        val version = connectionVersion
        update { it.copy(updateInstalling = true, updateError = null) }
        run(isCurrent = { version == connectionVersion }) {
            try {
                val bytes = connection.downloadUpdate(target)
                if (version != connectionVersion || api !== connection) return@run
                if (bytes.isEmpty()) throw ApiFailure(0, "update.empty")
                if (!updateChecksumMatches(available.sha256, platform.sha256(bytes))) {
                    throw ApiFailure(0, "update.checksum")
                }
                val accepted = CompletableDeferred<Boolean>()
                platform.installPackage(PickedFile("ClaudeBot-${available.versionName}.apk", "application/vnd.android.package-archive", bytes)) { accepted.complete(it) }
                val opened = accepted.await()
                if (version == connectionVersion) update {
                    if (opened) it.copy(updatePromptOpen = false, updateStatus = "update.installerOpened")
                    else it.copy(updateError = "update.failed")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ApiFailure) {
                val code = if (failure.code == "update.empty" || failure.code == "update.checksum") failure.code else "update.failed"
                if (version == connectionVersion) update { it.copy(updateError = code) }
            } catch (_: Exception) {
                if (version == connectionVersion) update { it.copy(updateError = "update.failed") }
            } finally {
                if (version == connectionVersion) update { it.copy(updateInstalling = false) }
            }
        }
    }
    fun close() { platform.cancelRecording(); scope.cancel(); api?.close() }

    private fun <T> MutableList<T>.replaceAllInPlace(transform: (T) -> T) {
        indices.forEach { index -> this[index] = transform(this[index]) }
    }

    private companion object {
        val terminalStates = setOf("completed", "failed", "stopped", "interrupted", "unsupported", "cancelled")
        const val MAX_THUMBNAIL_BYTES = 8 * 1024 * 1024
        const val MAX_THUMBNAIL_CACHE_BYTES = 16L * 1024 * 1024
        const val MAX_THUMBNAILS = 16
    }
}
