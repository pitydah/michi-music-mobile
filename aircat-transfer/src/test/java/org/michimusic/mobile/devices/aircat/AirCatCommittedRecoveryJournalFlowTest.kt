package org.michimusic.mobile.devices.aircat

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.michimusic.core.models.PersistentCheckpoint
import org.michimusic.core.models.TransferStatus
import org.michimusic.link.AirCatRecoveryJournal

/** A23: real file/journal transitions, fake database RECEIPT only. No Android build claimed. */
class AirCatCommittedRecoveryJournalFlowTest {
    private lateinit var root: File
    private lateinit var journal: AirCatRecoveryJournal
    private lateinit var final: File
    private val oldBytes = "old committed track"
    private val newBytes = "new raw track"
    private val sha: String get() = MessageDigest.getInstance("SHA-256")
        .digest(newBytes.toByteArray()).joinToString("") { "%02x".format(it) }
    @Before fun setup() {
        root = Files.createTempDirectory("a23_crash_").toFile()
        final = File(root, "song.flac").also { it.writeText(oldBytes) }
        journal = AirCatRecoveryJournal(File(root, ".aircat-journal"))
        assertTrue(journal.promote(File(root, "song.part").also { it.writeText(newBytes) },
            final, "key", sha))
    }
    @After fun cleanup() { root.deleteRecursively() }
    private fun cp(state: TransferStatus = TransferStatus.COMPLETED) = PersistentCheckpoint(
        transferKey = "key", transferId = "transfer-id", remoteDeviceId = "peer",
        sourceLibraryId = "source", destinationLibraryId = "library",
        trackId = "song", remoteTrackId = "remote-song",
        tempFilePath = File(root, "song.part").absolutePath, finalFilePath = final.absolutePath,
        expectedSize = newBytes.toByteArray().size.toLong(), receivedBytes = newBytes.toByteArray().size.toLong(),
        verifiedBytes = newBytes.toByteArray().size.toLong(), expectedSha256 = sha,
        transferState = state,
    )
    private fun approved(checkpoint: PersistentCheckpoint, location: Boolean = true, identityHash: String? = sha) =
        AirCatCommittedRecoveryPolicy.mayFinalize(checkpoint, "peer", root, location, identityHash)

    @Test fun crashAfterDatabaseCommitClosesJournalWithoutNetwork() {
        val durable = cp()
        assertEquals("old committed track", File(root,"song.flac.bak").readText())
        assertTrue(journal.hasJournal(final))
        assertTrue(approved(durable))
        assertTrue(journal.finishCommitted(final,"key",sha,true,true))
        assertFalse(journal.hasJournal(final))
        assertFalse(File(root,"song.flac.bak").exists())
        assertEquals(newBytes, final.readText())
    }
    @Test fun interruptedBeforeDatabaseCommitRetainsBothGenerations() {
        assertFalse(approved(cp(TransferStatus.COMMITTING)))
        assertTrue(journal.hasJournal(final))
        assertEquals(oldBytes, File(root,"song.flac.bak").readText())
        assertEquals(newBytes, final.readText())
    }
    @Test fun claimedRoomLocationWithDifferentIdentityCannotReleaseBackup() {
        assertFalse(approved(cp(), identityHash="0".repeat(64)))
        assertTrue(journal.hasJournal(final))
        assertEquals(oldBytes, File(root,"song.flac.bak").readText())
    }
    @Test fun sameLengthTamperFailsJournalHashAndPreservesPreviousFile() {
        assertTrue(approved(cp()))
        final.writeText("x".repeat(newBytes.length))
        assertFalse(journal.finishCommitted(final,"key",sha,true,true))
        assertTrue(journal.hasJournal(final))
        assertEquals(oldBytes, File(root,"song.flac.bak").readText())
    }
}
