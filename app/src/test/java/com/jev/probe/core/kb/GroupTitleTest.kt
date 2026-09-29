package com.jev.probe.core.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 首期只看标题末尾的成员数来判断是不是群；判断不出来按个人处理。 */
class GroupTitleTest {

    @Test
    fun `a trailing member count marks a group`() {
        assertTrue(KbStore.isGroupTitle("测试群(12)"))
        assertTrue(KbStore.isGroupTitle("家人群 (5)"))
        assertTrue(KbStore.isGroupTitle("  项目组(120)  "))
    }

    @Test
    fun `full-width brackets count too`() {
        assertTrue(KbStore.isGroupTitle("测试群（12）"))
        assertTrue(KbStore.isGroupTitle("测试群（ 12 ）"))
        assertTrue(KbStore.isGroupTitle("测试群(12）"))
    }

    @Test
    fun `zero-width characters do not hide the count`() {
        val zeroWidth = 0x200B.toChar()
        assertTrue(KbStore.isGroupTitle("测试群${zeroWidth}(12)"))
    }

    @Test
    fun `an ordinary title is a person`() {
        assertFalse(KbStore.isGroupTitle("老王"))
        assertFalse(KbStore.isGroupTitle(null))
        assertFalse(KbStore.isGroupTitle(""))
        assertFalse(KbStore.isGroupTitle("   "))
    }

    @Test
    fun `brackets that are not a trailing number are not a member count`() {
        assertFalse(KbStore.isGroupTitle("Project (v2)"))
        assertFalse(KbStore.isGroupTitle("老王(3)的朋友"))
        assertFalse(KbStore.isGroupTitle("测试群(12"))
        assertFalse(KbStore.isGroupTitle("老王()"))
    }

    @Test
    fun `a count with no name in front is not a group`() {
        assertFalse(KbStore.isGroupTitle("(12)"))
        assertFalse(KbStore.isGroupTitle("（12）"))
    }

    @Test
    fun `member count changes keep the same contact key`() {
        assertEquals(KbStore.normalizeName("测试群(12)"), KbStore.normalizeName("测试群(13)"))
        assertEquals(KbStore.normalizeName("测试群(12)"), KbStore.normalizeName("测试群（13）"))
        assertEquals("测试群", KbStore.normalizeName("测试群(9)"))
    }
}
