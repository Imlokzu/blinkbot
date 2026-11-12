package me.waveio.blinkchat

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ProviderStorageTest {
    private fun fixture():Pair<ChatStorage,FailingBookPreferences> {
        val app=ApplicationProvider.getApplicationContext<Context>()
        val name="provider-storage-${UUID.randomUUID()}"
        var prefs:FailingBookPreferences?=null
        val context=object:ContextWrapper(app) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=File(app.cacheDir,name).apply { mkdirs() }
            override fun getSharedPreferences(key:String,mode:Int):SharedPreferences {
                return prefs ?: FailingBookPreferences(app.getSharedPreferences("$name-$key",mode)).also { prefs=it }
            }
        }
        return ChatStorage(context) to requireNotNull(prefs)
    }

    @Test fun legacyMigrationReencryptsKeyAndRetainsSettingsAndConversations() {
        val (storage,prefs)=fixture()
        val settings=ProviderSettings(chatModel="legacy-chat",systemPrompt="Keep it",toolsEnabled=false)
        storage.saveConnection(settings,"legacy-test-key")
        val oldCipher=prefs.getString("api_key",null)
        val history=listOf(StoredChat("history","Old chat","legacy-chat",1))
        storage.saveChats(history)
        val migrated=storage.loadProviderBook()
        assertEquals(ModelSelection(LEGACY_PROVIDER_ID,"legacy-chat"),migrated.defaultChat)
        assertEquals("Keep it",migrated.systemPrompt); assertFalse(migrated.toolsEnabled)
        assertEquals("legacy-test-key",storage.readProviderApiKey(LEGACY_PROVIDER_ID))
        assertEquals(settings,storage.loadSettings()); assertEquals(history,storage.loadChats())
        assertFalse(prefs.contains("api_key")); assertFalse(prefs.contains("provider.settings.v1"))
        assertNotEquals(oldCipher,prefs.getString("api.provider.legacy",null))
        assertFalse(prefs.getString("provider.book.v2","")!!.contains("legacy-test-key"))
    }

    @Test fun keyEntriesCannotBeSwappedBetweenProvidersOrLeakedAfterRemoval() {
        val (storage,prefs)=fixture()
        val book=storage.loadProviderBook()
        val other=book.providers.single().copy(id="other",name="Other")
        val both=book.copy(providers=book.providers+other)
        storage.saveProviderBook(both,mapOf(LEGACY_PROVIDER_ID to "first-private",other.id to "second-private"))
        val first=prefs.getString("api.provider.legacy",null)
        val second=prefs.getString("api.provider.other",null)
        prefs.edit().putString("api.provider.legacy",second).putString("api.provider.other",first).commit()
        assertNull(storage.readProviderApiKey(LEGACY_PROVIDER_ID)); assertNull(storage.readProviderApiKey(other.id))
        storage.saveProviderBook(both,mapOf(LEGACY_PROVIDER_ID to "first-private",other.id to "second-private"))
        storage.saveProviderBook(book)
        assertFalse(prefs.contains("api.provider.other")); assertNull(storage.readProviderApiKey(other.id))
        assertEquals("first-private",storage.readProviderApiKey(LEGACY_PROVIDER_ID))
    }

    @Test fun failedMigrationAndBookSaveKeepMatchingOldSettingsAndKeys() {
        val (storage,prefs)=fixture()
        val settings=ProviderSettings()
        storage.saveConnection(settings,"before-key")
        prefs.failNext=true
        assertThrows(ProviderFailure::class.java) { storage.loadProviderBook() }
        assertFalse(prefs.contains("provider.book.v2")); assertTrue(prefs.contains("api_key"))
        assertEquals("before-key",storage.readApiKey())
        val book=storage.loadProviderBook()
        val other=book.providers.single().copy(id="other",name="Other")
        val next=book.copy(providers=book.providers+other,defaultChat=ModelSelection(other.id,other.settings.chatModel))
        prefs.failNext=true
        assertThrows(ProviderFailure::class.java) { storage.saveProviderBook(next,mapOf(other.id to "new-key")) }
        assertEquals(book,storage.loadProviderBook()); assertNull(storage.readProviderApiKey(other.id))
        assertEquals("before-key",storage.readProviderApiKey(LEGACY_PROVIDER_ID))
    }

    @Test fun corruptBookFailsWithoutOverwritingItOrFallingBackToLegacyData() {
        val (storage,prefs)=fixture()
        storage.saveConnection(ProviderSettings(),"before-key")
        prefs.edit().putString("provider.book.v2","{broken-book").commit()
        assertThrows(ProviderFailure::class.java) { storage.loadProviderBook() }
        assertThrows(ProviderFailure::class.java) { storage.saveProviderBook(ProviderBook.legacy(ProviderSettings())) }
        assertEquals("{broken-book",prefs.getString("provider.book.v2",null))
        assertTrue(prefs.contains("api_key"))
    }

    @Test fun unreadableLegacyCredentialDoesNotBlockReplacingItOrDiscardItsCiphertext() {
        val (storage,prefs)=fixture()
        storage.saveSettings(ProviderSettings(chatModel="old-model"))
        prefs.edit().putString("api_key","corrupt-ciphertext").commit()
        val book=storage.loadProviderBook()
        assertEquals("old-model",book.defaultChat.modelId)
        assertNull(storage.readProviderApiKey(LEGACY_PROVIDER_ID))
        assertEquals("corrupt-ciphertext",prefs.getString("api_key",null))
        storage.saveProviderBook(book,mapOf(LEGACY_PROVIDER_ID to "replacement-key"))
        assertEquals("replacement-key",storage.readProviderApiKey(LEGACY_PROVIDER_ID))
    }
}

private class FailingBookPreferences(private val actual:SharedPreferences):SharedPreferences by actual {
    var failNext=false
    override fun edit():SharedPreferences.Editor {
        val editor=actual.edit()
        return object:SharedPreferences.Editor by editor {
            override fun putString(key:String,value:String?):SharedPreferences.Editor { editor.putString(key,value);return this }
            override fun remove(key:String):SharedPreferences.Editor { editor.remove(key);return this }
            override fun commit():Boolean {
                val success=editor.commit()
                if (failNext) { failNext=false;return false }
                return success
            }
        }
    }
}
