package org.michimusic.mobile.perf

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.intelligence.MichiGenrePreviewPolicy
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.mobile.artist.ArtistEnrichmentState
import org.michimusic.mobile.artist.ArtistEnrichmentUiState
import org.michimusic.mobile.artist.ArtistExternalProfile
import org.michimusic.mobile.artist.artwork.ArtistArtworkCoverageUiState
import org.michimusic.mobile.artist.artwork.ArtistArtworkSelection
import org.michimusic.mobile.artist.artwork.ArtistArtworkSlotState
import org.michimusic.mobile.artist.artwork.ArtistArtworkUiState
import org.michimusic.mobile.artist.artwork.ArtworkFocus
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.ui.components.GenreEditorUiState
import org.michimusic.mobile.ui.components.MichiGenreEditorContract
import org.michimusic.mobile.ui.components.MichiGenreEditorOrigin
import org.michimusic.mobile.ui.components.MichiGenreLookupPhase

/**
 * K48-R10 opt-in Android instrumented development probe, NOT a release benchmark.
 *
 * Measures only the pure in-process work the K48 refactors moved: the artist summary
 * projection, the artwork coverage projection that feeds it, and the genre editor state
 * derivation. No media scan, no audio decode, no network, no file IO, no service and no
 * cache is touched, and nothing here ships in the app.
 *
 * The numbers bound *projection cost*, not screen-open latency: a real open also pays
 * for Room reads and image decode, which this probe deliberately does not claim. Run it
 * repeatedly on the same device with fixed thermal state before quoting p95.
 */
@RunWith(AndroidJUnit4::class)
class K48UiProjectionProbeTest {
    private val iterations = 200

    @Test
    fun artistSummaryProjectionIsBoundedWorkPerRecompositionPass() {
        val state = artistState()
        val artworkUi = artworkUiState()
        val coverage = ArtistArtworkCoverageUiState.from(
            uiState = artworkUi,
            report = null,
            identityConfirmed = true,
            verifiedQid = MBID,
        )
        val coverageMicros = measure {
            ArtistArtworkCoverageUiState.from(
                uiState = artworkUi,
                report = null,
                identityConfirmed = true,
                verifiedQid = MBID,
            )
        }
        val projectionMicros = measure {
            ArtistEnrichmentUiState.from(
                artistCredit = CREDIT,
                state = state,
                coverage = coverage,
                canPersist = true,
                albumCount = 3,
            )
        }

        // Sanity, not performance: the projection still answers the contract question.
        val projected = ArtistEnrichmentUiState.from(
            artistCredit = CREDIT,
            state = state,
            coverage = coverage,
            canPersist = true,
            albumCount = 3,
        )
        assertNotNull(projected.mosaic.imagePath)
        assertTrue(projected.card.hasThumbnail)
        assertEquals(null, projected.missingSummary)

        record(
            metric = "artist_enrichment_projection",
            samples = projectionMicros,
            extra = "slots=2 albums=3 coverage_micros_p50=${percentile(coverageMicros, 50) / 1_000L}",
        )
        record(metric = "artist_artwork_coverage_projection", samples = coverageMicros)
    }

    @Test
    fun genreEditorDerivationIsBoundedWorkPerRecompositionPass() {
        val embedded = listOf("House", "Disco")
        val target = MichiGenreEditorContract.target(
            track = TRACK,
            embeddedGenres = embedded,
            tagsVerified = true,
            origin = MichiGenreEditorOrigin.NOW_PLAYING,
        )
        val preview = MichiGenrePreviewPolicy.of(embedded)
        val construction = measure {
            GenreEditorUiState(
                target = target,
                evidenceReason = null,
                libraryClassification = null,
                classificationHint = "",
                lookup = MichiGenreLookupPhase.IDLE,
                preview = preview,
            )
        }
        val state = GenreEditorUiState(
            target = target,
            evidenceReason = null,
            libraryClassification = null,
            classificationHint = "",
            lookup = MichiGenreLookupPhase.IDLE,
            preview = preview,
        )
        // Warm the lazy search intent once so the derivation sample is the
        // read path a recomposition actually pays.
        state.phase
        val derivation = measure { state.let { it.phase to it.primaryActionLabel } }

        assertEquals("No disponible: escritura sin certificar", state.primaryActionLabel)

        record(metric = "genre_editor_state_construction", samples = construction, extra = "phases=12")
        record(metric = "genre_editor_derivation", samples = derivation, extra = "phases=12")
    }

