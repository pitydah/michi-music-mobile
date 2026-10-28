package org.michimusic.core.models

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TrackAvailabilityCheckerTest {

    @Test
    fun blankOrNullFilepathIsNotAvailable() {
        assertFalse(TrackAvailabilityChecker.isTrackAvailable(null))
        assertFalse(TrackAvailabilityChecker.isTrackAvailable(""))
        assertFalse(TrackAvailabilityChecker.isTrackAvailable("   "))
    }

    @Test
    fun contentUriWithoutContextValidatesSchemeAndPathStructure() {
        assertTrue(TrackAvailabilityChecker.isTrackAvailable("content://media/external/audio/media/1234"))
        assertFalse(TrackAvailabilityChecker.isTrackAvailable("content://"))
    }

    @Test
    fun contentUriWithContextVerifiesViaContentResolver() {
        val mockContext = mockk<Context>()
        val mockResolver = mockk<ContentResolver>()
        every { mockContext.contentResolver } returns mockResolver

        val validUri = Uri.parse("content://media/external/audio/media/1001")
        val invalidUri = Uri.parse("content://media/external/audio/media/9999")
        val emptyUri = Uri.parse("content://media/external/audio/media/1002")

        val validPfd = mockk<ParcelFileDescriptor>()
        every { validPfd.statSize } returns 1024L
        every { validPfd.close() } returns Unit

        val emptyPfd = mockk<ParcelFileDescriptor>()
        every { emptyPfd.statSize } returns 0L
        every { emptyPfd.close() } returns Unit

        every { mockResolver.openFileDescriptor(validUri, "r") } returns validPfd
        every { mockResolver.openFileDescriptor(emptyUri, "r") } returns emptyPfd
        every { mockResolver.openFileDescriptor(invalidUri, "r") } throws FileNotFoundException("No such record")

        // Valid content URI with positive file size returns true
        assertTrue(TrackAvailabilityChecker.isTrackAvailable(validUri.toString(), mockContext))

        // Empty file in content URI returns false
        assertFalse(TrackAvailabilityChecker.isTrackAvailable(emptyUri.toString(), mockContext))

        // Non-existent content record in MediaStore throws FileNotFoundException -> returns false
        assertFalse(TrackAvailabilityChecker.isTrackAvailable(invalidUri.toString(), mockContext))

        // SecurityException when accessing ungranted content URI -> returns false
        val securityUri = Uri.parse("content://media/external/audio/media/restricted")
        every { mockResolver.openFileDescriptor(securityUri, "r") } throws SecurityException("Permission Denial")
        assertFalse(TrackAvailabilityChecker.isTrackAvailable(securityUri.toString(), mockContext))
    }

    @Test
    fun localFilepathChecksActualFileExistenceAndNonZeroSize() {
        val temp = File.createTempFile("michi_test_track", ".flac")
        try {
            // Empty file
            assertFalse(TrackAvailabilityChecker.isTrackAvailable(temp.absolutePath))

            // Non-empty file
            temp.writeBytes(byteArrayOf(1, 2, 3, 4))
            assertTrue(TrackAvailabilityChecker.isTrackAvailable(temp.absolutePath))
            assertTrue(TrackAvailabilityChecker.isTrackAvailable("file://${temp.absolutePath}"))
        } finally {
            temp.delete()
        }

        // Non-existent path
        assertFalse(TrackAvailabilityChecker.isTrackAvailable("/tmp/definitely_not_existing_song.flac"))
        assertFalse(TrackAvailabilityChecker.isTrackAvailable("file:///tmp/definitely_not_existing_song.flac"))
    }
}
