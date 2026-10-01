package com.jev.probe.contract

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.capture.ChatAdapters
import com.jev.probe.core.FillGuard
import com.jev.probe.core.FillSupportMap
import com.jev.probe.core.FillTarget
import com.jev.probe.core.FillVerdict
import com.jev.probe.core.Msg
import com.jev.probe.core.VisionRoute
import com.jev.probe.jev.ApiException
import com.jev.probe.jev.ChatReply
import com.jev.probe.jev.ErrorKind
import com.jev.probe.jev.InvalidResponseException
import com.jev.probe.jev.ModelCapabilities
import com.jev.probe.jev.ModelRoute
import com.jev.probe.jev.Retry
import com.jev.probe.jev.Route
import com.jev.probe.jev.CapState
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

    // ---------------------------------------------------------------- S4: capability

    @Test
    fun `capability parse vectors`() {
        for (c in doc("capability.json").getJSONArray("parse_cases").objects()) {
            val got = ModelCapabilities.parseImage(c.getString("base"), c.getString("body"), c.getString("model"))
            val want = c.getJSONObject("expect")
            assertEquals(c.getString("name"), want.getString("state") to want.getString("evidence"), got.state.id to got.evidence.id)
        }
    }

    @Test
    fun `capability fetch vectors - a failed lookup is unknown and never cached`() {
        for (c in doc("capability.json").getJSONArray("fetch_cases").objects()) {
            val outcome = c.getString("outcome")
            var calls = 0
            val caps = ModelCapabilities(fetch = {
                calls++
                when {
                    outcome.startsWith("http_") -> throw ApiException(Route.REPLY, outcome.removePrefix("http_").toInt(), "x")
                    outcome == "timeout" -> throw ApiException(Route.REPLY, null, "x", ErrorKind.TIMEOUT)
                    else -> throw ApiException(Route.REPLY, null, "x")
                }
            })
            val route = ModelRoute(Route.REPLY, "https://api.example.com/v1", "m", if (outcome == "no_key") "" else "k")
            val want = c.getJSONObject("expect")
            val got = caps.imageInput(route)
            assertEquals(c.getString("name"), want.getString("state") to want.getString("evidence"), got.state.id to got.evidence.id)
            caps.imageInput(route)
            assertEquals(c.getString("name"), if (want.getBoolean("cached")) 1 else (if (outcome == "no_key") 0 else 2), calls)
        }
    }

    @Test
    fun `capability cache key vectors`() {
        for (c in doc("capability.json").getJSONArray("cache_key_cases").objects()) {
            val a = c.getJSONObject("a")
            val b = c.getJSONObject("b")
            val ka = ModelCapabilities.cacheKey(a.getString("base"), a.getString("model"))
            val kb = ModelCapabilities.cacheKey(b.getString("base"), b.getString("model"))
            assertEquals(c.getString("name"), c.getBoolean("same"), ka != null && ka == kb)
        }
    }

    @Test
    fun `image decision vectors`() {
        for (c in doc("capability.json").getJSONArray("image_decision_cases").objects()) {
            val cap = CapState.values().first { it.id == c.getString("capability") }
            val got = ModelCapabilities.decideImage(cap, c.getBoolean("owner_enabled"), c.getBoolean("client_can_attach"))
            val want = c.getJSONObject("expect")
            assertEquals(c.getString("name"), listOf(want.getString("effective"), want.getBoolean("attach"), want.getBoolean("fall_back_to_text"), want.getString("reason")),
                listOf(got.effective.id, got.attach, got.fallBackToText, got.reason))
        }
    }

    // ---------------------------------------------------------------- S4: retry policy

    @Test
    fun `retry table vectors`() {
        val d = doc("retry_policy.json")
        val table = d.getJSONObject("retry")
        assertEquals(table.keys().asSequence().toSet(), ErrorKind.values().map { it.id }.toSet())
        for (k in ErrorKind.values()) {
            val want = table.getJSONObject(k.id)
            assertEquals(k.id, want.getBoolean("retry"), k.retries)
            assertEquals(k.id, want.getInt("max_attempts"), k.maxAttempts)
        }
    }

    @Test
    fun `status class vectors`() {
        for (c in doc("retry_policy.json").getJSONArray("status_cases").objects()) {
            assertEquals("status ${c.getInt("status")}", c.getString("class"), ErrorKind.ofStatus(c.getInt("status")).id)
        }
    }

    private fun failureFor(code: String): Exception = when {
        code.startsWith("http_") -> code.removePrefix("http_").toInt().let { ApiException(Route.REPLY, it, ApiException.hintFor(it)) }
        code == "timeout" -> ApiException(Route.REPLY, null, "x", ErrorKind.TIMEOUT)
        code == "transport" -> ApiException(Route.REPLY, null, "x")
        code == "invalid" -> InvalidResponseException("x")
        code == "cancelled" -> java.util.concurrent.CancellationException("x")
        // Android has no key-binding gate (same-origin rule instead); the class the contract gives it is auth.
        code == "route_mismatch" -> ApiException(Route.REPLY, null, "x", ErrorKind.AUTH)
        else -> error(code)
    }

    @Test
    fun `failure class vectors`() {
        for (c in doc("retry_policy.json").getJSONArray("failure_cases").objects()) {
            val code = c.getString("failure")
            assertEquals(code, c.getString("class"), ErrorKind.of(failureFor(code))!!.id)
        }
    }

    @Test
    fun `retry flow vectors run through the production Retry`() {
        for (c in doc("retry_policy.json").getJSONArray("flow_cases").objects()) {
            val attempts = c.getJSONArray("attempts").strings()
            val live = c.getJSONArray("live").let { a -> (0 until a.length()).map { a.getBoolean(it) } }.toMutableList()
            var calls = 0
            var pauses = 0
            val outcome = try {
                Retry.run(
                    isLive = { if (live.isEmpty()) true else live.removeAt(0) },
                    pause = { pauses++ },
                    attempt = { n -> calls++; attempts[n - 1].let { if (it == "ok") "ok" else throw failureFor(it) } }
                )
            } catch (e: Exception) { ErrorKind.of(e)!!.id }
            val want = c.getJSONObject("expect")
            assertEquals(c.getString("name"), listOf(want.getInt("calls"), want.getString("outcome"), want.getInt("pauses")), listOf(calls, outcome, pauses))
        }
    }

    // ---------------------------------------------------------------- S4: reply outcome

    @Test
    fun `reply outcome vectors`() {
        val d = doc("reply_outcome.json")
        val errors = d.getJSONObject("errors")
        for (c in d.getJSONArray("cases").objects()) {
            val name = c.getString("name")
            val env = c.getJSONObject("envelope")
            val reply = ChatReply(env.strOrNull("content").orEmpty(), env.strOrNull("refusal").orEmpty(), env.strOrNull("finish_reason").orEmpty())
            if (c.has("error")) {
                val e = try { ReplyParser.parseBilingual(reply); null } catch (e: InvalidResponseException) { e }
                assertEquals(name, errors.getString(c.getString("error")), e?.message)
                assertEquals(name, ErrorKind.INVALID_RESPONSE, ErrorKind.of(e!!))
                if (c.has("must_not_leak")) assertFalse(name, e.message!!.contains(c.getString("must_not_leak")))
            } else {
                val got = ReplyParser.parseBilingual(reply)
                val want = c.getJSONObject("expect")
                if (want.has("lang")) assertEquals(name, want.getString("lang"), got.lang)
                if (want.has("analysis")) assertEquals(name, want.getString("analysis"), got.analysis)
                if (want.has("translation")) assertEquals(name, want.getString("translation"), got.translation)
                assertEquals(name, want.getJSONArray("candidates").strings(), got.replies.map { it.text })
                if (want.has("glosses")) assertEquals(name, want.getJSONArray("glosses").strings(), got.replies.map { it.zh })
            }
        }
    }

    @Test
    fun `the envelope is read from a real chat-completions response`() {
        fun env(json: String) = ChatReply.of(JSONObject(json))
        val ok = env("""{"choices":[{"finish_reason":"stop","message":{"content":"{\"replies\":[\"a\"]}"}}]}""")
        assertEquals("a", ReplyParser.parseBilingual(ok).replies.single().text)
        val refused = env("""{"choices":[{"finish_reason":"stop","message":{"content":null,"refusal":"no"}}]}""")
        assertEquals("模型拒绝回答这条消息", try { ReplyParser.parseBilingual(refused); "" } catch (e: InvalidResponseException) { e.message })
        val nullContent = env("""{"choices":[{"message":{"content":null}}]}""")
        assertEquals("", nullContent.content)  // not the word "null"
        assertEquals("", env("""{}""").content)
    }

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
    fun `production fill capability equals the contract declaration`() {
        val d = doc("fill_support.json")
        val statuses = (0 until d.getJSONArray("statuses").length()).map { d.getJSONArray("statuses").getString(it) }.toSet()
        val declared = d.getJSONArray("entries").objects().filter { it.getString("platform") == "android" }
            .associate { it.getString("app") to it.getString("status") }
        assertTrue(statuses.containsAll(declared.values))
        // What the adapters actually do (the fill path obeys ChatAppAdapter.fillSupport), grouped by contract app id.
        val production = ChatAdapters.all.groupBy({ FillSupportMap.appOf(it.pkg) ?: error("no contract app for ${it.pkg}") }) { it.fillSupport.wire }
        for ((app, wires) in production) assertEquals("$app: adapters of one app must agree", 1, wires.toSet().size)
        assertEquals(declared, production.mapValues { it.value.first() })
        // Every package the contract lists is really an adapter's package.
        assertEquals(FillSupportMap.packages.values.flatten().toSet(), ChatAdapters.all.map { it.pkg }.toSet())
    }

}
