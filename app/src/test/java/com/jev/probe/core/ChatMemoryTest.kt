package com.jev.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [ChatMemory]'s pure merge/parse logic plus a real on-disk
 * round trip (local unit tests run on the JVM, where java.io.File works fine;
 * org.json comes from testImplementation).
 */
class ChatMemoryTest {

    private fun me(t: String) = Msg("me", t)
    private fun other(t: String) = Msg("other", t)

    // ------------------------------------------------------------ decideMerge

    @Test
    fun `decideMerge on empty log adopts the visible window`() {
        val win = listOf(other("hi"), me("yo"))
        assertEquals(win, ChatMemory.decideMerge(emptyList(), win, atBottom = true))
    }

    @Test
    fun `decideMerge with empty visible window keeps the log unchanged`() {
        assertNull(ChatMemory.decideMerge(listOf(other("a")), emptyList(), atBottom = true))
    }

    @Test
    fun `decideMerge appends only the new tail on overlap`() {
        val log = listOf(other("a"), other("b"))
        val win = listOf(other("b"), other("c"))
        assertEquals(
            listOf(other("a"), other("b"), other("c")),
            ChatMemory.decideMerge(log, win, atBottom = true)
        )
    }

    @Test
    fun `decideMerge drops a window already contained in the log`() {
        val log = listOf(other("a"), other("b"), other("c"))
        assertNull(ChatMemory.decideMerge(log, listOf(other("b"), other("c")), atBottom = true))
    }

    @Test
    fun `decideMerge inserts a gap marker for new messages after a disconnect`() {
        val log = listOf(other("a"))
        val win = listOf(other("x"), other("y"))
        assertEquals(
            listOf(other("a"), Msg("gap", ChatMemory.GAP), other("x"), other("y")),
            ChatMemory.decideMerge(log, win, atBottom = true)
        )
    }

    @Test
    fun `decideMerge ignores a disconnected window when not at the bottom`() {
        // User scrolled up into old history: no overlap and not at bottom means
        // the window must NOT be glued onto the log as if it were new.
        assertNull(ChatMemory.decideMerge(listOf(other("a")), listOf(other("x")), atBottom = false))
    }

    // ------------------------------------------------------------ overlap / containsRun

    @Test
    fun `overlap finds the longest shared boundary`() {
        val log = listOf(other("a"), other("b"), other("c"))
        assertEquals(2, ChatMemory.overlap(log, listOf(other("b"), other("c"), other("d"))))
        // log's tail "c" == win's head "c": a 1-message overlap, not zero.
        assertEquals(1, ChatMemory.overlap(log, listOf(other("c"), other("x"))))
        assertEquals(0, ChatMemory.overlap(log, listOf(other("x"))))
        assertEquals(0, ChatMemory.overlap(emptyList(), listOf(other("a"))))
    }

    @Test
    fun `containsRun requires a contiguous run`() {
        val log = listOf(other("a"), other("b"), other("c"))
        assertTrue(ChatMemory.containsRun(log, listOf(other("b"), other("c"))))
        assertTrue(!ChatMemory.containsRun(log, listOf(other("a"), other("c"))))
        assertTrue(!ChatMemory.containsRun(log, listOf(other("a"), other("b"), other("c"), other("d"))))
    }

    // ------------------------------------------------------------ parseLog (k validation)

    @Test
    fun `parseLog returns messages when the stored key matches`() {
        val json = """{"k":"chatA","m":[{"s":"me","t":"hello"},{"s":"other","t":"hi"}]}"""
        assertEquals(listOf(me("hello"), other("hi")), ChatMemory.parseLog("chatA", json))
    }

    @Test
    fun `parseLog rejects a file written for a different key`() {
        // A file-name collision must never hand back another chat's messages.
        val json = """{"k":"chatB","m":[{"s":"me","t":"hello"}]}"""
        assertTrue(ChatMemory.parseLog("chatA", json).isEmpty())
    }

    @Test
    fun `parseLog rejects corrupt or keyless content`() {
        assertTrue(ChatMemory.parseLog("chatA", "{not json").isEmpty())
        assertTrue(ChatMemory.parseLog("chatA", "").isEmpty())
        assertTrue(ChatMemory.parseLog("chatA", """{"m":[{"s":"me","t":"hi"}]}""").isEmpty())
    }

    // ------------------------------------------------------------ keyHash

    @Test
    fun `keyHash is a stable 16-char hex string that differs per key`() {
        val h1 = ChatMemory.keyHash("pkg|Alice")
        assertEquals(16, h1.length)
        assertTrue(h1.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(h1, ChatMemory.keyHash("pkg|Alice"))
        assertNotEquals(h1, ChatMemory.keyHash("pkg|Bob"))
    }

    // ------------------------------------------------------------ on-disk round trip

    @Test
    fun `merge persists and a fresh instance reads it back`() {
        val dir = java.nio.file.Files.createTempDirectory("jevmem").toFile()
        try {
            val merged = ChatMemory(dir).merge("pkg|Alice", listOf(other("hi"), me("yo")), atBottom = true)
            assertEquals(2, merged.size)
            // A fresh instance over the same dir proves the save/load round trip
            // (and that the stored "k" passes validation for the right key).
            assertEquals(merged, ChatMemory(dir).merge("pkg|Alice", emptyList(), atBottom = true))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `merge twice with the same window does not duplicate`() {
        val dir = java.nio.file.Files.createTempDirectory("jevmem").toFile()
        try {
            val m = ChatMemory(dir)
            val win = listOf(other("a"), other("b"))
            m.merge("k", win, atBottom = true)
            assertEquals(win, m.merge("k", win, atBottom = true))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `merge records only my messages as style samples`() {
        val dir = java.nio.file.Files.createTempDirectory("jevmem").toFile()
        try {
            val m = ChatMemory(dir)
            m.merge("k", listOf(me("好的"), other("嗯")), atBottom = true)
            assertEquals(listOf("好的"), m.styleSamples(10))
        } finally {
            dir.deleteRecursively()
        }
    }
}
