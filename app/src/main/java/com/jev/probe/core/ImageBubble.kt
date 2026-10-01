package com.jev.probe.core

/**
 * The picture inside an image bubble, in screen pixels, and whose it is ("me" / "other").
 * Only the picture itself: not the bubble around it, not the row, not the timestamp strip.
 */
data class ImageBubble(val left: Int, val top: Int, val right: Int, val bottom: Int, val side: String) {
    val width get() = right - left
    val height get() = bottom - top
}
