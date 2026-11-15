package org.michimusic.mobile.artist.artwork

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistArtworkCatalogObservationTest {
    private fun snap(
        ids: List<String>,
        source: String = "local-music",
        revision: Long = 1L,
        fresh: Boolean = true,
        complete: Boolean = true,
    ) = ArtistArtworkCatalogSnapshot(
        libraryId = "library-a", sourceId = source, sourceRevision = revision,
        fresh = fresh, completeForActiveSource = complete,
        rows = ids.map { ArtistArtworkCatalogRow(it, "album-1", "Midnight Oil") },
    )

    @Test fun firstSnapshotNeverDownloadsWholeLibrary() {
        assertEquals(ArtistArtworkCatalogDelta.BaselineOnly,
            ArtistArtworkCatalogObservation.delta(null, snap(listOf("t1", "t2"))))
    }

    @Test fun reorderingAndRemovalAloneAreNoOp() {
        val old = snap(listOf("t1", "t2"))
        assertEquals(ArtistArtworkCatalogDelta.Ignore,
            ArtistArtworkCatalogObservation.delta(old, snap(listOf("t2", "t1"))))
        assertEquals(ArtistArtworkCatalogDelta.Ignore,
            ArtistArtworkCatalogObservation.delta(old, snap(listOf("t1"))))
    }

    @Test fun twoNewTracksWithSameCreditYieldOneHint() {
        val result = ArtistArtworkCatalogObservation.delta(
            snap(listOf("t1")), snap(listOf("t1", "t2", "t3")),
        )
        assertEquals(ArtistArtworkCatalogDelta.NewCredits(setOf("midnight oil")), result)
    }

    @Test fun cacheHitAndPartialResultNeverAdvance() {
        val old = snap(listOf("t1"))
        assertEquals(ArtistArtworkCatalogDelta.Ignore,
            ArtistArtworkCatalogObservation.delta(old, snap(listOf("t1", "t2"), fresh = false)))
        assertEquals(ArtistArtworkCatalogDelta.Ignore,
            ArtistArtworkCatalogObservation.delta(old, snap(listOf("t1", "t2"), complete = false)))
    }

    @Test fun rootSwitchOrRollbackNeedsNewBaseline() {
        val old = snap(listOf("t1"))
        assertEquals(ArtistArtworkCatalogDelta.BaselineOnly,
            ArtistArtworkCatalogObservation.delta(old, snap(listOf("t1", "t2"), source = "saf-tree")))
        assertEquals(ArtistArtworkCatalogDelta.BaselineOnly,
            ArtistArtworkCatalogObservation.delta(old, snap(listOf("t1", "t2"), revision = 2L)))
    }

    @Test fun unscopedOrUncreditedTracksNeverTriggerWork() {
        val old = snap(listOf("t1"))
        val blank = snap(listOf("t1", "t2")).copy(
            rows = listOf(ArtistArtworkCatalogRow("t1", "album", "artist"),
                ArtistArtworkCatalogRow("t2", "", "artist")),
        )
        assertEquals(ArtistArtworkCatalogDelta.Ignore,
            ArtistArtworkCatalogObservation.delta(old, blank))
        assertEquals(ArtistArtworkCatalogDelta.Ignore,
            ArtistArtworkCatalogObservation.delta(old, blank.copy(libraryId = "SCOPE_ALL")))
    }
}
