package org.michimusic.link

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** A20: run with :aircat-transfer:testDebugUnitTest after applying the cumulative patch. */
class AirCatRecoveryJournalTest {
    private lateinit var root: File
    private lateinit var journal: AirCatRecoveryJournal
    @Before fun setup() {
        root = Files.createTempDirectory("aircat_a20_").toFile()
        journal = AirCatRecoveryJournal(File(root, ".aircat-journal"))
    }
    @After fun teardown() { root.deleteRecursively() }
    private fun file(name: String, bytes: String) = File(root, name).also { it.writeText(bytes) }
    private fun sha(bytes: String): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun newFileProducesOrphanEvidenceUntilCommitted() {
        val final = File(root, "one.flac")
        assertTrue(journal.promote(file("one.part", "verified"), final, "key1", sha("verified")))
        assertEquals(AirCatRecoveryJournal.Phase.PROMOTED, journal.pending(final)?.phase)
        assertEquals(AirCatRecoveryJournal.Assessment.ORPHAN_FINAL, journal.assess(final, false, false))
        assertFalse(journal.finishCommitted(final, "key1", sha("verified"), true, false))
        assertTrue(journal.finishCommitted(final, "key1", sha("verified"), true, true))
        assertFalse(journal.hasJournal(final))
    }

    @Test fun overwritingFileKeepsBackupUntilVerifiedCommit() {
        val final = file("two.flac", "previous")
        assertTrue(journal.promote(file("two.part", "new"), final, "key2", sha("new")))
        assertEquals("previous", File(root, "two.flac.bak").readText())
        assertEquals(AirCatRecoveryJournal.Assessment.READY_FOR_EXPLICIT_COMPLETION,
            journal.assess(final, true, true))
        assertTrue(journal.finishCommitted(final, "key2", sha("new"), true, true))
        assertFalse(File(root, "two.flac.bak").exists())
    }

    @Test fun processRestartSeesPendingJournalWithoutDeletingFiles() {
        val final = file("three.flac", "old")
        assertTrue(journal.promote(file("three.part", "new"), final, "key3", sha("new")))
        val restoredInstance = AirCatRecoveryJournal(File(root, ".aircat-journal"))
        assertEquals("key3", restoredInstance.pending(final)?.transferKey)
        assertEquals(AirCatRecoveryJournal.Assessment.NEEDS_REPAIR, restoredInstance.assess(final, null, null))
        assertEquals("old", File(root, "three.flac.bak").readText())
    }

    @Test fun differentTransferCannotOverwriteJournalAndBackup() {
        val final = file("four.flac", "old")
        assertTrue(journal.promote(file("one.part", "new"), final, "keyA", sha("new")))
        val outsider = file("other.part", "other")
        assertFalse(journal.promote(outsider, final, "keyB", sha("other")))
        assertEquals("other", outsider.readText())
        assertEquals("old", File(root, "four.flac.bak").readText())
    }

    @Test fun invalidChecksumDoesNotCreateJournalOrTouchFile() {
        val final = File(root, "five.flac")
        val part = file("five.part", "new")
        assertFalse(journal.promote(part, final, "key", "not-a-sha"))
        assertTrue(part.exists())
        assertFalse(final.exists())
        assertFalse(journal.hasJournal(final))
    }

    @Test fun mutatedFinalNeverPurgesPreviousValidFile() {
        val final = file("six.flac", "old")
        assertTrue(journal.promote(file("six.part", "new"), final, "key", sha("new")))
        final.writeText("tampered")
        assertFalse(journal.finishCommitted(final, "key", sha("new"), true, true))
        assertEquals(AirCatRecoveryJournal.Assessment.HASH_MISMATCH, journal.assess(final, true, true))
        assertEquals("old", File(root, "six.flac.bak").readText())
    }

