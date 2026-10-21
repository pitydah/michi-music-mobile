package org.michimusic.link.transfer

import android.content.Context
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
}

