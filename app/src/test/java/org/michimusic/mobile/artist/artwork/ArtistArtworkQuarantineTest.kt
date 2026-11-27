package org.michimusic.mobile.artist.artwork

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistArtworkQuarantineTest {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun fixture() = Files.createTempDirectory("artist-d45-")

    @Test fun verifiedFileIsStagedAndDestroyedWithoutDeletingOriginalPath() {
        val root = fixture();val content = byteArrayOf(1,2,3,4,5)
        val source = root.resolve("${sha(content)}.jpg")
        Files.write(source, content)
        val stage = ArtistArtworkQuarantine.stage(root.toFile(), source.toFile(), "task-1", "asset",sha(content))
        assertTrue(stage is ArtistArtworkQuarantineStage.Staged)
        assertFalse(Files.exists(source))
        assertEquals(ArtistArtworkQuarantineDelete.DELETED,
            ArtistArtworkQuarantine.destroy(stage as ArtistArtworkQuarantineStage.Staged,
                root.toFile(),"task-1","asset",sha(content)))
        assertFalse(Files.exists(stage.path))
    }

    @Test fun wrongBytesAreNeverRemoved() {
        val root=fixture();val good=byteArrayOf(1,2,3)
        val source=root.resolve("source.jpg");Files.write(source,byteArrayOf(2,3,4))
        assertEquals(ArtistArtworkQuarantineStage.ReviewRequired,
            ArtistArtworkQuarantine.stage(root.toFile(),source.toFile(),"task","asset",sha(good)))
        assertTrue(Files.exists(source))
    }

    @Test fun swapBetweenHashAndRenameDoesNotDeleteReplacement() {
        val root=fixture();val good=byteArrayOf(3,4,5)
        val source=root.resolve("source.jpg");Files.write(source,good)
        val moved=root.resolve("original.jpg")
        val stage=ArtistArtworkQuarantine.stage(root.toFile(),source.toFile(),"task","asset",sha(good),
            beforeAtomicMove = {
                Files.move(source,moved,StandardCopyOption.ATOMIC_MOVE)
                Files.write(source,byteArrayOf(6,7,8))
            })
        assertEquals(ArtistArtworkQuarantineStage.ReviewRequired,stage)
        assertTrue(Files.exists(moved))
        // Unexpected replacement stays in the original source slot, never staged.
        assertTrue(Files.exists(source))
        assertFalse(Files.exists(ArtistArtworkQuarantine.destination(root.toFile(),"task","asset")))
    }

    @Test fun crashAfterMoveResumesOnlyVerifiedQuarantine() {
        val root=fixture();val good=byteArrayOf(1,9,3)
        val source=root.resolve("original.jpg");Files.write(source,good)
        val first=ArtistArtworkQuarantine.stage(root.toFile(),source.toFile(),"task","asset",sha(good))
        assertTrue(first is ArtistArtworkQuarantineStage.Staged)
        val second=ArtistArtworkQuarantine.stage(root.toFile(),source.toFile(),"task","asset",sha(good))
        assertEquals(first,second)
    }

    @Test fun symlinkIsNeverStagedOrRemoved() {
        val root=fixture();val content=byteArrayOf(3,6,9)
        val victim=root.resolve("victim");Files.write(victim,content)
        val symlink=root.resolve("source.jpg");Files.createSymbolicLink(symlink,victim)
        assertEquals(ArtistArtworkQuarantineStage.ReviewRequired,
            ArtistArtworkQuarantine.stage(root.toFile(),symlink.toFile(),"task","asset",sha(content)))
        assertTrue(Files.exists(victim))
        assertTrue(Files.isSymbolicLink(symlink))
    }

    @Test fun foreignQuarantineEntryBlocksDeletion() {
        val root=fixture();val content=byteArrayOf(2,4,6)
        val source=root.resolve("source.jpg");Files.write(source,content)
        val destination=ArtistArtworkQuarantine.destination(root.toFile(),"task","asset")
        Files.createDirectories(destination.parent);Files.write(destination,content)
        assertEquals(ArtistArtworkQuarantineStage.ReviewRequired,
            ArtistArtworkQuarantine.stage(root.toFile(),source.toFile(),"task","asset",sha(content)))
        assertTrue(Files.exists(source))
    }

    @Test fun unsupportedAtomicMoveIsReviewNotInfiniteRetry() {
        val root=fixture();val bytes=byteArrayOf(7,8,9)
        val source=root.resolve("source.jpg");Files.write(source,bytes)
        assertEquals(ArtistArtworkQuarantineStage.ReviewRequired,
            ArtistArtworkQuarantine.stage(root.toFile(),source.toFile(),"task","asset",sha(bytes)) {
                throw UnsupportedOperationException("non atomic filesystem")
            })
        assertTrue(Files.exists(source))
    }

    @Test fun sharedAssetCanBeRestoredBeforeAck() {
        val root=fixture();val good=byteArrayOf(7,5,2)
        val source=root.resolve("original.jpg");Files.write(source,good)
        val stage=ArtistArtworkQuarantine.stage(root.toFile(),source.toFile(),"task","asset",sha(good))
        assertTrue(ArtistArtworkQuarantine.restore(stage as ArtistArtworkQuarantineStage.Staged,
            root.toFile(),source.toFile(),"task","asset",sha(good)))
        assertTrue(Files.exists(source))
    }
    @Test fun replacementAfterLastDigestIsReturnedToOriginalSlot() {
        val root = fixture()
        val good = byteArrayOf(1, 2, 3)
        val replacement = byteArrayOf(4, 5, 6)
        val source = root.resolve("candidate.webp")
        Files.write(source, good)
        val out = ArtistArtworkQuarantine.stage(
            root.toFile(), source.toFile(), "task", "asset", sha(good),
            afterFinalDigest = {
                Files.delete(source)
                Files.write(source, replacement)
            },
        )
        assertEquals(ArtistArtworkQuarantineStage.ReviewRequired, out)
        assertTrue(Files.exists(source))
        assertTrue(Files.readAllBytes(source).contentEquals(replacement))
        assertFalse(Files.exists(ArtistArtworkQuarantine.destination(root.toFile(), "task", "asset")))
    }

    @Test fun restoreNeverOverwritesARecreatedSource() {
        val root = fixture()
        val good = byteArrayOf(1, 3, 5, 7)
        val unexpected = byteArrayOf(4, 2, 1)
        val source = root.resolve("candidate.webp")
        Files.write(source, good)
        val staged = ArtistArtworkQuarantine.stage(root.toFile(), source.toFile(),
            "task", "asset", sha(good)) as ArtistArtworkQuarantineStage.Staged
        Files.write(source, unexpected)
        assertFalse(ArtistArtworkQuarantine.restore(staged, root.toFile(), source.toFile(),
            "task", "asset", sha(good)))
        assertTrue(Files.readAllBytes(source).contentEquals(unexpected))
        assertTrue(Files.exists(staged.path))
    }

    @Test fun mutatedQuarantineCannotSpinForever() {
        val root = fixture(); val bytes = byteArrayOf(1, 5, 9)
        val source = root.resolve("art.jpg"); Files.write(source, bytes)
        val staged = ArtistArtworkQuarantine.stage(root.toFile(), source.toFile(),
            "task", "asset", sha(bytes)) as ArtistArtworkQuarantineStage.Staged
        Files.write(staged.path, byteArrayOf(9, 5, 1))
        assertEquals(ArtistArtworkQuarantineDelete.REVIEW_REQUIRED,
            ArtistArtworkQuarantine.destroy(staged, root.toFile(), "task", "asset", sha(bytes)))
        assertTrue(Files.exists(staged.path))
    }

    @Test fun referencedAfterCrashRestoresOriginalBeforeRetainedAck() {
        val root = fixture(); val bytes = byteArrayOf(5, 6, 7)
        val source = root.resolve("source.jpg"); Files.write(source, bytes)
        val stage = ArtistArtworkQuarantine.stage(root.toFile(), source.toFile(), "task", "asset", sha(bytes))
            as ArtistArtworkQuarantineStage.Staged
        assertTrue(ArtistArtworkQuarantine.recoverReferenced(root.toFile(), source.toFile(), "task", "asset", sha(bytes)))
        assertTrue(Files.readAllBytes(source).contentEquals(bytes))
        assertFalse(Files.exists(stage.path))
        assertTrue(ArtistArtworkQuarantine.recoverReferenced(root.toFile(), source.toFile(), "task", "asset", sha(bytes)))
    }

    @Test fun referencedMissingOrConflictingBytesDoNotReportRetained() {
        val root = fixture(); val bytes = byteArrayOf(2, 3, 5)
        val source = root.resolve("source.jpg")
        assertFalse(ArtistArtworkQuarantine.recoverReferenced(root.toFile(), source.toFile(), "task", "asset", sha(bytes)))
        Files.write(source, bytes)
        val stage = ArtistArtworkQuarantine.stage(root.toFile(), source.toFile(), "task", "asset", sha(bytes))
            as ArtistArtworkQuarantineStage.Staged
        Files.write(source, byteArrayOf(9, 9, 9))
        assertFalse(ArtistArtworkQuarantine.recoverReferenced(root.toFile(), source.toFile(), "task", "asset", sha(bytes)))
        assertTrue(Files.exists(stage.path))
        assertTrue(Files.readAllBytes(source).contentEquals(byteArrayOf(9, 9, 9)))
    }

    @Test fun absentRootIsIdempotentButSymlinkRootIsNot() {
        val root = fixture(); val absent = root.resolve("absent"); val source = absent.resolve("source.jpg")
        assertEquals(ArtistArtworkQuarantineStage.SourceAbsent,
            ArtistArtworkQuarantine.stage(absent.toFile(), source.toFile(), "task", "asset", sha(byteArrayOf(1))))
        Files.createSymbolicLink(absent, root)
        assertEquals(ArtistArtworkQuarantineStage.ReviewRequired,
            ArtistArtworkQuarantine.stage(absent.toFile(), source.toFile(), "task", "asset", sha(byteArrayOf(1))))
    }

    @Test fun lateQuarantineCollisionPreservesBothFiles() {
        val root = fixture(); val good = byteArrayOf(1, 2, 3); val foreign = byteArrayOf(8, 9)
        val source = root.resolve("source.jpg"); Files.write(source, good)
        val destination = ArtistArtworkQuarantine.destination(root.toFile(), "task", "asset")
        assertEquals(ArtistArtworkQuarantineStage.ReviewRequired,
            ArtistArtworkQuarantine.stage(root.toFile(), source.toFile(), "task", "asset", sha(good),
                afterFinalDigest = { Files.write(destination, foreign) }))
        assertTrue(Files.readAllBytes(source).contentEquals(good))
        assertTrue(Files.readAllBytes(destination).contentEquals(foreign))
    }

    @Test fun redirectedQuarantineDirectoryCannotDeleteExternalFile() {
        val root = fixture(); val bytes = byteArrayOf(1, 4, 7)
        val source = root.resolve("source.jpg"); Files.write(source, bytes)
        val stage = ArtistArtworkQuarantine.stage(root.toFile(), source.toFile(), "task", "asset", sha(bytes))
            as ArtistArtworkQuarantineStage.Staged
        val external = fixture(); val victim = external.resolve(stage.path.fileName)
        Files.move(stage.path, victim); Files.delete(stage.path.parent)
        Files.createSymbolicLink(stage.path.parent, external)
        assertEquals(ArtistArtworkQuarantineDelete.REVIEW_REQUIRED,
            ArtistArtworkQuarantine.destroy(stage, root.toFile(), "task", "asset", sha(bytes)))
        assertTrue(Files.exists(victim))
    }

    @Test fun interruptedRestoreWithSameInodeCompletesAndIsIdempotent() {
        val root = fixture(); val bytes = byteArrayOf(1, 7, 9)
        val source = root.resolve("source.jpg"); Files.write(source, bytes)
        val staged = ArtistArtworkQuarantine.stage(root.toFile(), source.toFile(), "task", "asset", sha(bytes))
            as ArtistArtworkQuarantineStage.Staged
        Files.createLink(source, staged.path)
        assertTrue(ArtistArtworkQuarantine.recoverReferenced(root.toFile(), source.toFile(), "task", "asset", sha(bytes)))
        assertFalse(Files.exists(staged.path))
        assertTrue(Files.readAllBytes(source).contentEquals(bytes))
        assertTrue(ArtistArtworkQuarantine.recoverReferenced(root.toFile(), source.toFile(), "task", "asset", sha(bytes)))
    }

    @Test fun equalBytesInDistinctInodesNeverAuthorizeRemoval() {
        val root = fixture(); val bytes = byteArrayOf(3, 1, 4)
        val source = root.resolve("source.jpg"); Files.write(source, bytes)
        val staged = ArtistArtworkQuarantine.stage(root.toFile(), source.toFile(), "task", "asset", sha(bytes))
            as ArtistArtworkQuarantineStage.Staged
        Files.write(source, bytes)
        assertFalse(ArtistArtworkQuarantine.recoverReferenced(root.toFile(), source.toFile(), "task", "asset", sha(bytes)))
        assertTrue(Files.exists(staged.path)); assertTrue(Files.exists(source))
    }

    @Test fun unreferencedInterruptedRestoreCanResumeStaging() {
        val root = fixture(); val bytes = byteArrayOf(9, 1, 4)
        val source = root.resolve("source.jpg"); Files.write(source, bytes)
        val staged = ArtistArtworkQuarantine.stage(root.toFile(), source.toFile(), "task", "asset", sha(bytes))
            as ArtistArtworkQuarantineStage.Staged
        Files.createLink(source, staged.path)
        assertEquals(ArtistArtworkQuarantineStage.Retry,
            ArtistArtworkQuarantine.stage(root.toFile(), source.toFile(), "task", "asset", sha(bytes)))
        assertTrue(Files.exists(source)); assertFalse(Files.exists(staged.path))
        assertTrue(ArtistArtworkQuarantine.stage(root.toFile(), source.toFile(), "task", "asset", sha(bytes))
            is ArtistArtworkQuarantineStage.Staged)
    }

    @Test fun corruptedSameInodePreservesBothNames() {
        val root = fixture(); val bytes = byteArrayOf(2, 7, 1)
        val source = root.resolve("source.jpg"); Files.write(source, bytes)
        val staged = ArtistArtworkQuarantine.stage(root.toFile(), source.toFile(), "task", "asset", sha(bytes))
            as ArtistArtworkQuarantineStage.Staged
        Files.createLink(source, staged.path); Files.write(source, byteArrayOf(0))
        assertFalse(ArtistArtworkQuarantine.recoverReferenced(root.toFile(), source.toFile(), "task", "asset", sha(bytes)))
        assertTrue(Files.exists(source)); assertTrue(Files.exists(staged.path))
    }
}
