package org.michimusic.link.transfer

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.michimusic.core.models.DownloadItem
import org.michimusic.link.LinkClient
import org.michimusic.link.LinkTransferManager
import org.michimusic.link.StreamRangeResult
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest

class ResumableTransferTest {

    private lateinit var tempDir: File
    private lateinit var mockContext: Context
    private lateinit var transferManager: LinkTransferManager

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("transfer_test").toFile()
        mockContext = mockk(relaxed = true)
        every { mockContext.filesDir } returns tempDir
        transferManager = LinkTransferManager(mockContext)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun partialDownloadResumesAtOffsetTest() = runBlocking {
        val fullData = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toByteArray()
        val totalSize = fullData.size.toLong()
        val expectedHash = sha256(fullData)
        val trackId = "track_resume_1"

        // Simulate existing .part file of 10 bytes
        val partFile = File(File(tempDir, "synced_music").also { it.mkdirs() }, "$trackId.mp3.part")
        partFile.writeBytes(fullData.sliceArray(0 until 10))
        assertEquals(10L, partFile.length())

        val mockClient = mockk<LinkClient>()
        every {
            runBlocking {
                mockClient.streamTrackRange(
                    trackId = trackId,
                    outputStream = any(),
                    startBytes = 10L,
                    expectedEtag = any(),
                    bufferSize = any(),
                    onProgress = any(),
                )
            }
        } answers {
            val os = secondArg<OutputStream>()
            val remaining = fullData.sliceArray(10 until fullData.size)
            os.write(remaining)
            Result.success(
                StreamRangeResult(
                    bytesRead = remaining.size.toLong(),
                    statusCode = 206,
                    isPartialContent = true,
                    etag = "etag-123",
                    contentLength = remaining.size.toLong(),
                )
            )
        }

        val item = DownloadItem(
            trackId = trackId,
            title = "Resume Track",
            format = "mp3",
            size = totalSize,
            checksum = expectedHash,
        )

        val result = transferManager.downloadTrack(mockClient, item)
        assertTrue("Download should succeed", result.isSuccess)

        val finalFile = result.getOrThrow()
        assertTrue("Final file exists", finalFile.exists())
        assertEquals(totalSize, finalFile.length())
        assertEquals(expectedHash, sha256(finalFile.readBytes()))
        assertFalse(".part file was cleaned up after promotion", partFile.exists())

        val checkpoint = transferManager.checkpoints.value[trackId]
        assertNotNull(checkpoint)
        assertEquals(ResumeMode.RESUMED_FROM_OFFSET, checkpoint?.resumeMode)
        assertEquals(TransferState.COMPLETED, checkpoint?.state)
    }

    @Test
    fun rangeNotSupportedFallsBackHonestRestartTest() = runBlocking {
        val fullData = "Hello World From Audio Server".toByteArray()
        val totalSize = fullData.size.toLong()
        val expectedHash = sha256(fullData)
        val trackId = "track_no_range"

        // Existing .part file of 5 bytes
        val partFile = File(File(tempDir, "synced_music").also { it.mkdirs() }, "$trackId.mp3.part")
        partFile.writeBytes(fullData.sliceArray(0 until 5))

        val mockClient = mockk<LinkClient>()
        // Server ignores Range: startBytes=5 and returns 200 OK with full stream
        every {
            runBlocking {
                mockClient.streamTrackRange(
                    trackId = trackId,
                    outputStream = any(),
                    startBytes = 5L,
                    expectedEtag = any(),
                    bufferSize = any(),
                    onProgress = any(),
                )
            }
        } answers {
            val os = secondArg<OutputStream>()
            os.write(fullData)
            Result.success(
                StreamRangeResult(
                    bytesRead = fullData.size.toLong(),
                    statusCode = 200,
                    isPartialContent = false, // Ignored range
                    etag = "etag-full",
                )
            )
        }

        // Restarted call from zero
        every {
            runBlocking {
                mockClient.streamTrackRange(
                    trackId = trackId,
                    outputStream = any(),
                    startBytes = 0L,
                    expectedEtag = any(),
                    bufferSize = any(),
                    onProgress = any(),
                )
            }
        } answers {
            val os = secondArg<OutputStream>()
            os.write(fullData)
            Result.success(
                StreamRangeResult(
                    bytesRead = fullData.size.toLong(),
                    statusCode = 200,
                    isPartialContent = false,
                    etag = "etag-full",
                )
            )
        }

        val item = DownloadItem(
            trackId = trackId,
            title = "No Range Track",
            format = "mp3",
            size = totalSize,
            checksum = expectedHash,
        )

        val result = transferManager.downloadTrack(mockClient, item)
        assertTrue(result.isSuccess)
        val finalFile = result.getOrThrow()
        assertEquals(totalSize, finalFile.length())
        assertEquals(expectedHash, sha256(finalFile.readBytes()))

        val checkpoint = transferManager.checkpoints.value[trackId]
        assertEquals(ResumeMode.RESTARTED_FROM_ZERO, checkpoint?.resumeMode)
    }

