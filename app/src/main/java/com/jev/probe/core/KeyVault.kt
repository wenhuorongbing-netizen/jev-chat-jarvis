package com.jev.probe.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore-backed AES/GCM vault for the three API keys. A single
 * app-managed key (alias [ALIAS]) is generated on first use and never leaves
 * the hardware-backed Keystore; only the ciphertext is written to
 * SharedPreferences, as `v1:<base64(IV || ciphertext)>` ([KeyVaultCodec]).
 *
 * minSdk is 30, so this hand-rolls the ~80 lines instead of pulling in the
 * deprecated androidx security-crypto library.
 *
 * Privacy: no log line on this path ever contains plaintext or ciphertext —
 * lengths only.
 *
 * Keystore is unavailable to JVM unit tests, so the encrypt/decrypt round
 * trip (including key generation, GCM tag verification, and the
 * decrypt-failure-returns-empty branch after the key is wiped) has been
 * self-tested on a real device by the integrator; the pure storage-format
 * decisions are covered by KeyVaultCodecTest on the JVM.
 */
object KeyVault {

    private const val TAG = "JEVASSIST"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "jev_key_vault_v1"
    private const val IV_LEN = 12        // 96-bit GCM nonce
    private const val TAG_LEN = 128      // GCM auth tag bits

    /** Lazy: the Keystore is only touched on first encrypt/decrypt, never at load. */
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    private fun cipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding")

    private fun secretKey(): SecretKey {
        if (!keyStore.containsAlias(ALIAS)) {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            gen.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(128)
                    .build(),
            )
            gen.generateKey()
        }
        return keyStore.getKey(ALIAS, null) as SecretKey
    }

    /**
     * Encrypts [plain] and returns `v1:<base64(IV || ciphertext)>`.
     * Throws on Keystore failure — the caller decides whether to surface or
     * swallow it (a setter propagates; a lazy migration write-back does not).
     */
    fun encrypt(plain: String): String {
        val c = cipher()
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        val blob = ByteArray(IV_LEN + ct.size)
        System.arraycopy(c.iv, 0, blob, 0, IV_LEN)
        System.arraycopy(ct, 0, blob, IV_LEN, ct.size)
        return KeyVaultCodec.PREFIX + Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    /**
     * Inverse of [encrypt] for a `v1:` value. Any failure (Keystore gone,
     * key wiped by the system, corrupted blob, bad GCM tag) returns "" so the
     * app falls back to "no key configured" instead of crashing; the log
     * carries only the stored length.
     */
    fun decrypt(stored: String): String = try {
        val blob = Base64.decode(stored.removePrefix(KeyVaultCodec.PREFIX), Base64.NO_WRAP)
        val c = cipher()
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LEN, blob, 0, IV_LEN))
        String(c.doFinal(blob, IV_LEN, blob.size - IV_LEN), Charsets.UTF_8)
    } catch (e: Exception) {
        Log.w(TAG, "keyVault decrypt failed stored.len=${stored.length} cause=${e.javaClass.simpleName}")
        ""
    }
}
