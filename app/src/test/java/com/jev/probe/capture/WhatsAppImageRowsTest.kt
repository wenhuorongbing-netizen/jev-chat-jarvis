package com.jev.probe.capture

import com.jev.probe.core.ImageBubble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

/**
 * S5-B：WhatsApp 的图片气泡从真机 uiautomator 样本里读出来（红米 25060RK16C / Android 15 / WhatsApp 2.26.37.73，
 * 1280x2772，聊天内容已脱敏，只剩结构）。只测外部行为：最新一行是不是图、是谁的、裁哪一块、会不会裁到一半。
 */
class WhatsAppImageRowsTest {

    private class XmlNode(private val e: org.w3c.dom.Element) : NodeView {
        private val b = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""").matchEntire(e.getAttribute("bounds"))!!.groupValues
        override val id: String? = e.getAttribute("resource-id").ifEmpty { null }
        override val text: String? = e.getAttribute("text").ifEmpty { null }
        override val left = b[1].toInt()
        override val top = b[2].toInt()
        override val right = b[3].toInt()
        override val bottom = b[4].toInt()
        override val children: List<NodeView> = (0 until e.childNodes.length).map { e.childNodes.item(it) }
            .filterIsInstance<org.w3c.dom.Element>().map { XmlNode(it) }
    }

    private fun sample(name: String): NodeView {
        val stream = javaClass.classLoader!!.getResourceAsStream("samples/$name")!!
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream)
        return XmlNode(doc.documentElement.firstChild as org.w3c.dom.Element)
    }

    private fun scan(name: String) = WhatsAppImageRows.scan(sample(name), "com.whatsapp", 1280)

    @Test
    fun `the other side's picture at the bottom is found and cropped to the picture itself`() {
        val s = scan("whatsapp_incoming_image.xml")
        // the `image` node, not the bubble around it and not the whole row
        assertEquals(ImageBubble(68, 1260, 874, 2388, "other"), s.latest)
        assertEquals(1, s.trailingImages)
    }

    @Test
    fun `a picture I sent is found and filed as mine`() {
        val s = scan("whatsapp_self_image.xml")
        assertEquals(ImageBubble(406, 1225, 1212, 2353, "me"), s.latest)
    }

    @Test
    fun `a picture cut off by the top of the list is not offered`() {
        val s = scan("whatsapp_incoming_image_clipped.xml")
        assertNull(s.latest)
        // it is still the newest row, so the snapshot knows an image is there
        assertEquals(1, s.trailingImages)
    }

    @Test
    fun `a screen with only text rows has no image`() {
        val s = scan("whatsapp_incoming_image.xml")
        val noImage = Filtered(sample("whatsapp_incoming_image.xml")) { it.id != "com.whatsapp:id/conversation_row_image" }
        val t = WhatsAppImageRows.scan(noImage, "com.whatsapp", 1280)
        assertNotNull(s.latest)
        assertNull(t.latest)
        assertEquals(0, t.trailingImages)
    }

    @Test
    fun `a text message below the picture means the picture is no longer the latest`() {
        val tree = Synthetic.chat(
            Synthetic.image(top = 400, bottom = 1400, left = 55, right = 887),
            Synthetic.text(top = 1500, bottom = 1630, left = 55, right = 613)
        )
        val s = WhatsAppImageRows.scan(tree, "com.whatsapp", 1280)
        assertNull(s.latest)
        assertEquals(0, s.trailingImages)
    }

    @Test
    fun `a row of another kind below the picture also means it is not the latest`() {
        val tree = Synthetic.chat(
            Synthetic.image(top = 400, bottom = 1400, left = 55, right = 887),
            Synthetic.row("conversation_row_audio", top = 1450, bottom = 1600)
        )
        assertNull(WhatsAppImageRows.scan(tree, "com.whatsapp", 1280).latest)
    }

    @Test
    fun `two pictures in a row count as two and the lower one is the latest`() {
        val tree = Synthetic.chat(
            Synthetic.image(top = 330, bottom = 900, left = 55, right = 887),
            Synthetic.image(top = 950, bottom = 1500, left = 55, right = 887)
        )
        val s = WhatsAppImageRows.scan(tree, "com.whatsapp", 1280)
        assertEquals(2, s.trailingImages)
        assertEquals(950 + 29, s.latest!!.top)
    }

    @Test
    fun `the business app uses the same ids under its own package`() {
        val tree = Synthetic.chat(Synthetic.image(top = 400, bottom = 1400, left = 55, right = 887, pkg = "com.whatsapp.w4b"))
        assertNotNull(WhatsAppImageRows.scan(tree, "com.whatsapp.w4b", 1280).latest)
        assertNull(WhatsAppImageRows.scan(tree, "com.whatsapp", 1280).latest)
    }

    @Test
    fun `the date divider inside a picture row is not a row of its own`() {
        // in the real sample the divider hangs inside the picture row; it must not hide the picture
        val s = scan("whatsapp_incoming_image.xml")
        assertNotNull(s.latest)
    }

    // ---- tiny builders for the cases no real sample covers

    private class Filtered(private val n: NodeView, private val keep: (NodeView) -> Boolean) : NodeView by n {
        override val children: List<NodeView> get() = n.children.filter(keep).map { Filtered(it, keep) }
    }

    private class Fake(
        override val id: String?, override val left: Int, override val top: Int, override val right: Int, override val bottom: Int,
        override val children: List<NodeView> = emptyList(), override val text: String? = null
    ) : NodeView

    private object Synthetic {
        fun chat(vararg rows: NodeView): NodeView =
            Fake(null, 0, 0, 1280, 2772, listOf(Fake("android:id/list", 0, 314, 1280, 2418, rows.toList())))

        fun row(id: String, top: Int, bottom: Int, pkg: String = "com.whatsapp") =
            Fake("$pkg:id/$id", 0, top, 1280, bottom)

        fun text(top: Int, bottom: Int, left: Int, right: Int, pkg: String = "com.whatsapp") =
            Fake("$pkg:id/conversation_row_text", 0, top, 1280, bottom, listOf(Fake("$pkg:id/main_layout", left, top + 16, right, bottom - 16)))

        fun image(top: Int, bottom: Int, left: Int, right: Int, pkg: String = "com.whatsapp") =
            Fake("$pkg:id/conversation_row_image", 0, top, 1280, bottom, listOf(
                Fake("$pkg:id/main_layout", left, top + 16, right, bottom - 16, listOf(
                    Fake("$pkg:id/media_container", left, top + 16, right, bottom - 16, listOf(
                        Fake("$pkg:id/image", left + 13, top + 29, right - 13, bottom - 29)))))))
    }
}