    @Test
    fun checksumMismatchNeverPromotesTest() = runBlocking {
        val fullData = "Corrupted Audio Stream".toByteArray()
        val trackId = "track_bad_checksum"
        val wrongChecksum = "0000000000000000000000000000000000000000000000000000000000000000"

        val mockClient = mockk<LinkClient>()
        every {
            runBlocking {
                mockClient.streamTrackRange(
                    trackId = trackId,
                    outputStream = any(),
                    startBytes = 0L,
                    expectedEtag = any(),
                    bufferSize = any(),
                    onProgress = any(),
                )
            }
        } answers {
            val os = secondArg<OutputStream>()
            os.write(fullData)
            Result.success(
                StreamRangeResult(
                    bytesRead = fullData.size.toLong(),
                    statusCode = 200,
                    isPartialContent = false,
                )
            )
        }

        val item = DownloadItem(
            trackId = trackId,
            title = "Corrupt Track",
            format = "mp3",
            size = fullData.size.toLong(),
            checksum = wrongChecksum,
        )

        val result = transferManager.downloadTrack(mockClient, item)
        assertTrue("Checksum mismatch must fail result", result.isFailure)

        val musicDir = File(tempDir, "synced_music")
        val finalFile = File(musicDir, "$trackId.mp3")
        assertFalse("Final file must NEVER be promoted when checksum fails", finalFile.exists())
    }

    @Test
    fun cancelPreservesSafePartTest() = runBlocking {
        val trackId = "track_cancel"
        val mockClient = mockk<LinkClient>()

        every {
            runBlocking {
                mockClient.streamTrackRange(
                    trackId = trackId,
                    outputStream = any(),
                    startBytes = 0L,
                    expectedEtag = any(),
                    bufferSize = any(),
                    onProgress = any(),
                )
            }
        } coAnswers {
            val os = secondArg<OutputStream>()
            os.write("First Chunk".toByteArray())
            delay(5000) // Simulate ongoing transfer
            Result.success(StreamRangeResult(bytesRead = 11L, statusCode = 200, isPartialContent = false))
        }

        val item = DownloadItem(
            trackId = trackId,
            title = "Cancellable Track",
            format = "mp3",
            size = 100L,
        )

        val job = launch {
            transferManager.downloadTrack(mockClient, item)
        }

        delay(100)
        job.cancelAndJoin()

        val partFile = File(File(tempDir, "synced_music"), "$trackId.mp3.part")
        assertTrue(".part file must be preserved on cancellation for future resume", partFile.exists())
        assertEquals(11L, partFile.length())

        val checkpoint = transferManager.checkpoints.value[trackId]
        assertEquals(TransferState.PAUSED, checkpoint?.state)
    }

    @Test
    fun atomicPromotionKeepsOldGoodFileTest() = runBlocking {
        val goodOldData = "Old Good Sound".toByteArray()
        val trackId = "track_atomic"
        val musicDir = File(tempDir, "synced_music").also { it.mkdirs() }
        val finalFile = File(musicDir, "$trackId.mp3")
        finalFile.writeBytes(goodOldData)
        val goodOldHash = sha256(goodOldData)

        // Mock client that fails during transfer
        val mockClient = mockk<LinkClient>()
        every {
            runBlocking {
                mockClient.streamTrackRange(
                    trackId = trackId,
                    outputStream = any(),
                    startBytes = 0L,
                    expectedEtag = any(),
                    bufferSize = any(),
                    onProgress = any(),
                )
            }
        } returns Result.failure(Exception("Network dropped during stream"))

        val item = DownloadItem(
            trackId = trackId,
            title = "Atomic Track",
            format = "mp3",
            size = 500L,
            checksum = "some-new-checksum",
        )

        val result = transferManager.downloadTrack(mockClient, item)
        assertTrue(result.isFailure)

        // The good old file must still exist and be intact!
        assertTrue("Good old file must be preserved", finalFile.exists())
        assertEquals(goodOldHash, sha256(finalFile.readBytes()))
    }

