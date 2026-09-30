package com.jev.probe.jev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S0-D：接口报错正文里回显的 key 不能进入提示文案（也就进不了日志/崩溃文件）。 */
class RedactTest {

    // synthetic markers only — never a real credential
    private val fakeKey = "FAKEKEY-0123456789abcdef"

    @Test
    fun `the literal key in use is masked wherever it appears`() {
        val body = """{"error":{"message":"Incorrect API key provided: $fakeKey"}}"""
        val out = Redact.secrets(body, fakeKey)
        assertFalse(out.contains(fakeKey))
        assertTrue(out.contains("***"))
    }

    @Test
    fun `key-shaped tokens are masked even when they are not the key in use`() {
        val out = Redact.secrets("bad key sk-or-v1-abcdef123456 and Bearer abcdef1234567890")
        assertFalse(out.contains("abcdef123456"))
        assertFalse(out.contains("abcdef1234567890"))
    }

    @Test
    fun `plain error text passes through untouched`() {
        assertEquals("模型不存在", Redact.secrets("模型不存在", fakeKey))
        assertEquals("rate limited", Redact.secrets("rate limited"))
    }

    @Test
    fun `ApiException message never carries the key echoed by the server`() {
        val e = ApiException(Route.REPLY, 401, Redact.secrets("invalid key $fakeKey", fakeKey))
        assertFalse(e.message!!.contains(fakeKey))
        assertFalse(ApiException(Route.REPLY, 401, "invalid key sk-abcdef123456").message!!.contains("abcdef123456"))
    }

    @Test
    fun `a key cut off by the 120 char limit is masked before the cut`() {
        val padded = "x".repeat(100) + " sk-abcdefghijklmnop"
        val msg = ApiException(Route.REPLY, 400, padded).message!!
        assertFalse(msg.contains("sk-abcd"))
    }
}
