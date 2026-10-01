package com.jev.probe.jev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM tests for the reply-payload parsing contract: as many replies as the
 * model gave (never padded), strict JSON object only.
 * No Android classes involved; org.json comes from testImplementation.
 */
class ReplyParserTest {

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

    // ------------------------------------------------------------ relation proposal

    private fun withRelations(relations: String) =
        """{"replies":[{"text":"a"}],"relations":$relations}"""

    @Test
    fun `parseBilingual reads three relation candidates`() {
        val r = ReplyParser.parseBilingual(withRelations("""["同事","客服","朋友"]"""))
        assertEquals(listOf("同事", "客服", "朋友"), r.relationCandidates)
    }

    @Test
    fun `parseBilingual has no candidates when relations is absent`() {
        assertTrue(ReplyParser.parseBilingual("""{"replies":[{"text":"a"}]}""").relationCandidates.isEmpty())
    }

    @Test
    fun `parseBilingual drops candidates unless there are exactly three`() {
        assertTrue(ReplyParser.parseBilingual(withRelations("""["同事","客服"]""")).relationCandidates.isEmpty())
        assertTrue(ReplyParser.parseBilingual(withRelations("""["同事","客服","朋友","家人"]""")).relationCandidates.isEmpty())
        assertTrue(ReplyParser.parseBilingual(withRelations("[]")).relationCandidates.isEmpty())
    }

    @Test
    fun `parseBilingual trims candidates and rejects blank or non-string ones`() {
        assertEquals(listOf("同事", "客服", "朋友"),
            ReplyParser.parseBilingual(withRelations("""["  同事 ","客服","朋友"]""")).relationCandidates)
        assertTrue(ReplyParser.parseBilingual(withRelations("""["同事","","朋友"]""")).relationCandidates.isEmpty())
        assertTrue(ReplyParser.parseBilingual(withRelations("""["同事",null,"朋友"]""")).relationCandidates.isEmpty())
        assertTrue(ReplyParser.parseBilingual(withRelations("""["同事",{"a":1},"朋友"]""")).relationCandidates.isEmpty())
    }

    @Test
    fun `parseBilingual rejects duplicate candidates`() {
        assertTrue(ReplyParser.parseBilingual(withRelations("""["同事","同事","朋友"]""")).relationCandidates.isEmpty())
        assertTrue(ReplyParser.parseBilingual(withRelations("""["Boss","boss ","朋友"]""")).relationCandidates.isEmpty())
    }

    @Test
    fun `parseBilingual rejects a whole sentence posing as a relation`() {
        val long = "对方是我在公司认识的一位关系很好的同事"
        assertTrue(ReplyParser.parseBilingual(withRelations("""["$long","客服","朋友"]""")).relationCandidates.isEmpty())
        // exactly at the cap is fine
        val atCap = "一二三四五六七八九十"
        assertEquals(atCap, ReplyParser.parseBilingual(withRelations("""["$atCap","客服","朋友"]""")).relationCandidates[0])
    }

    @Test
    fun `parseBilingual keeps replies when relations is malformed`() {
        val r = ReplyParser.parseBilingual(withRelations(""""同事、客服、朋友""""))
        assertTrue(r.relationCandidates.isEmpty())
        assertEquals("a", r.replies[0].text)
    }
}
