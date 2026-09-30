package com.jev.probe.jev

import org.json.JSONObject

/**
 * Does this model take image input? Asked of the provider's own model list
 * (`GET <base>/models`, `data[].input_modalities` contains "image") instead of
 * hard-coding model names. Anything unclear — request failed, field missing,
 * an endpoint whose list has no such field — counts as "not supported".
 *
 * Only successful lookups are cached, per base + model, so a network blip is
 * retried next time and switching the model gives that model's own answer.
 */
class ModelCapabilities(
    /** (modelsUrl, key) -> response body, or null on failure. May throw. */
    private val fetch: (String, String) -> String?
) {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** The lock-free map keeps a slow request from blocking other callers; two racing lookups just both ask. */
    fun supportsImage(baseUrl: String, key: String, model: String): Boolean {
        if (key.isBlank() || model.isBlank()) return false
        val cacheKey = "${baseUrl.trim().trimEnd('/')}|${model.trim()}"
        cache[cacheKey]?.let { return it }
        val body = try { fetch(modelsUrl(baseUrl), key) } catch (_: Exception) { null } ?: return false
        return parseSupportsImage(body, model.trim()).also { cache[cacheKey] = it }
    }

    companion object {
        /** App-wide instance backed by a real GET. */
        val shared = ModelCapabilities { url, key -> HttpJson.get(url, key, Route.REPLY) }

        fun modelsUrl(baseUrl: String): String = "${baseUrl.trim().trimEnd('/')}/models"

        fun parseSupportsImage(json: String?, model: String): Boolean {
            if (json.isNullOrBlank()) return false
            return try {
                val data = JSONObject(json).optJSONArray("data") ?: return false
                for (i in 0 until data.length()) {
                    val m = data.optJSONObject(i) ?: continue
                    if (m.optString("id") != model) continue
                    val mods = m.optJSONArray("input_modalities") ?: return false
                    return (0 until mods.length()).any { mods.optString(it) == "image" }
                }
                false
            } catch (_: Exception) { false }
        }
    }
}
