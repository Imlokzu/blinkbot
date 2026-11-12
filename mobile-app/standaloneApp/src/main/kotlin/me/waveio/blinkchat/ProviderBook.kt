package me.waveio.blinkchat

import java.util.Base64
import kotlinx.serialization.Serializable

const val LEGACY_PROVIDER_ID = "legacy"

@Serializable
data class ProviderAccount(
    val id: String,
    val name: String,
    val settings: ProviderSettings = ProviderSettings(),
    val chatModels: List<String> = listOfNotNull(settings.chatModel.takeIf(String::isNotBlank)),
    val imageModels: List<String> = listOfNotNull(settings.imageModel.takeIf { it.isNotBlank() && settings.imageProtocol != ImageLayer.NONE }),
    /** Catalog metadata keyed by ID. A manual ID without an entry simply has no metadata. */
    val models: List<ModelInfo> = emptyList(),
) {
    fun info(modelId: String): ModelInfo? = models.firstOrNull { it.id == modelId }
    val chats: Boolean get() = chatModels.isNotEmpty()
    val images: Boolean get() = imageModels.isNotEmpty()
}

@Serializable
data class ModelSelection(val providerId: String, val modelId: String)

/** Model names are scoped to a provider, including when two services use the same ID. */
@Serializable
data class ProviderBook(
    val providers: List<ProviderAccount>,
    val defaultChat: ModelSelection,
    /** Null when no saved provider generates images. */
    val defaultImage: ModelSelection? = null,
    val systemPrompt: String = "",
    val toolsEnabled: Boolean = true,
) {
    fun account(id: String): ProviderAccount? = providers.firstOrNull { it.id == id }

    companion object {
        fun legacy(settings: ProviderSettings): ProviderBook {
            val host = runCatching { java.net.URI(settings.baseUrl).host }.getOrNull().orEmpty()
            val name = if (host == "token-plan.maas.qwencloudapi.com") "QwenCloud" else host.ifBlank { LEGACY_PROVIDER_ID }
            val account = ProviderAccount(LEGACY_PROVIDER_ID, name, settings)
            return ProviderBook(listOf(account),
                ModelSelection(LEGACY_PROVIDER_ID,settings.chatModel),
                if (account.images) ModelSelection(LEGACY_PROVIDER_ID,settings.imageModel) else null,
                settings.systemPrompt,settings.toolsEnabled)
        }
    }
}

fun ModelSelection.pickerId(image: Boolean = false): String {
    fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    return (if (image) "image:" else "chat:") + encode(providerId) + ":" + encode(modelId)
}

fun parseModelSelection(id: String): Pair<ModelSelection, Boolean>? = runCatching {
    val parts = id.split(':')
    require(parts.size == 3 && parts[0] in listOf("image","chat"))
    fun decode(value: String) = Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8)
    ModelSelection(decode(parts[1]),decode(parts[2])) to (parts[0] == "image")
}.getOrNull()

fun validateProviderBook(book: ProviderBook) {
    fun bad(): Nothing = throw ProviderFailure("standalone.error.settings")
    if (book.providers.isEmpty() || book.providers.size > 30 || book.providers.map { it.id }.distinct().size != book.providers.size) bad()
    for (account in book.providers) {
        if (!account.id.matches(Regex("[a-zA-Z0-9_-]{1,80}")) || account.name.isBlank() || account.name.length > 80 || account.name.any(Char::isISOControl)) bad()
        ProviderClient.validateSettings(account.settings)
        // A provider may serve chat, images or both, but not nothing.
        if (account.chatModels.isEmpty() && account.imageModels.isEmpty()) bad()
        for (models in listOf(account.chatModels,account.imageModels)) {
            if (models.size > 512 || models.any { it.isBlank() || it.length > 256 || it.any(Char::isISOControl) }) bad()
        }
        if (account.models.size > 1024 || account.models.map { it.id }.distinct().size != account.models.size) bad()
        if (account.models.any { m -> m.id.isBlank() || m.id.length > 256 || m.label.length > 120 || m.efforts.any { it !in KNOWN_EFFORTS } }) bad()
        val chatModel = account.settings.chatModel
        if (if (account.chats) chatModel !in account.chatModels else chatModel.isNotBlank()) bad()
        val imageModel = account.settings.imageModel
        if (account.images) { if (imageModel !in account.imageModels || account.settings.imageProtocol == ImageLayer.NONE) bad() }
        else if (account.settings.imageProtocol != ImageLayer.NONE) bad()
    }
    val chat = book.account(book.defaultChat.providerId) ?: bad()
    if (book.defaultChat.modelId !in chat.chatModels) bad()
    book.defaultImage?.let { selection ->
        val image = book.account(selection.providerId) ?: bad()
        if (selection.modelId !in image.imageModels) bad()
    }
}
