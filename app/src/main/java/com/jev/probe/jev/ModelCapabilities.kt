package com.jev.probe.jev

import com.jev.probe.core.VisionRoute
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** What we know about a model's ability (contracts/jev/v1/capability.json). */
enum class CapState(val id: String) {
    SUPPORTED("supported"),
    UNSUPPORTED("unsupported"),
    UNKNOWN("unknown"),
    DISABLED_BY_POLICY("disabled_by_policy")
}

/** Why we know it. Only [PROVIDER_DECLARED] is the provider speaking; the rest say what we failed to find out. */
enum class Evidence(val id: String) {
    PROVIDER_DECLARED("provider_declared"),
    FIELD_ABSENT("field_absent"),
    MODEL_NOT_LISTED("model_not_listed"),
    UNKNOWN_SHAPE("unknown_shape"),
    FETCH_FAILED("fetch_failed"),
    OWNER_DISABLED("owner_disabled")
}

data class Capability(val state: CapState, val evidence: Evidence) {
    /** One line for the settings page; says which kind of "don't know" it is. */
    fun describe(): String = when (state) {
        CapState.SUPPORTED -> "支持直接看图（接口声明）"
        CapState.UNSUPPORTED -> "不支持直接看图（接口声明）"
        CapState.DISABLED_BY_POLICY -> "已在设置里关闭看图"
        CapState.UNKNOWN -> "不确定能不能直接看图（" + when (evidence) {
            Evidence.FETCH_FAILED -> "模型列表请求失败"
            Evidence.MODEL_NOT_LISTED -> "模型列表里没有这个模型"
            Evidence.FIELD_ABSENT -> "接口没有声明输入类型"
            else -> "接口返回的格式认不出"
        } + "）"
    }
}

/** What a request does with an image once the capability is known. */
data class ImageDecision(val effective: CapState, val attach: Boolean, val fallBackToText: Boolean, val reason: String)

/**
 * Does this model take image input? Asked of the provider's own model list
 * (`GET <base>/models`) by an adapter that reads only the fields that provider is known to
 * declare; never guessed from a model name. A failed request, a missing field, an unknown shape
 * and "the provider says no" are different answers: only the last is UNSUPPORTED.
 *
 * Only provider answers (SUPPORTED / UNSUPPORTED) are cached, under base + model, for [ttlMs]; an
 * UNKNOWN is asked again next time. Switching the base or the model therefore never reuses
 * another route's answer.
 */
class ModelCapabilities(
    /** The route's `/models` body; null or a throw = the request failed. */
    private val fetch: (ModelRoute) -> String?,
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = TTL_MS
) {
    private class Entry(val capability: Capability, val expiresAt: Long)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    /** The lock-free map keeps a slow request from blocking other callers; two racing lookups just both ask. */
    fun imageInput(route: ModelRoute): Capability {
        if (route.model.isBlank()) return Capability(CapState.UNKNOWN, Evidence.MODEL_NOT_LISTED)
        val key = cacheKey(route.base, route.model)
        if (key == null || !route.hasKey) return Capability(CapState.UNKNOWN, Evidence.FETCH_FAILED)
        cache[key]?.let { if (it.expiresAt > now()) return it.capability else cache.remove(key) }
        val body = try { fetch(route) } catch (_: Exception) { null }
            ?: return Capability(CapState.UNKNOWN, Evidence.FETCH_FAILED)
        val cap = parseImage(route.base, body, route.model)
        if (cap.state == CapState.SUPPORTED || cap.state == CapState.UNSUPPORTED) cache[key] = Entry(cap, now() + ttlMs)
        return cap
    }

    companion object {
        const val TTL_MS = 6L * 60 * 60 * 1000

        /** App-wide instance backed by a real GET. */
        val shared = ModelCapabilities(fetch = { it.fetchModels() })

        /** Where each provider's model list declares input modalities; a field an adapter does not read does not exist for it. */
        private fun modalityPaths(base: String): List<List<String>> =
            if (VisionRoute.originString(base) == "https://openrouter.ai")
                listOf(listOf("architecture", "input_modalities"), listOf("input_modalities"))
            else listOf(listOf("input_modalities"))

        fun parseImage(base: String, json: String?, model: String): Capability {
            val unknownShape = Capability(CapState.UNKNOWN, Evidence.UNKNOWN_SHAPE)
            if (json.isNullOrBlank()) return unknownShape
            val data = try { JSONObject(json).optJSONArray("data") } catch (_: Exception) { null } ?: return unknownShape
            val wanted = model.trim()
            val entry = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
                .firstOrNull { it.optString("id") == wanted }
                ?: return Capability(CapState.UNKNOWN, Evidence.MODEL_NOT_LISTED)
            for (path in modalityPaths(base)) {
                val holder = path.dropLast(1).fold<String, JSONObject?>(entry) { o, k -> o?.optJSONObject(k) }
                val value = holder?.opt(path.last())?.takeUnless { it === JSONObject.NULL } ?: continue  // null = absent, same as the other platform
                if (value !is JSONArray || value.length() == 0) return unknownShape
                val image = (0 until value.length()).any { value.optString(it) == "image" }
                return Capability(if (image) CapState.SUPPORTED else CapState.UNSUPPORTED, Evidence.PROVIDER_DECLARED)
            }
            return Capability(CapState.UNKNOWN, Evidence.FIELD_ABSENT)
        }

        /** base (origin + path, trailing slash and host case ignored) | model; null = nothing to key on. */
        fun cacheKey(base: String, model: String): String? {
            val origin = VisionRoute.originString(base) ?: return null
            val m = model.trim().ifEmpty { return null }
            val path = try { URI(base.trim()).rawPath ?: "" } catch (_: Exception) { "" }
            return origin + path.trimEnd('/') + "|" + m
        }

        /**
         * owner off wins over everything; unsupported never sends an image; unknown tries it with a
         * text fallback; the client's own limit (no image path) is not a statement about the model.
         */
        fun decideImage(capability: CapState, ownerEnabled: Boolean, clientCanAttach: Boolean): ImageDecision = when {
            !ownerEnabled || capability == CapState.DISABLED_BY_POLICY ->
                ImageDecision(CapState.DISABLED_BY_POLICY, false, false, "owner_disabled")
            capability == CapState.UNSUPPORTED ->
                ImageDecision(capability, false, false, "provider_declared")
            !clientCanAttach -> ImageDecision(capability, false, false, "client_limit")
            capability == CapState.SUPPORTED -> ImageDecision(capability, true, false, "provider_declared")
            else -> ImageDecision(capability, true, true, "not_known")
        }
    }
}
