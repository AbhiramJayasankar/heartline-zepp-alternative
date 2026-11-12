package dev.heartline.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore as PlatformKeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A few secret strings (a sign-in token, say), each encrypted with a key that never leaves Android Keystore, as
 * [KeyStore] does for the watch's auth key. Not in the database, so never in an export or backup of it.
 */
class SecretStore(context: Context, private val name: String) {
    private val prefs = context.applicationContext.getSharedPreferences("heartline_secret_$name", Context.MODE_PRIVATE)
    private val alias = "heartline_secret_$name"

    fun save(value: String) = synchronized(lock) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey(create = true))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("encrypted", Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).commit()) { "Unable to save $name" }
    }

    /** Null when nothing is saved or it can no longer be decrypted. */
    fun load(): String? = synchronized(lock) {
        val encoded = prefs.getString("encrypted", null) ?: return@synchronized null
        try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            if (bytes.size <= 12 + 16) return@synchronized null
            val key = wrappingKey(create = false) ?: return@synchronized null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: GeneralSecurityException) { null } catch (_: IllegalArgumentException) { null }
    }

    fun clear() = synchronized(lock) {
        prefs.edit().remove("encrypted").commit()
        val store = PlatformKeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    private fun wrappingKey(create: Boolean): SecretKey? {
        val existing = PlatformKeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null) as? SecretKey
        if (existing != null || !create) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    private companion object { val lock = Any() }
}
