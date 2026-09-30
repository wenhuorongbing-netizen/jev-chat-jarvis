package com.jev.probe.core

/**
 * Which conversation a candidate reply was generated for. Captured when the
 * result is shown, checked again right before every write into an input box.
 * A title is only an attribute of a conversation, not its identity — when it is
 * missing or transient the visible-message [signature] has to vouch instead.
 */
data class FillTarget(val pkg: String, val title: String?, val signature: String)

/** What a tap on a reply card asks for: this text, into that conversation. Body text only. */
data class FillIntent(val text: String, val target: FillTarget) {
    companion object {
        /** The Chinese gloss ([RankedReply.zh]) is display-only and never part of the fill. */
        fun of(reply: RankedReply, target: FillTarget) = FillIntent(reply.text, target)
    }
}

enum class FillVerdict { ALLOW, DENY_WINDOW, DENY_NOT_CHAT, DENY_TITLE, DENY_UNVERIFIED }

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
        if (livePkg != target.pkg) return FillVerdict.DENY_WINDOW
        if (live == null) return FillVerdict.DENY_NOT_CHAT

        val wanted = target.title?.trim()
        val seen = live.title?.trim()
        if (isUsableTitle(wanted) && isUsableTitle(seen)) {
            return if (wanted == seen) FillVerdict.ALLOW else FillVerdict.DENY_TITLE
        }
        // A title is missing on one side (OCR-only, transient placeholder): the
        // messages on screen must be the same ones the candidate was written for.
        if (target.signature.isNotEmpty() && target.signature == live.signature()) return FillVerdict.ALLOW
        return FillVerdict.DENY_UNVERIFIED
    }
}
