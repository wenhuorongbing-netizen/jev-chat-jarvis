package com.jev.probe.jev

import org.json.JSONException
import org.json.JSONObject

/**
 * The one reliable sign that a failed picture request means "this model takes no images"
 * (contracts/jev/v1/image_rejection.json): the provider's own structured error code or type on an
 * HTTP 400 / 415 / 422. A bare status (a 404 may be a wrong model name, a 413 a payload that is too
 * large) and a human-readable message are never evidence. The body is read only to answer this one
 * yes/no question and is thrown away: nothing of it is kept or shown.
 */
internal object ImageRejection {
    val CODES = setOf("image_not_supported", "unsupported_modality", "unsupported_image_input", "image_input_not_supported")
    private val STATUSES = setOf(400, 415, 422)
    private const val MAX_BODY = 4096

    fun worthReading(status: Int) = status in STATUSES

    /** [stream] = the error stream of the response (may be null); read at most [MAX_BODY] bytes. */
    fun fromStream(status: Int, stream: java.io.InputStream?): Boolean {
        if (!worthReading(status) || stream == null) return false
        // a manual bounded read: InputStream.readNBytes(Int) needs API 33, minSdk is 30
        val text = try {
            stream.use {
                val buf = ByteArray(MAX_BODY)
                var n = 0
                while (n < MAX_BODY) {
                    val r = it.read(buf, n, MAX_BODY - n)
                    if (r < 0) break
                    n += r
                }
                String(buf, 0, n, Charsets.UTF_8)
            }
        } catch (_: Exception) { return false }
        return fromBody(status, text)
    }

    fun fromBody(status: Int, body: String): Boolean {
        if (!worthReading(status) || body.isBlank()) return false
        val root = try { JSONObject(body) } catch (_: JSONException) { return false }
        return listOfNotNull(root.optJSONObject("error"), root).any { o ->
            listOf("code", "type").any { k -> (o.opt(k) as? String)?.let { it.lowercase() in CODES } == true }
        }
    }
}
