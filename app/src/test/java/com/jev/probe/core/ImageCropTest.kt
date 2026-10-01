package com.jev.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageCropTest {
    private val bubble = ImageBubble(68, 1260, 874, 2388, "other")

    @Test
    fun `a full-resolution shot maps the picture one to one and is not the whole screen`() {
        val box = ImageCrop.region(bubble, 0, 0, 1f, 1f, 1280, 2772)!!
        assertEquals(PixelBox(68, 1260, 874, 2388), box)
        assert(box.width < 1280 && box.height < 2772)
    }

    @Test
    fun `origin and scale are applied the way the OCR path does`() {
        val box = ImageCrop.region(bubble, 0, 100, 0.5f, 0.5f, 640, 1386)!!
        assertEquals(PixelBox(34, 580, 437, 1144), box)
    }

    @Test
    fun `a box that falls outside the bitmap is clamped, and nothing left means null`() {
        assertEquals(PixelBox(68, 1260, 874, 2000), ImageCrop.region(bubble, 0, 0, 1f, 1f, 1280, 2000))
        assertNull(ImageCrop.region(bubble, 0, 0, 1f, 1f, 1280, 1270))
        assertNull(ImageCrop.region(ImageBubble(10, 10, 30, 30, "other"), 0, 0, 1f, 1f, 1280, 2772))
    }

    @Test
    fun `output keeps the aspect, caps the long edge and never upscales`() {
        assertEquals(806 to 1128, ImageCrop.outputSize(806, 1128))
        assertEquals(1000 to 1280, ImageCrop.outputSize(1250, 1600))
        assertEquals(1 to 1280, ImageCrop.outputSize(1, 5000))
    }

    @Test
    fun `a box that spans the whole shot is refused instead of being sent`() {
        assertNull(ImageCrop.region(ImageBubble(0, 0, 1280, 2772, "other"), 0, 0, 1f, 1f, 1280, 2772))
        assertNull(ImageCrop.region(ImageBubble(10, 100, 1270, 2700, "other"), 0, 0, 1f, 1f, 1280, 2772))
    }
}
