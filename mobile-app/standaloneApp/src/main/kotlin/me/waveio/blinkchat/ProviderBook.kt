package me.waveio.blinkchat

import java.util.Base64
import kotlinx.serialization.Serializable

const val LEGACY_PROVIDER_ID = "legacy"

@Serializable
data class ProviderAccount(
    val id: String,
    val name: String,
    val settings: ProviderSettings = ProviderSettings(),
    val chatModels: List<String> = listOf(settings.chatModel),
    val imageModels: List<String> = listOf(settings.imageModel),
)

@Serializable
data class ModelSelection(val providerId: String, val modelId: String)

/** Model names are scoped to a provider, including when two services use the same ID. */
@Serializable
data class ProviderBook(
    val providers: List<ProviderAccount>,
    val defaultChat: ModelSelection,
    val defaultImage: ModelSelection,
    val systemPrompt: String = "",
    val toolsEnabled: Boolean = true,
) {
    fun account(id: String): ProviderAccount? = providers.firstOrNull { it.id == id }

    companion object {
        fun legacy(settings: ProviderSettings): ProviderBook {
            val host = runCatching { java.net.URI(settings.baseUrl).host }.getOrNull().orEmpty()
            val name = if (host == "token-plan.maas.qwencloudapi.com") "QwenCloud" else host.ifBlank { LEGACY_PROVIDER_ID }
            return ProviderBook(listOf(ProviderAccount(LEGACY_PROVIDER_ID, name, settings)),
                ModelSelection(LEGACY_PROVIDER_ID,settings.chatModel), ModelSelection(LEGACY_PROVIDER_ID,settings.imageModel),
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
    if (book.providers.isEmpty() || book.providers.size > 30 || book.providers.map { it.id }.distinct().size != book.providers.size)
        throw ProviderFailure("standalone.error.settings")
    for (account in book.providers) {
        if (!account.id.matches(Regex("[a-zA-Z0-9_-]{1,80}")) || account.name.isBlank() || account.name.length > 80 || account.name.any(Char::isISOControl))
            throw ProviderFailure("standalone.error.settings")
        ProviderClient.validateSettings(account.settings)
        for (models in listOf(account.chatModels,account.imageModels)) {
            if (models.isEmpty() || models.size > 512 || models.any { it.isBlank() || it.length > 256 || it.any(Char::isISOControl) })
                throw ProviderFailure("standalone.error.settings")
        }
        if (account.settings.chatModel !in account.chatModels || account.settings.imageModel !in account.imageModels)
            throw ProviderFailure("standalone.error.settings")
    }
    val chat = book.account(book.defaultChat.providerId) ?: throw ProviderFailure("standalone.error.settings")
    val image = book.account(book.defaultImage.providerId) ?: throw ProviderFailure("standalone.error.settings")
    if (book.defaultChat.modelId !in chat.chatModels || book.defaultImage.modelId !in image.imageModels)
        throw ProviderFailure("standalone.error.settings")
}
