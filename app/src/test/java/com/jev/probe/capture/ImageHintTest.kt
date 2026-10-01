package com.jev.probe.capture

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.ImageBubble
import com.jev.probe.core.Msg
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

/**
 * S6 confirm-first (PO R12): when the pointer "对方发来一张图 · 识别并回复" is offered, and what a tap on it may do.
 * The pointer only points; every case here is about whether it appears and whether a tap is honoured.
 */
class ImageHintTest {

    private val other = ImageBubble(68, 1260, 874, 2388, "other")
    private fun snap(
        image: ImageBubble? = other, direct: Boolean = true, title: String? = "Anna"
    ) = ChatSnapshot(title, listOf(Msg("other", "hi")), latestImage = image, directChat = direct)

    private fun offered(s: ChatSnapshot = snap(), pkg: String = WhatsAppAdapter.PKG, on: Boolean = true) =
        ImageHint.offered(pkg, s, on)

    @Test fun `the other side's whole picture in a proven one-to-one chat is offered`() = assertTrue(offered())

    @Test fun `owner switch off means no pointer`() = assertFalse(offered(on = false))
    @Test fun `my own picture is not offered`() = assertFalse(offered(snap(image = other.copy(side = "me"))))
    @Test fun `no whole latest picture is not offered`() = assertFalse(offered(snap(image = null)))
    @Test fun `a chat not proven one-to-one is not offered`() = assertFalse(offered(snap(direct = false)))
    @Test fun `a group title is not offered even with presence evidence`() = assertFalse(offered(snap(title = "Family (12)")))
    @Test fun `other apps and WhatsApp Business are not offered`() {
        assertFalse(offered(pkg = "com.tencent.mobileqq"))
        assertFalse(offered(pkg = WhatsAppAdapter.PKG_BUSINESS))
    }

    @Test fun `a presence line proves a direct chat and a member list does not`() {
        assertTrue(ImageHint.directChatProof("在线"))
        assertTrue(ImageHint.directChatProof(" 在线 "))
        // the same line in other languages and states is not sampled on a device yet: no proof
        assertFalse(ImageHint.directChatProof("online"))
        assertFalse(ImageHint.directChatProof("last seen today at 10:00"))
        assertFalse(ImageHint.directChatProof("最后上线 今天 10:00"))
        assertFalse(ImageHint.directChatProof("正在输入…"))
        assertFalse(ImageHint.directChatProof("你, Anna, Bob"))
        assertFalse(ImageHint.directChatProof("给自己发消息"))
        assertFalse(ImageHint.directChatProof(""))
        assertFalse(ImageHint.directChatProof(null))
    }

    // ---- what a tap may do

    @Test fun `a picture that was taken up is not offered again, another one is`() {
        val p = ImagePointer()
        assertTrue(p.offer("a", eligible = true))
        p.spend("a")  // a crop was taken, from the pointer or from the bubble menu
        assertFalse(p.offer("a", eligible = true))
        assertTrue("a different picture is offered again", p.offer("b", eligible = true))
    }

    @Test fun `a tap on a pointer that no longer matches the screen goes nowhere`() {
        val p = ImagePointer()
        assertTrue(p.mayTap("a", currentId = "a"))
        assertFalse("new message / chat switch since the pointer was drawn", p.mayTap("a", currentId = "b"))
        assertFalse("screen no longer qualifies or is not a readable chat", p.mayTap("a", currentId = null))
        assertTrue("a refused tap spends nothing", p.offer("a", eligible = true))
    }

    @Test fun `an ineligible screen is never offered`() = assertFalse(ImagePointer().offer("a", eligible = false))

    // ---- the toolbar evidence read off the node tree (status text is redacted in the committed fixtures)

    private class Override(private val e: org.w3c.dom.Element, private val statusText: String) : NodeView {
        private val b = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""").matchEntire(e.getAttribute("bounds"))!!.groupValues
        override val id: String? = e.getAttribute("resource-id").ifEmpty { null }
        override val text: String? = if (id == "com.whatsapp:id/conversation_contact_status") statusText
            else e.getAttribute("text").ifEmpty { null }
        override val left = b[1].toInt()
        override val top = b[2].toInt()
        override val right = b[3].toInt()
        override val bottom = b[4].toInt()
        override val children: List<NodeView> = (0 until e.childNodes.length).map { e.childNodes.item(it) }
            .filterIsInstance<org.w3c.dom.Element>().map { Override(it, statusText) }
    }

    private fun scan(status: String): WhatsAppImageRows.Scan {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(javaClass.classLoader!!.getResourceAsStream("samples/whatsapp_incoming_image.xml")!!)
        return WhatsAppImageRows.scan(Override(doc.documentElement.firstChild as org.w3c.dom.Element, status), "com.whatsapp", 1280)
    }

    @Test fun `the toolbar presence line on a real incoming-picture sample proves the chat is direct`() {
        val s = scan("在线")
        assertTrue(s.directChat)
        assertTrue(s.latest?.side == "other")
    }

    @Test fun `a member list or an empty subtitle on the same sample does not`() {
        assertFalse(scan("你, Anna, Bob").directChat)
        assertFalse(scan("").directChat)
    }
}
