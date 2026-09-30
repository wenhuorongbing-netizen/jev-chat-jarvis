package com.jev.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S2: one owner of "which conversation is current, which generation is wanted".
 * A late network result or OCR callback is dropped by identity, never by comparing titles.
 */
class ConversationStateTest {

    private val qq = "com.tencent.mobileqq"

    private fun cap(title: String?, vararg texts: String, pkg: String = qq, source: SnapshotSource = SnapshotSource.TREE) =
        CapturedSnapshot(ConversationRef(pkg, title), ChatSnapshot(title, texts.map { Msg("other", it) }), source)

    private val a1 = cap("小王", "在吗")
    private val a2 = cap("小王", "在吗", "人呢")
    private val b1 = cap("老李", "吃了吗")

    @Test
    fun `a request is an immutable snapshot and traces back to its conversation`() {
        val s = ConversationState()
        s.observe(a1)
        val req = s.begin(a1)!!
        s.observe(a2)  // the conversation grows afterwards
        assertEquals(a1, req.captured)
        assertEquals("com.tencent.mobileqq|小王", req.captured.conv.key)
        assertEquals("other:在吗", req.captured.signature)
    }

    // ---- A generating, switch to B, A returns
    @Test
    fun `switching to another conversation retires the generation, A's late result is dropped`() {
        val s = ConversationState()
        s.observe(a1)
        val reqA = s.begin(a1)!!
        assertTrue(s.observe(b1))  // user switched to B
        assertFalse(s.finish(reqA))  // A returns: not wanted
        assertNotNull(s.begin(b1))  // and B was never blocked by A
    }

    // ---- Generation N, message N+1, N returns
    @Test
    fun `a new message retires the generation in flight`() {
        val s = ConversationState()
        s.observe(a1)
        val n = s.begin(a1)!!
        assertTrue(s.observe(a2))  // N+1 arrived
        assertFalse(s.finish(n))
        val next = s.begin(a2)!!
        assertTrue(next.id > n.id)
        assertTrue(s.finish(next))
    }

    @Test
    fun `an unchanged read neither retires the generation nor asks for a reset`() {
        val s = ConversationState()
        s.observe(a1)
        val req = s.begin(a1)!!
        assertFalse(s.observe(a1))  // caret blink, same content
        assertTrue(s.finish(req))
    }

    @Test
    fun `the same snapshot is not generated twice at once, a different one is`() {
        val s = ConversationState()
        s.observe(a1)
        assertNotNull(s.begin(a1))
        assertNull(s.begin(a1))  // manual tap while the auto one is in flight
        assertNotNull(s.begin(a2))
    }

    @Test
    fun `after a generation finished the same snapshot can be asked for again`() {
        val s = ConversationState()
        s.observe(a1)
        assertTrue(s.finish(s.begin(a1)!!))
        assertNotNull(s.begin(a1))  // retry after an error, or a manual re-run
    }

    @Test
    fun `a finished generation cannot finish twice`() {
        val s = ConversationState()
        s.observe(a1)
        val req = s.begin(a1)!!
        assertTrue(s.finish(req))
        assertFalse(s.finish(req))
    }

    // ---- Two chats that end with the same messages must still be told apart
    @Test
    fun `another conversation with identical last messages retires the generation`() {
        val s = ConversationState()
        val a = cap("小王", "在吗")
        val b = cap("老李", "在吗")
        s.observe(a)
        val reqA = s.begin(a)!!
        assertTrue(s.observe(b))  // same signature, different chat
        assertFalse(s.finish(reqA))
        assertNotNull(s.begin(b))
    }

    @Test
    fun `a title that flickers to unreadable is not a conversation switch`() {
        val s = ConversationState()
        s.observe(a1)
        val req = s.begin(a1)!!
        assertFalse(s.observe(cap(null, "在吗")))
        assertFalse(s.observe(a1))
        assertTrue(s.finish(req))
    }

