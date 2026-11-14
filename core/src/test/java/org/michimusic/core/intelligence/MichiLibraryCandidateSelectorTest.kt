package org.michimusic.core.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource

class MichiLibraryCandidateSelectorTest {
    private fun t(id: Int, artist: String = "Artist ${id % 100}", genre: String = "Rock",
                  sourceId: String = "", source: TrackSource = TrackSource.LOCAL) =
        Track(id = "id-$id", title = "Song $id", artist = artist, genre = genre,
            album = "Album ${id % 200}", filepath = "/internal/$id.flac", sourceId=sourceId,
            source=source)

    @Test fun findsLateAffinityInFiftyThousandTrackLibraryRatherThanFixedStride() {
        val library = (0 until 50000).map { i ->
            t(i, if (i >= 49800) "Late Favorite" else "Artist ${i % 1200}")
        }
        val taste = MichiLibraryCandidateSelector.Signals(
            artists = mapOf(MichiTasteFingerprint.sha256("late favorite") to 35),
        )
        val result = MichiLibraryCandidateSelector.select(library, taste, 20000)
        assertTrue(result.hits.size <= 128)
        assertTrue(result.hits.any { it.index >= 49800 })
        assertTrue(result.matchingAffinity >= 200)
        assertEquals(50000, result.scanned)
    }

    @Test fun changingLibraryOrderDoesNotChangeSelectionIdentityForIndependentTracks() {
        val library = (0 until 800).map { t(it) }
        val s = MichiLibraryCandidateSelector.Signals()
        val a = MichiLibraryCandidateSelector.select(library, s, 21000).hits.map { it.key }.toSet()
        val b = MichiLibraryCandidateSelector.select(library.reversed(), s, 21000)
            .hits.map { it.key }.toSet()
        assertEquals(a, b)
        assertTrue(a.size <= 128)
    }

    @Test fun idOnlyPlaybackRejectsAllCollidingOwnersRatherThanPickingAConvenientSource() {
        val duplicate = t(42)
        val otherOwner = duplicate.copy(sourceId = "saf-root")
        val source = listOf(duplicate, duplicate.copy(filepath="/other.flac"), otherOwner)
        val result = MichiLibraryCandidateSelector.select(source,
            MichiLibraryCandidateSelector.Signals(), 21000)
        assertEquals(2, result.rejectedAmbiguous)
        assertTrue(result.hits.isEmpty())
    }

    @Test fun remoteIdCollisionDisqualifiesLocalRecommendationEvenIfRemoteCannotRank() {
        val local = t(88, artist = "Liked")
        val remote = local.copy(source = TrackSource.STREAMING, sourceId = "peer")
        val result = MichiLibraryCandidateSelector.select(listOf(local, remote, t(5)),
            MichiLibraryCandidateSelector.Signals(artists = mapOf(
                MichiTasteFingerprint.sha256("liked") to 100)), 21000)
        assertTrue(result.hits.none { it.key.trackId == "id-88" })
        assertEquals(1, result.rejectedAmbiguous)
        assertTrue(result.hits.any { it.key.trackId == "id-5" })
    }

    @Test fun collisionAtVeryEndOfFiftyThousandSnapshotInvalidatesEarlyCandidate() {
        val tracks = (0 until 50000).map { t(it) }.toMutableList()
        tracks[11] = tracks[11].copy(artist = "Unique Cat Listener")
        tracks[49999] = tracks[11].copy(sourceId = "late-owner", filepath = "/other/11.flac")
        val evidence = MichiLibraryCandidateSelector.Signals(artists = mapOf(
            MichiTasteFingerprint.sha256("unique cat listener") to 100))
        val result = MichiLibraryCandidateSelector.select(tracks, evidence, 21000)
        assertTrue(result.hits.none { it.key.trackId == "id-11" })
        assertTrue(result.rejectedAmbiguous >= 1)
        assertEquals(50000, result.scanned)
        assertTrue(result.hits.any { it.key.trackId != "id-11" })
    }

    @Test fun disallowsRemoteMissingIdsAndFiles() {
        val local = t(0)
        val sources = listOf(local, t(1,source=TrackSource.STREAMING),
            t(2).copy(filepath=""), t(3).copy(id=""))
        val result = MichiLibraryCandidateSelector.select(sources,
            MichiLibraryCandidateSelector.Signals(), 21000)
        assertEquals(listOf(MichiIntelligencePolicy.TrackKey("", "id-0")),
            result.hits.map { it.key })
    }

    @Test fun verifiedOwnedEvidenceAtEndGetsDedicatedLaneAndUnknownIsNotZero() {
        val library = (0 until 3000).map { t(it) }
        val evidence = MichiOwnedPlayCountPolicy.Count(2, 20960)
        val target = library.last()
        val result = MichiLibraryCandidateSelector.select(library,
            MichiLibraryCandidateSelector.Signals(owned = mapOf(
                MichiTasteFingerprint.ownedTrack(target.sourceId, target.id) to evidence)),21000)
        assertTrue(result.hits.any { it.key.trackId == target.id })
        assertTrue(result.hits.size <= 128)
    }

    @Test fun scanMayBeCancelledWithoutPublishingPartialCandidates() {
        var checks = 0
        val library = (0 until 5000).map { t(it) }
        var aborted = false
        try {
            MichiLibraryCandidateSelector.select(library,
                MichiLibraryCandidateSelector.Signals(), 21000) {
                checks++
                if (checks == 5) throw IllegalStateException("canceled")
            }
        } catch (_: IllegalStateException) { aborted = true }
        assertTrue(aborted)
    }

    @Test fun privateDigestContractNormalizesButDoesNotExposeArtistInStoredKey() {
        val artist = MichiTasteFingerprint.normalizeArtist("  The Kittens  ")
        assertEquals("the kittens", artist)
        val digest = MichiTasteFingerprint.sha256(artist)
        assertEquals(64, digest.length)
        assertFalse(digest.contains("kittens"))
        assertFalse(MichiTasteFingerprint.ownedTrack("a", "bc") ==
            MichiTasteFingerprint.ownedTrack("ab", "c"))
        assertEquals("", MichiTasteFingerprint.normalizeArtist("x".repeat(129)))
    }

    @Test fun noTasteDoesNotGenerateInventedPositiveAffinity() {
        val result = MichiLibraryCandidateSelector.select(
            (0 until 1000).map { t(it) }, MichiLibraryCandidateSelector.Signals(), 21000)
        assertEquals(0, result.matchingAffinity)
        assertTrue(result.hits.isNotEmpty())
    }

    @Test fun c12LiteralTagDoesNotCreateFalseGenreAffinityInSelection() {
        val literal = t(1, genre = "Rock; Jazz").copy(genreValues = listOf("Rock; Jazz"))
        val legacy = t(2, genre = "Rock; Jazz")
        val signals = MichiLibraryCandidateSelector.Signals(
            genres = mapOf(MichiTasteFingerprint.sha256("jazz") to 25),
        )
        assertEquals(0, MichiLibraryCandidateSelector.select(listOf(literal), signals, 21000).matchingAffinity)
        // MI-26: without verified values a legacy "Rock; Jazz" is one literal key, not two genres.
        assertEquals(0, MichiLibraryCandidateSelector.select(listOf(legacy), signals, 21000).matchingAffinity)
        assertEquals(1, MichiLibraryCandidateSelector.select(
            listOf(literal.copy(genreValues = listOf("Jazz"))), signals, 21000).matchingAffinity)
    }
}
