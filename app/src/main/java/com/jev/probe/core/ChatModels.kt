package com.jev.probe.core

import android.graphics.Rect

/** One captured chat bubble. side is "me" (right) or "other" (left). */
data class Msg(val side: String, val text: String)

/**
 * A bubble the node tree can locate but not read (Feishu draws its message text
 * itself). [rect] is in screen coordinates; [side] is what the tree could infer
 * around the bubble. The service OCRs each rect to get the words.
 */
data class BubbleRect(val rect: Rect, val side: String)

/**
 * A snapshot of the currently-open conversation in whichever chat app is
 * foreground (see ChatAppAdapter).
 *
 * Adapter contract: `extract` returning null means "not in a chat window".
 * Returning a snapshot whose [messages] is empty means "in a chat window, but
 * the tree holds no text" — that is the OCR fallback's cue, and the one case
 * where [bubbleRects] may be populated.
 *
 * [note] is a caveat about how this snapshot was produced, shown verbatim in
 * the analysis panel (OCR captures cannot tell who said what).
 */
data class ChatSnapshot(
    val title: String?,
    val messages: List<Msg>,
    val bubbleRects: List<BubbleRect> = emptyList(),
    val note: String? = null,
    /** The newest message row, when it is a picture whose whole bubble is on screen (S5). */
    val latestImage: ImageBubble? = null,
    /** How many picture rows end the visible list; the picture has no message id, so a second
     *  one arriving (or the first one leaving) changes this and with it [signature]. */
    val trailingImages: Int = 0,
    /** The toolbar proves a one-to-one chat (S6 [com.jev.probe.capture.ImageHint]); false = unknown, not "group". */
    val directChat: Boolean = false
) {
    val latestFrom: String? get() = messages.lastOrNull()?.side

    /** A stable signature of the last few messages, to detect real changes. */
    fun signature(): String =
        messages.takeLast(6).joinToString("|") { "${it.side}:${it.text}" } +
            if (trailingImages > 0) "|img:$trailingImages" else ""
}

/** [zh] is the Chinese gloss shown under a foreign-language reply in bilingual
 *  mode; only [text] is ever filled into the input box. */
data class RankedReply(val text: String, val prob: Double, val zh: String = "")

/** Bilingual-mode result: the other side's recent messages in Chinese, plus 3
 *  replies in the target language with Chinese glosses. */
data class BilingualResult(
    val translation: String,
    val replies: List<RankedReply>,
    val lang: String = "",
    val analysis: String = "",
    /** 关系提议的三个候选；空 = 没有提议（没要、缺失或格式不对）。 */
    val relationCandidates: List<String> = emptyList(),
    /** What the request did with the picture; [ImageUse.NONE] when it had none. */
    val imageUse: ImageUse = ImageUse.NONE
)
