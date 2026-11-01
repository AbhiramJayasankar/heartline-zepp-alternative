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

/** Only IV + authenticated ciphertext enter preferences; the wrapping key never leaves Android Keystore. */
class KeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("heartline_auth", Context.MODE_PRIVATE)

    fun save(key: ByteArray) = synchronized(lock) {
        require(key.size == 16) { "Watch auth key must contain exactly 16 bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey(create = true))
        val encrypted = cipher.doFinal(key)
        check(prefs.edit().putString("encrypted", Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).commit()) {
            "Unable to persist encrypted auth key"
        }
    }

    /** Null means absent, invalidated, or authenticated decryption failed. Never return corrupt key bytes. */
    fun load(): ByteArray? = synchronized(lock) {
        val encoded = prefs.getString("encrypted", null) ?: return@synchronized null
        try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            if (bytes.size != 12 + 16 + 16) return@synchronized null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val key = wrappingKey(create = false) ?: return@synchronized null
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.doFinal(bytes.copyOfRange(12, bytes.size)).takeIf { it.size == 16 }
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun clear() = synchronized(lock) {
        check(prefs.edit().remove("encrypted").commit()) { "Unable to remove encrypted auth key" }
        val store = platformStore()
        if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
    }

    private fun platformStore() = PlatformKeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun wrappingKey(create: Boolean): SecretKey? {
        val existing = platformStore().getKey(ALIAS, null) as? SecretKey
        if (existing != null || !create) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    companion object {
        private const val ALIAS = "heartline_auth"
        private val lock = Any()
        fun validateHex(hex: String): Boolean = Hex.isValidAuthKey(hex)
        fun parseHex(hex: String): ByteArray = Hex.authKey(hex)
    }
}
