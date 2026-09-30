package com.jev.probe.jev

import com.jev.probe.core.VisionRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 视觉路线默认值：与回复同源；回复 key 只发给同一个主机。 */
class VisionRouteTest {

    private val ds = "https://api.deepseek.com/v1"
    private val or = "https://openrouter.ai/api/v1"

    @Test
    fun `default base follows a DeepSeek reply route`() {
        assertEquals(ds, VisionRoute.defaultBase(ds))
        assertEquals(ds, VisionRoute.defaultBase("https://api.deepseek.com/v1/"))
    }

    @Test
    fun `default base for any other reply host keeps the OpenRouter preset`() {
        assertEquals(or, VisionRoute.defaultBase(or))
        assertEquals(or, VisionRoute.defaultBase("https://example.com/v1"))
    }

    @Test
    fun `default model is deepseek-flash on DeepSeek and the old preset elsewhere`() {
        assertEquals("deepseek-flash", VisionRoute.defaultModel(ds))
        assertEquals("qwen/qwen2.5-vl-72b-instruct", VisionRoute.defaultModel(or))
    }

    @Test
    fun `same origin ignores path, case and trailing slash`() {
        assertTrue(VisionRoute.sameOrigin("https://api.deepseek.com/v1", "HTTPS://API.DEEPSEEK.COM"))
        assertTrue(VisionRoute.sameOrigin(ds, "https://api.deepseek.com/v1/"))
        assertTrue(VisionRoute.sameOrigin("https://api.deepseek.com", "https://api.deepseek.com:443/x"))
    }

    @Test
    fun `different host, scheme or port is not the same origin`() {
        assertFalse(VisionRoute.sameOrigin(ds, or))
        assertFalse(VisionRoute.sameOrigin(ds, "http://api.deepseek.com/v1"))
        assertFalse(VisionRoute.sameOrigin(ds, "https://api.deepseek.com:8443/v1"))
    }

    @Test
    fun `blank or unparseable urls are never the same origin`() {
        assertFalse(VisionRoute.sameOrigin("", ""))
        assertFalse(VisionRoute.sameOrigin("not a url", "not a url"))
    }

    @Test
    fun `own vision key always wins`() {
        assertEquals("vk", VisionRoute.effectiveKey("vk", ds, or, "rk"))
    }

    @Test
    fun `blank vision key borrows the reply key only from the same origin`() {
        assertEquals("rk", VisionRoute.effectiveKey("", ds, "https://api.deepseek.com/v1/", "rk"))
        assertEquals("", VisionRoute.effectiveKey("", ds, or, "rk"))
        assertEquals("", VisionRoute.effectiveKey("", "http://api.deepseek.com/v1", ds, "rk"))
    }
}
