package org.michimusic.mobile.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.michimusic.mobile.ui.theme.MichiMotion
import org.junit.Assert.assertNull
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource

class AeroTrackMotionTest {
    @Test fun motionStopsForReducedMotionAndHighContrast() {
        assertEquals(0, aeroTrackArtworkDurationMs(reducedMotion = true, highContrast = false))
        assertEquals(0, aeroTrackArtworkDurationMs(reducedMotion = false, highContrast = true))
        assertEquals(MichiMotion.durationStandard, aeroTrackArtworkDurationMs(false, false))
    }

    @Test fun coverSnapshotsKeepTheOutgoingTrackIdentity() {
        val prior = AeroTrackArtSnapshot("one", "cover-a", "file-a", "Title A")
        val next = AeroTrackArtSnapshot("two", "cover-b", "file-b", "Title B")
        assertNotEquals(prior, next)
        assertEquals("file-a", prior.filePath)
        assertEquals("file-b", next.filePath)
    }

    @Test fun remoteNumericCoverNeverBorrowsLocalMediaStore() {
        assertEquals("42", aeroTrackArtworkCoverModel(TrackSource.LOCAL, "42"))
        assertNull(aeroTrackArtworkCoverModel(TrackSource.STREAMING, "42"))
        assertNull(aeroTrackArtworkCoverModel(TrackSource.SYNCED, "42"))
        assertEquals("https://cdn.invalid/real-art.jpg",
            aeroTrackArtworkCoverModel(TrackSource.STREAMING, "https://cdn.invalid/real-art.jpg"))
    }

    @Test fun remoteEmbeddedFallbackIsNeverBorrowed() {
        assertNull(aeroTrackArtworkLocalFallback(TrackSource.STREAMING, "content://media/42"))
        assertNull(aeroTrackArtworkLocalFallback(TrackSource.SYNCED, "https://server/music.mp3"))
        assertEquals("content://media/42", aeroTrackArtworkLocalFallback(TrackSource.LOCAL, "content://media/42"))
        assertEquals("/storage/emulated/0/Music/song.mp3",
            aeroTrackArtworkLocalFallback(TrackSource.SYNCED, "/storage/emulated/0/Music/song.mp3"))
        assertNull(aeroTrackArtworkLocalFallback(TrackSource.LOCAL, "//remote/share.mp3"))
    }

    @Test fun immutableSnapshotRemembersProvenance() {
        val t = Track(id = "remote", title = "Live", coverId = "42", source = TrackSource.STREAMING)
        val snapshot = aeroTrackArtSnapshot(t)!!
        assertEquals(TrackSource.STREAMING, snapshot.source)
        assertNull(aeroTrackArtworkCoverModel(snapshot.source, snapshot.coverId))
    }

    @Test fun titleSnapshotsDistinguishSameTitleAcrossDifferentTracks() {
        assertNotEquals(AeroTrackTitleSnapshot("one", "Intro"), AeroTrackTitleSnapshot("two", "Intro"))
    }
}