    private fun artistState() = ArtistEnrichmentState(
        profile = ArtistExternalProfile(
            mbid = MBID,
            name = CREDIT,
            type = "Group",
            area = "Oslo",
            disambiguation = "",
            wikidataQid = "Q1234",
        ),
        profilePersisted = true,
        persistedMbid = MBID,
        selections = mapOf(
            ArtworkSlot.ARTIST_THUMB to selection(ArtworkSlot.ARTIST_THUMB, "/cache/mosaic.webp"),
            ArtworkSlot.ARTIST_HERO to selection(ArtworkSlot.ARTIST_HERO, "/cache/card.webp"),
        ),
        unboundAlbumIds = emptyList(),
    )

    private fun selection(slot: ArtworkSlot, path: String) = ArtistArtworkSelection(
        slot = slot,
        assetId = "asset-${slot.name}",
        confirmedMbid = MBID,
        localImagePath = path,
        focus = ArtworkFocus(),
        manuallyChosen = false,
        revision = 1L,
        licenseId = "cc-by-2.0",
        attribution = "Charles Millot",
        status = org.michimusic.mobile.artist.artwork.ArtworkLoadStatus.READY,
    )

    private fun artworkUiState(): ArtistArtworkUiState {
        val selections = mapOf(
            ArtworkSlot.ARTIST_THUMB to selection(ArtworkSlot.ARTIST_THUMB, "/cache/mosaic.webp"),
            ArtworkSlot.ARTIST_HERO to selection(ArtworkSlot.ARTIST_HERO, "/cache/card.webp"),
        )
        return ArtistArtworkUiState(
            thumb = ArtistArtworkSlotState(
                slot = ArtworkSlot.ARTIST_THUMB,
                selection = selections[ArtworkSlot.ARTIST_THUMB],
                status = org.michimusic.mobile.artist.artwork.ArtworkLoadStatus.READY,
            ),
            hero = ArtistArtworkSlotState(
                slot = ArtworkSlot.ARTIST_HERO,
                selection = selections[ArtworkSlot.ARTIST_HERO],
                status = org.michimusic.mobile.artist.artwork.ArtworkLoadStatus.READY,
            ),
        )
    }

    private fun measure(block: () -> Unit): LongArray {
        repeat(20) { block() }
        val samples = LongArray(iterations)
        repeat(iterations) { index ->
            val started = SystemClock.elapsedRealtimeNanos()
            block()
            samples[index] = SystemClock.elapsedRealtimeNanos() - started
        }
        return samples
    }

    private fun percentile(samples: LongArray, which: Int): Long {
        val ordered = samples.sorted()
        val index = ((ordered.size - 1) * which / 100.0).toInt()
        return ordered[index]
    }

    private fun record(metric: String, samples: LongArray, extra: String = "") {
        val p50 = percentile(samples, 50) / 1_000L
        val p95 = percentile(samples, 95) / 1_000L
        val max = samples.max() / 1_000L
        // Logged numbers only: no user data, no paths, no identifiers.
        Log.i("K48ProjectionProbe", "$metric samples=${samples.size} p50_us=$p50 p95_us=$p95 max_us=$max $extra")
        val output = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "certification",
        )
        output.mkdirs()
        File(output, "k48_projection_${metric}.tsv").writeText(
            "metric\tsamples\tp50_us\tp95_us\tmax_us\textra\n" +
                "$metric\t${samples.size}\t$p50\t$p95\t$max\t$extra\n",
        )
    }

    private companion object {
        const val CREDIT = "A-ha"
        const val MBID = "a-ha-mbid-verified"

        val TRACK = Track(
            id = "probe-1",
            title = "Take On Me",
            artist = CREDIT,
            album = "Hunting High and Low",
            genre = "Pop",
            sourceId = "",
            source = TrackSource.LOCAL,
            filepath = "/nonexistent/k48_projection_probe.flac",
            duration = 225_000L,
        )
    }
}
