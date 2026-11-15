package org.michimusic.core.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MI-23 P1 4.3: provenance. A recording classifies the track; a release group or
 * an artist is orientative context and is never auto-attributed. Metadata also
 * distinguishes a strict search from the optional relaxed second search.
 */
class MichiGenreProvenanceAndSearchTest {
    private val policy = MichiGenreNormalizationPolicy
    private val mbid = "ae57a2bc-4de1-4e94-9f07-b5301eacdd91"

    @Test
    fun artistAndReleaseGroupContextNeverClassifyTheTrack() {
        assertEquals(MichiGenreNormalizationPolicy.Provenance.RECORDING,
            policy.provenanceOf(MichiGenreNormalizationPolicy.Entity.RECORDING))
        assertEquals(MichiGenreNormalizationPolicy.Provenance.RELEASE_GROUP,
            policy.provenanceOf(MichiGenreNormalizationPolicy.Entity.RELEASE_GROUP))
        assertEquals(MichiGenreNormalizationPolicy.Provenance.ARTIST,
            policy.provenanceOf(MichiGenreNormalizationPolicy.Entity.ARTIST))

        assertTrue(policy.isTrackClassification(MichiGenreNormalizationPolicy.Provenance.FILE))
        assertTrue(policy.isTrackClassification(MichiGenreNormalizationPolicy.Provenance.RECORDING))
        assertTrue(policy.isTrackClassification(MichiGenreNormalizationPolicy.Provenance.MANUAL))
        assertFalse(policy.isContextOnly(MichiGenreNormalizationPolicy.Provenance.RECORDING))

        assertFalse(policy.isTrackClassification(MichiGenreNormalizationPolicy.Provenance.ARTIST))
        assertFalse(policy.isTrackClassification(MichiGenreNormalizationPolicy.Provenance.RELEASE_GROUP))
        assertTrue(policy.isContextOnly(MichiGenreNormalizationPolicy.Provenance.ARTIST))
        assertTrue(policy.provenanceLabel(MichiGenreNormalizationPolicy.Provenance.ARTIST)
            .contains("orientativo"))
    }

    @Test
    fun artistScopedProposalIsRejectedEvenWithTheExactRecordingId() {
        val record = MichiGenreNormalizationPolicy.RecordingCandidate(mbid, "Song", "Artist")
        val recordingClaim = MichiGenreNormalizationPolicy.GenreProposal(
            "Rock", MichiGenreNormalizationPolicy.Entity.RECORDING, mbid, 4, mbid)
        assertTrue(policy.approvedProposal(record, recordingClaim, mbid))
        // Same names and ids, but the entity is NOT the recording.
        listOf(
            MichiGenreNormalizationPolicy.Entity.ARTIST,
            MichiGenreNormalizationPolicy.Entity.RELEASE_GROUP,
        ).forEach { entity ->
            assertFalse(policy.approvedProposal(record, recordingClaim.copy(entity = entity), mbid))
        }
    }

    @Test
    fun strictSearchKeepsAlbumAndRelaxedSearchDropsIt() {
        val strict = policy.prepareSearch("Song", "Artist", "Album")!!
        assertEquals(setOf("recording", "artist", "release"), strict.outgoingFields().keys)
        assertEquals("Album", strict.outgoingFields()["release"])

        val relaxed = policy.relaxedSearch(strict)!!
        assertNotNull(relaxed)
        assertEquals(setOf("recording", "artist"), relaxed.outgoingFields().keys)
        assertTrue(policy.isRelaxedSearch(relaxed))
        assertFalse(policy.isRelaxedSearch(strict))
        assertEquals(relaxed, policy.prepareRelaxedSearch("Song", "Artist"))
        // Nothing to relax when the first search never sent a release.
        assertNull(policy.relaxedSearch(policy.prepareSearch("Song", "Artist")!!))
        assertNull(policy.prepareRelaxedSearch("", "Artist"))
    }
}
