package me.waveio.claudebot.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import me.waveio.claudebot.R

/** Only ciphertext leaves the Keystore; binding the entry name prevents swaps. */
internal class AndroidSecureStorage(
    private val context: Context,
    private val alias: String = "me.waveio.claudebot.tokens.v1",
    preferencesName: String = "native-secrets",
) {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun read(key: String): String? = synchronized(storageLock) {
        try {
            val encoded = preferences.getString(key, null) ?: return@synchronized null
            val data = Base64.decode(encoded, Base64.NO_WRAP)
            if (data.size < 12 + 16) return@synchronized null
            val existing = keyStore().getKey(alias, null) as? SecretKey ?: return@synchronized null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, existing, GCMParameterSpec(128, data.copyOfRange(0, 12)))
            cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
            cipher.doFinal(data, 12, data.size - 12).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            // Corrupt preferences, missing keys, and unavailable Keystore fail closed.
            null
        }
    }

    fun write(key: String, value: String?) = synchronized(storageLock) {
        try { writeEntry(key, value) } catch (error: Exception) {
            throw IllegalStateException(context.getString(R.string.native_storage_error), error)
        }
    }

    private fun writeEntry(key: String, value: String?) {
        val edit = preferences.edit()
        if (value == null) {
            edit.remove(key)
        } else {
            val store = keyStore()
            val secretKey = store.getKey(alias, null) as? SecretKey ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build())
                generateKey()
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            check(cipher.iv.size == 12)
            cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
            edit.putString(key, Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
        }
        check(edit.commit()) { context.getString(R.string.native_storage_error) }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private companion object {
        // Key creation must be serialized across Activity/storage instances.
        val storageLock = Any()
    }
}
