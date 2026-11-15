package org.michimusic.mobile.artist.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.data.repository.LocalCatalogRow

class ArtistArtworkCatalogDigestTest {
    private fun row(id: String, credit: String = "Artist", album: String = "album-1") =
        LocalCatalogRow(id, album, credit)

    @Test fun stableDigestAcrossReorderingButNotCreditCorrection() {
        val a = ArtistArtworkCatalogDigest.plan("device", listOf(row("t1"), row("t2")), emptySet())
        val b = ArtistArtworkCatalogDigest.plan("device", listOf(row("t2"), row("t1")), emptySet())
        assertEquals(a.digest, b.digest)
        assertEquals(a.allTrackHashes, b.allTrackHashes)
        val corrected = ArtistArtworkCatalogDigest.plan("device", listOf(row("t2", "Different"), row("t1")), a.allTrackHashes)
        assertNotEquals(a.digest, corrected.digest)
        assertEquals(listOf(row("t2", "Different")), corrected.added)
        assertEquals(1, (a.allTrackHashes - corrected.allTrackHashes).size)
    }

    @Test fun oneNewTrackEmitsOnlyItsActualAlbumCredit() {
        val first = ArtistArtworkCatalogDigest.plan("device", listOf(row("t1")), emptySet())
        val second = ArtistArtworkCatalogDigest.plan("device", listOf(row("t1"), row("t2", "Band", "album-2")), first.allTrackHashes)
        assertEquals(listOf(row("t2", "Band", "album-2")), second.added)
        assertNotEquals(first.digest, second.digest)
    }

    @Test fun correctedCreditOrAlbumWithSameTrackIdIsObserved() {
        val first = ArtistArtworkCatalogDigest.plan("device", listOf(row("t1")), emptySet())
        val creditChanged = ArtistArtworkCatalogDigest.plan("device", listOf(row("t1", "Other Credit")), first.allTrackHashes)
        assertEquals(listOf(row("t1", "Other Credit")), creditChanged.added)
        assertNotEquals(first.digest, creditChanged.digest)
        val albumChanged = ArtistArtworkCatalogDigest.plan("device", listOf(row("t1", "Artist", "album-2")), first.allTrackHashes)
        assertEquals(listOf(row("t1", "Artist", "album-2")), albumChanged.added)
    }

    @Test fun differentSourcesDoNotReuseTrackBaseline() {
        val a = ArtistArtworkCatalogDigest.plan("device", listOf(row("t1")), emptySet())
        val b = ArtistArtworkCatalogDigest.plan("saf", listOf(row("t1")), a.allTrackHashes)
        assertEquals(listOf(row("t1")), b.added)
    }

    @Test fun hashDoesNotContainPersonalTrackOrPath() {
        val hash = ArtistArtworkCatalogDigest.trackHash("device", "content://private/track.mp3")
        assertTrue(hash.matches(Regex("^[0-9a-f]{24}$")))
        assertTrue(!hash.contains("private"))
    }
    @Test fun manyCreditsOnSameTrackAreDistinctButIdenticalRowsDeduplicate() {
        val many = (0 until 40).map { row("same-track", "Artist-$it") }
        val first = ArtistArtworkCatalogDigest.plan("device", many + many, emptySet())
        assertEquals(40, first.allTrackHashes.size)
        assertEquals(40, first.added.size)
        val again = ArtistArtworkCatalogDigest.plan("device", many, first.allTrackHashes)
        assertTrue(again.added.isEmpty())
        assertEquals(first.digest, again.digest)
    }

    @Test fun sequentialDigestReusesHasherButMatchesIndependentHashes() {
        val rows = (0 until 1024).map { row("song-$it", "Artist-${it % 17}", "album-${it % 3}") }
        val plan = ArtistArtworkCatalogDigest.plan("saf-source", rows, emptySet())
        val independent = rows.mapTo(HashSet()) { ArtistArtworkCatalogDigest.rowHash("saf-source", it) }
        assertEquals(independent, plan.allTrackHashes)
        val expectedBytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(independent.sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
        val expectedDigest = expectedBytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(expectedDigest, plan.digest)
        assertTrue(ArtistArtworkCatalogDigest.plan("saf-source", rows, plan.allTrackHashes).added.isEmpty())
    }

    @Test fun aLargeCatalogHasStableHashAcrossReordering() {
        val rows = (0 until 8000).map { row("song-$it", "Artist-${it % 12}") }
        val a = ArtistArtworkCatalogDigest.plan("device", rows, emptySet())
        val b = ArtistArtworkCatalogDigest.plan("device", rows.asReversed(), a.allTrackHashes)
        assertEquals(8000, a.allTrackHashes.size)
        assertEquals(a.digest, b.digest)
        assertTrue(b.added.isEmpty())
    }
}