    @Test fun pendingPromotionWithPreviousBackupDoesNotOverwriteBackup() {
        val final = file("seven.flac", "old")
        file("seven.flac.bak", "older")
        assertFalse(journal.promote(file("seven.part", "new"), final, "key", sha("new")))
        assertEquals("older", File(root, "seven.flac.bak").readText())
        assertEquals(AirCatRecoveryJournal.Phase.PREPARED, journal.pending(final)?.phase)
    }

    @Test fun legitimateAlreadyVerifiedReuseRequiresNoBackupOrJournal() {
        val final = file("eight.flac", "verified")
        assertTrue(journal.finishCommitted(final, "key", sha("verified"), true, true))
        file("eight.flac.bak", "old")
        assertFalse(journal.finishCommitted(final, "key", sha("verified"), true, true))
    }

    @Test fun unrelatedFilesHaveIndependentRecoveryTickets() {
        for (n in 1..2) {
            val final = File(root, "file$n.flac")
            assertTrue(journal.promote(file("file$n.part", "content$n"), final, "key", sha("content$n")))
        }
        assertEquals(2, File(root, ".aircat-journal").listFiles()?.size)
    }

    @Test fun symlinkPartIsRejectedBeforeJournalCreation() {
        val actual = file("real.dat", "audio")
        val link = File(root, "link.part")
        Files.createSymbolicLink(link.toPath(), actual.toPath())
        val final = File(root, "nine.flac")
        assertFalse(journal.promote(link, final, "key", sha("audio")))
        assertFalse(journal.hasJournal(final))
        assertEquals("audio", actual.readText())
    }

