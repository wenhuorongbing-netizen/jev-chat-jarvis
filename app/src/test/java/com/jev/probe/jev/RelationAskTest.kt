package com.jev.probe.jev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The request only changes when a relation proposal is wanted. */
class RelationAskTest {

    @Test
    fun `no proposal adds nothing to the request`() {
        assertEquals("", ReplyClient.relationAsk(false))
        assertEquals("", ReplyClient.relationAsk(false, isGroup = true))
    }

    @Test
    fun `a person gets person wording`() {
        val ask = ReplyClient.relationAsk(true, isGroup = false)
        assertTrue(ask.contains("同事、客服、朋友"))
        assertFalse(ask.contains("同事群"))
    }

    @Test
    fun `a group gets group wording`() {
        val ask = ReplyClient.relationAsk(true, isGroup = true)
        assertTrue(ask.contains("\"relations\""))
        assertTrue(ask.contains("3 个不同的候选"))
        assertTrue(ask.contains("同事群、家人群、同学群"))
        assertFalse(ask.contains("同事、客服、朋友"))
    }

    @Test
    fun `proposal asks for a relations field with three short candidates`() {
        val ask = ReplyClient.relationAsk(true)
        assertTrue(ask.contains("\"relations\""))
        assertTrue(ask.contains("3 个不同的候选"))
    }
}
