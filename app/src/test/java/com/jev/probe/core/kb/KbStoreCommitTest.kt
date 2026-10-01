package com.jev.probe.core.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * S3: what the store reports must be what reached disk. The failures here are real filesystem
 * failures (a directory where the temp file goes, a read-only directory), and every check is made
 * again after re-creating the store, i.e. against the files, not the in-memory copy.
 */
class KbStoreCommitTest {

    @get:Rule val tmp = TemporaryFolder()

    private val qq = "com.tencent.mobileqq"
    private val wa = "com.whatsapp"

    private fun store() = KbStore(tmp.root)
    private val kb get() = File(tmp.root, "kb")

    /** A directory where the temp file must go: the write cannot even start. */
    private fun blockWrites() { File(kb, "contacts.json.tmp").mkdirs() }
    private fun unblockWrites() { File(kb, "contacts.json.tmp").deleteRecursively() }

    private fun contact(id: String, name: String, vararg apps: String, aliases: List<String> = emptyList()) =
        Contact(id = id, name = name, apps = apps.toList(), aliases = aliases)

    // ------------------------------------------------------------ S3-A/C commit semantics

    @Test
    fun `a write that cannot start reports Failed and the old data survives a reload`() {
        val s = store()
        assertTrue(s.saveContact(contact("a", "小王", qq)).committed)
        blockWrites()

        val r = s.saveContact(contact("b", "老李", qq))

        assertTrue(r is KbResult.Failed)
        assertEquals(listOf("a"), s.contacts().map { it.id })            // memory does not claim it
        assertEquals(listOf("a"), store().contacts().map { it.id })      // neither does the disk
    }

    @Test
    fun `after a failed write the next write commits and only then the data is there`() {
        val s = store()
        s.saveContact(contact("a", "小王", qq))
        blockWrites()
        assertFalse(s.saveContact(contact("b", "老李", qq)).committed)
        unblockWrites()

        assertTrue(s.saveContact(contact("b", "老李", qq)).committed)

        assertEquals(setOf("a", "b"), store().contacts().map { it.id }.toSet())
    }

    @Test
    fun `temp file written fine but the replace step fails reports Failed and leaves no temp behind`() {
        val s = store()
        assertTrue(s.saveContact(contact("a", "小王", qq)).committed)
        // The destination becomes a non-empty directory: the temp write succeeds, the replace cannot.
        val dest = File(kb, "contacts.json")
        assertTrue(dest.delete())
        File(dest, "x").apply { parentFile.mkdirs(); writeText("x") }

        val r = s.saveContact(contact("b", "老李", qq))

        assertTrue(r is KbResult.Failed)
        assertFalse(File(kb, "contacts.json.tmp").exists())
        assertFalse(s.contacts().any { it.id == "b" })   // never claims the failed write
    }

    @Test
    fun `a read-only directory fails the replace step and leaves the old file whole`() {
        val s = store()
        assertTrue(s.saveNote(Note("n1", "口味", "不吃香菜")).committed)
        // Needs an OS (and user) that really enforces it; elsewhere the case is covered above.
        assumeTrue(kb.setWritable(false))
        val enforced = runCatching { File(kb, "probe.tmp").createNewFile() }.getOrDefault(false).also {
            File(kb, "probe.tmp").delete()
        }.not()
        try {
            assumeTrue(enforced)
            val r = s.saveNote(Note("n2", "代号", "小蓝"))
            assertTrue(r is KbResult.Failed)
        } finally {
            kb.setWritable(true)
        }
        assertEquals(listOf("n1"), store().notes().map { it.id })
    }

    @Test
    fun `an interrupted write leaves a half temp file that is ignored on reload and replaced by the next commit`() {
        val s = store()
        s.saveContact(contact("a", "小王", qq))
        File(kb, "contacts.json.tmp").writeText("""[{"id":"x","na""")   // what a kill mid-write leaves

        assertEquals(listOf("a"), store().contacts().map { it.id })

        assertTrue(s.saveContact(contact("b", "老李", qq)).committed)
        assertFalse(File(kb, "contacts.json.tmp").exists())
        assertEquals(setOf("a", "b"), store().contacts().map { it.id }.toSet())
    }

