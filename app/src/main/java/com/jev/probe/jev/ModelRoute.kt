package com.jev.probe.jev

import com.jev.probe.core.Prefs
import com.jev.probe.core.VisionRoute
import org.json.JSONObject

/**
 * One model call's route, read from [Prefs] once and then fixed: where it goes, which model, which
 * key. The call chain holds this object and never goes back to [Prefs], so a settings change made
 * while a call is in flight cannot change that call. Endpoint and [destination] come from the same
 * base, and the key leaves only through [post] / [fetchModels], which use that endpoint.
 */
class ModelRoute(
    /** One of [Route], used only for error text. */
    val label: String,
    baseUrl: String,
    model: String,
    private val key: String
) {
    val base: String = baseUrl.trim().trimEnd('/')
    val model: String = model.trim()
    val endpoint: String = "$base/chat/completions"

    /** `scheme://host[:port]` the key is sent to; null when the base is not an http(s) address. */
    val destination: String? = VisionRoute.originString(base)
    val hasKey: Boolean get() = key.isNotBlank()
    private val headers: Map<String, String> = HttpJson.headersFor(endpoint)

    /** The body needs `model` = [model]; the caller builds the rest. */
    fun post(body: JSONObject, isLive: () -> Boolean = { true }): JSONObject =
        HttpJson.post(endpoint, key, body, label, headers, isLive)

    /** Raw `GET <base>/models` body; throws [ApiException]. */
    fun fetchModels(): String = HttpJson.get("$base/models", key, label)

    override fun toString(): String = "ModelRoute($label $base $model)"  // never the key

    companion object {
        fun reply(prefs: Prefs) = ModelRoute(Route.REPLY, prefs.replyBaseUrl, prefs.replyModel, prefs.effectiveReplyKey())
        fun vision(prefs: Prefs) = ModelRoute(Route.VISION, prefs.visionBaseUrl, prefs.visionModel, prefs.effectiveVisionKey())
    }
}
