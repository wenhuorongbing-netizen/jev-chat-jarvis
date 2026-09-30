package com.jev.probe.jev

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Which of the two API routes a failure came from. Used to build error text
 * the user can act on ("回复接口 HTTP 401：…" vs "视觉接口 …").
 */
object Route {
    const val REPLY = "回复接口"
    const val VISION = "视觉接口"
}

/**
 * Carries the route, the HTTP status (null = transport failure) and a fixed,
 * locally written [hint]. The provider's response body is never read into it:
 * error text ends up in the overlay and in crash files, and a third party can
 * echo the key or the chat back in a body, which no redaction rule can promise
 * to catch.
 */
class ApiException(
    val route: String,
    val status: Int?,
    val hint: String
) : RuntimeException(buildMessage(route, status, hint)) {

    companion object {
        fun buildMessage(route: String, status: Int?, hint: String): String =
            if (status != null) "$route HTTP $status：$hint" else "$route 请求失败：$hint"

        /** What a user can do about an HTTP status. Text is ours, never the server's. */
        fun hintFor(status: Int): String = when (status) {
            401, 403 -> "密钥被拒，请检查该接口的密钥"
            404 -> "地址或模型名不对"
            400, 422 -> "请求被拒绝，请检查模型名和接口地址"
            402 -> "账户余额或额度不足"
            429, 529 -> "服务繁忙，已重试"
            in 500..599 -> "服务端出错，请稍后再试"
            else -> "请求没有成功"
        }
    }
}

/**
 * Shared POST-JSON helper: UTF-8 body, exponential backoff on 429/529, no retry
 * on other 4xx, and every failure normalized to [ApiException]. Keys are passed
 * in per call and never logged.
 */
object HttpJson {

    private const val MAX_ATTEMPTS = 3

    /**
     * @param route one of [Route], used only for error text.
     * @param extraHeaders additional request headers (e.g. OpenRouter attribution).
     */
    fun post(
        url: String,
        key: String,
        body: JSONObject,
        route: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): JSONObject {
        var attempt = 0
        var last: ApiException? = null
        while (attempt < MAX_ATTEMPTS) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 40000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.outputStream.use { os: OutputStream -> os.write(bytes) }
                val code = conn.responseCode
                if (code == 429 || code == 529) {
                    last = ApiException(route, code, ApiException.hintFor(code))
                    attempt++
                    if (attempt < MAX_ATTEMPTS) Thread.sleep(500L * (1L shl attempt))
                    continue
                }
                // Branch on the status code FIRST, and do not read an error body at
                // all: only the status and our own hint go into the exception.
                if (code !in 200..299) throw ApiException(route, code, ApiException.hintFor(code))
                val text = readBody(conn.inputStream)
                if (text.isBlank()) throw ApiException(route, code, "响应体为空")
                return JSONObject(text)
            } catch (e: ApiException) {
                if (e.status != null && e.status in 400..499) throw e  // client error: no retry
                last = e
                attempt++
                if (attempt < MAX_ATTEMPTS) Thread.sleep(500L * (1L shl attempt))
            } catch (e: Exception) {
                last = ApiException(route, null, describe(e))
                attempt++
                if (attempt < MAX_ATTEMPTS) Thread.sleep(500L * (1L shl attempt))
            } finally {
                conn?.disconnect()
            }
        }
        throw last ?: ApiException(route, null, "请求失败")
    }

    /**
     * Single GET, no retry (callers treat any failure as "unknown"). Returns the
     * body of a 2xx response; anything else throws [ApiException].
     */
    fun get(url: String, key: String, route: String): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 15000
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Accept", "application/json")
                headersFor(url).forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = conn.responseCode
            if (code !in 200..299) throw ApiException(route, code, ApiException.hintFor(code))
            return readBody(conn.inputStream)
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(route, null, describe(e))
        } finally {
            conn?.disconnect()
        }
    }

    /** Body text, or "" — a null stream or a read failure never costs us the status code. */
    private fun readBody(stream: java.io.InputStream?): String {
        stream ?: return ""
        return try {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        } catch (_: Exception) { "" }
    }

    /** OpenRouter wants attribution headers; other hosts reject unknown ones politely. */
    fun headersFor(url: String): Map<String, String> =
        if (url.contains("openrouter.ai", ignoreCase = true))
            mapOf("HTTP-Referer" to "https://jev-assistant.local", "X-Title" to "Jev Assistant")
        else emptyMap()

    /**
     * Human-readable transport failures. Only known causes get a sentence; anything
     * else is the exception's class name, never its message (a JSON parse failure
     * message quotes the response text).
     */
    private fun describe(e: Exception): String {
        val m = e.message ?: ""
        return when {
            m.contains("timed out") || m.contains("timeout", true) -> "网络超时，请检查连接"
            m.contains("Unable to resolve host") -> "域名解析失败，地址填错或无网络"
            m.contains("Failed to connect") || m.contains("ECONNREFUSED") -> "无法连接该地址"
            m.contains("CertPath") || m.contains("SSL") -> "HTTPS 证书校验失败"
            else -> e.javaClass.simpleName
        }
    }
}
