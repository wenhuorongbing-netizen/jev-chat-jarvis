package com.jev.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** S0-B：填入只进入候选所属的会话；证明不了就不写，退回复制。 */
class FillGuardTest {

    private val usable: (String?) -> Boolean = { !it.isNullOrBlank() && !it.contains("连接中") }

    private fun snap(title: String?, vararg texts: String) =
        ChatSnapshot(title, texts.map { Msg("other", it) })

    private val chatA = snap("小王", "在吗", "明天见面吗")
    private val targetA = FillTarget("com.tencent.mobileqq", "小王", chatA.signature())

    private fun check(target: FillTarget, pkg: String?, live: ChatSnapshot?) =
        FillGuard.check(target, pkg, live, usable)

    @Test
    fun `same conversation is allowed`() {
        assertEquals(FillVerdict.ALLOW, check(targetA, "com.tencent.mobileqq", chatA))
    }

    @Test
    fun `switching from conversation A to B then tapping A's candidate is denied`() {
        val chatB = snap("老李", "吃了吗")
        assertEquals(FillVerdict.DENY_TITLE, check(targetA, "com.tencent.mobileqq", chatB))
    }

    @Test
    fun `window switched to another app between click and write is denied`() {
        assertEquals(FillVerdict.DENY_WINDOW, check(targetA, "com.whatsapp", chatA))
        assertEquals(FillVerdict.DENY_WINDOW, check(targetA, null, chatA))
    }

    @Test
    fun `a non-chat page of the same app with an editable node is denied`() {
        // adapter.extract returned null: payment / settings / list page
        assertEquals(FillVerdict.DENY_NOT_CHAT, check(targetA, "com.tencent.mobileqq", null))
    }

    @Test
    fun `missing title on screen is allowed only when the visible messages match`() {
        assertEquals(FillVerdict.ALLOW, check(targetA, "com.tencent.mobileqq", snap(null, "在吗", "明天见面吗")))
        assertEquals(FillVerdict.DENY_UNVERIFIED, check(targetA, "com.tencent.mobileqq", snap(null, "别的会话")))
    }

    @Test
    fun `transient title on screen falls back to the message signature`() {
        val connecting = snap("连接中…", "在吗", "明天见面吗")
        assertEquals(FillVerdict.ALLOW, check(targetA, "com.tencent.mobileqq", connecting))
        assertEquals(FillVerdict.DENY_UNVERIFIED, check(targetA, "com.tencent.mobileqq", snap("连接中…", "x")))
    }

    @Test
    fun `target without a usable title needs a non-empty matching signature`() {
        val untitled = FillTarget("com.tencent.mobileqq", null, chatA.signature())
        assertEquals(FillVerdict.ALLOW, check(untitled, "com.tencent.mobileqq", snap("小王", "在吗", "明天见面吗")))
        assertEquals(FillVerdict.DENY_UNVERIFIED, check(untitled, "com.tencent.mobileqq", snap("小王", "换了")))
        val blindEmpty = FillTarget("com.tencent.mobileqq", null, "")
        assertEquals(FillVerdict.DENY_UNVERIFIED, check(blindEmpty, "com.tencent.mobileqq", snap(null)))
    }

    @Test
    fun `title comparison ignores surrounding whitespace only`() {
        assertEquals(FillVerdict.ALLOW, check(targetA, "com.tencent.mobileqq", snap(" 小王 ", "另一条")))
        assertEquals(FillVerdict.DENY_TITLE, check(targetA, "com.tencent.mobileqq", snap("小王2", "在吗", "明天见面吗")))
    }

    @Test
    fun `fill intent carries the body text only, never the Chinese gloss`() {
        val reply = RankedReply(text = "See you tomorrow!", prob = 0.6, zh = "明天见！")
        val intent = FillIntent.of(reply, targetA)
        assertEquals("See you tomorrow!", intent.text)
        assertFalse(intent.text.contains("明天"))
        assertEquals(targetA, intent.target)
    }
}
