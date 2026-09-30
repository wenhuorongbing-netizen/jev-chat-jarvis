package com.jev.probe.contract

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.FillGuard
import com.jev.probe.core.FillSupportMap
import com.jev.probe.core.FillTarget
import com.jev.probe.core.FillVerdict
import com.jev.probe.core.Msg
import com.jev.probe.core.VisionRoute
import com.jev.probe.jev.ApiException
import com.jev.probe.jev.ReplyParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The Android consumer of contracts/jev/v1 (the Windows repo carries a byte-identical
 * mirror and its own consumer). Same vectors, same expectations: whoever changes
 * behaviour turns a test red first. Rules for changing a vector: contracts/jev/v1/README.md.
 */
class ContractV1Test {

    private val dir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "contracts/jev/v1") }.first { it.isDirectory }

    private fun doc(name: String) = JSONObject(File(dir, name).readText(Charsets.UTF_8))

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    /** JSON null -> Kotlin null (org.json's optString would give the string "null"). */
    private fun JSONObject.strOrNull(key: String): String? = if (isNull(key)) null else getString(key)

    @Test
    fun `manifest matches the files byte for byte`() {
        val listed = File(dir, "MANIFEST.sha256").readLines().filter { it.isNotBlank() }
            .associate { it.substringAfter(" *") to it.substringBefore(" *") }
        val onDisk = dir.listFiles()!!.filter { it.isFile && it.name != "MANIFEST.sha256" && it.name != ".gitattributes" }
            .map { it.name }.toSet()
        assertEquals(onDisk, listed.keys)
        for ((name, digest) in listed) {
            val sha = MessageDigest.getInstance("SHA-256").digest(File(dir, name).readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals(name, digest, sha)
        }
    }

    @Test
    fun `origin vectors`() {
        for (c in doc("origin.json").getJSONArray("origin_cases").objects()) {
            assertEquals(c.getString("name"), c.strOrNull("origin"), VisionRoute.originString(c.getString("url")))
        }
    }

    @Test
    fun `same origin vectors`() {
        for (c in doc("origin.json").getJSONArray("same_origin_cases").objects()) {
            assertEquals(c.getString("name"), c.getBoolean("same"), VisionRoute.sameOrigin(c.getString("a"), c.getString("b")))
        }
    }

    @Test
    fun `http hint vectors`() {
        for (c in doc("http_hint.json").getJSONArray("cases").objects()) {
            assertEquals("status ${c.getInt("status")}", c.getString("hint"), ApiException.hintFor(c.getInt("status")))
        }
    }

    @Test
    fun `reply parse vectors`() {
        val d = doc("reply_parse.json")
        val errors = d.getJSONObject("errors")
        for (c in d.getJSONArray("cases").objects()) {
            val name = c.getString("name")
            if (c.has("error")) {
                val e = try { ReplyParser.parseBilingual(c.getString("content")); null } catch (e: IllegalStateException) { e }
                assertEquals(name, errors.getString(c.getString("error")), e?.message)
                if (c.has("must_not_leak")) assertFalse(name, e!!.message!!.contains(c.getString("must_not_leak")))
            } else {
                val got = ReplyParser.parseBilingual(c.getString("content"))
                val want = c.getJSONObject("expect")
                assertEquals(name, want.getString("lang"), got.lang)
                assertEquals(name, want.getString("analysis"), got.analysis)
                assertEquals(name, want.getString("translation"), got.translation)
                assertEquals(name, want.getJSONArray("candidates").strings(), got.replies.map { it.text })
                assertEquals(name, want.getJSONArray("glosses").strings(), got.replies.map { it.zh })
            }
        }
    }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    @Test
    fun `fill verdict vectors`() {
        val usable: (String?) -> Boolean = { !it.isNullOrBlank() }
        fun snap(title: String?, msgs: JSONArray) = ChatSnapshot(title, (0 until msgs.length()).map {
            val m = msgs.getJSONArray(it)
            Msg(if (m.getString(0) == "her") "other" else "me", m.getString(1))
        })
        for (c in doc("fill_verdict.json").getJSONArray("cases").objects()) {
            val target = c.getJSONObject("target")
            val live = c.getJSONObject("live")
            val t = FillTarget("pkg", target.getString("conversation"), snap(null, target.getJSONArray("messages")).signature())
            val liveSnap = if (live.getBoolean("on_chat_screen")) snap(live.strOrNull("conversation"), live.getJSONArray("messages")) else null
            val verdict = FillGuard.check(t, "pkg", liveSnap, usable)
            val want = c.optString("expect_android", c.getString("expect"))
            assertEquals(c.getString("name"), want, if (verdict == FillVerdict.ALLOW) "allow" else "deny")
        }
    }

    @Test
    fun `every divergence is explained`() {
        for (c in doc("fill_verdict.json").getJSONArray("cases").objects()) {
            if (c.has("expect_android") || c.has("expect_windows")) assertTrue(c.getString("name"), c.optString("divergence").isNotBlank())
        }
    }

    @Test
    fun `fill support matches the contract`() {
        val d = doc("fill_support.json")
        val statuses = (0 until d.getJSONArray("statuses").length()).map { d.getJSONArray("statuses").getString(it) }.toSet()
        val android = d.getJSONArray("entries").objects().filter { it.getString("platform") == "android" }
            .associate { it.getString("app") to it.getString("status") }
        assertEquals(android, FillSupportMap.byApp.mapValues { it.value.wire })
        assertTrue(statuses.containsAll(android.values))
        assertEquals(FillSupportMap.byApp.keys, FillSupportMap.packages.keys)
        // The package names must be the adapters' own (the declaration cannot drift from the code).
        val adapters = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/java/com/jev/probe/capture/ChatAppAdapter.kt") }.first { it.isFile }.readText()
        for ((app, pkg) in FillSupportMap.packages) assertTrue("$app: $pkg", adapters.contains("\"$pkg\""))
    }

}
