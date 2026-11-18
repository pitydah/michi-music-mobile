package org.michimusic.mobile.devices.aircat

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.michimusic.core.models.PersistentCheckpoint
import org.michimusic.core.models.TransferStatus

class AirCatRecoveryBatchPreflightPolicyTest {
    private fun valid(root: File, suffix: String = "one") = PersistentCheckpoint(
        transferKey = "peer:dst:$suffix", transferId = "plan-$suffix",
        remoteDeviceId = "peer", sourceLibraryId = "src", destinationLibraryId = "dst",
        trackId = "track-$suffix", remoteTrackId = "remote-$suffix",
        tempFilePath = File(root, "$suffix.flac.part").absolutePath,
        finalFilePath = File(root, "$suffix.flac").absolutePath,
        expectedSize = 1024L, receivedBytes = 32L, verifiedBytes = 16L,
        expectedSha256 = "a".repeat(64), transferState = TransferStatus.PAUSED,
    )
    private fun check(root: File, vararg checkpoints: PersistentCheckpoint): String? =
        AirCatRecoveryBatchPreflightPolicy.refusal(checkpoints.toList(), "peer", root)

    @Test fun acceptsValidScopedBatch() {
        val root = Files.createTempDirectory("aircat-batch").toFile()
        assertNull(check(root, valid(root), valid(root, "two")))
    }
    @Test fun emptyBatchIsSafeNoOp() {
        val root = Files.createTempDirectory("aircat-batch").toFile()
        assertNull(check(root))
    }
    @Test fun wrongPeerFailsBeforeAnyCommit() {
        val root = Files.createTempDirectory("aircat-batch").toFile()
        assertEquals("AIRC_RECOVERY_INVALID_CHECKPOINT", check(root, valid(root).copy(remoteDeviceId="other")))
    }
    @Test fun duplicateKeyFailsForWholeBatch() {
        val root = Files.createTempDirectory("aircat-batch").toFile()
        assertEquals("AIRC_RECOVERY_DUPLICATE_KEY", check(root, valid(root), valid(root, "two").copy(transferKey="peer:dst:one")))
    }
    @Test fun duplicateFinalPathFailsForWholeBatch() {
        val root = Files.createTempDirectory("aircat-batch").toFile()
        assertEquals("AIRC_RECOVERY_DUPLICATE_TARGET", check(root, valid(root), valid(root, "two").copy(finalFilePath=valid(root).finalFilePath)))
    }
    @Test fun rejectsForeignDestinationEvenIfFileDoesNotExist() {
        val root = Files.createTempDirectory("aircat-batch").toFile()
        val foreign = Files.createTempDirectory("aircat-other").toFile()
        assertEquals("AIRC_RECOVERY_UNMANAGED_PATH", check(root, valid(root).copy(finalFilePath=File(foreign,"out.flac").absolutePath)))
    }
    @Test fun rejectsForeignPartEvenIfFileDoesNotExist() {
        val root = Files.createTempDirectory("aircat-batch").toFile()
        val foreign = Files.createTempDirectory("aircat-other").toFile()
        assertEquals("AIRC_RECOVERY_UNMANAGED_PATH", check(root, valid(root).copy(tempFilePath=File(foreign,"out.part").absolutePath)))
    }
    @Test fun rejectsSymlinkFinal() {
        val root = Files.createTempDirectory("aircat-batch").toFile()
        val other = Files.createTempFile("aircat-foreign", ".flac")
        Files.createSymbolicLink(File(root,"one.flac").toPath(), other)
        assertEquals("AIRC_RECOVERY_UNMANAGED_PATH", check(root, valid(root)))
    }
    @Test fun rejectsSymlinkPart() {
        val root = Files.createTempDirectory("aircat-batch").toFile()
        val other = Files.createTempFile("aircat-foreign", ".part")
        Files.createSymbolicLink(File(root,"one.flac.part").toPath(), other)
        assertEquals("AIRC_RECOVERY_UNMANAGED_PATH", check(root, valid(root)))
    }
    @Test fun rejectsMaliciousSecondItemBeforeFirstCanCommit() {
        val root = Files.createTempDirectory("aircat-batch").toFile()
        val first = valid(root)
        val second = valid(root, "two").copy(expectedSha256="not a hash")
        assertEquals("AIRC_RECOVERY_INVALID_CHECKPOINT", check(root, first, second))
    }
    @Test fun rejectsVerifiedOffsetBeyondReceived() {
        val root = Files.createTempDirectory("aircat-batch").toFile()
        assertEquals("AIRC_RECOVERY_INVALID_CHECKPOINT", check(root,valid(root).copy(receivedBytes=5, verifiedBytes=6)))
    }
    @Test fun rejectsSizeOverflow() {
        val root = Files.createTempDirectory("aircat-batch").toFile()
        val first = valid(root).copy(expectedSize=Long.MAX_VALUE)
        assertEquals("AIRC_RECOVERY_INVALID_TOTAL", check(root,first,valid(root,"two")))
    }
    @Test fun rejectsNonDirectoryRoot() {
        val root = Files.createTempFile("aircat-batch", ".file").toFile()
        assertEquals("AIRC_RECOVERY_INVALID_ROOT", check(root))
    }
    @Test fun existingRegularPartAndFinalAreAllowed() {
        val root = Files.createTempDirectory("aircat-batch").toFile()
        File(root,"one.flac").writeText("old")
        File(root,"one.flac.part").writeText("partial")
        assertNull(check(root,valid(root)))
    }
    @Test fun rejectsDuplicateTempPathAcrossDifferentFiles() {
        val root = Files.createTempDirectory("aircat-batch").toFile()
        val first = valid(root)
        val second = valid(root, "two").copy(tempFilePath=first.tempFilePath)
        assertEquals("AIRC_RECOVERY_PATH_COLLISION", check(root, first, second))
    }
    @Test fun rejectsTempAliasOfOtherFinal() {
        val root = Files.createTempDirectory("aircat-batch").toFile()
        val first = valid(root)
        val second = valid(root, "two").copy(
            finalFilePath=File(root, "one.flac.part").absolutePath,
            tempFilePath=File(root, "third.flac.part").absolutePath,
        )
        assertEquals("AIRC_RECOVERY_INVALID_PATH", check(root, first, second))
    }
    @Test fun refusesSameLogicalTrackWithDifferentDestinationFiles() {
        val root = Files.createTempDirectory("aircat-batch").toFile()
        val first = valid(root)
        val second = valid(root, "two").copy(trackId=first.trackId)
        assertEquals("AIRC_RECOVERY_DUPLICATE_LOGICAL_TARGET", check(root, first, second))
    }
    @Test fun allowsSameLogicalTrackAcrossDistinctDestinationLibraries() {
        val root = Files.createTempDirectory("aircat-batch").toFile()
        val first = valid(root)
        val second = valid(root, "two").copy(trackId=first.trackId,
            destinationLibraryId="other-library")
        assertNull(check(root, first, second))
    }
    @Test fun rejectsPartAliasOfOwnBackup() {
        val root = Files.createTempDirectory("aircat-batch").toFile()
        val item = valid(root).copy(tempFilePath=File(root,"one.flac.bak").absolutePath)
        assertEquals("AIRC_RECOVERY_INVALID_PATH", check(root, item))
    }
    @Test fun refusesReservedJournalSuffixAsAudioDestination() {
        val root = Files.createTempDirectory("aircat-batch").toFile()
        val item = valid(root).copy(finalFilePath=File(root,"track.aircat-journal").absolutePath)
        assertEquals("AIRC_RECOVERY_INVALID_PATH", check(root, item))
    }
    @Test fun rejectsStagedBackupAliasWithinBatch() {
        val root = Files.createTempDirectory("aircat-batch").toFile()
        val first = valid(root)
        val second = valid(root, "two").copy(tempFilePath=File(root,"one.flac.bak.staged").absolutePath)
        assertEquals("AIRC_RECOVERY_INVALID_PATH", check(root, first, second))
    }
    @Test fun rejectsFullBatchAboveTenThousandBeforeTouchingFiles() {
        val root = Files.createTempDirectory("aircat-batch").toFile()
        val batch = (0..10_000).map { valid(root, "entry-$it") }
        assertEquals("AIRC_RECOVERY_INVALID_TOTAL",
            AirCatRecoveryBatchPreflightPolicy.refusal(batch,"peer",root))
    }

