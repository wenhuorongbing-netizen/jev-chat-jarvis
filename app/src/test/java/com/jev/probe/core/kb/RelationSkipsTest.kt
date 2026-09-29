package com.jev.probe.core.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Skip memory is keyed by conversation (app + normalized title), never by contact. */
class RelationSkipsTest {

    private val app = "com.tencent.mobileqq"

    @Test
    fun `nothing is skipped by default`() {
        assertFalse(RelationSkips.contains(emptySet(), app, "老王"))
    }

    @Test
    fun `a skipped conversation is remembered`() {
        val skips = RelationSkips.add(emptySet(), app, "老王")
        assertTrue(RelationSkips.contains(skips, app, "老王"))
    }

    @Test
    fun `other titles and other apps are not skipped`() {
        val skips = RelationSkips.add(emptySet(), app, "老王")
        assertFalse(RelationSkips.contains(skips, app, "老李"))
        assertFalse(RelationSkips.contains(skips, "com.whatsapp", "老王"))
    }

    @Test
    fun `title is normalized so casing, spaces and member count do not matter`() {
        val skips = RelationSkips.add(emptySet(), app, "测试群(12)")
        assertTrue(RelationSkips.contains(skips, app, "测试群(13)"))
        assertTrue(RelationSkips.contains(skips, app, " 测试群 "))
        val en = RelationSkips.add(emptySet(), app, "Boss")
        assertTrue(RelationSkips.contains(en, app, "boss"))
    }

    @Test
    fun `a blank title is never skipped and never stored`() {
        assertEquals(emptySet<String>(), RelationSkips.add(emptySet(), app, null))
        assertEquals(emptySet<String>(), RelationSkips.add(emptySet(), app, "   "))
        assertFalse(RelationSkips.contains(setOf("x"), app, null))
    }

    @Test
    fun `adding twice keeps one entry and leaves the input untouched`() {
        val first = RelationSkips.add(emptySet(), app, "老王")
        val second = RelationSkips.add(first, app, "老王")
        assertEquals(first, second)
        assertEquals(1, second.size)
    }
}
