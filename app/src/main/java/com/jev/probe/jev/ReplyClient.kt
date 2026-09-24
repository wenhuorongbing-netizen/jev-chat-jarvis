package com.jev.probe.jev

import com.jev.probe.core.BilingualResult
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.RankedReply
import com.jev.probe.core.Prefs
import com.jev.probe.core.kb.ChatContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The generative route: any OpenAI-compatible `/chat/completions` endpoint.
 * Drafts the 3 candidate replies, and (D stage) summarizes text. Reads
 * replyBaseUrl / replyKey / replyModel from [Prefs].
 */
class ReplyClient(private val prefs: Prefs) {

    /**
     * Exactly 3 varied candidate replies in Chinese.
     *
     * @param ctx D-stage knowledge context. When present its background and
     *        history are prepended to the prompt with an instruction to stay
     *        consistent with them and invent nothing beyond them.
     */
    fun draft(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): List<String> {
        val convo = snapshot.messages.takeLast(10).joinToString("\n") {
            (if (it.side == "me") "我" else "对方") + "：" + it.text
        }
        val sys = "你是中文即时通讯回复助手。只输出一个 JSON 数组，含且仅含 3 条候选回复文本，" +
            "三条策略要有区别（例如：一条稳妥承接、一条给具体行动或承诺、一条简短低姿态）。" +
            "每条不超过 40 字，口语、自然、像真人在聊天软件里发消息。不要解释，不要加引号以外的内容，直接输出 JSON 数组。"
        val user = knowledgeBlock(relationship, ctx) +
            "关系：$relationship\n\n最近对话：\n$convo\n\n请给出 3 条候选回复。"
        return parseThree(chat(sys, user, temperature = 0.8))
    }

    /**
     * Bilingual mode, one round trip: the other side's latest messages rendered
     * in Chinese, plus 3 context-aware replies written in [lang] (the language
     * that gets filled) each with a Chinese gloss. Order = model's preference,
     * so the first card is the recommended one.
     */
    fun draftBilingual(
        snapshot: ChatSnapshot,
        relationship: String,
        lang: String,
        ctx: ChatContext? = null
    ): BilingualResult {
        val convo = snapshot.messages.takeLast(12).joinToString("\n") {
            (if (it.side == "me") "我" else "对方") + "：" + it.text
        }
        val sys = "你是跨语言即时通讯回复助手，用户是中国人，正在和对方用${lang}聊天。" +
            "只输出一个 JSON 对象，格式：" +
            "{\"translation\":\"对方最近连续几条消息的中文翻译（原文已是中文则照抄）\"," +
            "\"replies\":[{\"text\":\"${lang}回复\",\"zh\":\"这条回复的中文意思\"}, …共3条]}。" +
            "先读懂上下文和对方真实意图，再写回复。3 条回复策略要有区别（例如：一条稳妥承接、" +
            "一条给具体行动或承诺、一条简短轻松），按最推荐到最不推荐排序。" +
            "text 必须是地道、口语化、像母语者在聊天软件里发的${lang}，不超过 60 词；" +
            "zh 是忠实的中文对照。不要解释，不要输出 JSON 以外的任何内容。"
        val user = knowledgeBlock(relationship, ctx) +
            "关系：$relationship\n\n最近对话：\n$convo\n\n请翻译对方的最新消息，并给出 3 条${lang}回复。"
        return parseBilingual(chat(sys, user, temperature = 0.7))
    }

    private fun parseBilingual(content: String): BilingualResult {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) throw IllegalStateException("模型没有返回 JSON：${content.take(80)}")
        val obj = JSONObject(content.substring(start, end + 1))
        val arr = obj.optJSONArray("replies") ?: JSONArray()
        val replies = ArrayList<RankedReply>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val text = r.optString("text").trim()
            if (text.isNotEmpty()) replies.add(RankedReply(text, 0.0, r.optString("zh").trim()))
        }
        if (replies.isEmpty()) throw IllegalStateException("模型没有给出候选回复")
        return BilingualResult(obj.optString("translation").trim(), replies.take(3))
    }

    /** The background + history preamble; empty string when there is no context. */
    private fun knowledgeBlock(relationship: String, ctx: ChatContext?): String {
        ctx ?: return ""
        val background = ctx.background(relationship)
        val history = ctx.history
        if (background.isBlank() && history.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("以下是关于我和对方的背景与知识库，回复必须与之一致，")
            .append("可以直接引用其中事实，不要编造知识库里没有的事实。\n")
        if (background.isNotBlank()) sb.append(background).append('\n')
        if (history.isNotEmpty()) {
            sb.append("\n更早的聊天记录（越靠下越新）：\n")
            history.takeLast(prefs.contextHistoryCount.coerceIn(0, 100)).forEach {
                sb.append(if (it.side == "me") "我：" else "对方：").append(it.text).append('\n')
            }
        }
        sb.append('\n')
        return sb.toString()
    }

    /**
     * One plain chat round trip for the settings connectivity test. Deliberately
     * NOT [summarize]: the test should exercise the ordinary path, not whatever
     * the summary prompt happens to be.
     */
    fun ping(): String =
        chat("你是连通性测试助手，只按要求回答，不要解释。", "请只回复两个字：收到", temperature = 0.0).trim()

    /** Condense a block of text (used by the D-stage contact auto-summary). */
    fun summarize(text: String): String {
        if (text.isBlank()) return ""
        val sys = "你是中文摘要助手。把给到的聊天记录压缩成不超过 120 字的第三人称要点摘要，" +
            "只保留事实、偏好、承诺和待办，不要评论，不要编造。直接输出摘要正文。"
        return chat(sys, text, temperature = 0.2).trim()
    }

    /** One chat-completions round trip; returns the assistant message content. */
    private fun chat(system: String, user: String, temperature: Double): String {
        val url = prefs.replyEndpoint()
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", prefs.replyModel)
            .put("messages", messages)
            .put("temperature", temperature)
        val resp = HttpJson.post(url, prefs.effectiveReplyKey(), body, Route.REPLY, HttpJson.headersFor(url))
        return resp.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content") ?: ""
    }

    private fun parseThree(content: String): List<String> {
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        if (start >= 0 && end > start) {
            try {
                val arr = JSONArray(content.substring(start, end + 1))
                val out = ArrayList<String>()
                for (i in 0 until arr.length()) out.add(arr.getString(i).trim())
                if (out.size >= 3) return out.take(3)
                while (out.size < 3) out.add("（稍等，我看下）")
                return out
            } catch (_: Exception) { }
        }
        // Fallback: split lines.
        val lines = content.split("\n").map { it.trim().trimStart('-', '*', '1', '2', '3', '.', ' ', '"') }
            .filter { it.isNotBlank() }
        val out = lines.take(3).toMutableList()
        while (out.size < 3) out.add("（稍等，我看下）")
        return out
    }
}