    @Test fun rejectsSymlinkBackupBeforeBeginningBatchRecovery() {
        val root = Files.createTempDirectory("aircat-batch").toFile()
        val foreign = Files.createTempFile("aircat-foreign-backup", ".bak")
        Files.createSymbolicLink(File(root, "two.flac.bak").toPath(), foreign)
        // First checkpoint is valid, second must abort the entire selection.
        assertEquals("AIRC_RECOVERY_UNSAFE_BACKUP",
            check(root, valid(root), valid(root, "two")))
    }
    @Test fun rejectsDanglingSymlinkInStagedBackup() {
        val root = Files.createTempDirectory("aircat-batch").toFile()
        val missing = File(root, "missing-outside.flac")
        Files.createSymbolicLink(File(root, "one.flac.bak.staged").toPath(), missing.toPath())
        assertEquals("AIRC_RECOVERY_UNSAFE_BACKUP", check(root, valid(root)))
    }
    @Test fun rejectsDirectoryDisguisedAsRollbackBackup() {
        val root = Files.createTempDirectory("aircat-batch").toFile()
        File(root, "one.flac.bak").mkdir()
        assertEquals("AIRC_RECOVERY_UNSAFE_BACKUP", check(root, valid(root)))
    }
    @Test fun refusesEmptyBackupRatherThanStartingPartialBatch() {
        val root = Files.createTempDirectory("aircat-batch").toFile()
        File(root, "one.flac.bak.staged").createNewFile()
        assertEquals("AIRC_RECOVERY_UNSAFE_BACKUP", check(root, valid(root)))
    }
    @Test fun rejectsTwoCompetingBackupsBeforeAnyMutation() {
        val root = Files.createTempDirectory("aircat-batch").toFile()
        File(root, "two.flac.bak").writeText("old-good")
        File(root, "two.flac.bak.staged").writeText("another-old")
        assertEquals("AIRC_RECOVERY_AMBIGUOUS_BACKUP",
            check(root, valid(root), valid(root, "two")))
    }
    @Test fun allowsOneValidNonemptyBackupForExplicitRecovery() {
        val root = Files.createTempDirectory("aircat-batch").toFile()
        File(root, "one.flac.bak").writeText("last-good-file")
        assertNull(check(root, valid(root)))
    }
    @Test fun allowsOneValidStagedBackupForExplicitRecovery() {
        val root = Files.createTempDirectory("aircat-batch").toFile()
        File(root, "one.flac.bak.staged").writeText("last-good-file")
        assertNull(check(root, valid(root)))
    }

    @Test fun rejectsAmbiguousRemoteIdInSameSignedPlan() {
        val root = Files.createTempDirectory("aircat-a43").toFile()
        try {
            val a = valid(root)
            val b = valid(root,"two").copy(transferId=a.transferId,remoteTrackId=a.remoteTrackId)
            assertEquals("AIRC_RECOVERY_DUPLICATE_REMOTE", check(root,a,b))
            assertNull(check(root,a,b.copy(transferId="plan-other")))
        } finally { root.deleteRecursively() }
    }

}