    @Test
    fun `a corrupt original is set aside whole and never overwritten by a save`() {
        kb.mkdirs()
        File(kb, "contacts.json").writeText("""[{"id":"a","name":"小""")

        val s = store()
        assertTrue(s.contacts().isEmpty())
        assertTrue(s.saveContact(contact("b", "老李", qq)).committed)

        val kept = kb.listFiles { f -> f.name.startsWith("contacts.json.corrupt.") }!!
        assertEquals(1, kept.size)
        assertEquals("""[{"id":"a","name":"小""", kept[0].readText())
        assertEquals(listOf("b"), store().contacts().map { it.id })
    }

    @Test
    fun `a failed delete keeps the contact and its history, a good one removes both`() {
        val s = store()
        s.saveContact(contact("a", "小王", qq))
        s.appendLog("a", listOf(LogEntry("other", "在吗", 1L, qq)))
        blockWrites()

        assertTrue(s.deleteContact("a") is KbResult.Failed)
        assertEquals(1, store().logSize("a"))
        assertNotNull(store().contact("a"))

        unblockWrites()
        assertTrue(s.deleteContact("a").committed)
        assertNull(store().contact("a"))
        assertEquals(0, store().logSize("a"))
    }

    @Test
    fun `a late capture for a deleted contact does not bring its history back`() {
        val s = store()
        s.saveContact(contact("a", "小王", qq))
        s.deleteContact("a")

        assertFalse(s.appendLog("a", listOf(LogEntry("other", "迟到的一屏", 1L, qq))))

        assertFalse(File(kb, "logs/a.json").exists())
    }

    @Test
    fun `an edit holding an old copy cannot resurrect a deleted contact`() {
        val s = store()
        val old = contact("a", "小王", qq)
        s.saveContact(old)
        s.deleteContact("a")

        assertTrue(s.saveContact(old.copy(notes = "改过"), mustExist = true) is KbResult.Rejected)
        assertNull(store().contact("a"))
    }

    // ------------------------------------------------------------ S3-B identity

    @Test
    fun `the same name in another app gets its own contact and the look-alike is only named`() {
        val s = store()
        s.saveContact(contact("a", "小王", qq, aliases = listOf("王哥")))

        val r = s.saveOrMergeContact("小王", wa)

        assertTrue(r.committed)
        assertTrue(r.message.contains("另有同名联系人"))
        val all = store().contacts()
        assertEquals(2, all.size)
        val original = all.first { it.id == "a" }
        assertEquals(listOf(qq), original.apps)            // nothing folded into it
        assertEquals(listOf("王哥"), original.aliases)
    }

    @Test
    fun `the same title in the same app is the same conversation, no duplicate and nothing merged`() {
        val s = store()
        assertTrue(s.saveOrMergeContact("小王", qq).committed)

        val again = s.saveOrMergeContact("小王", qq)

        assertTrue(again.message.contains("已存在"))
        assertEquals(1, store().contacts().size)
    }

    @Test
    fun `an alias of another app's contact does not claim this conversation`() {
        val s = store()
        s.saveContact(contact("a", "老王", qq))
        s.saveContact(contact("x", "小李", wa, aliases = listOf("老王")))

        // A third app: both look alike, neither is this conversation's identity.
        val r = s.saveOrMergeContact("老王", "org.telegram.messenger")

        assertTrue(r.committed)
        val all = store().contacts()
        assertEquals(3, all.size)
        assertEquals(listOf(qq), all.first { it.id == "a" }.apps)
        assertEquals(listOf(wa), all.first { it.id == "x" }.apps)
    }

    @Test
    fun `a contact bound to another app is not a match for context, one bound to none is`() {
        val s = store()
        s.saveContact(contact("a", "小王", qq))
        s.saveContact(contact("g", "老张"))               // made by hand, no app

        assertNull(s.findContact("小王", wa))
        assertEquals("a", s.findContact("小王", qq)?.id)
        assertEquals("g", s.findContact("老张", wa)?.id)
    }

