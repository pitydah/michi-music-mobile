package org.michimusic.core.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TrackAvailabilityCheckerTest {

    @Test
    fun blankOrNullFilepathIsNotAvailable() {
        assertFalse(TrackAvailabilityChecker.isTrackAvailable(null))
        assertFalse(TrackAvailabilityChecker.isTrackAvailable(""))
        assertFalse(TrackAvailabilityChecker.isTrackAvailable("   "))
    }

    @Test
    fun contentSchemeUriIsConsideredAvailable() {
        assertTrue(TrackAvailabilityChecker.isTrackAvailable("content://media/external/audio/media/1234"))
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
