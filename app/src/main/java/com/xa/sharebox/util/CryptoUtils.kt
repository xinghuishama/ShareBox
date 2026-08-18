package com.xa.sharebox.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts/decrypts strings using Android Keystore-backed AES-GCM.
 * No external dependencies — uses only javax.crypto + AndroidKeyStore provider.
 *
 * Used to protect passwords stored in SharedPreferences.
 */
object CryptoUtils {

    private const val KEY_ALIAS = "sharebox_master_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_LENGTH = 12  // GCM standard IV length

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        // Key doesn't exist — generate it
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    /** Encrypts a plaintext string. Returns Base64(iv + ciphertext). Returns "" for empty input. */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            // Format: Base64(iv + ciphertext)
            Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            // Keystore failure — log warning, fall back to plaintext to avoid data loss
            android.util.Log.w("CryptoUtils", "encrypt failed, storing plaintext fallback", e)
            plain
        }
    }

    /** Decrypts a Base64(iv + ciphertext) string. Returns "" on failure or empty input. */
    fun decrypt(encrypted: String): String {
        if (encrypted.isEmpty()) return ""
        // If not Base64-encoded (legacy plaintext), return as-is for backward compat
        if (!isBase64(encrypted)) return encrypted
        return try {
            val data = Base64.decode(encrypted, Base64.NO_WRAP)
            if (data.size <= IV_LENGTH) return encrypted  // Too short, probably plaintext
            val iv = data.copyOfRange(0, IV_LENGTH)
            val ciphertext = data.copyOfRange(IV_LENGTH, data.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            // Decryption failed — could be legacy plaintext password
            encrypted
        }
    }

    private fun isBase64(s: String): Boolean {
        if (s.isEmpty()) return false
        // Base64 strings only contain [A-Za-z0-9+/=] and have no whitespace
        return s.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
    }
}
