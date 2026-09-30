package com.jev.probe.core

/** Which conversation: the app and its title. A title is only a label (two chats can share
 *  one); what the snapshot shows is the proof. [key] is the per-conversation cache/memory key. */
data class ConversationRef(val pkg: String, val title: String?) {
    val key: String get() = pkg + "|" + (title ?: "")
}

/** Where the messages of a snapshot came from. OCR carries no sides and no stable proof. */
enum class SnapshotSource { TREE, OCR }

/** What the screen showed when it was read. Immutable; the network only ever sees this. */
data class CapturedSnapshot(val conv: ConversationRef, val snapshot: ChatSnapshot, val source: SnapshotSource) {
    val signature: String get() = snapshot.signature()
}

/** One generation. Its [id] is what decides whether its result may still be shown. */
data class GenerationRequest(val id: Long, val captured: CapturedSnapshot)

/** One screenshot + OCR pass, bound to the conversation it was started for. */
class OcrTicket internal constructor(internal val epoch: Long, internal val ref: ConversationRef)

/**
 * The single owner of "which conversation is current and which generation is wanted".
 * Capture, network and UI only report events to it; nothing else writes these fields.
 *
 * A result is wanted only while its [GenerationRequest.id] is the live one. Any change of
 * conversation or content ([observe]), leaving the chat window or turning the assistant off
 * ([invalidate]) retires the live generation, so a late network result or OCR callback
 * can never update a newer state. Main thread only.
 */
class ConversationState {
    var activePkg: String? = null
        private set

    /** What was last read from the screen; other threads may read it. */
    @Volatile var current: CapturedSnapshot? = null
        private set

    /** The snapshot the next automatic or manual generation will use. */
    var pending: CapturedSnapshot? = null
        private set

    private var lastSignature = ""
    private var lastKnown: ConversationRef? = null  // last observed ref with a readable title
    private var nextId = 0L
    private var live: GenerationRequest? = null
    private var ocrEpoch = 0L
    private var ocrOpen: OcrTicket? = null

    /**
     * Report a snapshot just read. Returns true when it is another app, another conversation
     * (both titles readable and different — two chats can end with the same messages), new visible
     * messages, or [force] (e.g. a manual OCR tap): the live generation and any open OCR pass are
     * then retired and the caller resets what the overlay shows. False when nothing changed.
     * An unreadable title never counts as a switch, so a title that flickers does not regenerate.
     */
    fun observe(c: CapturedSnapshot, force: Boolean = false): Boolean {
        var switched = false
        if (c.conv.pkg.isNotEmpty() && c.conv.pkg != activePkg) { activePkg = c.conv.pkg; switched = true }
        if (c.conv.title != null) {
            val prev = lastKnown
            if (prev != null && prev.pkg == c.conv.pkg && prev.title != c.conv.title) switched = true
            lastKnown = c.conv
        }
        if (switched) { lastSignature = ""; ocrEpoch++ }
        current = c
        if (!force && c.signature == lastSignature) return false
        lastSignature = c.signature
        live = null
        return true
    }

    fun pend(c: CapturedSnapshot) { pending = c }

    /** Ask for a generation of [c]. Null when the same snapshot is already being generated. */
    fun begin(c: CapturedSnapshot): GenerationRequest? {
        live?.let { if (it.captured == c) return null }
        return GenerationRequest(++nextId, c).also { live = it }
    }

    /** The network thread came back (success or error). True = this is still the wanted generation. */
    fun finish(req: GenerationRequest): Boolean {
        if (live?.id != req.id) return false
        live = null
        return true
    }

    /** The chat window was left, the assistant was turned off, or the service is going away. */
    fun invalidate() {
        live = null
        ocrEpoch++
    }

    /** Start a screenshot + OCR pass for [ref]; null while another one is open. */
    fun ocrBegin(ref: ConversationRef): OcrTicket? {
        if (ocrOpen != null) return null
        return OcrTicket(ocrEpoch, ref).also { ocrOpen = it }
    }

    /**
     * The OCR pass came back (always call, also on failure). True only when nothing retired it
     * meanwhile and [liveRef] (what is on screen now, null = unknown) is still the conversation
     * it was started for; an unreadable title on either side is not a mismatch (OCR-only
     * results are copy-only anyway, see [FillGuard]).
     */
    fun ocrEnd(t: OcrTicket, liveRef: ConversationRef?): Boolean {
        if (ocrOpen === t) ocrOpen = null
        if (t.epoch != ocrEpoch || liveRef == null || liveRef.pkg != t.ref.pkg) return false
        return t.ref.title == null || liveRef.title == null || t.ref.title == liveRef.title
    }
}
