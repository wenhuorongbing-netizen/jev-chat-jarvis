package com.jev.probe.core.ui

import com.jev.probe.core.ui.OverlayRules.BubbleState
import com.jev.probe.core.ui.OverlayRules.ErrorAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayRulesTest {

    // ---- bubbleVisual ----

    @Test
    fun `bubbleVisual IDLE is Jev at half alpha`() {
        val v = OverlayRules.bubbleVisual(BubbleState.IDLE)
        assertEquals("Jev", v.label)
        assertEquals(0.5f, v.alpha, 1e-6f)
        assertFalse(v.danger)
    }

    @Test
    fun `bubbleVisual LOADING is dots at 0_9 alpha`() {
        val v = OverlayRules.bubbleVisual(BubbleState.LOADING)
        assertEquals("···", v.label)
        assertEquals(0.9f, v.alpha, 1e-6f)
        assertFalse(v.danger)
    }

    @Test
    fun `bubbleVisual READY is check at full alpha`() {
        val v = OverlayRules.bubbleVisual(BubbleState.READY)
        assertEquals("✓", v.label)
        assertEquals(1f, v.alpha, 1e-6f)
        assertFalse(v.danger)
    }

    @Test
    fun `bubbleVisual ERROR is bang at full alpha and danger`() {
        val v = OverlayRules.bubbleVisual(BubbleState.ERROR)
        assertEquals("!", v.label)
        assertEquals(1f, v.alpha, 1e-6f)
        assertTrue(v.danger)
    }

    // ---- shouldShowGloss ----

    @Test
    fun `gloss hidden when lang is blank`() {
        assertFalse(OverlayRules.shouldShowGloss("", "你好", "hello"))
        assertFalse(OverlayRules.shouldShowGloss("   ", "你好", "hello"))
    }

    @Test
    fun `gloss hidden when lang is Chinese`() {
        assertFalse(OverlayRules.shouldShowGloss("中文", "你好", "你好"))
    }

    @Test
    fun `gloss hidden when lang is Chinese with different case or padding`() {
        assertFalse(OverlayRules.shouldShowGloss("中文".lowercase(), "你好", "你好"))
        assertFalse(OverlayRules.shouldShowGloss(" 中文 ", "你好", "你好"))
    }

    @Test
    fun `gloss hidden when gloss is blank`() {
        assertFalse(OverlayRules.shouldShowGloss("en", "", "hello"))
        assertFalse(OverlayRules.shouldShowGloss("en", "   ", "hello"))
    }

    @Test
    fun `gloss hidden when gloss equals body`() {
        assertFalse(OverlayRules.shouldShowGloss("en", "hello", "hello"))
    }

    @Test
    fun `gloss shown for normal foreign language`() {
        assertTrue(OverlayRules.shouldShowGloss("en", "你好", "hello"))
        assertTrue(OverlayRules.shouldShowGloss("ja", "こんにちは的意思是你好", "こんにちは"))
    }

    // ---- errorView ----

    @Test
    fun `errorView without reply key points to settings`() {
        val v = OverlayRules.errorView("anything", hasReplyKey = false)
        assertEquals("还没填回复接口的 key", v.message)
        assertEquals(ErrorAction.OPEN_SETTINGS, v.action)
        assertEquals("去设置", v.actionLabel)
    }

    @Test
    fun `errorView maps 401 or 403 to key problem`() {
        listOf("HTTP 401 Unauthorized", "http 403 forbidden", "Error 401").forEach { raw ->
            val v = OverlayRules.errorView(raw, hasReplyKey = true)
            assertEquals("回复接口的 key 不对或已过期", v.message)
            assertEquals(ErrorAction.OPEN_SETTINGS, v.action)
            assertEquals("去设置", v.actionLabel)
        }
    }

    @Test
    fun `errorView maps key-word errors to key problem ignoring case`() {
        listOf("invalid API KEY", "密钥无效", "wrong Key provided").forEach { raw ->
            val v = OverlayRules.errorView(raw, hasReplyKey = true)
            assertEquals("回复接口的 key 不对或已过期", v.message)
            assertEquals(ErrorAction.OPEN_SETTINGS, v.action)
            assertEquals("去设置", v.actionLabel)
        }
    }

    @Test
    fun `errorView maps timeout and network errors to retry`() {
        listOf("connect timeout", "请求超时", "Unable to resolve host api.example.com", "网络异常").forEach { raw ->
            val v = OverlayRules.errorView(raw, hasReplyKey = true)
            assertEquals("网络连不上，稍后再试", v.message)
            assertEquals(ErrorAction.RETRY, v.action)
            assertEquals("重试", v.actionLabel)
        }
    }

    @Test
    fun `errorView fallback is a generic human line and retries`() {
        val v = OverlayRules.errorView("boom", hasReplyKey = true)
        assertEquals("出错了，稍后再试", v.message)
        assertEquals(ErrorAction.RETRY, v.action)
        assertEquals("重试", v.actionLabel)
    }

    @Test
    fun `errorView never leaks the raw message into the UI`() {
        // SPEC A3：原始错误信息不进 UI——无论长短、是否含可疑内容。
        listOf("boom", "x".repeat(200), "HTTP 500 internal: sk-abc123secret").forEach { raw ->
            val v = OverlayRules.errorView(raw, hasReplyKey = true)
            assertFalse("raw leaked into UI message: $raw", v.message.contains(raw.take(20)))
        }
    }

    @Test
    fun `errorView key check wins over network words`() {
        val v = OverlayRules.errorView("401 timeout", hasReplyKey = true)
        assertEquals(ErrorAction.OPEN_SETTINGS, v.action)
    }
}
