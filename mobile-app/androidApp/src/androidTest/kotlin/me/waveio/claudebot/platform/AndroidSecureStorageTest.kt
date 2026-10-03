package me.waveio.claudebot.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import me.waveio.claudebot.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the real Android Keystore, isolated from the app's keys and preferences. */
@RunWith(AndroidJUnit4::class)
class AndroidSecureStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "me.waveio.claudebot.test.${UUID.randomUUID()}"
    private val preferencesName = "native-secrets-test-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private lateinit var storage: AndroidSecureStorage

    @Before fun setUp() { storage = AndroidSecureStorage(context, alias, preferencesName) }

    @After fun tearDown() {
        try { context.deleteSharedPreferences(preferencesName) } finally { keyStore().deleteEntry(alias) }
    }

    @Test fun realKeyIsNonExportableAndValuesSurviveNewStorageInstance() {
        storage.write("token", "test-value")
        assertEquals("test-value", AndroidSecureStorage(context, alias, preferencesName).read("token"))
        assertNull((keyStore().getKey(alias, null) as SecretKey).encoded)
        assertNotEquals("test-value", preferences.getString("token", null))
        storage.write("token", null)
        assertNull(storage.read("token"))
    }

    @Test fun encryptionUsesFreshIvForEachWrite() {
        storage.write("token", "test-value")
        val first = Base64.decode(preferences.getString("token", null), Base64.NO_WRAP)
        storage.write("token", "test-value")
        val second = Base64.decode(preferences.getString("token", null), Base64.NO_WRAP)
        assertFalse(first.copyOfRange(0, 12).contentEquals(second.copyOfRange(0, 12)))
        assertEquals("test-value", storage.read("token"))
    }

    @Test fun entryNamesAreAuthenticated() {
        storage.write("first", "test-first")
        assertTrue(preferences.edit().putString("second", preferences.getString("first", null)).commit())
        assertNull(storage.read("second"))
        assertEquals("test-first", storage.read("first"))
    }

    @Test fun tamperedCiphertextIsRejected() {
        storage.write("token", "test-value")
        val bytes = Base64.decode(preferences.getString("token", null), Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertTrue(preferences.edit().putString("token", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit())
        assertNull(storage.read("token"))
    }

    @Test fun corruptPreferenceTypeAndEncodingFailClosed() {
        assertTrue(preferences.edit().putInt("token", 123).commit())
        assertNull(storage.read("token"))
        assertTrue(preferences.edit().putString("token", "!").commit())
        assertNull(storage.read("token"))
        assertTrue(preferences.edit().putString("token", Base64.encodeToString(ByteArray(27), Base64.NO_WRAP)).commit())
        assertNull(storage.read("token"))
    }

    @Test fun missingOrLostKeyDoesNotCreateKeyOnRead() {
        assertNull(storage.read("token"))
        assertFalse(keyStore().containsAlias(alias))
        storage.write("token", "test-value")
        keyStore().deleteEntry(alias)
        assertNull(storage.read("token"))
        assertFalse(keyStore().containsAlias(alias))
        storage.write("token", "replacement")
        assertEquals("replacement", storage.read("token"))
    }

    @Test fun concurrentInstancesDoNotReplaceEachOthersKey() {
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val futures = (0..1).map { index ->
                executor.submit {
                    val instance = AndroidSecureStorage(context, alias, preferencesName)
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    instance.write("entry-$index", "test-value-$index")
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            (0..1).forEach { assertEquals("test-value-$it", storage.read("entry-$it")) }
        } finally { start.countDown(); executor.shutdownNow() }
    }

    @Test fun keystoreWriteFailureIsLocalized() {
        // A real decrypt-only key cannot be used for encryption.
        KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
        val error = assertThrows(IllegalStateException::class.java) { storage.write("token", "test-value") }
        assertEquals(context.getString(R.string.native_storage_error), error.message)
        assertNotNull(error.cause)
        assertFalse(preferences.contains("token"))
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
