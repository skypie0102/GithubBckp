package com.skypie0102.githubbckp.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun put(key: String, value: String?) {
        if (value == null) {
            remove(key)
            return
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))

        preferences.edit()
            .putString(valueKey(key), Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(ivKey(key), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun get(key: String): String? {
        val encodedValue = preferences.getString(valueKey(key), null) ?: return null
        val encodedIv = preferences.getString(ivKey(key), null) ?: return null

        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = Base64.decode(encodedIv, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            val decrypted = cipher.doFinal(Base64.decode(encodedValue, Base64.NO_WRAP))
            String(decrypted, StandardCharsets.UTF_8)
        }.getOrNull()
    }

    fun remove(key: String) {
        preferences.edit()
            .remove(valueKey(key))
            .remove(ivKey(key))
            .apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun valueKey(key: String) = "$key.value"
    private fun ivKey(key: String) = "$key.iv"

    private companion object {
        const val PREFERENCES_NAME = "github-backup-secure"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "github-backup-auth-key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
