package com.jev.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [KeyVaultCodec]'s pure storage-format decision table — the
 * part of the key-encryption scheme that does not need the Android Keystore.
 * The actual AES/GCM round trip in [KeyVault] runs on-device only and was
 * self-tested on real hardware by the integrator (see KeyVault.kt's KDoc).
 */
class KeyVaultCodecTest {

    // ------------------------------------------------------------ classify

    @Test
    fun `classify null stored is EMPTY`() {
        assertEquals(KeyVaultCodec.StoredAction.EMPTY, KeyVaultCodec.classify(null))
    }

    @Test
    fun `classify blank stored is EMPTY`() {
        assertEquals(KeyVaultCodec.StoredAction.EMPTY, KeyVaultCodec.classify(""))
    }

    @Test
    fun `classify v1 prefixed stored is DECRYPT`() {
        assertEquals(
            KeyVaultCodec.StoredAction.DECRYPT,
            KeyVaultCodec.classify("v1:QUJDQUJD"),
        )
    }

    @Test
    fun `classify bare v1 prefix is DECRYPT and decrypt yields empty on bad payload`() {
        // "v1:" with no payload: format routes to DECRYPT; KeyVault.decrypt
        // then fails to decode/decrypt and returns "" (on-device behavior).
        assertEquals(KeyVaultCodec.StoredAction.DECRYPT, KeyVaultCodec.classify("v1:"))
    }

    @Test
    fun `classify legacy plaintext is PLAINTEXT_MIGRATE`() {
        assertEquals(
            KeyVaultCodec.StoredAction.PLAINTEXT_MIGRATE,
            KeyVaultCodec.classify("sk-or-v1-abc123"),
        )
    }

    @Test
    fun `classify v1 lookalike without colon is PLAINTEXT_MIGRATE`() {
        // A legacy key that merely starts with "v1" must not be misread as
        // ciphertext — only the exact "v1:" prefix counts.
        assertEquals(
            KeyVaultCodec.StoredAction.PLAINTEXT_MIGRATE,
            KeyVaultCodec.classify("v1abc"),
        )
    }

    // ---------------------------------------------------------- isEncrypted

    @Test
    fun `isEncrypted true only for v1 prefix`() {
        assertTrue(KeyVaultCodec.isEncrypted("v1:AAAA"))
        assertTrue(KeyVaultCodec.isEncrypted("v1:"))
        assertFalse(KeyVaultCodec.isEncrypted("sk-or-v1-abc"))
        assertFalse(KeyVaultCodec.isEncrypted(""))
        assertFalse(KeyVaultCodec.isEncrypted(null))
    }

    // -------------------------------------------------------- needsMigration

    @Test
    fun `needsMigration true only for non-blank legacy plaintext`() {
        assertTrue(KeyVaultCodec.needsMigration("sk-or-v1-abc123"))
        assertFalse(KeyVaultCodec.needsMigration("v1:QUJD"))
        assertFalse(KeyVaultCodec.needsMigration(""))
        assertFalse(KeyVaultCodec.needsMigration(null))
    }

    // -------------------------------------------------------------- prefix

    @Test
    fun `prefix is the documented v1 scheme`() {
        assertEquals("v1:", KeyVaultCodec.PREFIX)
    }
}
