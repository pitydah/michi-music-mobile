package org.michimusic.core.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test

class MichiGenreNormalizationPolicyTest {
    private val p = MichiGenreNormalizationPolicy
    private val mbid = "ae57a2bc-4de1-4e94-9f07-b5301eacdd91"
    @Test fun publicRequestContainsOnlyChosenMetadata() {
        val intent = p.prepareSearch("  Song ", " Artist ", "Album")!!
        assertEquals(mapOf("recording" to "Song", "artist" to "Artist", "release" to "Album"), intent.outgoingFields())
        assertNull(p.prepareSearch("Song", ""))
        assertNull(p.prepareSearch("S\n", "Artist"))
        assertNull(p.prepareSearch("x".repeat(121), "Artist"))
    }
    @Test fun constructedSearchIntentCannotBypassNetworkValidation() {
        val valid = p.prepareSearch("Canción", "Artista", "Álbum")!!
        assertTrue(p.isPreparedSearch(valid))
        assertFalse(p.isPreparedSearch(MichiGenreNormalizationPolicy.SearchIntent(" Song ", "Artist")))
        assertFalse(p.isPreparedSearch(MichiGenreNormalizationPolicy.SearchIntent("Song", "")))
        assertFalse(p.isPreparedSearch(MichiGenreNormalizationPolicy.SearchIntent("Song\nOther", "Artist")))
        assertFalse(p.isPreparedSearch(MichiGenreNormalizationPolicy.SearchIntent("x".repeat(121), "Artist")))
        assertFalse(p.isPreparedSearch(MichiGenreNormalizationPolicy.SearchIntent("Song", "Artist", " Album ")))
    }

    @Test fun noGenreGuessingOrMalformedPayload() {
        assertTrue(p.isPlaceholderGenre("sin clasificar"))
        assertNull(p.validateGenres(listOf("Rock", "a\u0000b")))
        assertNull(p.validateGenres((1..33).map { "Genre $it" }))
        assertNull(p.validateGenres(listOf(" Progressive Rock ")))
        assertEquals(listOf("Progressive Rock", "Rock"), p.validateGenres(listOf("Progressive Rock", "Rock")))
        assertEquals(listOf("Jazz; Fusion"), p.validateGenres(listOf("Jazz; Fusion")))
        assertEquals(listOf("Rock"), p.validateGenres(listOf("Rock", "rock")))
    }
    @Test fun candidateIdentityIsNeverAutomaticallyTrusted() {
        val intent = p.prepareSearch("Song", "Artist")!!
        assertTrue(p.plausibleCandidate(intent, MichiGenreNormalizationPolicy.RecordingCandidate(mbid, "song", "artist", 200_000), 203_000))
        assertFalse(p.plausibleCandidate(intent, MichiGenreNormalizationPolicy.RecordingCandidate(mbid, "song", "Other", 200_000)))
        assertFalse(p.plausibleCandidate(intent, MichiGenreNormalizationPolicy.RecordingCandidate(mbid, "song", "artist", 200_000), 240_000))
        assertNull(p.canonicalMbid("not-an-id"))
    }
    @Test fun labelNeedsASelectedRecordingAndProvenance() {
        val record = MichiGenreNormalizationPolicy.RecordingCandidate(mbid, "Song", "Artist")
        val claim = MichiGenreNormalizationPolicy.GenreProposal("Rock", MichiGenreNormalizationPolicy.Entity.RECORDING, mbid, 7, mbid)
        assertTrue(p.approvedProposal(record, claim, mbid))
        assertFalse(p.approvedProposal(record, claim, "fe37d317-c7c0-47db-8dba-1200d5fb2e2d"))
        assertFalse(p.approvedProposal(record, claim.copy(name="Unknown"), mbid))
        val other = "fe37d317-c7c0-47db-8dba-1200d5fb2e2d"
        assertFalse(p.approvedProposal(record, claim.copy(sourceMbid=other), mbid))
        assertFalse(p.approvedProposal(record, claim.copy(entity=MichiGenreNormalizationPolicy.Entity.ARTIST), mbid))
        val forged = record.copy(mbid = "not-a-uuid")
        assertFalse(p.approvedProposal(forged, claim.copy(
            recordingMbid = "invalid", sourceMbid = "also-invalid"), "untrusted"))
    }
    @Test fun overwriteRequiresSeparateConsentAndValidWritePermission() {
        assertEquals(MichiGenreNormalizationPolicy.Decision.Denied("WRITE_PERMISSION_REQUIRED"),
            p.decideWrite(emptyList(),listOf("Rock"),true,false,true,false))
        assertEquals(MichiGenreNormalizationPolicy.Decision.Denied("REPLACE_EXISTING_GENRE_NEEDS_CONFIRMATION"),
            p.decideWrite(listOf("Jazz"),listOf("Rock"),true,false,true,true))
        assertEquals(MichiGenreNormalizationPolicy.Decision.Allowed(listOf("Rock")),
            p.decideWrite(listOf("Jazz"),listOf("Rock"),true,true,true,true))
        assertEquals(MichiGenreNormalizationPolicy.Decision.Allowed(listOf("Rock")),
            p.decideWrite(listOf("Unknown"),listOf("Rock"),true,false,true,true))
        assertEquals(MichiGenreNormalizationPolicy.Decision.Denied("IDENTITY_OR_SCOPE_CHANGED"),
            p.decideWrite(emptyList(),listOf("Rock"),true,false,false,true))
    }
}
