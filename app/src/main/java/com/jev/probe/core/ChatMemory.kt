package com.jev.probe.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Local-only memory that makes replies sound like the user, without screenshots.
 *
 * - Transcript: the accessibility tree only exposes the ~6-10 bubbles on screen,
 *   so each chat's visible window is stitched onto a per-chat rolling log. The
 *   prompt then sees the last few dozen messages as plain text (cheap: ~1.5k
 *   tokens) instead of re-sending screenshots of the whole history.
 * - Style: the user's own sent messages across all chats, used as examples of
 *   how they actually type (length, punctuation, emoji, casing).
 *
 * Everything stays in the app's private files dir; nothing is uploaded except as
 * part of the prompt for the analysis the user triggered. Every caller must gate
 * writes AND prompt injection behind Prefs.contextEnabled ("记录聊天历史"): when
 * it is off, nothing about the chats reaches this class at all.
 */
class ChatMemory internal constructor(private val dir: File) {

    /**
     * Merge the visible window into this chat's log and return the log.
     *
     * [atBottom] = the message list cannot scroll further down, i.e. the window
     * ends at the newest message. A window that does not overlap the log's tail
     * is only appended when it is at the bottom (new messages arrived while we
     * were away) — otherwise the user has scrolled up and it is older history
     * that must not be glued on as if it were new.
     */
    @Synchronized
    fun merge(key: String, visible: List<Msg>, atBottom: Boolean): List<Msg> {
        val log = load(key)
        val merged = decideMerge(log, visible, atBottom)?.takeLast(MAX_LOG) ?: return log
        save(key, merged)
        merged.filter { it.side == "me" }.takeLast(visible.size).forEach { addStyle(it.text) }
        return merged
    }

    /** The user's recent own messages (newest last), for style imitation. */
    @Synchronized
    fun styleSamples(n: Int): List<String> = loadStyle().takeLast(n)

    private fun addStyle(text: String) {
        val t = text.trim()
        if (t.isEmpty() || t.length > 200) return
        val list = loadStyle().filter { it != t }.toMutableList()
        list.add(t)
        val arr = JSONArray(); list.takeLast(MAX_STYLE).forEach { arr.put(it) }
        dir.mkdirs()   // survives clearAll() wiping the dir between calls
        styleFile.writeText(arr.toString())
    }

    private val styleFile get() = File(dir, "my_style.json")

    private fun loadStyle(): List<String> = runCatching {
        val arr = JSONArray(styleFile.readText())
        List(arr.length()) { arr.getString(it) }
    }.getOrDefault(emptyList())

    // The file name is derived from the key with SHA-256 (first 16 hex chars):
    // stable across processes and collision-resistant, unlike String.hashCode.
    // Files written by the old hashCode scheme are simply orphaned (never read
    // again) and wiped together with everything else by KbStore.clearAll().
    private fun file(key: String) = File(dir, "t_" + keyHash(key) + ".json")

    private fun load(key: String): List<Msg> {
        val f = file(key)
        if (!f.exists()) return emptyList()
        return parseLog(key, runCatching { f.readText() }.getOrDefault(""))
    }

    private fun save(key: String, msgs: List<Msg>) {
        val arr = JSONArray()
        msgs.forEach { arr.put(JSONObject().put("s", it.side).put("t", it.text)) }
        dir.mkdirs()   // survives clearAll() wiping the dir between calls
        file(key).writeText(JSONObject().put("k", key).put("m", arr).toString())
    }

    companion object {
        private const val MAX_LOG = 120
        private const val MAX_STYLE = 80
        const val GAP = "（中间有几条没读到）"

        @Volatile private var inst: ChatMemory? = null
        fun get(ctx: Context): ChatMemory = inst ?: synchronized(this) {
            inst ?: ChatMemory(File(ctx.filesDir, "memory").apply { mkdirs() }).also { inst = it }
        }

        /** Storage key hash: first 16 hex chars of SHA-256(key). */
        internal fun keyHash(key: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(16)
        }

        /**
         * The merge decision with no I/O: what the log becomes after seeing
         * [visible], or null when the log stays as it is (nothing new, window
         * already contained, or a disconnected window while scrolled up).
         */
        internal fun decideMerge(log: List<Msg>, visible: List<Msg>, atBottom: Boolean): List<Msg>? {
            if (visible.isEmpty()) return null
            if (log.isEmpty()) return visible
            if (containsRun(log, visible)) return null
            val k = overlap(log, visible)
            return when {
                k > 0 -> log + visible.drop(k)
                atBottom -> log + Msg("gap", GAP) + visible
                else -> null
            }
        }

        /**
         * Parse a saved log, requiring the stored "k" to equal [key]. A mismatch
         * means a file-name collision or corruption, and the safe answer is an
         * empty log rather than another chat's messages.
         */
        internal fun parseLog(key: String, text: String): List<Msg> {
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
            if (obj.optString("k") != key) return emptyList()
            return runCatching {
                val arr = obj.getJSONArray("m")
                List(arr.length()) { i ->
                    arr.getJSONObject(i).let { o -> Msg(o.getString("s"), o.getString("t")) }
                }
            }.getOrDefault(emptyList())
        }

        private fun same(a: Msg, b: Msg) = a.side == b.side && a.text == b.text

        /** Largest k such that the log's last k messages equal the window's first k. */
        internal fun overlap(log: List<Msg>, win: List<Msg>): Int {
            for (k in minOf(log.size, win.size) downTo 1) {
                val off = log.size - k
                if ((0 until k).all { same(log[off + it], win[it]) }) return k
            }
            return 0
        }

        /** Whether [win] already appears as a contiguous run inside [log]. */
        internal fun containsRun(log: List<Msg>, win: List<Msg>): Boolean {
            if (win.size > log.size) return false
            for (s in 0..log.size - win.size) {
                if ((win.indices).all { same(log[s + it], win[it]) }) return true
            }
            return false
        }
    }
}
