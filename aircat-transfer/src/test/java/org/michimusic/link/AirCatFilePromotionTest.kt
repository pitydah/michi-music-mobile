package org.michimusic.link

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

class AirCatFilePromotionTest {
    private lateinit var dir: File
    @Before fun create() { dir = Files.createTempDirectory("aircat_a19_").toFile() }
    @After fun cleanup() { dir.deleteRecursively() }
    private fun file(name: String, value: String): File = File(dir, name).also { it.writeText(value) }

    @Test fun firstPromotionDoesNotNeedBackup() {
        val part = file("a.part", "verified")
        val final = File(dir, "a.flac")
        assertTrue(AirCatFilePromotion.promote(part, final))
        assertEquals("verified", final.readText())
        assertFalse(part.exists())
        assertFalse(File(dir, "a.flac.bak").exists())
    }

    @Test fun replacingFinalPreservesOldCopyUntilCommit() {
        val old = file("a.flac", "old verified audio")
        val part = file("a.part", "new verified audio")
        assertTrue(AirCatFilePromotion.promote(part, old))
        assertEquals("new verified audio", old.readText())
        assertEquals("old verified audio", File(dir, "a.flac.bak").readText())
        assertTrue(AirCatFilePromotion.discardAfterCommit(old))
        assertFalse(File(dir, "a.flac.bak").exists())
    }

    @Test fun oldBackupCannotBeOverwrittenByNewAttempt() {
        val final = file("a.flac", "new registered")
        val bak = file("a.flac.bak", "old registered")
        val part = file("a.part", "new candidate")
        assertFalse(AirCatFilePromotion.promote(part, final))
        assertEquals("new registered", final.readText())
        assertEquals("old registered", bak.readText())
        assertEquals("new candidate", part.readText())
    }

    @Test fun stagedLegacyBackupAlsoBlocksPromotion() {
        val final = file("a.flac", "current")
        val pending = file("a.flac.bak.staged", "older")
        val part = file("a.part", "future")
        assertFalse(AirCatFilePromotion.promote(part, final))
        assertEquals("older", pending.readText())
    }

    @Test fun atomicRollbackKeepsOldBytes() {
        val final = file("a.flac", "uncommitted")
        file("a.flac.bak", "last committed")
        assertTrue(AirCatFilePromotion.restore(final))
        assertEquals("last committed", final.readText())
        assertFalse(File(dir, "a.flac.bak").exists())
    }

    @Test fun restoreWithoutBackupNeverDeletesFinal() {
        val final = file("a.flac", "keep")
        assertFalse(AirCatFilePromotion.restore(final))
        assertEquals("keep", final.readText())
    }

    @Test fun ambiguousDualBackupsArePreserved() {
        val final = file("a.flac", "current")
        val bak = file("a.flac.bak", "older")
        val staged = file("a.flac.bak.staged", "other")
        assertFalse(AirCatFilePromotion.restore(final))
        assertEquals("older", bak.readText())
        assertEquals("other", staged.readText())
        assertEquals("current", final.readText())
    }

    @Test fun emptyPartAndEmptyBackupAreRejected() {
        val final = file("a.flac", "good")
        val part = file("a.part", "")
        assertFalse(AirCatFilePromotion.promote(part, final))
        file("a.flac.bak", "")
        assertFalse(AirCatFilePromotion.restore(final))
        assertEquals("good", final.readText())
    }

    @Test fun discardDoesNotDestroyBackupIfFinalMissing() {
        file("a.flac.bak", "good")
        assertFalse(AirCatFilePromotion.discardAfterCommit(File(dir, "a.flac")))
        assertTrue(File(dir, "a.flac.bak").exists())
    }

    @Test fun missingFinalIsRecoveredFromBackup() = runBlocking {
        file("a.flac.bak", "good")
        assertEquals(1, AirCatFilePromotion.reconcile(dir))
        assertEquals("good", File(dir, "a.flac").readText())
    }

    @Test fun unregisteredCandidateRollsBackWithoutPriorDelete() = runBlocking {
        val final = file("a.flac", "uncommitted")
        file("a.flac.bak", "committed")
        assertEquals(1, AirCatFilePromotion.reconcile(dir) { false })
        assertEquals("committed", final.readText())
    }

    @Test fun registeredFileDoesNotDiscardBackupWithoutHashProof() = runBlocking {
        val final = file("a.flac", "new")
        val bak = file("a.flac.bak", "old")
        assertEquals(0, AirCatFilePromotion.reconcile(dir) { true })
        assertEquals("new", final.readText())
        assertEquals("old", bak.readText())
    }

    @Test fun conflictingBackupCandidatesNeverAutoRestore() = runBlocking {
        val final = file("a.flac", "uncommitted")
        val bak = file("a.flac.bak", "first")
        val staged = file("a.flac.bak.staged", "second")
        assertEquals(0, AirCatFilePromotion.reconcile(dir) { false })
        assertEquals("uncommitted", final.readText())
        assertEquals("first", bak.readText())
        assertEquals("second", staged.readText())
    }

