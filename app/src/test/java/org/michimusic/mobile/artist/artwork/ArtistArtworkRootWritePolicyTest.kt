package org.michimusic.mobile.artist.artwork

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.data.repository.MusicRootStamp

class ArtistArtworkRootWritePolicyTest {
    private val initial = MusicRootStamp("device_music_root", 0L)

    @Test fun exactSourceAndEpochAuthorizeTheCommit() {
        assertTrue(isArtistArtworkRootCurrent(initial, initial.copy()))
        assertTrue(isArtistArtworkRootCurrent(MusicRootStamp("saf:alpha", 9L), MusicRootStamp("saf:alpha", 9L)))
    }

    @Test fun switchingRootRevokesOldDownloads() {
        assertFalse(isArtistArtworkRootCurrent(initial, MusicRootStamp("saf:alpha", 1L)))
        assertFalse(isArtistArtworkRootCurrent(MusicRootStamp("saf:alpha", 9L), MusicRootStamp("saf:beta", 9L)))
    }

    @Test fun returningToSameSourceNeverRevivesOlderReceipt() {
        val before = MusicRootStamp("saf:alpha", 9L)
        val returned = MusicRootStamp("saf:alpha", 11L)
        assertFalse(isArtistArtworkRootCurrent(before, returned))
    }

    @Test fun rollbackAlsoInvalidatesOldReceipt() {
        assertFalse(isArtistArtworkRootCurrent(MusicRootStamp("saf:alpha", 4L), MusicRootStamp("saf:alpha", 5L)))
    }

    @Test fun invalidReceiptNeverAuthorizesAMutation() {
        assertFalse(isArtistArtworkRootCurrent(MusicRootStamp("", 0L), initial))
        assertFalse(isArtistArtworkRootCurrent(MusicRootStamp("device_music_root", -1L), initial))
    }
}
