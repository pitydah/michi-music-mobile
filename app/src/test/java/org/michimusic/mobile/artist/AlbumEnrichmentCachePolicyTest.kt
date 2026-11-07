package org.michimusic.mobile.artist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumEnrichmentCachePolicyTest {
    private val id = "a5ec1cd7-52e3-4a85-a04e-62fe0c5f216f"
    private val profile = AlbumHistoricalProfile(
        mbid = id, title = "Diesel and Dust", firstReleaseDate = "1987-08-01",
        disambiguation = "", description = null, wikipediaUrl = null,
    )

    @Test fun validStoredIdentityAndClock() {
        assertTrue(albumHistoryCacheRecordValid(id, profile, 1_000L, 1_001L))
        assertFalse(albumHistoryCacheRecordValid("00000000-0000-0000-0000-000000000000", profile, 1_000L, 1_001L))
        assertFalse(albumHistoryCacheRecordValid(id, profile, 1_002L, 1_001L))
        assertFalse(albumHistoryCacheRecordValid(id, profile, -1L, 1_001L))
    }

    @Test fun provenanceAndBoundsProtectPersistedHistoricalText() {
        assertFalse(albumHistoryPayloadSafe(profile.copy(title = " ")))
        assertFalse(albumHistoryPayloadSafe(profile.copy(title = "x".repeat(301))))
        assertFalse(albumHistoryPayloadSafe(profile.copy(description = "source-less description")))
        assertFalse(albumHistoryPayloadSafe(profile.copy(description = "x".repeat(2401),
            wikipediaUrl = "https://es.wikipedia.org/wiki/Diesel_and_Dust")))
        assertFalse(albumHistoryPayloadSafe(profile.copy(description = "test",
            wikipediaUrl = "https://es.wikipedia.org.evil.org/wiki/Diesel_and_Dust")))
        assertTrue(albumHistoryPayloadSafe(profile.copy(description = "Short sourced summary",
            wikipediaUrl = "https://es.wikipedia.org/wiki/Diesel_and_Dust")))
    }

    @Test fun staleCacheBoundaryIsExact() {
        assertFalse(albumHistoryCacheIsStale(5_000L, 5_000L))
        assertFalse(albumHistoryCacheIsStale(5_000L, 5_000L + ALBUM_HISTORY_TTL_MS - 1L))
        assertTrue(albumHistoryCacheIsStale(5_000L, 5_000L + ALBUM_HISTORY_TTL_MS))
        assertFalse(albumHistoryCacheIsStale(5_000L, 4_000L))
    }
}
