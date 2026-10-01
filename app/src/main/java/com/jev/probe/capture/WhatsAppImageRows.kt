package com.jev.probe.capture

import com.jev.probe.core.ImageBubble

/**
 * What WhatsApp's message list says about pictures, read from the node tree (2.26.37.73 on a
 * Redmi 25060RK16C, Android 15; not yet seen on the Xiaomi 14).
 *
 * Every message is a `conversation_row_*` node; a picture is `conversation_row_image`, whose
 * `image` child is exactly the picture (the bubble `media_container` is 13px wider on each side).
 * Side comes from the bubble's `main_layout` the same way the text rule does it: whichever edge is
 * nearer the screen border belongs to the sender.
 *
 * [Scan.latest] is set only when the NEWEST row is a picture and the whole picture is on screen. A
 * picture under another row kind (text, audio, sticker…) is not the latest message; a picture the
 * list has scrolled half under the toolbar would be cropped to a slice, so it is not offered.
 */
object WhatsAppImageRows {

    class Scan(val latest: ImageBubble?, val trailingImages: Int)

    private const val GUARD = 6000

    fun scan(root: NodeView, pkg: String, width: Int): Scan {
        val rowPrefix = "$pkg:id/conversation_row_"
        val imageRow = "${rowPrefix}image"
        val divider = "${rowPrefix}date_divider"
        val rows = ArrayList<NodeView>()
        var viewport: NodeView? = null

        val stack = ArrayDeque<NodeView>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < GUARD) {
            guard++
            val node = stack.removeLast()
            val id = node.id
            if (id == "android:id/list" && viewport == null) viewport = node
            if (id != null && id.startsWith(rowPrefix) && id != divider) {
                rows.add(node)
                continue  // rows do not nest; what is inside is read per row below
            }
            node.children.forEach { stack.addLast(it) }
        }
        rows.sortBy { it.top }

        var trailing = 0
        for (r in rows.asReversed()) { if (r.id == imageRow) trailing++ else break }
        val last = rows.lastOrNull()
        if (last == null || last.id != imageRow) return Scan(null, trailing)

        val layout = find(last, "$pkg:id/main_layout")
        val picture = find(last, "$pkg:id/image") ?: find(last, "$pkg:id/media_container")
        if (layout == null || picture == null) return Scan(null, trailing)

        val view = viewport ?: root
        val whole = picture.left >= view.left && picture.right <= view.right &&
            picture.top > view.top && picture.bottom < view.bottom
        if (!whole || picture.right <= picture.left || picture.bottom <= picture.top) return Scan(null, trailing)

        val side = if (width - layout.right < layout.left) "me" else "other"
        return Scan(ImageBubble(picture.left, picture.top, picture.right, picture.bottom, side), trailing)
    }

    private fun find(from: NodeView, id: String): NodeView? {
        val stack = ArrayDeque<NodeView>()
        stack.addLast(from)
        var guard = 0
        while (stack.isNotEmpty() && guard < GUARD) {
            guard++
            val node = stack.removeLast()
            if (node.id == id) return node
            node.children.forEach { stack.addLast(it) }
        }
        return null
    }
}
