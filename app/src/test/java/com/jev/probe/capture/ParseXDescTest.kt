package com.jev.probe.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM spot-checks for [parseXDesc] (file-level in ChatAppAdapter.kt). The four
 * positive cases are the real X (Twitter) DM contentDescriptions quoted in the
 * function's / adapter's KDoc, captured on X 12.25.2 with a Chinese system
 * language; the last one is the DM-list row shape that must be rejected.
 */
class ParseXDescTest {

    @Test
    fun `body with time stamp and read receipt`() {
        assertEquals(
            "你" to "你这个说的就是那个虚拟人物，是吗？",
            parseXDesc("你：你这个说的就是那个虚拟人物，是吗？。8:11 上午。Read。")
        )
    }

    @Test
    fun `trailing dot run before read receipt`() {
        assertEquals(
            "你" to "他这个东西开源应该问题不大",
            parseXDesc("你：他这个东西开源应该问题不大。。。Read。")
        )
    }

    @Test
    fun `attachment row with trailing dots only`() {
        assertEquals(
            "All-In" to "附加的帖子",
            parseXDesc("All-In：附加的帖子。。")
        )
    }

    @Test
    fun `emoji body with time stamp`() {
        assertEquals(
            "All-In" to "重新写了一个😂",
            parseXDesc("All-In：重新写了一个😂。10:29 下午。")
        )
    }

    @Test
    fun `noise without sender separator is rejected`() {
        // DM-list row shape ("sender, @handle, preview…"): no "："/": " → null.
        assertNull(parseXDesc("All-In, @all_in_2026, 你这个说的就是那…"))
    }
}
