package org.michimusic.mobile.devices.aircat

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.michimusic.core.models.PersistentCheckpoint
import org.michimusic.core.models.TransferStatus

class AirCatInterruptedCommitPolicyTest {
    private lateinit var root: File
    private lateinit var finalFile: File
    private lateinit var digest: String

    @Before fun setUp() {
        root=Files.createTempDirectory("a24_committing_").toFile()
        finalFile=File(root, "song.flac").also { it.writeText("original file bytes") }
        digest=MessageDigest.getInstance("SHA-256").digest(finalFile.readBytes())
            .joinToString("") { "%02x".format(it) }
    }
    @After fun tearDown() { root.deleteRecursively() }
    private fun cp()=PersistentCheckpoint(
        transferKey="peer:lib:song:tx", transferId="tx", remoteDeviceId="peer",
        sourceLibraryId="src", destinationLibraryId="lib", trackId="song",
        remoteTrackId="raw-id", tempFilePath=File(root,"song.part").absolutePath,
        finalFilePath=finalFile.absolutePath, expectedSize=finalFile.length(),
        receivedBytes=finalFile.length(), verifiedBytes=finalFile.length(),
        expectedSha256=digest, transferState=TransferStatus.COMMITTING,
    )
    private fun allow(
        checkpoint:PersistentCheckpoint=cp(), peer:String="peer",
        registered:Boolean=true, sha:String?=digest,
        diagnostic:String="READY_FOR_EXPLICIT_COMPLETION",
    )=AirCatInterruptedCommitPolicy.mayConfirmExistingRoomCommit(
        checkpoint, peer, root, registered, sha, diagnostic)

    @Test fun committedRoomButCheckpointStillCommittingMayBeFinalized()=assertTrue(allow())
    @Test fun interruptedDownloadingWithCommittedRoomCanRecover()=assertTrue(allow(checkpoint=cp().copy(transferState=TransferStatus.DOWNLOADING)))
    @Test fun interruptedPausedWithCommittedRoomCanRecover()=assertTrue(allow(checkpoint=cp().copy(transferState=TransferStatus.PAUSED)))
    @Test fun interruptedPendingWithCommittedRoomCanRecover()=assertTrue(allow(checkpoint=cp().copy(transferState=TransferStatus.PENDING)))
    @Test fun cannotConfirmAlreadyFailed()=assertFalse(allow(checkpoint=cp().copy(transferState=TransferStatus.FAILED)))
    @Test fun cannotConfirmIfJournalNotReady()=assertFalse(allow(diagnostic="NEEDS_REPAIR"))
    @Test fun cannotConfirmCorruptJournal()=assertFalse(allow(diagnostic="CORRUPT_JOURNAL"))
    @Test fun cannotConfirmWithoutRoomLocation()=assertFalse(allow(registered=false))
    @Test fun cannotConfirmWithoutRoomIdentity()=assertFalse(allow(sha=null))
    @Test fun cannotConfirmWrongRoomIdentity()=assertFalse(allow(sha="f".repeat(64)))
    @Test fun cannotConfirmAnotherPeer()=assertFalse(allow(peer="other"))
    @Test fun cannotConfirmWrongFileSize()=assertFalse(allow(checkpoint=cp().copy(expectedSize=9999)))
    @Test fun cannotConfirmMissingFinalFile() {
        val checkpoint=cp(); finalFile.delete(); assertFalse(allow(checkpoint=checkpoint))
    }
    @Test fun cannotConfirmFileOutsideManagedRoot() {
        val checkpoint=cp(); assertFalse(AirCatInterruptedCommitPolicy.mayConfirmExistingRoomCommit(
            checkpoint,"peer",File(root,"elsewhere"),true,digest,"READY_FOR_EXPLICIT_COMPLETION"))
    }
}
