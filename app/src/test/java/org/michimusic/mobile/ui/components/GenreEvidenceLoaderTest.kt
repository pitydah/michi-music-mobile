package org.michimusic.mobile.ui.components

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.data.repository.ExtendedTagEvidenceResult
import org.michimusic.data.repository.ExtendedTagUnavailableReason
import org.michimusic.data.repository.ExtendedTrackMetadata
import org.michimusic.data.repository.ExtendedTrackMetadataRepository

/**
 * K48-S4: both genre routes must reach the same verdict with the same reason.
 * These cases exercise the shared loader the Genres host and Now Playing use.
 */
class GenreEvidenceLoaderTest {
    private val repository = mockk<ExtendedTrackMetadataRepository>(relaxed = true)

    private val track = Track(
        id = "track-1",
        title = "Voices Carry",
        artist = "'Til Tuesday",
        album = "Voices Carry",
        filepath = "content://michi/track-1",
        source = TrackSource.LOCAL,
        sourceId = "saf-A",
        size = 4321L,
        genre = "New Wave",
    )

    private fun metadata(
        sourceId: String = track.sourceId,
        trackId: String = track.id,
        genres: List<String> = listOf("New Wave"),
    ) = ExtendedTrackMetadata(
        sourceId = sourceId,
        trackId = trackId,
        composer = "",
        trackNumber = null,
        trackTotal = null,
        discNumber = null,
        discTotal = null,
        date = "",
        genres = genres,
    )

    @Test
    fun aVerifiedReadKeepsEveryGenreTheFileDeclares() = runTest {
        coEvery { repository.loadVerifiedResult(any(), any(), any(), any()) } returns
            ExtendedTagEvidenceResult.Verified(metadata(genres = listOf("New Wave", "Synth-Pop")))

        val state = loadGenreEvidence(repository, track)

        assertTrue(state is GenreEvidenceUiState.Verified)
        assertEquals(listOf("New Wave", "Synth-Pop"), (state as GenreEvidenceUiState.Verified).genres)
    }

    @Test
    fun anEmptyVerifiedListMeansNoGenreInsteadOfAnUnverifiedFile() = runTest {
        coEvery { repository.loadVerifiedResult(any(), any(), any(), any()) } returns
            ExtendedTagEvidenceResult.Verified(metadata(genres = emptyList()))

        val state = loadGenreEvidence(repository, track)

        assertTrue(state is GenreEvidenceUiState.Verified)
        assertTrue((state as GenreEvidenceUiState.Verified).genres.isEmpty())
    }

    @Test
    fun everyUnavailableReasonSurvivesVerbatim() = runTest {
        ExtendedTagUnavailableReason.entries.forEach { reason ->
            coEvery { repository.loadVerifiedResult(any(), any(), any(), any()) } returns
                ExtendedTagEvidenceResult.Unavailable(reason)

            val state = loadGenreEvidence(repository, track)

            assertEquals(GenreEvidenceUiState.Unavailable(reason), state)
        }
    }

    @Test
    fun aReceiptFromAnotherOwnerIsNeverTrusted() = runTest {
        coEvery { repository.loadVerifiedResult(any(), any(), any(), any()) } returns
            ExtendedTagEvidenceResult.Verified(metadata(trackId = "track-other"))

        val state = loadGenreEvidence(repository, track)

        assertEquals(
            GenreEvidenceUiState.Unavailable(ExtendedTagUnavailableReason.TAG_MISMATCH),
            state,
        )
    }

    @Test
    fun aThrowingProviderBecomesAnUnavailableProviderInsteadOfACrash() = runTest {
        coEvery { repository.loadVerifiedResult(any(), any(), any(), any()) } throws
            IllegalStateException("provider died")

        val state = loadGenreEvidence(repository, track)

        assertEquals(
            GenreEvidenceUiState.Unavailable(ExtendedTagUnavailableReason.PROVIDER_UNAVAILABLE),
            state,
        )
    }

    @Test
    fun theReceiptCarriesTheTrackSizeSoAReplacedFileReadsAgain() = runTest {
        coEvery { repository.loadVerifiedResult(any(), any(), any(), any()) } returns
            ExtendedTagEvidenceResult.Unavailable(ExtendedTagUnavailableReason.SIZE_MISMATCH)

        loadGenreEvidence(repository, track)
        loadGenreEvidence(repository, track.copy(size = track.size + 1))

        coVerify(exactly = 1) {
            repository.loadVerifiedResult(track.sourceId, track.id, track.filepath, track.size)
        }
        coVerify(exactly = 1) {
            repository.loadVerifiedResult(
                track.sourceId, track.id, track.filepath, track.size + 1,
            )
        }
    }
}
