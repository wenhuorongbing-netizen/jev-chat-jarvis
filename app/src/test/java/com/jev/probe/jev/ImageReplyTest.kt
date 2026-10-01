package com.jev.probe.jev

import com.jev.probe.core.EphemeralImage
import com.jev.probe.core.ImageSession
import com.jev.probe.core.ImageUse
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * S5-A 的单一接缝「一次图片回复」：假消息 + 假图 + 假服务商（模型列表和回复都在本机假服务器上），
 * 只看外部行为：发没发图、发了几次、失败文案里有没有图片字节、图片什么时候不能再用。
 */
class ImageReplyTest {

    private class Seen(val path: String, val body: JSONObject?) {
        val hasImage: Boolean get() = body.toString().contains("image_url")
    }

    /** `/models` answers [models] (null = 404); `/chat/completions` answers [chat] in order, the last repeating. */
    private class Provider(private val models: String?, private val chat: List<Int>) {
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val seen = CopyOnWriteArrayList<Seen>()
        val base get() = "http://127.0.0.1:${server.localPort}/v1"
        val chats get() = seen.filter { it.path.endsWith("/chat/completions") }
        val modelRequests get() = seen.count { it.path.endsWith("/models") }

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
                            val first = line().split(' ')
                            var length = 0
                            while (true) {
                                val h = line()
                                if (h.isEmpty()) break
                                if (h.startsWith("Content-Length:", true)) length = h.substringAfter(':').trim().toInt()
                            }
                            val raw = ByteArray(length).also { var n = 0; while (n < length) n += input.read(it, n, length - n) }
                            val path = first[1]
                            val body = if (length > 0) JSONObject(String(raw, Charsets.UTF_8)) else null
                            val before = chats.size
                            seen.add(Seen(path, body))
                            val (status, text) = if (path.endsWith("/models")) {
                                if (models == null) 404 to "not found" else 200 to models
                            } else {
                                val s = chat[minOf(before, chat.size - 1)]
                                // an error body that echoes the request, as a careless provider would
                                if (s == 200) 200 to ENVELOPE else s to "echo:" + String(raw, Charsets.UTF_8)
                            }
                            val out = text.toByteArray(Charsets.UTF_8)
                            sock.getOutputStream().apply {
                                write("HTTP/1.1 $status X\r\nContent-Length: ${out.size}\r\nContent-Type: application/json; charset=utf-8\r\nConnection: close\r\n\r\n".toByteArray())
                                write(out); flush()
                            }
                        }
                    } catch (_: Exception) { }
                }
            }
        }
    }

    private val providers = mutableListOf<Provider>()
    private fun provider(models: String?, vararg chat: Int) = Provider(models, chat.toList()).also { providers += it }

    @After
    fun down() = providers.forEach { it.server.close() }

    private fun listing(vararg modalities: String) =
        """{"data":[{"id":"m","input_modalities":[${modalities.joinToString(",") { "\"$it\"" }}]}]}"""

    private val snapshot = ChatSnapshot("测试", listOf(Msg("other", "看这个")))
    private val picture = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
    private val pictureB64 = java.util.Base64.getEncoder().encodeToString(picture)

    private fun reply(
        p: Provider, image: EphemeralImage?, ownerEnabled: Boolean = true, isLive: () -> Boolean = { true }
    ) = ReplyClient(
        ReplyConfig(ModelRoute(Route.REPLY, p.base, "m", "k"), "", 30, imageEnabled = ownerEnabled),
        isLive, ModelCapabilities(fetch = { it.fetchModels() })
    ).draftBilingual(snapshot, "朋友", image = image)

    @Test
    fun `a model that declares image input gets the picture with the one reply request`() {
        val p = provider(listing("text", "image"), 200)
        val r = reply(p, EphemeralImage(picture))
        assertEquals(ImageUse.ATTACHED, r.imageUse)
        assertEquals(3, r.replies.size)
        assertEquals(1, p.chats.size)
        assertTrue(p.chats[0].body.toString().contains("data:image/jpeg;base64,$pictureB64"))
    }

    @Test
    fun `with the owner switch off no picture byte leaves and the provider is not even asked about images`() {
        val p = provider(listing("text", "image"), 200)
        val r = reply(p, EphemeralImage(picture), ownerEnabled = false)
        assertEquals(ImageUse.TEXT_DISABLED, r.imageUse)
        assertEquals(1, p.chats.size)
        assertFalse(p.chats[0].hasImage)
        assertFalse(p.chats[0].body.toString().contains(pictureB64))
        assertEquals(0, p.modelRequests)
    }

    @Test
    fun `a model that declares text only is sent text only and still answers`() {
        val p = provider(listing("text"), 200)
        val r = reply(p, EphemeralImage(picture))
        assertEquals(ImageUse.TEXT_UNSUPPORTED, r.imageUse)
        assertEquals(3, r.replies.size)
        assertEquals(1, p.chats.size)
        assertFalse(p.chats[0].hasImage)
    }

    @Test
    fun `an unknown capability tries the picture first and keeps the answer`() {
        val p = provider(null, 200)
        val r = reply(p, EphemeralImage(picture))
        assertEquals(ImageUse.ATTACHED, r.imageUse)
        assertEquals(1, p.chats.size)
        assertTrue(p.chats[0].hasImage)
    }

    @Test
    fun `an unknown capability and an explicit refusal of the picture falls back to text exactly once`() {
        val p = provider(null, 400, 200)
        val r = reply(p, EphemeralImage(picture))
        assertEquals(ImageUse.FELL_BACK, r.imageUse)
        assertEquals(2, p.chats.size)
        assertTrue(p.chats[0].hasImage)
        assertFalse(p.chats[1].hasImage)
    }

    @Test
    fun `a rejected key is not turned into a text fallback`() {
        val p = provider(null, 401, 200)
        try { reply(p, EphemeralImage(picture)); throw AssertionError("expected a failure") } catch (e: ApiException) {
            assertEquals(ErrorKind.AUTH, e.kind)
        }
        assertEquals(1, p.chats.size)
    }

    @Test
    fun `a rate limit is retried by the retry policy with the picture still attached, not turned into text`() {
        val p = provider(null, 429, 200)
        val r = reply(p, EphemeralImage(picture))
        assertEquals(ImageUse.ATTACHED, r.imageUse)
        assertEquals(2, p.chats.size)
        assertTrue(p.chats.all { it.hasImage })
    }

    @Test
    fun `a generation that went stale does not make the fallback call`() {
        val p = provider(null, 400, 200)
        try {
            reply(p, EphemeralImage(picture), isLive = { p.chats.isEmpty() })
            throw AssertionError("expected a failure")
        } catch (e: ApiException) {
            assertEquals(ErrorKind.UNSUPPORTED, e.kind)
        }
        assertEquals(1, p.chats.size)
    }

    @Test
    fun `a failure never carries the picture bytes even when the provider echoes the request`() {
        val p = provider(listing("text", "image"), 400)
        try { reply(p, EphemeralImage(picture)); throw AssertionError("expected a failure") } catch (e: ApiException) {
            assertFalse(e.message!!.contains(pictureB64))
            assertFalse(e.message!!.contains("echo:"))
        }
    }

    @Test
    fun `a released picture is never sent`() {
        val p = provider(listing("text", "image"), 200)
        val image = EphemeralImage(picture).also { it.release() }
        val r = reply(p, image)
        assertEquals(ImageUse.NONE, r.imageUse)
        assertFalse(p.chats[0].hasImage)
    }

    @Test
    fun `no picture at all leaves the text request exactly as it was`() {
        val p = provider(listing("text", "image"), 200)
        val r = reply(p, null)
        assertEquals(ImageUse.NONE, r.imageUse)
        assertEquals(0, p.modelRequests)
        assertTrue(p.chats[0].body!!.getJSONArray("messages").getJSONObject(1).get("content") is String)
    }

    // ---- the picture's own lifetime: one reply session

    @Test
    fun `the session hands the picture to the same conversation state again for a reroll`() {
        val image = EphemeralImage(picture)
        val s = ImageSession(image, "wa|A", "other:hi|img:1")
        assertSame(image, s.take("wa|A", "other:hi|img:1"))
        assertSame(image, s.take("wa|A", "other:hi|img:1"))
        assertFalse(image.released)
    }

    @Test
    fun `a picture from one generation cannot enter the next conversation state, and is gone after trying`() {
        val image = EphemeralImage(picture)
        val s = ImageSession(image, "wa|A", "other:hi|img:1")
        assertNull(s.take("wa|A", "other:hi|other:newer|img:1"))   // a new message
        assertTrue(image.released)
        assertNull(s.take("wa|A", "other:hi|img:1"))               // and it does not come back
    }

    @Test
    fun `another conversation cannot use the picture either`() {
        val image = EphemeralImage(picture)
        val s = ImageSession(image, "wa|A", "sig")
        assertNull(s.take("wa|B", "sig"))
        assertTrue(image.released)
    }

    @Test
    fun `releasing wipes the bytes`() {
        val bytes = picture.copyOf()
        val image = EphemeralImage(bytes)
        image.release()
        assertTrue(image.released)
        assertTrue(bytes.all { it == 0.toByte() })
        try { image.base64(); throw AssertionError("expected a failure") } catch (_: IllegalStateException) { }
    }

    companion object {
        private val ENVELOPE = JSONObject().put("choices", org.json.JSONArray().put(JSONObject()
            .put("finish_reason", "stop")
            .put("message", JSONObject().put("content",
                """{"lang":"中文","translation":"","analysis":"一张图","replies":[{"text":"a","zh":""},{"text":"b","zh":""},{"text":"c","zh":""}]}""")))).toString()
    }
}
