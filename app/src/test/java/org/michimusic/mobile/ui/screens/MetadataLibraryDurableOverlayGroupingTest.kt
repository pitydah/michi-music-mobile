package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import org.michimusic.core.models.Track
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.EmbeddedTagEvidence
import org.michimusic.data.cache.MetadataAppliedTransaction
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.repository.MetadataLibraryDurableOverlay

/**
 * MF02 grouping/counter proof: after the durable overlay, every committed
 * individual genre must create its own membership. A scalar compatibility value
 * is never split on ';' and a singleton is never invented.
 */
class MetadataLibraryDurableOverlayGroupingTest {
    private val source = MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID
    private val appliedAt = 1_700_000_000_000L
    private val providerModified = appliedAt / 1_000L

    @Test
    fun everyCommittedGenreGetsAMembershipAndTheRockCounterIsTwo() {
        val first = track("local_1", "Rock")
        val second = track("local_2", "Rock")
        val overlaid = MetadataLibraryDurableOverlay.apply(
            listOf(first, second),
            listOf(
                durable("local_1", "Rock; Jazz; Blues"),
                durable("local_2", "Metal; Rock"),
            ),
            listOf(marker("local_1"), marker("local_2")),
            listOf(
                evidence("local_1", "Rock; Jazz; Blues", listOf("Rock", "Jazz", "Blues")),
                evidence("local_2", "Metal; Rock", listOf("Metal", "Rock")),
            ),
            listOf(observation("local_1"), observation("local_2")),
        )

        val genres = groupLibraryGenres(overlaid)
        val counts = genres.associate { it.title to it.tracks.size }

        assertEquals(4, genres.size)
        assertEquals(2, counts["Rock"])
        assertEquals(1, counts["Jazz"])
        assertEquals(1, counts["Blues"])
        assertEquals(1, counts["Metal"])
        assertEquals(5, genres.sumOf { it.tracks.size })
    }

    private fun track(id: String, genre: String) = Track(
        id = id,
        title = "Provider title",
        artist = "Provider artist",
        album = "Provider album",
        albumId = "7",
        duration = 200_000L,
        size = 120L,
        format = "FLAC",
        filepath = uri(id),
        genre = genre,
    )

    private fun durable(id: String, genre: String) = CachedTrack(
        id = id,
        title = "Durable title",
        artist = "Durable artist",
        album = "Durable album",
        albumArtist = "Durable album artist",
        duration = 200_000L,
        size = 120L,
        format = "FLAC",
        trackNumber = 3,
        year = 2021,
        filepath = uri(id),
        sourceId = source,
        genre = genre,
        metadataRevision = 2L,
    )

    private fun marker(id: String) = MetadataAppliedTransaction(
        transactionId = if (id == "local_1") {
            "0f0e0d0c-0b0a-4908-8706-050403020100"
        } else {
            "0f0e0d0c-0b0a-4908-8706-050403020101"
        },
        sourceId = source,
        trackId = id,
        uri = uri(id),
        originalRevision = 1L,
        committedRevision = 2L,
        candidateSha256 = "b".repeat(64),
        candidateBytes = 120L,
        appliedAt = appliedAt,
    )

    private fun evidence(id: String, genre: String, values: List<String>) = EmbeddedTagEvidence(
        sourceId = source,
        trackId = id,
        sizeBytes = 120L,
        modifiedAt = providerModified,
        parserVersion = EmbeddedTagEvidence.CURRENT_PARSER_VERSION,
        genre = genre,
        genreValues = values.joinToString("\u0000"),
    )

    private fun observation(id: String) = MetadataLibraryDurableOverlay.ProviderObservation(
        sourceId = source,
        trackId = id,
        uri = uri(id),
        sizeBytes = 120L,
        modifiedAt = providerModified,
        capability = MetadataLibraryDurableOverlay.ProviderCapability.MEDIASTORE,
    )

    private fun uri(id: String): String {
        val number = id.removePrefix("local_")
        return "content://media/external_primary/audio/media/$number"
    }
}
