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

class AirCatCommittedRecoveryPolicyTest {
    private lateinit var root: File
    private lateinit var file: File
    private lateinit var sha: String

    @Before fun setUp() {
        root = Files.createTempDirectory("aircat_a23_committed_").toFile()
        file = File(root, "song.flac").also { it.writeText("verified audio") }
        sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
    }
    @After fun clean() { root.deleteRecursively() }

    private fun checkpoint() = PersistentCheckpoint(
        transferKey="sender:library:song", transferId="aircat-123",
        remoteDeviceId="sender", sourceLibraryId="source", destinationLibraryId="library",
        trackId="song", remoteTrackId="remote-song", tempFilePath="${file.absolutePath}.part",
        finalFilePath=file.absolutePath, expectedSize=file.length(),
        receivedBytes=file.length(), verifiedBytes=file.length(), expectedSha256=sha,
        transferState=TransferStatus.COMPLETED,
    )
    private fun accept(cp: PersistentCheckpoint = checkpoint(), registered: Boolean = true,
        identityHash: String? = sha, remoteId: String = "sender", dir: File = root) =
        AirCatCommittedRecoveryPolicy.mayFinalize(cp, remoteId, dir, registered, identityHash)

    @Test fun validPersistedReceiptWithExactRoomLocationCanClose() { assertTrue(accept()) }
    @Test fun downloadingCannotClose() { assertFalse(accept(checkpoint().copy(transferState=TransferStatus.DOWNLOADING))) }
    @Test fun committingCannotClose() { assertFalse(accept(checkpoint().copy(transferState=TransferStatus.COMMITTING))) }
    @Test fun missingLocationCannotClose() { assertFalse(accept(registered=false)) }
    @Test fun unknownIdentityCannotClose() { assertFalse(accept(identityHash=null)) }
    @Test fun differentIdentityCannotClose() { assertFalse(accept(identityHash="f".repeat(64))) }
    @Test fun otherPeerCannotClose() { assertFalse(accept(remoteId="intruder")) }
    @Test fun missingRemoteIdCannotClose() { assertFalse(accept(checkpoint().copy(remoteTrackId=""))) }
    @Test fun missingTransferKeyCannotClose() { assertFalse(accept(checkpoint().copy(transferKey=""))) }
    @Test fun wrongSizeCannotClose() { assertFalse(accept(checkpoint().copy(expectedSize=999))) }
    @Test fun wrongHashCannotClose() { assertFalse(accept(checkpoint().copy(expectedSha256="f".repeat(64)))) }
    @Test fun nonexistentFileCannotClose() {
        file.delete()
        assertFalse(accept())
    }
    @Test fun alteredFileLengthCannotClose() {
        val durable = checkpoint()
        file.appendText("modified")
        assertFalse(accept(durable))
    }
    @Test fun outsideManagedDirectoryCannotClose() {
        assertFalse(accept(dir=File(root, "elsewhere")))
    }
    @Test fun symlinkToOutsideManagedDirectoryCannotClose() {
        val outside = Files.createTempFile("aircat_a23_outside_", ".flac").toFile()
        try {
            outside.writeBytes(file.readBytes())
            file.delete()
            Files.createSymbolicLink(file.toPath(), outside.toPath())
            assertFalse(accept())
        } finally { outside.delete() }
    }
    @Test fun pathOutsideRootCannotClose() {
        val outside = Files.createTempFile("aircat_a23_escape_", ".flac").toFile()
        try {
            outside.writeBytes(file.readBytes())
            assertFalse(accept(checkpoint().copy(finalFilePath=outside.absolutePath)))
        } finally { outside.delete() }
    }
}
