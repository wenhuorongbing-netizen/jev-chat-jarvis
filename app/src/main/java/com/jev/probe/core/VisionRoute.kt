package com.jev.probe.core

import java.net.URI

/**
 * Vision route defaults. The vision route follows the reply route's source:
 * a DeepSeek reply key also serves `deepseek-flash` vision, so nobody has to
 * fill in a second key. Pure functions so they can be tested on the JVM.
 */
object VisionRoute {

    private val DEEPSEEK_HOST = hostOf(Prefs.DEEPSEEK_BASE)

    /** No vision base saved yet: DeepSeek when the reply route is DeepSeek, else the OpenRouter preset. */
    fun defaultBase(replyBase: String): String =
        if (hostOf(replyBase) == DEEPSEEK_HOST) Prefs.DEEPSEEK_BASE else Prefs.DEFAULT_VISION_BASE

    /** No vision model saved yet: `deepseek-flash` on DeepSeek, else the old OpenRouter preset. */
    fun defaultModel(visionBase: String): String =
        if (hostOf(visionBase) == DEEPSEEK_HOST) Prefs.DEEPSEEK_VISION_MODEL else Prefs.DEFAULT_VISION_MODEL

    /**
     * True only when scheme, host and port all match — a key must never cross
     * vendors, nor fall from https to plain http.
     */
    fun sameOrigin(a: String, b: String): Boolean {
        val oa = originOf(a) ?: return false
        return oa == originOf(b)
    }

    /** The vision key to send: its own, else the reply key when both routes share an origin, else blank. */
    fun effectiveKey(visionKey: String, visionBase: String, replyBase: String, replyKey: String): String =
        visionKey.ifBlank { if (sameOrigin(visionBase, replyBase)) replyKey else "" }

    private fun uriOf(url: String): URI? = try { URI(url.trim()) } catch (_: Exception) { null }

    private fun hostOf(url: String): String? = uriOf(url)?.host?.lowercase()?.ifBlank { null }

    private fun originOf(url: String): Triple<String, String, Int>? {
        val u = uriOf(url) ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        val host = hostOf(url) ?: return null
        val port = if (u.port != -1) u.port else if (scheme == "https") 443 else if (scheme == "http") 80 else -1
        return Triple(scheme, host, port)
    }
}
