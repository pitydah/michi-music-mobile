package org.michimusic.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBrainzTagIdPolicyTest {
    private val a = "bd6d4f63-5cd0-4d78-a292-167997794c21"
    private val b = "05072650-4b05-4e8b-83d0-dba2576e306a"

    @Test fun rolesStaySeparateAndMultipleArtistsRemainMultiple() {
        val r = MusicBrainzTagIdPolicy.fromTags(mapOf(
            "MUSICBRAINZ_ARTISTID" to listOf("$a/$b"),
            "MUSICBRAINZ_ALBUMARTISTID" to listOf(b),
        ))
        assertEquals(listOf(a,b), r.artistIds)
        assertEquals(listOf(b), r.albumArtistIds)
    }
    @Test fun hostileOrUnstructuredTextIsNotAnArtistIdentity() {
        val r = MusicBrainzTagIdPolicy.fromTags(mapOf(
            "ARTIST" to listOf(a),
            "MusicBrainz Artist Id" to listOf("person:$b", "not-a-uuid"),
        ))
        assertTrue(r.artistIds.isEmpty())
        assertTrue(r.albumArtistIds.isEmpty())
    }
    @Test fun duplicatedAndMixedCaseMbidsAreCanonicalAndBounded() {
        val r = MusicBrainzTagIdPolicy.fromTags(mapOf(
            "MusicBrainz Artist Id" to listOf(a.uppercase(), a, b),
        ))
        assertEquals(listOf(a,b),r.artistIds)
    }
    @Test fun recordingAndReleaseMbidsAreNotArtistMbids() {
        val r = MusicBrainzTagIdPolicy.fromTags(mapOf(
            "MUSICBRAINZ_TRACKID" to listOf(a),
            "MUSICBRAINZ_ALBUMID" to listOf(b),
        ))
        assertEquals(listOf(a), r.recordingIds)
        assertEquals(listOf(b), r.releaseIds)
        assertTrue(r.artistIds.isEmpty())
        assertTrue(r.albumArtistIds.isEmpty())
    }
    @Test fun recordingAndReleaseAliasesDeduplicate() {
        val r = MusicBrainzTagIdPolicy.fromTags(mapOf(
            "MusicBrainz Recording Id" to listOf(a),
            "MUSICBRAINZ_TRACKID" to listOf(a.uppercase()),
            "MusicBrainz Release Id" to listOf(b),
            "MusicBrainz Album Id" to listOf(b),
        ))
        assertEquals(listOf(a), r.recordingIds)
        assertEquals(listOf(b), r.releaseIds)
    }
    @Test fun invalidRecordingOrReleaseNeverCreatesPseudoId() {
        val r = MusicBrainzTagIdPolicy.fromTags(mapOf(
            "MUSICBRAINZ_TRACKID" to listOf("recording:$a"),
            "MUSICBRAINZ_ALBUMID" to listOf("../../release", "not-an-id"),
        ))
        assertTrue(r.recordingIds.isEmpty())
        assertTrue(r.releaseIds.isEmpty())
    }

}