    @Test
    fun rangeNotSatisfiableRecoversGracefullyTest() = runBlocking {
        val fullData = "Recovered From 416 Audio Stream".toByteArray()
        val totalSize = fullData.size.toLong()
        val expectedHash = sha256(fullData)
        val trackId = "track_416_recovery"

        // Existing .part file with 20 bytes (offset 20)
        val partFile = File(File(tempDir, "synced_music").also { it.mkdirs() }, "$trackId.mp3.part")
        partFile.writeBytes(fullData.sliceArray(0 until 20))

        val mockClient = mockk<LinkClient>()
        // Server rejects Range request with 416 Range Not Satisfiable
        every {
            runBlocking {
                mockClient.streamTrackRange(
                    trackId = trackId,
                    outputStream = any(),
                    startBytes = 20L,
                    expectedEtag = any(),
                    bufferSize = any(),
                    onProgress = any(),
                )
            }
        } returns Result.failure(org.michimusic.link.errors.LinkException.RangeNotSatisfiable)

        // Resumed from 0L succeeds
        every {
            runBlocking {
                mockClient.streamTrackRange(
                    trackId = trackId,
                    outputStream = any(),
                    startBytes = 0L,
                    expectedEtag = any(),
                    bufferSize = any(),
                    onProgress = any(),
                )
            }
        } answers {
            val os = secondArg<OutputStream>()
            os.write(fullData)
            Result.success(
                StreamRangeResult(
                    bytesRead = fullData.size.toLong(),
                    statusCode = 200,
                    isPartialContent = false,
                    etag = "etag-full-clean",
                )
            )
        }

        val item = DownloadItem(
            trackId = trackId,
            title = "416 Recovery Track",
            format = "mp3",
            size = totalSize,
            checksum = expectedHash,
        )

        val result = transferManager.downloadTrack(mockClient, item)
        assertTrue("Transfer must recover from 416 by restarting honestly", result.isSuccess)
        val finalFile = result.getOrThrow()
        assertEquals(totalSize, finalFile.length())
        assertEquals(expectedHash, sha256(finalFile.readBytes()))

        val checkpoint = transferManager.checkpoints.value[trackId]
        assertEquals(ResumeMode.RESTARTED_FROM_ZERO, checkpoint?.resumeMode)
        assertEquals(TransferState.COMPLETED, checkpoint?.state)
    }

    @Test
    fun offsetAlignment_truncatesPartFileWhenPhysicalSizeExceedsVerifiedCheckpointBytes() = runBlocking {
        val fullData = ByteArray(1000) { (it % 100).toByte() }
        val totalSize = fullData.size.toLong()
        val expectedHash = sha256(fullData)
        val trackId = "track_align_test"

        // Simulated scenario: physical .part file has 700 bytes on disk (e.g. 400 good bytes + 300 unverified bytes)
        val musicDir = File(tempDir, "synced_music").also { it.mkdirs() }
        val partFile = File(musicDir, "$trackId.mp3.part")
        // Write 400 valid bytes + 300 garbage bytes
        val initialPartData = fullData.sliceArray(0 until 400) + ByteArray(300) { 0xFF.toByte() }
        partFile.writeBytes(initialPartData)
        assertEquals(700L, partFile.length())

        val mockCheckpointRepo = mockk<org.michimusic.core.models.TransferCheckpointRepository>(relaxed = true)
        val cp = org.michimusic.core.models.PersistentCheckpoint(
            transferKey = trackId,
            transferId = "plan_1",
            remoteDeviceId = "dev_1",
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_dest",
            trackId = trackId,
            remoteTrackId = trackId,
            tempFilePath = partFile.absolutePath,
            finalFilePath = File(musicDir, "$trackId.mp3").absolutePath,
            expectedSize = totalSize,
            receivedBytes = 400L,
            verifiedBytes = 400L,
            expectedSha256 = expectedHash,
            transferState = org.michimusic.core.models.TransferStatus.DOWNLOADING,
        )
        coEvery { mockCheckpointRepo.getCheckpoint(trackId) } returns cp

        val alignedTransferManager = LinkTransferManager(mockContext, mockCheckpointRepo)
        val mockClient = mockk<LinkClient>()

        // LinkClient must be called with startBytes = 400L
        every {
            runBlocking {
                mockClient.streamTrackRange(
                    trackId = trackId,
                    outputStream = any(),
                    startBytes = 400L,
                    expectedEtag = any(),
                    bufferSize = any(),
                    onProgress = any(),
                )
            }
        } answers {
            val os = secondArg<OutputStream>()
            // Server streams remaining 600 bytes
            val remaining = fullData.sliceArray(400 until 1000)
            os.write(remaining)
            Result.success(
                StreamRangeResult(
                    bytesRead = remaining.size.toLong(),
                    statusCode = 206,
                    isPartialContent = true,
                    etag = "etag-align",
                )
            )
        }

        val item = DownloadItem(
            trackId = trackId,
            title = "Aligned Track",
            format = "mp3",
            size = totalSize,
            checksum = expectedHash,
            transferKey = trackId,
        )

        val result = alignedTransferManager.downloadTrack(mockClient, item)
        assertTrue("Download must succeed with offset alignment", result.isSuccess)

        val finalFile = result.getOrThrow()
        assertEquals(1000L, finalFile.length())
        assertEquals("Sha256 must match exactly without corrupted byte overlap", expectedHash, sha256(finalFile.readBytes()))
    }

