package com.jev.probe.capture

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.kb.KbStore

/**
 * S6 confirm-first: when may JEV point out "the other side just sent a picture"?
 *
 * Only a pointer. Nothing is encoded, no capability is asked, no request is made and no pixel leaves
 * before the user taps; the tap goes into the S5 manual picture path, which re-reads the screen itself.
 *
 * Offered only where there is real-device evidence for it (PO R12): the stock WhatsApp app, a chat
 * the toolbar proves to be one-to-one, the newest row is a whole picture and it is the other side's.
 * "Other" is a position, not an identity, so a group never gets the pointer; a chat whose kind cannot
 * be proven (status line missing or in a language not listed) gets none either: failing quiet costs
 * one tap, guessing wrong costs a picture.
 */
object ImageHint {

    /**
     * A one-to-one chat's toolbar subtitle is a presence line; a group's is the list of its members.
     * "在线" is seen on a real device (WhatsApp 2.26.37.73, zh-CN); the others are the same line in the
     * other UI languages the owner uses and are NOT verified — a miss only means no pointer.
     */
    private val PRESENCE = Regex("^(在线|online|最后上线.*|last seen.*|zuletzt online.*|正在输入.*|typing.*)$", RegexOption.IGNORE_CASE)

    fun directChatProof(status: String?): Boolean =
        !status.isNullOrBlank() && PRESENCE.matches(status.trim())

    fun offered(pkg: String, snapshot: ChatSnapshot, ownerEnabled: Boolean): Boolean =
        ownerEnabled &&
            pkg == WhatsAppAdapter.PKG &&  // not Business: no sample
            snapshot.latestImage?.side == "other" &&
            snapshot.directChat &&
            !KbStore.isGroupTitle(snapshot.title)
}

/**
 * What the pointer remembers: which picture was already taken up ([id] = conversation + what the
 * screen said), so the same one is not offered again. A tap on a pointer whose picture is no longer what
 * the service currently holds does nothing — the S5 path is never entered for a picture the user did not see offered.
 */
class ImagePointer {
    private var spent: String? = null

    fun offer(id: String, eligible: Boolean): Boolean = eligible && id != spent

    /** true = the pointer still points at what the service holds now, so the tap may go on into the manual picture path. */
    fun mayTap(id: String, currentId: String?): Boolean = currentId == id

    /** A crop was taken for this picture (from the pointer or the bubble menu): do not offer it again. */
    fun spend(id: String) { spent = id }
}