    @Test fun competingJournalInstancesCannotClaimSameTarget() {
        val final = file("race.flac", "last committed")
        val first = file("race-a.part", "new A")
        val second = file("race-b.part", "new B")
        val secondJournal = AirCatRecoveryJournal(File(root, ".aircat-journal"))
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val barrier = java.util.concurrent.CountDownLatch(1)
            val a = pool.submit<Boolean> { barrier.await(); journal.promote(first, final, "one", sha("new A")) }
            val b = pool.submit<Boolean> { barrier.await(); secondJournal.promote(second, final, "two", sha("new B")) }
            barrier.countDown()
            assertTrue(a.get(10, java.util.concurrent.TimeUnit.SECONDS) xor
                b.get(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(journal.hasJournal(final))
            assertEquals("last committed", File(root, "race.flac.bak").readText())
            assertEquals(1, listOf(first, second).count { it.exists() })
        } finally { pool.shutdownNow() }
    }

    @Test fun danglingSymlinkJournalBlocksFurtherPromotion() {
        val final = File(root, "dangling.flac")
        assertTrue(journal.promote(file("dangling.part", "yes"), final, "owned", sha("yes")))
        val ticket = File(root, ".aircat-journal").listFiles()!!.single()
        assertTrue(ticket.delete())
        Files.createSymbolicLink(ticket.toPath(), File(root, "missing-ticket").toPath())
        assertTrue(journal.hasJournal(final))
        assertEquals(AirCatRecoveryJournal.Assessment.CORRUPT_JOURNAL,
            journal.assess(final, null, null))
        assertFalse(journal.promote(file("again.part", "again"), final, "next", sha("again")))
    }

    @Test fun corruptReservationBlocksNewPromotionEvenIfNoFinalFileExists() {
        val final = File(root, "crash-before-rename.flac")
        assertTrue(journal.promote(file("crash.part", "new"), final, "key", sha("new")))
        val ticket = File(root, ".aircat-journal").listFiles()!!.single()
        ticket.writeText("AIRCJ1\npartial\n")
        assertEquals(AirCatRecoveryJournal.Assessment.CORRUPT_JOURNAL,
            journal.assess(final, true, true))
        assertFalse(journal.promote(file("new.part", "fresh"), final, "new", sha("fresh")))
        assertEquals("AIRCJ1\npartial\n", ticket.readText())
    }

    @Test fun crossDirectoryPartialRejectedBeforeJournalCreation() {
        val foreign = Files.createDirectory(File(root, "foreign").toPath()).toFile()
        val part = File(foreign, "track.part").also { it.writeText("data") }
        val final = File(root, "cross.flac")
        assertFalse(journal.promote(part, final, "key", sha("data")))
        assertFalse(journal.hasJournal(final))
        assertEquals("data", part.readText())
    }

    @Test fun finalSymlinkIsNeverReplacedByJournal() {
        val outside = file("actual.flac", "old")
        val final = File(root, "alias.flac")
        Files.createSymbolicLink(final.toPath(), outside.toPath())
        assertFalse(journal.promote(file("alias.part", "new"), final, "key", sha("new")))
        assertEquals("old", outside.readText())
    }

    @Test fun legacyReconcileSkipsJournalOwnedBackupsWithoutRoomGuessing() = kotlinx.coroutines.runBlocking {
        val final = file("journal-protected.flac", "old")
        assertTrue(journal.promote(file("protected.part", "new"), final, "key", sha("new")))
        assertEquals(0, AirCatFilePromotion.reconcile(root) { false })
        assertEquals("new", final.readText())
        assertEquals("old", File(root, "journal-protected.flac.bak").readText())
        assertTrue(journal.hasJournal(final))
    }

    @Test fun legacyReconcileStillRestoresWhenNoJournalExists() = kotlinx.coroutines.runBlocking {
        val final = file("legacy.flac", "uncommitted")
        file("legacy.flac.bak", "last good")
        assertEquals(1, AirCatFilePromotion.reconcile(root) { false })
        assertEquals("last good", final.readText())
    }

    @Test fun verifiedFinalCannotBeCompletedWithWrongTransferKey() {
        val final = File(root, "wrong-key.flac")
        assertTrue(journal.promote(file("wrong-key.part", "new"), final, "right", sha("new")))
        assertFalse(journal.finishCommitted(final, "other", sha("new"), true, true))
        assertTrue(journal.hasJournal(final))
    }

    @Test fun committedReuseRejectsDanglingBackupEvenWithoutJournal() {
        val final = file("dangling-reuse.flac", "known")
        val backup = File(root, "dangling-reuse.flac.bak")
        Files.createSymbolicLink(backup.toPath(), File(root, "missing-old").toPath())
        assertFalse(journal.finishCommitted(final, "key", sha("known"), true, true))
        assertTrue(Files.isSymbolicLink(backup.toPath()))
    }

    @Test fun finalizedJournalCannotIgnoreNewDanglingStagedBackup() {
        val final = file("dangling-finish.flac", "old")
        assertTrue(journal.promote(file("dangling-finish.part", "new"), final,
            "key", sha("new")))
        val backup = File(root, "dangling-finish.flac.bak")
        val staged = File(root, "dangling-finish.flac.bak.staged")
        Files.createSymbolicLink(staged.toPath(), File(root, "missing-staged").toPath())
        assertFalse(journal.finishCommitted(final, "key", sha("new"), true, true))
        assertTrue(backup.isFile)
        assertTrue(journal.hasJournal(final))
    }

    @Test fun oversizedJournalCannotBeAcceptedAsPendingOrPurged() {
        val final = File(root, "oversize.flac")
        assertTrue(journal.promote(file("oversize.part", "verified"), final, "key", sha("verified")))
        val ticket = File(root, ".aircat-journal").listFiles()!!.single()
        ticket.writeBytes(ByteArray(8193) { 'A'.code.toByte() })
        assertEquals(null, journal.pending(final))
        assertEquals(AirCatRecoveryJournal.Assessment.CORRUPT_JOURNAL,
            journal.assess(final,true,true))
        assertFalse(journal.finishCommitted(final,"key",sha("verified"),true,true))
        assertTrue(ticket.exists())
    }

}
