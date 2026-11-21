package org.michimusic.data.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit sources for OpenCode; no database/provider or Android instrumentation here. */
class MetadataPeoplePresentationPolicyTest {
    private val sha = "a".repeat(64)
    private fun row(
        owner: String = "", path: String = "content://songs/one", size: Long = 100L,
        artist: String = "A", albumArtist: String = "Together",
        people: List<String> = listOf("A", "B"),
        albumPeople: List<String> = listOf("Together", "Together"),
    ) = MetadataPeopleIndexedRow(
        evidence = MetadataPersonEvidence(
            sourceId = owner, trackId = "1", sizeBytes = size,
            modifiedAt = 123L, sourceSha256 = sha,
            artistCount = people.size, artistValues = people.joinToString("\u0000"),
            albumArtistCount = albumPeople.size, albumArtistValues = albumPeople.joinToString("\u0000"),
        ), indexedPath = path, indexedSize = size,
        indexedArtist = artist, indexedAlbumArtist = albumArtist,
    )
    private fun target(owner: String = "", path: String = "content://songs/one",
        size: Long = 100L, local: Boolean = true) =
        MetadataPeopleDisplayTarget(owner, "1", path, size, local)

    @Test fun exactOwnerAndUriPreserveOrderAndDuplicates() {
        val result = MetadataPeoplePresentationPolicy.forTarget(row(), target())!!
        assertEquals(listOf("A", "B"), result.artists)
        assertEquals(listOf("Together", "Together"), result.albumArtists)
        assertFalse(result.sourceRevalidatedNow)
    }
    @Test fun differentOwnerNeverLeaks() {
        assertNull(MetadataPeoplePresentationPolicy.forTarget(row(), target(owner = "another")))
    }
    @Test fun differentDocumentOrSizeRejected() {
        assertNull(MetadataPeoplePresentationPolicy.forTarget(row(), target(path = "content://songs/two")))
        assertNull(MetadataPeoplePresentationPolicy.forTarget(row(), target(size = 101)))
    }
    @Test fun staleIndexedArtistRejected() {
        assertNull(MetadataPeoplePresentationPolicy.forTarget(row(artist = "Old"), target()))
    }
    @Test fun remoteAndLegacyPathNotProjected() {
        assertNull(MetadataPeoplePresentationPolicy.forTarget(row(), target(local = false)))
        assertNull(MetadataPeoplePresentationPolicy.forTarget(row(), target(path = "/music/local.flac")))
    }
    @Test fun exactMultipleValuesAreNeverSplitOnPunctuation() {
        val result = MetadataPeoplePresentationPolicy.forTarget(
            row(artist = "Jazz; Fusion", people = listOf("Jazz; Fusion", "R&B/Soul")), target())!!
        assertEquals("Jazz; Fusion · R&B/Soul", result.artistsText)
    }
    @Test fun partialAlbumEvidenceCannotRewriteHeader() {
        val value = MetadataPeoplePresentationPolicy.forTarget(row(), target())!!
        assertNull(MetadataPeoplePresentationPolicy.commonAlbumArtists(listOf("1", "2"), mapOf("1" to value)))
        assertNull(MetadataPeoplePresentationPolicy.commonAlbumArtists(listOf("1", "1"), mapOf("1" to value)))
    }
    @Test fun commonAlbumCreditsRequireAgreement() {
        val first = MetadataPeoplePresentationPolicy.forTarget(row(), target())!!
        val disagree = first.copy(albumArtists = listOf("Another", "Artist"))
        assertNull(MetadataPeoplePresentationPolicy.commonAlbumArtists(
            listOf("1", "2"), mapOf("1" to first, "2" to disagree)))
        assertEquals("Together · Together", MetadataPeoplePresentationPolicy.commonAlbumArtists(
            listOf("1", "2"), mapOf("1" to first, "2" to first)))
    }
    @Test fun malformedSidecarNotPromotedToDisplay() {
        val invalid = row().copy(evidence = row().evidence.copy(sourceSha256 = "NOT_A_HASH"))
        assertNull(MetadataPeoplePresentationPolicy.forTarget(invalid, target()))
    }
}