    @Test
    fun `switching conversation retires an open OCR pass`() {
        val s = ConversationState()
        s.observe(cap("项目群", "好的", pkg = ref.pkg))
        val t = s.ocrBegin(ref)!!
        s.observe(cap("另一个群", "好的", pkg = ref.pkg))
        assertFalse(s.ocrEnd(t, ref))
    }

    // ---- Cancel / assistant off / left the chat window
    @Test
    fun `invalidate retires the live generation so a late result cannot revive`() {
        val s = ConversationState()
        s.observe(a1)
        val req = s.begin(a1)!!
        s.invalidate()  // overlay hidden: left the chat window, turned off, service destroyed
        assertFalse(s.finish(req))
    }

    @Test
    fun `coming back to the same content after leaving asks for a fresh generation`() {
        val s = ConversationState()
        s.observe(a1)
        val old = s.begin(a1)!!
        s.invalidate()
        val fresh = s.begin(a1)!!
        assertFalse(s.finish(old))  // the zombie changes nothing
        assertTrue(s.finish(fresh))
    }

    @Test
    fun `a forced observe (manual OCR tap) resets even when the signature is the same`() {
        val s = ConversationState()
        assertTrue(s.observe(a1))
        assertFalse(s.observe(a1))
        assertTrue(s.observe(a1, force = true))
    }

    @Test
    fun `switching to another app resets the signature so two apps cannot swallow each other`() {
        val s = ConversationState()
        s.observe(a1)
        assertTrue(s.observe(cap("小王", "在吗", pkg = "com.whatsapp")))
        assertEquals("com.whatsapp", s.activePkg)
    }

    // ---- OCR callbacks
    private val ref = ConversationRef("com.ss.android.lark", "项目群")

    @Test
    fun `an OCR pass for the conversation still on screen is accepted`() {
        val s = ConversationState()
        val t = s.ocrBegin(ref)!!
        assertTrue(s.ocrEnd(t, ref))
    }

    @Test
    fun `a late OCR callback after switching conversation is dropped`() {
        val s = ConversationState()
        val t = s.ocrBegin(ref)!!
        assertFalse(s.ocrEnd(t, ConversationRef(ref.pkg, "另一个群")))
    }

    @Test
    fun `a late OCR callback after leaving the app or with nothing on screen is dropped`() {
        val s = ConversationState()
        assertFalse(s.ocrEnd(s.ocrBegin(ref)!!, ConversationRef("com.tencent.mobileqq", "项目群")))
        assertFalse(s.ocrEnd(s.ocrBegin(ref)!!, null))
    }

    @Test
    fun `an OCR pass retired by invalidate is dropped even if the screen looks the same`() {
        val s = ConversationState()
        val t = s.ocrBegin(ref)!!
        s.invalidate()  // assistant paused / chat window left meanwhile
        assertFalse(s.ocrEnd(t, ref))
    }

    @Test
    fun `an unreadable title on either side is not a mismatch`() {
        val s = ConversationState()
        assertTrue(s.ocrEnd(s.ocrBegin(ConversationRef(ref.pkg, null))!!, ref))
        assertTrue(s.ocrEnd(s.ocrBegin(ref)!!, ConversationRef(ref.pkg, null)))
    }

    @Test
    fun `only one OCR pass is open at a time and ending it - even a failed one - frees the slot`() {
        val s = ConversationState()
        val t = s.ocrBegin(ref)!!
        assertNull(s.ocrBegin(ref))
        s.ocrEnd(t, null)  // screenshot failed
        assertNotNull(s.ocrBegin(ref))
    }

    @Test
    fun `an OCR-derived capture stays marked as OCR all the way to the fill target`() {
        val c = cap("项目群", "好的", pkg = ref.pkg, source = SnapshotSource.OCR)
        val target = FillTarget(c.conv.pkg, c.conv.title, c.signature, fromOcr = c.source == SnapshotSource.OCR)
        assertEquals(FillVerdict.DENY_OCR_ONLY, FillGuard.check(target, ref.pkg, c.snapshot) { true })
    }
}
