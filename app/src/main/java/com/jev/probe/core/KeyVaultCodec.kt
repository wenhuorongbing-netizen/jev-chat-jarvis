package com.jev.probe.core

/**
 * Pure, Android-free decision logic for the versioned secret storage format
 * used by [KeyVault] and [Prefs]. Lives in its own file with zero Android
 * imports so JVM unit tests can exercise the full decision table (Keystore
 * itself is unavailable off-device).
 *
 * On-disk format: `v1:<base64>` — the prefix lets a future format rotation
 * (v2:) coexist with values written by this version.
 */
object KeyVaultCodec {

    const val PREFIX = "v1:"

    /** What a reader should do with a raw stored value. */
    enum class StoredAction {
        /** Nothing stored (null or blank) — the getter returns "". */
        EMPTY,

        /** `v1:` ciphertext — decrypt via [KeyVault]. */
        DECRYPT,

        /** Legacy pre-encryption plaintext — return as-is, lazily re-encrypt. */
        PLAINTEXT_MIGRATE,
    }

    /** True when [stored] carries the current version's ciphertext prefix. */
    fun isEncrypted(stored: String?): Boolean =
        stored?.startsWith(PREFIX) == true

    /**
     * True when [stored] is a non-blank legacy plaintext value that should be
     * lazily upgraded to ciphertext on read (or on the next write).
     */
    fun needsMigration(stored: String?): Boolean =
        !stored.isNullOrEmpty() && !isEncrypted(stored)

    /** Full decision table for a raw stored value. */
    fun classify(stored: String?): StoredAction = when {
        stored.isNullOrEmpty() -> StoredAction.EMPTY
        isEncrypted(stored) -> StoredAction.DECRYPT
        else -> StoredAction.PLAINTEXT_MIGRATE
    }
}
