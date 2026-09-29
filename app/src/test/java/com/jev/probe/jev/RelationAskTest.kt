package com.jev.probe.jev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The request only changes when a relation proposal is wanted. */
class RelationAskTest {

    @Test
    fun `no proposal adds nothing to the request`() {
        assertEquals("", ReplyClient.relationAsk(false))
    }

    @Test
    fun `proposal asks for a relations field with three short candidates`() {
        val ask = ReplyClient.relationAsk(true)
        assertTrue(ask.contains("\"relations\""))
        assertTrue(ask.contains("3 个不同的候选"))
    }
}