    @Test
    fun `two saves of the same conversation at once make one contact`() {
        val s = store()
        val threads = List(2) { Thread { s.saveOrMergeContact("小王", qq) } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(1, store().contacts().size)
    }

    @Test
    fun `a confirmed merge folds the conversation in and is committed`() {
        val s = store()
        s.saveContact(contact("a", "小王", qq))

        val r = s.mergeInto("a", "Wang (12)", wa)

        assertTrue(r.committed)
        val merged = store().contact("a")!!
        assertEquals(listOf(qq, wa), merged.apps)
        assertTrue(merged.aliases.contains("Wang (12)"))
    }

    @Test
    fun `a merge whose write fails changes nothing and says so`() {
        val s = store()
        s.saveContact(contact("a", "小王", qq))
        blockWrites()

        val r = s.mergeInto("a", "Wang", wa)

        assertTrue(r is KbResult.Failed)
        assertEquals(listOf(qq), s.contact("a")!!.apps)
        assertEquals(listOf(qq), store().contact("a")!!.apps)
    }

    @Test
    fun `merging into a contact deleted meanwhile is rejected, not resurrected`() {
        val s = store()
        s.saveContact(contact("a", "小王", qq))
        s.deleteContact("a")

        assertTrue(s.mergeInto("a", "Wang", wa) is KbResult.Rejected)
        assertNull(store().contact("a"))
    }

    @Test
    fun `a failed create reports Failed, not saved`() {
        val s = store()
        blockWrites()

        val r = s.saveOrMergeContact("小王", qq, "同事")

        assertTrue(r is KbResult.Failed)
        assertTrue(store().contacts().isEmpty())
    }

    @Test
    fun `a toggle holding an old copy cannot resurrect a deleted note`() {
        val s = store()
        val old = Note("n1", "口味", "不吃香菜")
        s.saveNote(old)
        s.deleteNote("n1")

        assertTrue(s.saveNote(old.copy(enabled = false), mustExist = true) is KbResult.Rejected)
        assertTrue(store().notes().isEmpty())
    }

    @Test
    fun `a delete that cannot remove the history file says so instead of claiming it is gone`() {
        val s = store()
        s.saveContact(contact("a", "小王", qq))
        // A real fault: the history path is a non-empty directory, so File.delete() fails.
        File(kb, "logs/a.json").apply { mkdirs(); File(this, "x").writeText("x") }

        val r = s.deleteContact("a")

        assertTrue(r is KbResult.Failed)
        assertNull(store().contact("a"))          // the contact itself is gone, committed
    }

    @Test
    fun `two people with the same title in the same app cannot be told apart and nothing from another source joins them`() {
        val s = store()
        s.saveOrMergeContact("小王", qq)
        s.saveOrMergeContact("小王", wa)          // the other source stays its own contact

        val again = s.saveOrMergeContact("小王", qq)

        assertTrue(again.message.contains("已存在"))
        val all = store().contacts()
        assertEquals(2, all.size)
        assertEquals(setOf(listOf(qq), listOf(wa)), all.map { it.apps }.toSet())
    }

    @Test
    fun `a same-app title equal to an alias the user wrote is that contact, an alias from another app is not`() {
        val s = store()
        s.saveContact(contact("x", "小李", qq, aliases = listOf("老王")))
        s.saveContact(contact("y", "小张", wa, aliases = listOf("老赵")))

        assertTrue(s.saveOrMergeContact("老王", qq).message.contains("已存在"))   // the user's own alias, same app
        assertTrue(s.saveOrMergeContact("老赵", qq).message.contains("另有同名联系人"))  // another app's alias: suggestion only
        assertEquals(listOf(wa), store().contact("y")!!.apps)
    }

    @Test
    fun `many simultaneous saves of one conversation make one contact and different ones make each`() {
        val s = store()
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = (0 until 8).map { i ->
            Thread { start.await(); s.saveOrMergeContact("同一个人", qq); s.saveOrMergeContact("人$i", qq) }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }

        val names = store().contacts().map { it.name }
        assertEquals(9, names.size)
        assertEquals(1, names.count { it == "同一个人" })
    }
}
