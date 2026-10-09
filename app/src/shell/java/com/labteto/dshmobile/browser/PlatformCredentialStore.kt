package com.labteto.dshmobile.browser

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Passwords are transient inputs/results; preferences contain only authenticated ciphertext. */
class PlatformCredentialStore internal constructor(
    context: Context,
    prefsName: String = "platform_credentials_v1",
    private val keyAlias: String = "han_platform_credentials_aes_v1",
) {
    private val preferences = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun save(origin: String, password: String): Boolean = synchronized(lock) {
        if (!validOrigin(origin) || password.isEmpty() || password.length > MAX_PASSWORD) return@synchronized false
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true)) // Keystore generates a fresh random IV.
            cipher.updateAAD(origin.toByteArray(Charsets.UTF_8))
            val ciphertext = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            val record = encode(cipher.iv) + "." + encode(ciphertext)
            preferences.edit().putString(origin, record).commit()
        } catch (_: Exception) { false }
    }

    fun read(origin: String): String? = synchronized(lock) {
        if (!validOrigin(origin)) return@synchronized null
        try {
            val record = preferences.getString(origin, null) ?: return@synchronized null
            if (record.length > MAX_RECORD) return@synchronized null
            val parts = record.split('.')
            if (parts.size != 2) return@synchronized null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            if (iv.size != 12 || ciphertext.size !in 16..(MAX_PASSWORD * 4 + 16)) return@synchronized null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, iv))
            cipher.updateAAD(origin.toByteArray(Charsets.UTF_8))
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8).takeIf { it.isNotEmpty() && it.length <= MAX_PASSWORD }
        } catch (_: Exception) { null } // Corrupt ciphertext, a lost key or authentication failure never exposes details.
    }

    /** Only explicit local logout invokes this; ordinary website changes retain other origins. */
    fun clear(): Boolean = synchronized(lock) {
        try {
            if (!preferences.edit().clear().commit()) return@synchronized false
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias)
            true
        } catch (_: Exception) { false }
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        check(create) { "Credential unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    private fun validOrigin(origin: String) = NavigationPolicy.origin(origin) == origin
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    companion object {
        const val MAX_PASSWORD = 4096
        private const val MAX_RECORD = 24000
        private val lock = Any()
    }
}
