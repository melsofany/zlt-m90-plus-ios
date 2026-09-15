package com.zltm90plus.app.data.session

import android.content.Context
import android.util.Base64
import com.zltm90plus.app.data.remote.SessionTokenStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the router session token only.
 *
 * Tokens and passwords never reach plain [android.content.SharedPreferences]: values are
 * encrypted with an AES-256 key held in the Android Keystore, which cannot be exported from
 * the device. Nothing here is ever written to Logcat.
 */
class SecureSessionStore(context: Context) : SessionTokenStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, encrypt(token)).apply()
    }

    override fun token(): String? = prefs.getString(KEY_TOKEN, null)?.let(::decrypt)

    override fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    fun isLoggedIn(): Boolean = token() != null

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    /** Returns null instead of throwing when a Keystore entry was invalidated by the OS. */
    private fun decrypt(encoded: String): String? = runCatching {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = payload.copyOfRange(0, IV_LENGTH)
        val body = payload.copyOfRange(IV_LENGTH, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(body))
    }.getOrNull()

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "zlt_secure_session"
        const val KEY_TOKEN = "session_token_enc"
        const val KEY_ALIAS = "zlt_session_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}

/** In-memory implementation used by unit tests and the demo mode. */
class InMemorySessionStore(initialToken: String? = null) : SessionTokenStore {
    private var value: String? = initialToken

    override fun saveToken(token: String) {
        value = token
    }

    override fun token(): String? = value

    override fun clear() {
        value = null
    }
}