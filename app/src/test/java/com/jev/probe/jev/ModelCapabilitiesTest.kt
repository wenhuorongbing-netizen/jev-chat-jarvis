package com.jev.probe.jev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模型能力查询：只测「给定模型列表返回，判断是否支持图片」这一外部行为。 */
class ModelCapabilitiesTest {

    private val deepseekList = """
        {"object":"list","data":[
          {"id":"deepseek-flash","object":"model","owned_by":"deepseek","input_modalities":["text","image"]},
          {"id":"deepseek-chat","object":"model","owned_by":"deepseek","input_modalities":["text"]},
          {"id":"no-modalities","object":"model","owned_by":"deepseek"}
        ]}
    """.trimIndent()

    @Test
    fun `a model that lists image input is supported`() {
        assertTrue(ModelCapabilities.parseSupportsImage(deepseekList, "deepseek-flash"))
    }

    @Test
    fun `a text-only model is not supported`() {
        assertFalse(ModelCapabilities.parseSupportsImage(deepseekList, "deepseek-chat"))
    }

    @Test
    fun `a model without the modalities field is not supported`() {
        assertFalse(ModelCapabilities.parseSupportsImage(deepseekList, "no-modalities"))
    }

    @Test
    fun `a model missing from the list is not supported`() {
        assertFalse(ModelCapabilities.parseSupportsImage(deepseekList, "gpt-x"))
    }

    @Test
    fun `broken or unexpected payloads count as not supported`() {
        assertFalse(ModelCapabilities.parseSupportsImage(null, "deepseek-flash"))
        assertFalse(ModelCapabilities.parseSupportsImage("", "deepseek-flash"))
        assertFalse(ModelCapabilities.parseSupportsImage("not json", "deepseek-flash"))
        assertFalse(ModelCapabilities.parseSupportsImage("""{"data":"oops"}""", "deepseek-flash"))
        assertFalse(ModelCapabilities.parseSupportsImage("""{"data":[1,2]}""", "deepseek-flash"))
        assertFalse(ModelCapabilities.parseSupportsImage("""{"data":[{"id":"deepseek-flash","input_modalities":"image"}]}""", "deepseek-flash"))
        assertFalse(ModelCapabilities.parseSupportsImage("""{"error":"x"}""", "deepseek-flash"))
    }

    @Test
    fun `an OpenRouter-shaped list without the top-level field is not supported`() {
        val or = """{"data":[{"id":"deepseek-flash","architecture":{"input_modalities":["text","image"]}}]}"""
        assertFalse(ModelCapabilities.parseSupportsImage(or, "deepseek-flash"))
    }

    // ------------------------------------------------------------ caching

    private class Fetcher(var body: String?) {
        var calls = 0
        val fetch: (String, String) -> String? = { _, _ -> calls++; body }
    }

    @Test
    fun `the answer is cached per base and model`() {
        val f = Fetcher(deepseekList)
        val caps = ModelCapabilities(f.fetch)
        assertTrue(caps.supportsImage("https://api.deepseek.com/v1", "k", "deepseek-flash"))
        assertTrue(caps.supportsImage("https://api.deepseek.com/v1/", "k", "deepseek-flash"))
        assertEquals(1, f.calls)
        assertFalse(caps.supportsImage("https://api.deepseek.com/v1", "k", "deepseek-chat"))
        assertEquals(2, f.calls)
    }

    @Test
    fun `switching the model gives the new model's answer`() {
        val caps = ModelCapabilities(Fetcher(deepseekList).fetch)
        assertTrue(caps.supportsImage("https://api.deepseek.com/v1", "k", "deepseek-flash"))
        assertFalse(caps.supportsImage("https://api.deepseek.com/v1", "k", "deepseek-chat"))
        assertTrue(caps.supportsImage("https://api.deepseek.com/v1", "k", "deepseek-flash"))
    }

    @Test
    fun `a failed lookup is not supported and is retried next time`() {
        val f = Fetcher(null)
        val caps = ModelCapabilities(f.fetch)
        assertFalse(caps.supportsImage("https://api.deepseek.com/v1", "k", "deepseek-flash"))
        f.body = deepseekList
        assertTrue(caps.supportsImage("https://api.deepseek.com/v1", "k", "deepseek-flash"))
        assertEquals(2, f.calls)
    }

    @Test
    fun `a throwing fetcher never escapes`() {
        val caps = ModelCapabilities { _, _ -> throw RuntimeException("boom") }
        assertFalse(caps.supportsImage("https://api.deepseek.com/v1", "k", "deepseek-flash"))
    }

    @Test
    fun `a blank key or model is not supported without a request`() {
        val f = Fetcher(deepseekList)
        val caps = ModelCapabilities(f.fetch)
        assertFalse(caps.supportsImage("https://api.deepseek.com/v1", "", "deepseek-flash"))
        assertFalse(caps.supportsImage("https://api.deepseek.com/v1", "k", " "))
        assertEquals(0, f.calls)
    }

    @Test
    fun `models url sits under the base`() {
        assertEquals("https://api.deepseek.com/v1/models", ModelCapabilities.modelsUrl("https://api.deepseek.com/v1/"))
    }
}
