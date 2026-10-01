package com.jev.probe.jev

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * S4：一次生成的路由在开始时定下来，之后设置怎么改都不影响它；key 只发往快照里的那个地址；
 * 过期的生成不再重试。用本机假服务器看真实的请求。
 */
class RouteSnapshotTest {

    private class Request(val path: String, val auth: String, val body: JSONObject)

    /** Answers the given statuses in order (the last one repeats); 200 carries a chat reply. */
    private class Fake(private val statuses: List<Int>) {
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val seen = CopyOnWriteArrayList<Request>()
        val base get() = "http://127.0.0.1:${server.localPort}/v1"

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    try {
                        server.accept().use { sock ->
                            val input = sock.getInputStream().buffered()
                            fun line(): String {
                                val sb = StringBuilder()
                                while (true) {
                                    val b = input.read()
                                    if (b < 0 || b == '\n'.code) break
                                    if (b != '\r'.code) sb.append(b.toChar())
                                }
                                return sb.toString()
                            }
                            val first = line()
                            var length = 0
                            var auth = ""
                            while (true) {
                                val h = line()
                                if (h.isEmpty()) break
                                if (h.startsWith("Content-Length:", true)) length = h.substringAfter(':').trim().toInt()
                                if (h.startsWith("Authorization:", true)) auth = h.substringAfter(':').trim()
                            }
                            val bytes = ByteArray(length).also { var n = 0; while (n < length) n += input.read(it, n, length - n) }
                            val n = seen.size
                            seen.add(Request(first.split(' ')[1], auth, JSONObject(String(bytes, Charsets.UTF_8))))
                            val status = statuses[minOf(n, statuses.size - 1)]
                            val body = if (status == 200) """{"choices":[{"finish_reason":"stop","message":{"content":"收到"}}]}""" else "denied"
                            val raw = body.toByteArray(Charsets.UTF_8)
                            sock.getOutputStream().apply {
                                write("HTTP/1.1 $status X\r\nContent-Length: ${raw.size}\r\nContent-Type: application/json; charset=utf-8\r\nConnection: close\r\n\r\n".toByteArray())
                                write(raw); flush()
                            }
                        }
                    } catch (_: Exception) { }
                }
            }
        }
    }

    private val fakes = mutableListOf<Fake>()
    private fun fake(vararg statuses: Int) = Fake(statuses.toList()).also { fakes += it }

    @After
    fun down() = fakes.forEach { it.server.close() }

    private fun client(f: Fake, key: String, model: String, isLive: () -> Boolean = { true }) =
        ReplyClient(ReplyConfig(ModelRoute(Route.REPLY, f.base, model, key), "", 30), isLive)

    @Test
    fun `a call goes where its snapshot says, with the snapshot's key and model`() {
        val a = fake(200)
        val b = fake(200)
        val inFlight = client(a, "key-a", "model-a")
        // The settings change after the generation started: a new route for the next call, not for this one.
        val next = client(b, "key-b", "model-b")

        assertEquals("收到", inFlight.ping())
        assertEquals(1, a.seen.size)
        assertEquals(0, b.seen.size)
        assertEquals("/v1/chat/completions", a.seen[0].path)
        assertEquals("Bearer key-a", a.seen[0].auth)
        assertEquals("model-a", a.seen[0].body.getString("model"))

        next.ping()
        assertEquals("Bearer key-b", b.seen[0].auth)
        assertEquals("model-b", b.seen[0].body.getString("model"))
        assertEquals(1, a.seen.size)
    }

    @Test
    fun `the destination and the endpoint come from the same base`() {
        val route = ModelRoute(Route.REPLY, " https://API.example.com/v1/ ", " m ", "k")
        assertEquals("https://api.example.com", route.destination)
        assertEquals("https://API.example.com/v1/chat/completions", route.endpoint)
        assertEquals("m", route.model)
        assertEquals(null, ModelRoute(Route.REPLY, "not a url", "m", "k").destination)
    }

    @Test
    fun `a generation that is no longer wanted does not retry`() {
        val f = fake(503, 200)
        try { client(f, "k", "m", isLive = { false }).ping(); throw AssertionError("expected a failure") } catch (e: ApiException) {
            assertEquals(ErrorKind.TRANSPORT, e.kind)
        }
        assertEquals(1, f.seen.size)
    }

    @Test
    fun `a rejected key is never retried`() {
        val f = fake(401, 200)
        try { client(f, "k", "m").ping(); throw AssertionError("expected a failure") } catch (e: ApiException) {
            assertEquals(ErrorKind.AUTH, e.kind)
            assertFalse(e.message!!.contains("denied"))
        }
        assertEquals(1, f.seen.size)
    }

    @Test
    fun `a rate limit is retried and a later success comes through`() {
        val f = fake(429, 200)
        assertEquals("收到", client(f, "k", "m").ping())
        assertEquals(2, f.seen.size)
    }

    @Test
    fun `a provider refusal is a fixed invalid-response failure and is not retried`() {
        val f = fake(200)
        val route = ModelRoute(Route.REPLY, f.base, "m", "k")
        // 200 with a refusal envelope: the Fake answers a normal reply, so exercise the parser on the real response shape.
        val resp = JSONObject("""{"choices":[{"finish_reason":"stop","message":{"content":null,"refusal":"nope"}}]}""")
        val e = try { ReplyParser.contentOf(ChatReply.of(resp)); null } catch (e: InvalidResponseException) { e }
        assertEquals(ErrorKind.INVALID_RESPONSE, ErrorKind.of(e!!))
        assertEquals(0, f.seen.size)
        assertTrue(route.hasKey)
    }

    @Test
    fun `the clients read the settings only when they are built`() {
        // Structural guard: after construction nothing in ReplyClient / VisionClient / ModelRoute may reach
        // back into Prefs, or a settings change could still change a generation that is already running.
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/java/com/jev/probe/jev") }.first { it.isDirectory }
        fun code(name: String) = File(root, name).readText(Charsets.UTF_8).lines()
            .filterNot { it.trim().startsWith("*") || it.trim().startsWith("/*") || it.trim().startsWith("//") }
        for (name in listOf("ReplyClient.kt", "VisionClient.kt", "ModelRoute.kt", "HttpJson.kt", "ModelCapabilities.kt")) {
            val reads = code(name).filter { Regex("""\bprefs\.[a-zA-Z]""").containsMatchIn(it) }
            val allowed = when (name) {
                "ReplyClient.kt" -> reads.filter { "ReplyConfig(ModelRoute.reply(prefs)" !in it }
                "ModelRoute.kt" -> reads.filter { "ModelRoute(Route." !in it }
                else -> reads
            }
            assertTrue("$name reads Prefs after construction: $allowed", allowed.isEmpty())
        }
    }
}