    @Test
    fun atomicPromotion_preservesBackupAndAllowsRestorationOnFailure() = runBlocking {
        val originalData = "original_good_audio_content".toByteArray()
        val originalHash = sha256(originalData)
        val trackId = "track_backup_test"

        val musicDir = File(tempDir, "synced_music").also { it.mkdirs() }
        val finalFile = File(musicDir, "$trackId.mp3")
        finalFile.writeBytes(originalData)

        val newData = "new_downloaded_audio_content_that_replaces_old".toByteArray()
        val newHash = sha256(newData)
        val mockClient = mockk<LinkClient>()

        every {
            runBlocking {
                mockClient.streamTrackRange(
                    trackId = trackId,
                    outputStream = any(),
                    startBytes = 0L,
                    expectedEtag = any(),
                    bufferSize = any(),
                    onProgress = any(),
                )
            }
        } answers {
            val os = secondArg<OutputStream>()
            os.write(newData)
            Result.success(
                StreamRangeResult(
                    bytesRead = newData.size.toLong(),
                    statusCode = 200,
                    isPartialContent = false,
                    etag = "etag-new",
                )
            )
        }

        val item = DownloadItem(
            trackId = trackId,
            title = "Backup Test Track",
            format = "mp3",
            size = newData.size.toLong(),
            checksum = newHash,
            transferKey = trackId,
        )

        val result = transferManager.downloadTrack(mockClient, item)
        assertTrue(result.isSuccess)

        // After download, finalFile has new content
        assertEquals(newHash, sha256(finalFile.readBytes()))

        // .bak file exists with original data
        val backupFile = File(musicDir, "$trackId.mp3.bak")
        assertTrue(".bak must be preserved on disk for caller rollback", backupFile.exists())
        assertEquals(originalHash, sha256(backupFile.readBytes()))

        // Simulate rollback by caller
        val restored = transferManager.restoreBackup(finalFile)
        assertTrue("restoreBackup should succeed", restored)
        assertEquals("File must be restored to original content", originalHash, sha256(finalFile.readBytes()))
        assertFalse(".bak must not exist after restoration", backupFile.exists())
    }

    @Test
    fun reconcileOrphanBackups_restoresMissingTargetFromBackup() = runBlocking {
        val musicDir = File(tempDir, "synced_music").also { it.mkdirs() }
        val goodContent = "survived_backup_content".toByteArray()
        val backupFile = File(musicDir, "track_reconcile.mp3.bak").also { it.writeBytes(goodContent) }
        val targetFile = File(musicDir, "track_reconcile.mp3")

        assertFalse("Target file should not exist initially", targetFile.exists())
        assertTrue("Backup file must exist", backupFile.exists())

        val restoredCount = transferManager.reconcileOrphanBackups(musicDir)
        assertEquals(1, restoredCount)
        assertTrue("Target file must be restored", targetFile.exists())
        assertEquals(goodContent.size.toLong(), targetFile.length())
        assertFalse("Backup file must have been renamed to target", backupFile.exists())
    }

