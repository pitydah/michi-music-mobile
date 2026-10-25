package org.michimusic.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TransferFileIdentityTest {

    @Test
    fun safeSegment_standardAlphanumeric_remainsUnchanged() {
        val segment = TransferFileIdentity.safeSegment("normal-album_01.flac")
        assertEquals("normal-album_01.flac", segment)
    }

    @Test
    fun safeSegment_differentStringsCollidingInUnderscore_produceDistinctSegments() {
        val segSlash = TransferFileIdentity.safeSegment("album/2026")
        val segQuestion = TransferFileIdentity.safeSegment("album?2026")
        val segColon = TransferFileIdentity.safeSegment("album:2026")

        // None should be identical even though regex replace alone produces "album_2026"
        assertNotEquals("album/2026 and album?2026 must not collide", segSlash, segQuestion)
        assertNotEquals("album?2026 and album:2026 must not collide", segQuestion, segColon)
        assertNotEquals("album/2026 and album:2026 must not collide", segSlash, segColon)

        assertTrue(segSlash.startsWith("album_2026_"))
        assertTrue(segQuestion.startsWith("album_2026_"))
        assertTrue(segColon.startsWith("album_2026_"))

        // Verify that the hash suffix is 16 hex characters (64-bit deterministic hash slice)
        val hashSlice = segSlash.removePrefix("album_2026_")
        assertEquals(16, hashSlice.length)
        assertTrue(hashSlice.all { it in "0123456789abcdef" })
    }

    @Test
    fun safeSegment_blankOrEmpty_returnsDefault() {
        assertEquals("default_seg", TransferFileIdentity.safeSegment("", default = "default_seg"))
        assertEquals("default_seg", TransferFileIdentity.safeSegment("   ", default = "default_seg"))
    }

    @Test
    fun buildFinalFileName_withDeviceAndLibrary_constructsScopedName() {
        val fileName = TransferFileIdentity.buildFinalFileName(
            remoteDeviceId = "node-1",
            destinationLibraryId = "lib-main",
            trackId = "track-42",
            format = "flac",
        )
        assertEquals("node-1_lib-main_track-42.flac", fileName)
    }

    @Test
    fun buildTempFileName_withPlanId_appendsPlanId() {
        val tempName = TransferFileIdentity.buildTempFileName(
            finalFileName = "track_1.mp3",
            planId = "plan_xyz",
        )
        assertEquals("track_1.mp3_plan_xyz.part", tempName)
    }

    @Test
    fun resolveFiles_preventsDirectoryTraversal() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "test_music_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val (finalFile, tempFile) = TransferFileIdentity.resolveFiles(
                musicDir = tempDir,
                remoteDeviceId = "../../etc",
                destinationLibraryId = "passwd",
                trackId = "shadow",
                format = "bin",
            )
            // Files must remain strictly within tempDir and cannot escape via traversal
            assertTrue(finalFile.canonicalPath.startsWith(tempDir.canonicalPath))
            assertTrue(tempFile.canonicalPath.startsWith(tempDir.canonicalPath))
            assertFalse(finalFile.name.contains("/"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test(expected = SecurityException::class)
    fun resolveFiles_rejectsSymbolicLinks() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "test_symlink_${System.currentTimeMillis()}").also { it.mkdirs() }
        val outsideTarget = File(System.getProperty("java.io.tmpdir"), "outside_secret_${System.currentTimeMillis()}").also { it.writeText("secret") }
        try {
            val (finalFile, _) = TransferFileIdentity.resolveFiles(
                musicDir = tempDir,
                remoteDeviceId = "node",
                destinationLibraryId = "lib",
                trackId = "symtrack",
                format = "mp3",
            )
            // Create a symlink in tempDir with the finalFile name pointing to outsideTarget
            java.nio.file.Files.createSymbolicLink(finalFile.toPath(), outsideTarget.toPath())
            assertTrue(java.nio.file.Files.isSymbolicLink(finalFile.toPath()))

            // Calling resolveFiles again should detect the symlink and reject with SecurityException
            TransferFileIdentity.resolveFiles(
                musicDir = tempDir,
                remoteDeviceId = "node",
                destinationLibraryId = "lib",
                trackId = "symtrack",
                format = "mp3",
            )
        } finally {
            tempDir.deleteRecursively()
            outsideTarget.delete()
        }
    }
}
