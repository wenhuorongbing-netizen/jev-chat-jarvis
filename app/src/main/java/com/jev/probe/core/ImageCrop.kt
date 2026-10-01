package com.jev.probe.core

/** Integer box in bitmap pixels; kept free of `android.graphics.Rect` so it runs under plain JVM tests. */
data class PixelBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

/** Where a picture bubble is inside a screenshot, and how big the JPEG sent to the model may be. */
object ImageCrop {
    /** Smaller than this is a sticker or a sliver, not a picture worth sending. */
    const val MIN_EDGE = 48
    const val MAX_EDGE = 1280

    /**
     * Screen box → bitmap box, the same mapping the OCR path uses (drop the window origin, then scale),
     * clamped to the bitmap. Null when nothing usable is left: it is never widened to "the whole screen".
     */
    fun region(b: ImageBubble, originX: Int, originY: Int, scaleX: Float, scaleY: Float, bmpW: Int, bmpH: Int): PixelBox? {
        val box = PixelBox(
            ((b.left - originX) * scaleX).toInt().coerceIn(0, bmpW),
            ((b.top - originY) * scaleY).toInt().coerceIn(0, bmpH),
            ((b.right - originX) * scaleX).toInt().coerceIn(0, bmpW),
            ((b.bottom - originY) * scaleY).toInt().coerceIn(0, bmpH)
        )
        // A bubble is inset in the chat list; a box that spans (almost) the whole shot is a mistaken read, not a picture.
        val wholeScreen = box.width >= bmpW * 0.95 && box.height >= bmpH * 0.9
        return box.takeIf { it.width >= MIN_EDGE && it.height >= MIN_EDGE && !wholeScreen }
    }

    /** Output size: the longer edge capped at [MAX_EDGE], aspect kept, never upscaled. */
    fun outputSize(w: Int, h: Int): Pair<Int, Int> {
        val longest = maxOf(w, h)
        if (longest <= MAX_EDGE) return w to h
        val k = MAX_EDGE.toDouble() / longest
        return maxOf(1, (w * k).toInt()) to maxOf(1, (h * k).toInt())
    }
}
