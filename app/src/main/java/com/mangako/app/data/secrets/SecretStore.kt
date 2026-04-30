package com.mangako.app.data.secrets

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypts short secrets (API keys, tokens) at rest using an AES-GCM key that
 * lives in the Android Keystore — the private key material never leaves the
 * secure element / TEE, so an attacker with filesystem access to the app's
 * data directory cannot decrypt the stored ciphertext.
 *
 * The on-disk format is a single Base64 string containing:
 *   [12 bytes GCM IV] + [ciphertext || 16-byte GCM tag]
 *
 * Callers can persist this string in DataStore, SharedPreferences, or anywhere
 * else that accepts plain text. Decryption fails loud (returns empty) if the
 * Keystore key is missing or the blob is tampered with.
 */
@Singleton
class SecretStore @Inject constructor() {

    /** Encrypts [plaintext] and returns the Base64 envelope, or `""` for empty input. */
    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, orCreateKey())
        }
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ct.size).also {
            System.arraycopy(iv, 0, it, 0, iv.size)
            System.arraycopy(ct, 0, it, iv.size, ct.size)
        }
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /** Decrypts a Base64 envelope produced by [encrypt]. Returns `""` on any failure. */
    fun decrypt(envelope: String): String {
        if (envelope.isEmpty()) return ""
        return try {
            val combined = Base64.decode(envelope, Base64.NO_WRAP)
            if (combined.size < GCM_IV_LEN + GCM_TAG_LEN) return ""
            val iv = combined.copyOfRange(0, GCM_IV_LEN)
            val ct = combined.copyOfRange(GCM_IV_LEN, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, orCreateKey(), GCMParameterSpec(GCM_TAG_LEN * 8, iv))
            }
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Throwable) {
            // Corrupt blob, missing key, OS crypto error — fail closed so callers
            // don't leak plaintext by mistake.
            ""
        }
    }

    private fun orCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "mangako.secrets.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LEN = 12
        private const val GCM_TAG_LEN = 16
    }
}
