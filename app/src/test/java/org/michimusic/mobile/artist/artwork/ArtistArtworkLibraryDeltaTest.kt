package org.michimusic.mobile.artist.artwork

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistArtworkLibraryDeltaTest {
    private fun item(library: String, id: String, fingerprint: String) =
        ArtistArtworkLibraryEntry(library, id, fingerprint)

    @Test fun initialLoadDoesNotProduceImplicitFullLibraryBackfill() {
        assertEquals(
            emptySet<Pair<String, String>>(),
            ArtistArtworkLibraryDelta.changedAssociations(null, listOf(item("a", "1", "v1"))),
        )
    }

    @Test fun aRepeatedSnapshotDoesNotQueueAnyWork() {
        val current = listOf(item("a", "1", "v1"), item("a", "2", "v2"))
        assertEquals(emptySet<Pair<String, String>>(), ArtistArtworkLibraryDelta.changedAssociations(current, current))
    }

    @Test fun newOrChangedMembershipIsScopedByLibraryAndAssociation() {
        val before = listOf(item("a", "1", "v1"), item("b", "1", "v3"))
        val after = listOf(item("a", "1", "v2"), item("b", "1", "v3"), item("a", "2", "new"))
        assertEquals(
            setOf("a" to "1", "a" to "2"),
            ArtistArtworkLibraryDelta.changedAssociations(before, after),
        )
    }

    @Test fun conflictingFingerprintsNeverQueueWork() {
        val base = listOf(item("lib", "assoc", "base"))
        val ambiguousAfter = listOf(item("lib", "assoc", "new"), item("lib", "assoc", "other"))
        assertEquals(emptySet<Pair<String, String>>(),
            ArtistArtworkLibraryDelta.changedAssociations(base, ambiguousAfter))
        val ambiguousBefore = listOf(item("lib", "assoc", "v1"), item("lib", "assoc", "v2"))
        assertEquals(emptySet<Pair<String, String>>(),
            ArtistArtworkLibraryDelta.changedAssociations(ambiguousBefore, listOf(item("lib", "assoc", "new"))))
    }
    @Test fun missingVersionNeverQueuesWorkButUnrelatedValidDeltaSurvives() {
        val before = listOf(item("lib", "a", "v1"), item("lib", "b", "v1"))
        val after = listOf(item("lib", "a", ""), item("lib", "b", "v2"))
        assertEquals(setOf("lib" to "b"), ArtistArtworkLibraryDelta.changedAssociations(before, after))
    }
    @Test fun duplicateIdenticalRowsRemainIdempotent() {
        val before = listOf(item("lib", "a", "v1"), item("lib", "a", "v1"))
        val after = listOf(item("lib", "a", "v2"), item("lib", "a", "v2"))
        assertEquals(setOf("lib" to "a"), ArtistArtworkLibraryDelta.changedAssociations(before, after))
    }

    @Test fun albumRemovalAloneDoesNotCauseBulkRediscovery() {
        assertEquals(
            emptySet<Pair<String, String>>(),
            ArtistArtworkLibraryDelta.changedAssociations(
                listOf(item("a", "1", "v1")), emptyList(),
            ),
        )
    }
}
