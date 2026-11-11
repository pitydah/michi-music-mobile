package org.michimusic.data.cache

import org.junit.Assert.assertEquals
import org.junit.Test
import org.michimusic.core.models.TrackSource

class CachedTrackMapperTest {

    @Test
    fun localRowKeepsLocalProvenance() {
        val track = CachedTrack(
            id = "local-1",
            title = "Local",
            artist = "Artist",
            format = "flac",
            sampleRate = 96000,
            sourceId = LOCAL_SOURCE_ID,
            downloaded = false,
        ).toTrack()

        assertEquals(TrackSource.LOCAL, track.source)
        assertEquals("", track.sourceId)
        assertEquals("flac", track.format)
        assertEquals(96000, track.sampleRate)
    }

    @Test
    fun remoteRowIsStreamingUntilItIsPhysicallyDownloaded() {
        val streaming = CachedTrack(
            id = "remote-1",
            title = "Remote",
            sourceId = "peer-a",
            downloaded = false,
        ).toTrack()

        assertEquals(TrackSource.STREAMING, streaming.source)
        assertEquals("peer-a", streaming.sourceId)

        val synced = CachedTrack(
            id = "remote-1",
            title = "Remote",
            sourceId = "peer-a",
            downloaded = true,
            filepath = "/data/user/0/app/files/synced_music/remote-1.flac",
        ).toTrack()

        assertEquals(TrackSource.SYNCED, synced.source)
        assertEquals("peer-a", synced.sourceId)
        assertEquals("/data/user/0/app/files/synced_music/remote-1.flac", synced.filepath)
    }

    @Test
    fun sourceIdAndTrackSourceRemainIndependentDimensions() {
        // A SAF root has a non-empty owner but its bytes remain local.
        val saf = CachedTrack(
            id = "saf-1",
            title = "SAF",
            sourceId = "safroot_42",
            downloaded = false,
        ).toTrack()

        assertEquals("safroot_42", saf.sourceId)
        assertEquals(TrackSource.LOCAL, saf.source)
    }
}