    @Test fun symbolicLinkAsPartialIsRejected() {
        val real = file("source.dat", "original")
        val link = File(dir, "alias.part")
        Files.createSymbolicLink(link.toPath(), real.toPath())
        assertFalse(AirCatFilePromotion.promote(link, File(dir, "a.flac")))
        assertEquals("original", real.readText())
    }

    @Test fun twoConcurrentPromotionsCannotOverwriteExistingBackup() {
        val final = file("a.flac", "original")
        val a = file("a.part", "first")
        val b = file("b.part", "second")
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val start = java.util.concurrent.CountDownLatch(1)
            val fa = executor.submit<Boolean> { start.await(); AirCatFilePromotion.promote(a, final) }
            val fb = executor.submit<Boolean> { start.await(); AirCatFilePromotion.promote(b, final) }
            start.countDown()
            assertTrue(fa.get(10, java.util.concurrent.TimeUnit.SECONDS) xor
                fb.get(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("original", File(dir, "a.flac.bak").readText())
        } finally { executor.shutdownNow() }
    }
    // A36: java.io.File.exists() follows links, so dangling symlinks used to
    // masquerade as free backup/final paths. NOFOLLOW checks must reject them.
    @Test fun danglingBackupMustBlockPromotionAndPreserveCurrentFile() {
        val final = file("blocked.flac", "last-committed")
        val partial = file("blocked.part", "candidate")
        val backup = File(dir, "blocked.flac.bak")
        Files.createSymbolicLink(backup.toPath(), File(dir, "missing.flac").toPath())
        assertFalse(AirCatFilePromotion.promote(partial, final))
        assertEquals("last-committed", final.readText())
        assertEquals("candidate", partial.readText())
        assertTrue(Files.isSymbolicLink(backup.toPath()))
    }

    @Test fun danglingStagedBackupMustBlockPromotion() {
        val final = file("staged.flac", "last-committed")
        val partial = file("staged.part", "candidate")
        val staged = File(dir, "staged.flac.bak.staged")
        Files.createSymbolicLink(staged.toPath(), File(dir, "missing-staged").toPath())
        assertFalse(AirCatFilePromotion.promote(partial, final))
        assertEquals("last-committed", final.readText())
        assertTrue(Files.isSymbolicLink(staged.toPath()))
    }

    @Test fun danglingFinalSymlinkCannotBePromotedOver() {
        val final = File(dir, "destination.flac")
        Files.createSymbolicLink(final.toPath(), File(dir, "missing-destination").toPath())
        val partial = file("destination.part", "candidate")
        assertFalse(AirCatFilePromotion.promote(partial, final))
        assertTrue(Files.isSymbolicLink(final.toPath()))
        assertEquals("candidate", partial.readText())
    }

    @Test fun restoreDoesNotReplaceDanglingFinalSymlink() {
        val final = File(dir, "restored.flac")
        Files.createSymbolicLink(final.toPath(), File(dir, "missing-destination").toPath())
        val backup = file("restored.flac.bak", "last-good")
        assertFalse(AirCatFilePromotion.restore(final))
        assertEquals("last-good", backup.readText())
        assertTrue(Files.isSymbolicLink(final.toPath()))
    }

    @Test fun hiddenDanglingStagedCannotBeIgnoredWhenRestoringValidBackup() {
        val final = file("ambiguous.flac", "currently-visible")
        val backup = file("ambiguous.flac.bak", "last-good")
        val staged = File(dir, "ambiguous.flac.bak.staged")
        Files.createSymbolicLink(staged.toPath(), File(dir, "missing-staged").toPath())
        assertFalse(AirCatFilePromotion.restore(final))
        assertEquals("currently-visible", final.readText())
        assertEquals("last-good", backup.readText())
    }

    @Test fun hiddenDanglingStagedMustBlockBackupDeletionAfterCommit() {
        val final = file("done.flac", "verified-new")
        val backup = file("done.flac.bak", "old-valid")
        val staged = File(dir, "done.flac.bak.staged")
        Files.createSymbolicLink(staged.toPath(), File(dir, "missing-staged").toPath())
        assertFalse(AirCatFilePromotion.discardAfterCommit(final))
        assertEquals("old-valid", backup.readText())
    }

    @Test fun reconcileDoesNotSelectOneOfTwoBackupsWhenSecondIsDangling() = runBlocking {
        val final = file("two.flac", "uncommitted")
        val bak = file("two.flac.bak", "prior-verified")
        val staged = File(dir, "two.flac.bak.staged")
        Files.createSymbolicLink(staged.toPath(), File(dir, "missing-backup").toPath())
        assertEquals(0, AirCatFilePromotion.reconcile(dir) { false })
        assertEquals("uncommitted", final.readText())
        assertEquals("prior-verified", bak.readText())
        assertTrue(Files.isSymbolicLink(staged.toPath()))
    }

    @Test fun reconcileRejectsSymlinkDirectory() = runBlocking {
        file("existing.flac.bak", "last-good")
        val alias = File(dir.parentFile, "aircat_link_${System.nanoTime()}")
        Files.createSymbolicLink(alias.toPath(), dir.toPath())
        try {
            assertEquals(0, AirCatFilePromotion.reconcile(alias))
            assertFalse(File(dir, "existing.flac").exists())
        } finally { Files.deleteIfExists(alias.toPath()) }
    }

}
