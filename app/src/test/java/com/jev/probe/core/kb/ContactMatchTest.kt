package com.jev.probe.core.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 合并推荐的名称匹配：纯函数，只推荐、不合并。 */
class ContactMatchTest {

    private fun c(name: String, vararg aliases: String) =
        Contact(id = name, name = name, aliases = aliases.toList())

    private fun names(title: String?, vararg contacts: Contact, max: Int = 3) =
        ContactMatch.similar(title, contacts.toList(), max).map { it.name }

    @Test
    fun `same name is recommended`() {
        assertEquals(listOf("老王"), names("老王", c("老王")))
    }

    @Test
    fun `contact name containing the title, or the other way round, is recommended`() {
        assertEquals(listOf("老王同学"), names("老王", c("老王同学")))
        assertEquals(listOf("老王"), names("老王同学", c("老王")))
    }

    @Test
    fun `member count and bracket width do not matter`() {
        assertEquals(listOf("测试群(12)"), names("测试群(13)", c("测试群(12)")))
        assertEquals(listOf("测试群(12)"), names("测试群（13）", c("测试群(12)")))
    }

    @Test
    fun `case and spaces do not matter`() {
        assertEquals(listOf("Boss Li"), names(" boss li ", c("Boss Li")))
    }

    @Test
    fun `aliases count as names`() {
        assertEquals(listOf("张三"), names("三哥的微信", c("张三", "三哥")))
    }

    @Test
    fun `unrelated names give nothing`() {
        assertTrue(names("老王", c("老李"), c("客服小美")).isEmpty())
        assertTrue(names("老王").isEmpty())
    }

    @Test
    fun `a blank title recommends nothing`() {
        assertTrue(names(null, c("老王")).isEmpty())
        assertTrue(names("   ", c("老王")).isEmpty())
    }

    @Test
    fun `a single character is too weak to match by containment`() {
        assertTrue(names("王", c("老王"), c("王大锤")).isEmpty())
        assertTrue(names("老王", c("王")).isEmpty())
        // ...but identical one-character names are still the same
        assertEquals(listOf("王"), names("王", c("王")))
    }

    @Test
    fun `a contact without any name is ignored`() {
        assertTrue(names("老王", Contact(id = "x", name = "  ")).isEmpty())
    }

    @Test
    fun `at most three, closest names first`() {
        val out = names(
            "老王",
            c("老王的老婆家"), c("老王同学会"), c("老王"), c("老王哥"), c("老王家人")
        )
        assertEquals(3, out.size)
        assertEquals("老王", out[0])
        assertEquals("老王哥", out[1])   // one extra character beats the longer ones
    }

    @Test
    fun `max can be lowered`() {
        assertEquals(1, names("老王", c("老王"), c("老王哥"), max = 1).size)
    }
}
