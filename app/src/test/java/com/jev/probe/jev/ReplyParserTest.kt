package com.jev.probe.jev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM tests for the reply-payload parsing contract: as many replies as the
 * model gave (never padded), graceful fallback when brackets are missing.
 * No Android classes involved; org.json comes from testImplementation.
 */
class ReplyParserTest {

    // ------------------------------------------------------------ parseThree

    @Test
    fun `parseThree returns three replies from a json array`() {
        assertEquals(listOf("先睡了", "明天说", "好的"), ReplyParser.parseThree("""["先睡了","明天说","好的"]"""))
    }

    @Test
    fun `parseThree returns as many as the model gave, never pads`() {
        val out = ReplyParser.parseThree("""["只有一条","第二条"]""")
        assertEquals(2, out.size)
        assertTrue(out.none { it.contains("稍等") })
    }

    @Test
    fun `parseThree caps at three`() {
        assertEquals(listOf("a", "b", "c"), ReplyParser.parseThree("""["a","b","c","d","e"]"""))
    }

    @Test
    fun `parseThree on an empty array returns empty`() {
        assertTrue(ReplyParser.parseThree("[]").isEmpty())
    }

    @Test
    fun `parseThree falls back to lines when brackets are missing`() {
        assertEquals(listOf("好的", "明天聊", "晚安"), ReplyParser.parseThree("1. 好的\n2. 明天聊\n3. 晚安"))
    }

    @Test
    fun `parseThree skips blank array entries and junk bracket lines`() {
        assertEquals(listOf("a", "b"), ReplyParser.parseThree("""["a","","  ","b"]"""))
        assertTrue(ReplyParser.parseThree("[").isEmpty())
        assertTrue(ReplyParser.parseThree("").isEmpty())
    }

    // ------------------------------------------------------------ parseBilingual

    @Test
    fun `parseBilingual parses a full payload`() {
        val json = """{"lang":"德语","translation":"你到哪了","analysis":"对方催进度","replies":[{"text":"Bin gleich da","zh":"快到了"},{"text":"5 Minuten","zh":"五分钟"}]}"""
        val r = ReplyParser.parseBilingual(json)
        assertEquals("德语", r.lang)
        assertEquals("你到哪了", r.translation)
        assertEquals("对方催进度", r.analysis)
        assertEquals(2, r.replies.size)
        assertEquals("Bin gleich da", r.replies[0].text)
        assertEquals("快到了", r.replies[0].zh)
    }

    @Test
    fun `parseBilingual defaults lang and zh when absent`() {
        val r = ReplyParser.parseBilingual("""{"translation":"t","replies":[{"text":"hi"}]}""")
        assertEquals("", r.lang)
        assertEquals("", r.replies[0].zh)
    }

    @Test
    fun `parseBilingual throws when replies are missing or empty`() {
        try {
            ReplyParser.parseBilingual("""{"translation":"t"}""")
            fail("expected IllegalStateException for missing replies")
        } catch (e: IllegalStateException) { }
        try {
            ReplyParser.parseBilingual("""{"replies":[]}""")
            fail("expected IllegalStateException for empty replies")
        } catch (e: IllegalStateException) { }
    }

    @Test
    fun `parseBilingual throws when there is no json at all`() {
        try {
            ReplyParser.parseBilingual("完全不是 JSON")
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) { }
    }

    @Test
    fun `parseBilingual caps replies at three and skips text-less entries`() {
        val json = """{"replies":[{"text":"a"},{"zh":"没正文"},{"text":"b"},{"text":"c"},{"text":"d"}]}"""
        assertEquals(listOf("a", "b", "c"), ReplyParser.parseBilingual(json).replies.map { it.text })
    }
}
