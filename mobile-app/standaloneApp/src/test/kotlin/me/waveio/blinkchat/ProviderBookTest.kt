package me.waveio.blinkchat

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ProviderBookTest {
    @Test fun sameModelFromTwoProvidersHasDistinctPickerIdentity() {
        val first = ModelSelection("first","same/model:with:colons")
        val second = first.copy(providerId="second")
        assertNotEquals(first.pickerId(),second.pickerId())
        assertEquals(first to false,parseModelSelection(first.pickerId()))
        assertEquals(second to true,parseModelSelection(second.pickerId(true)))
        assertNotEquals(first.pickerId(),first.pickerId(true))
        assertNull(parseModelSelection("image:raw-model"))
        assertNull(parseModelSelection("chat:%%%:invalid"))
    }

    @Test fun oldConversationsDecodeIntoTheOriginalProvider() {
        val old="""{"id":"chat","title":"History","model":"old-model","updatedAt":1,"messages":[{"id":"a","role":"assistant","text":"Saved"}]}"""
        val chat=Json.decodeFromString<StoredChat>(old)
        assertEquals(LEGACY_PROVIDER_ID,chat.providerId)
        assertEquals(LEGACY_PROVIDER_ID,chat.imageProviderId)
        assertEquals(LEGACY_PROVIDER_ID,chat.messages.single().providerId)
    }

    @Test fun defaultsMustResolveToTheirOwnProviderModelList() {
        val book=ProviderBook.legacy(ProviderSettings())
        validateProviderBook(book)
        val other=book.providers.single().copy(id="second",settings=ProviderSettings(chatModel="another-model"),chatModels=listOf("another-model"))
        val changed=book.copy(providers=book.providers+other,defaultChat=ModelSelection(other.id,"another-model"))
        validateProviderBook(changed)
        listOf(changed.copy(defaultChat=ModelSelection("missing","another-model")),
            changed.copy(defaultChat=ModelSelection(other.id,"not-offered")),
            changed.copy(providers=changed.providers+other)).forEach {
            assertThrows(ProviderFailure::class.java) { validateProviderBook(it) }
        }
    }

    @Test fun providerBookRoundTripKeepsDefaultsAndContainsNoKeyFields() {
        val book=ProviderBook.legacy(ProviderSettings(systemPrompt="Stay concise",toolsEnabled=false))
        val raw=Json.encodeToString(book)
        assertEquals(book,Json.decodeFromString<ProviderBook>(raw))
        assertFalse(raw.contains("apiKey")); assertFalse(raw.contains("api_key"))
        assertEquals("Stay concise",book.systemPrompt); assertFalse(book.toolsEnabled)
    }
}