    @Test
    fun reconcileOrphanBackups_restoresBackup_whenTargetFileExistsButIsNotRegisteredInRoom() = runBlocking {
        // Critical P0 Test: If target file exists on disk (e.g. from an uncommitted promotion)
        // but is NOT registered in Room, reconcileOrphanBackups must NOT delete the backup.
        // It must restore the previous valid backup over the unconfirmed target.
        val musicDir = File(tempDir, "synced_music").also { it.mkdirs() }
        val validOriginal = "valid_original_audio_content".toByteArray()
        val uncommittedNew = "uncommitted_unregistered_audio".toByteArray()

        val backupFile = File(musicDir, "track_unregistered.mp3.bak").also { it.writeBytes(validOriginal) }
        val targetFile = File(musicDir, "track_unregistered.mp3").also { it.writeBytes(uncommittedNew) }

        assertTrue(backupFile.exists())
        assertTrue(targetFile.exists())

        // isRegisteredInDatabase returns false (not registered)
        val restoredCount = transferManager.reconcileOrphanBackups(
            targetDir = musicDir,
            isRegisteredInDatabase = { false }
        )

        assertEquals(1, restoredCount)
        assertTrue("Target file must still exist", targetFile.exists())
        assertEquals("Target file must contain restored original content, not uncommitted",
            validOriginal.size.toLong(), targetFile.length())
        assertEquals(String(validOriginal), targetFile.readText())
        assertFalse("Backup file should have been restored over target", backupFile.exists())
    }

    @Test
    fun reconcileOrphanBackups_purgesBackup_whenTargetFileIsConfirmedRegisteredInRoom() = runBlocking {
        val musicDir = File(tempDir, "synced_music").also { it.mkdirs() }
        val oldBackup = "old_backup_audio_content".toByteArray()
        val confirmedNew = "confirmed_registered_audio".toByteArray()

        val backupFile = File(musicDir, "track_confirmed.mp3.bak").also { it.writeBytes(oldBackup) }
        val targetFile = File(musicDir, "track_confirmed.mp3").also { it.writeBytes(confirmedNew) }

        assertTrue(backupFile.exists())
        assertTrue(targetFile.exists())

        // isRegisteredInDatabase returns true (confirmed in Room)
        val restoredCount = transferManager.reconcileOrphanBackups(
            targetDir = musicDir,
            isRegisteredInDatabase = { true }
        )

        assertEquals(0, restoredCount)
        assertTrue("Target file must remain intact", targetFile.exists())
        assertEquals(confirmedNew.size.toLong(), targetFile.length())
        assertFalse("Redundant backup must be purged once confirmed in Room", backupFile.exists())
    }

    @Test
    fun receivedBytesSeparatedFromVerifiedBytes_onMidStreamFailure() = runBlocking {
        val trackId = "track_byte_separation"
        val totalSize = 1000L
        val mockCheckpointRepo = mockk<org.michimusic.core.models.TransferCheckpointRepository>(relaxed = true)
        val managerWithRepo = LinkTransferManager(mockContext, mockCheckpointRepo)
        val mockClient = mockk<LinkClient>()

        var recordedReceived = -1L
        var recordedVerified = -1L
        coEvery {
            mockCheckpointRepo.updateProgress(
                transferKey = trackId,
                receivedBytes = any(),
                verifiedBytes = any(),
                state = any(),
            )
        } answers {
            recordedReceived = secondArg()
            recordedVerified = thirdArg()
        }

        coEvery {
            mockClient.streamTrackRange(
                trackId = trackId,
                outputStream = any(),
                startBytes = 0L,
                expectedEtag = any(),
                bufferSize = any(),
                onProgress = any(),
            )
        } answers {
            val os = secondArg<OutputStream>()
            val onProg = arg<((Long, Long?) -> Unit)?>(5)
            os.write(ByteArray(350) { 0x11 })
            onProg?.invoke(350L, totalSize)
            // Stream fails abruptly after 350 bytes
            Result.failure(java.io.IOException("Socket timeout"))
        }

        val item = DownloadItem(
            trackId = trackId,
            title = "Byte Separation",
            format = "mp3",
            size = totalSize,
            checksum = "dummy",
            transferKey = trackId,
        )

        val result = managerWithRepo.downloadTrack(mockClient, item)
        assertTrue("Transfer must fail", result.isFailure)

        // Physical bytes (received) must be 350L, while verifiedBytes must stay strictly at startOffset (0L)
        assertEquals(350L, recordedReceived)
        assertEquals("verifiedBytes must NOT be credited when stream fails", 0L, recordedVerified)
    }
}

