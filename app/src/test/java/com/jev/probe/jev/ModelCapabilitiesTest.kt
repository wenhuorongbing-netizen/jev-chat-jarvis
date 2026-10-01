package com.jev.probe.jev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型能力查询的本机行为：缓存按 base+model 分、只缓存接口亲口说的、会过期、失败不当成「不支持」。
 * 解析口径（四种状态 + 证据）在共享契约 capability.json 里，由 ContractV1Test 读。
 */
class ModelCapabilitiesTest {

    private val list = """
        {"object":"list","data":[
          {"id":"vision-model","input_modalities":["text","image"]},
          {"id":"text-model","input_modalities":["text"]},
          {"id":"silent-model"}
        ]}
    """.trimIndent()

    private fun route(model: String, base: String = "https://api.example.com/v1", key: String = "k") =
        ModelRoute(Route.REPLY, base, model, key)

    private class Fetcher(var body: String?) {
        var calls = 0
        var lastBase: String? = null
        val fetch: (ModelRoute) -> String? = { calls++; lastBase = it.base; body }
    }

    @Test
    fun `provider answers are cached per base and model`() {
        val f = Fetcher(list)
        val caps = ModelCapabilities(f.fetch)
        assertEquals(CapState.SUPPORTED, caps.imageInput(route("vision-model")).state)
        assertEquals(CapState.SUPPORTED, caps.imageInput(route("vision-model", base = "https://API.example.com/v1/")).state)
        assertEquals(1, f.calls)
        assertEquals(CapState.UNSUPPORTED, caps.imageInput(route("text-model")).state)
        assertEquals(2, f.calls)
    }

    @Test
    fun `another base never reuses the answer of this one`() {
        val f = Fetcher(list)
        val caps = ModelCapabilities(f.fetch)
        assertEquals(CapState.SUPPORTED, caps.imageInput(route("vision-model", base = "https://a.example.com/v1")).state)
        f.body = """{"data":[{"id":"vision-model","input_modalities":["text"]}]}"""
        assertEquals(CapState.UNSUPPORTED, caps.imageInput(route("vision-model", base = "https://b.example.com/v1")).state)
        assertEquals(CapState.UNSUPPORTED, caps.imageInput(route("vision-model", base = "https://a.example.com/v2")).state)
        assertEquals(CapState.SUPPORTED, caps.imageInput(route("vision-model", base = "https://a.example.com/v1")).state)
        assertEquals(3, f.calls)
    }

    @Test
    fun `an answer expires`() {
        var t = 1_000L
        val f = Fetcher(list)
        val caps = ModelCapabilities(f.fetch, now = { t }, ttlMs = 100)
        caps.imageInput(route("vision-model"))
        t += 99
        caps.imageInput(route("vision-model"))
        assertEquals(1, f.calls)
        t += 2
        caps.imageInput(route("vision-model"))
        assertEquals(2, f.calls)
    }

    @Test
    fun `unknown is never cached, so a failed lookup is asked again`() {
        val f = Fetcher(null)
        val caps = ModelCapabilities(f.fetch)
        assertEquals(Capability(CapState.UNKNOWN, Evidence.FETCH_FAILED), caps.imageInput(route("vision-model")))
        f.body = list
        assertEquals(CapState.SUPPORTED, caps.imageInput(route("vision-model")).state)
        f.body = """{"data":[{"id":"silent-model"}]}"""
        assertEquals(Capability(CapState.UNKNOWN, Evidence.FIELD_ABSENT), caps.imageInput(route("silent-model")))
        assertEquals(Capability(CapState.UNKNOWN, Evidence.FIELD_ABSENT), caps.imageInput(route("silent-model")))
        assertEquals(4, f.calls)
    }

    @Test
    fun `a failed lookup is unknown, never unsupported`() {
        val caps = ModelCapabilities(fetch = { throw ApiException(Route.REPLY, 401, "x") })
        val cap = caps.imageInput(route("vision-model"))
        assertEquals(CapState.UNKNOWN, cap.state)
        assertNotEquals(CapState.UNSUPPORTED, cap.state)
    }

    @Test
    fun `a throwing fetcher never escapes`() {
        val caps = ModelCapabilities(fetch = { throw RuntimeException("boom") })
        assertEquals(CapState.UNKNOWN, caps.imageInput(route("vision-model")).state)
    }

    @Test
    fun `a blank key or model is unknown without a request`() {
        val f = Fetcher(list)
        val caps = ModelCapabilities(f.fetch)
        assertEquals(Evidence.FETCH_FAILED, caps.imageInput(route("vision-model", key = "")).evidence)
        assertEquals(Evidence.MODEL_NOT_LISTED, caps.imageInput(route(" ")).evidence)
        assertEquals(Evidence.FETCH_FAILED, caps.imageInput(route("vision-model", base = "not a url")).evidence)
        assertEquals(0, f.calls)
    }

    @Test
    fun `the route is what is asked`() {
        val f = Fetcher(list)
        ModelCapabilities(f.fetch).imageInput(route("vision-model", base = "https://api.example.com/v1/"))
        assertEquals("https://api.example.com/v1", f.lastBase)
    }

    @Test
    fun `a route never prints its key`() {
        val r = route("vision-model", key = "sk-secret-123")
        assertFalse(r.toString().contains("sk-secret"))
        assertTrue(r.toString().contains("vision-model"))
    }

    @Test
    fun `a describe line says which kind of not knowing it is`() {
        val lines = listOf(Evidence.FETCH_FAILED, Evidence.MODEL_NOT_LISTED, Evidence.FIELD_ABSENT, Evidence.UNKNOWN_SHAPE)
            .map { Capability(CapState.UNKNOWN, it).describe() }
        assertEquals(lines.size, lines.toSet().size)
        assertTrue(lines.all { it.startsWith("不确定") })
        assertFalse(Capability(CapState.UNSUPPORTED, Evidence.PROVIDER_DECLARED).describe().startsWith("不确定"))
    }
}
