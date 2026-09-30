package com.jev.probe.core

/**
 * Which conversation a candidate reply was generated for. Captured when the
 * result is shown, checked again right before every write into an input box.
 * A title is only an attribute of a conversation, not its identity — the
 * visible-message [signature] has to match as well, always. [fromOcr]: the
 * messages were read off a screenshot, which proves nothing about the chat.
 */
data class FillTarget(val pkg: String, val title: String?, val signature: String, val fromOcr: Boolean = false)

/** What a tap on a reply card asks for: this text, into that conversation. Body text only. */
data class FillIntent(val text: String, val target: FillTarget) {
    companion object {
        /** The Chinese gloss ([RankedReply.zh]) is display-only and never part of the fill. */
        fun of(reply: RankedReply, target: FillTarget) = FillIntent(reply.text, target)
    }
}

enum class FillVerdict { ALLOW, DENY_WINDOW, DENY_NOT_CHAT, DENY_TITLE, DENY_UNVERIFIED, DENY_OCR_ONLY }

/**
 * Pure decision: may [FillTarget]'s text be written into whatever is on screen
 * now? [livePkg] is the foreground package; [live] is what that app's adapter
 * reads from the current window (`null` = the adapter says this is not a chat
 * screen — a list, a settings or payment page that merely has an editable node).
 */
object FillGuard {

    fun check(
        target: FillTarget,
        livePkg: String?,
        live: ChatSnapshot?,
        isUsableTitle: (String?) -> Boolean
    ): FillVerdict {
        // OCR-only: no sides, no stable signature — nothing can prove the conversation, so copy.
        if (target.fromOcr) return FillVerdict.DENY_OCR_ONLY
        if (livePkg != target.pkg) return FillVerdict.DENY_WINDOW
        if (live == null) return FillVerdict.DENY_NOT_CHAT

        // Both titles must be readable and equal (same rule as Windows, see fill_verdict.json):
        // matching messages never stand in for a title that cannot be read.
        val wanted = target.title?.trim()
        val seen = live.title?.trim()
        if (!isUsableTitle(wanted) || !isUsableTitle(seen) || wanted != seen) return FillVerdict.DENY_TITLE
        // A title is only a label (two chats can share one), so it never proves
        // the conversation on its own: the visible messages must also be the ones
        // the candidate was written for. Scrolling or a new message means a
        // refusal and a copy — a false refusal is fine, a false fill is not.
        if (target.signature.isNotEmpty() && target.signature == live.signature()) return FillVerdict.ALLOW
        return FillVerdict.DENY_UNVERIFIED
    }
}
