package uk.co.ashunt.carmonitor

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts secrets (MQTT password, HA token) with an AES-256-GCM key held in the Android
 * Keystore. The key never leaves the keystore. Stored form: base64(iv || ciphertext).
 * The app also sets allowBackup=false, so secrets aren't copied off the phone.
 */
object SecretStore {
    private const val ALIAS = "carmonitor-secrets"
    private const val KS = "AndroidKeyStore"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KS).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** Returns "" if [stored] is empty or can't be decrypted (e.g. after a key reset). */
    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        return try {
            val all = Base64.decode(stored, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
            String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }
}
