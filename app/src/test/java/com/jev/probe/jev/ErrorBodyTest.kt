package com.jev.probe.jev

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * S0.1：供应商的报错正文不进任何提示文案。第三方可能把 key、提示词或聊天片段回显在
 * 正文里，正则打码保证不了，所以正文根本不读、不传：只留 状态码 + 我们自己写的提示。
 * 这里用本机假服务器回显合成 marker，真走 HttpJson。
 */
class ErrorBodyTest {

    private lateinit var server: ServerSocket
    @Volatile private var status = 400
    @Volatile private var body = ""

    // synthetic markers only — never a real credential or a real chat line
    private val chatMarker = "CHATMARKER-老王说明晚八点在老地方见"
    private val shortKey = "k123" // shorter than any old redaction length threshold
    private val longKey = "FAKEKEY-0123456789abcdef"

    @Before
    fun up() {
        server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    server.accept().use { sock ->
                        // read the request head (and any small body); the client sends one packet
                        val input = sock.getInputStream()
                        val buf = ByteArray(8192)
                        sock.soTimeout = 300
                        try { while (input.read(buf) > 0) { /* drain */ } } catch (_: Exception) { }
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        val head = "HTTP/1.1 $status X\r\nContent-Length: ${bytes.size}\r\n" +
                            "Content-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\n"
                        sock.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
                    }
                } catch (_: Exception) { }
            }
        }
    }

    @After
    fun down() = server.close()

    private fun url() = "http://127.0.0.1:${server.localPort}/v1/chat/completions"

    private fun failureOf(key: String, block: () -> Unit): String {
        try {
            block()
        } catch (e: ApiException) {
            return e.message!!
        }
        fail("expected ApiException")
        return ""
    }

    private fun assertClean(msg: String, vararg secrets: String) {
        for (s in secrets) assertFalse("leaked '$s' in: $msg", msg.contains(s))
    }

    @Test
    fun `post - a 400 body echoing chat text and keys never reaches the message`() {
        status = 400
        body = """{"error":{"message":"bad request: $chatMarker key=$shortKey $longKey sk-or-v1-abcdef123456 Bearer abcdef1234567890 AIzaSyA-fake-google-key"}}"""
        val msg = failureOf(shortKey) { HttpJson.post(url(), shortKey, JSONObject(), Route.REPLY) }
        assertClean(msg, "CHATMARKER", "老王", shortKey, longKey, "abcdef", "AIza")
        assertTrue(msg.startsWith("回复接口 HTTP 400"))
    }

    @Test
    fun `post - a 401 says the key was rejected without the body`() {
        status = 401
        body = """Incorrect API key provided: $longKey"""
        val msg = failureOf(longKey) { HttpJson.post(url(), longKey, JSONObject(), Route.VISION) }
        assertClean(msg, longKey, "Incorrect")
        assertEquals("视觉接口 HTTP 401：密钥被拒，请检查该接口的密钥", msg)
    }

    @Test
    fun `post - a 500 body is not echoed either`() {
        status = 500
        body = "internal error while handling: $chatMarker"
        val msg = failureOf(shortKey) { HttpJson.post(url(), shortKey, JSONObject(), Route.REPLY) }
        assertClean(msg, "CHATMARKER", "老王", "internal error")
    }

    @Test
    fun `post - a 200 body that is not JSON does not put its text in the message`() {
        status = 200
        body = "<html>$chatMarker</html>"
        val msg = failureOf(shortKey) { HttpJson.post(url(), shortKey, JSONObject(), Route.REPLY) }
        assertClean(msg, "CHATMARKER", "老王", "html")
    }

    @Test
    fun `get - an error body is not echoed`() {
        status = 403
        body = """{"detail":"$chatMarker $longKey"}"""
        val msg = failureOf(longKey) { HttpJson.get(url(), longKey, Route.REPLY) }
        assertClean(msg, "CHATMARKER", "老王", longKey)
        assertTrue(msg.startsWith("回复接口 HTTP 403"))
    }

    @Test
    fun `every status has a fixed local hint`() {
        for (code in listOf(400, 401, 402, 403, 404, 422, 429, 500, 502, 503, 418)) {
            assertTrue(ApiException.hintFor(code).isNotBlank())
        }
        assertNull(ApiException(Route.REPLY, null, "网络超时，请检查连接").status)
    }

    @Test
    fun `a model reply that is not JSON does not put its text in the exception`() {
        val e = try {
            ReplyParser.parseBilingual("对方的话 $chatMarker，但我不给 JSON")
            null
        } catch (e: IllegalStateException) { e }
        assertClean(e!!.message!!, "CHATMARKER", "老王")
        val broken = try {
            ReplyParser.parseBilingual("{ $chatMarker: nope }")
            null
        } catch (e: IllegalStateException) { e }
        assertClean(broken!!.message!!, "CHATMARKER", "老王")
    }
}
