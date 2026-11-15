package org.michimusic.mobile.artist

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Local tag -> exact same-file claim -> MusicBrainz JSON -> independent policy.
 * Never writes Room or confirms identity: only a typed REVIEW_ONLY result.
 */
class ArtistStructuredIdentityPipelineTest {
    private val artist = "05072650-4b05-4e8b-83d0-dba2576e306a"
    private val other = "bd6d4f63-5cd0-4d78-a292-167997794c21"
    private val recording = "11111111-1111-4111-8111-111111111111"
    private val release = "22222222-2222-4222-8222-222222222222"
    private fun observation(tags: EmbeddedArtistTags) = EmbeddedArtistReview(
        inspectedFiles = 1, taggedFiles = 1, artistMbids = tags.artistMbids,
        albumArtistMbids = tags.albumArtistMbids, creditedNames = tags.creditedNames,
        albumArtists = tags.albumArtists,
        trackEvidence = listOf(EmbeddedTrackEvidence("content://local/1", tags)),
    )
    private fun tags() = EmbeddedArtistTags(artistMbids = setOf(artist),
        creditedNames = listOf("Band"), recordingMbids = setOf(recording), releaseMbids = setOf(release))
    private fun recordingJson(artists: String) = Json.parseToJsonElement(
        """{"id":"$recording","artist-credit":[{"artist":{"id":"$artists"}}]}""") as JsonObject
    private fun releaseJson(trackArtist: String, memberId: String = recording) = Json.parseToJsonElement(
        """{"id":"$release","artist-credit":[{"artist":{"id":"$artist"}}],
            "media":[{"tracks":[{"recording":{"id":"$memberId"},
            "artist-credit":[{"artist":{"id":"$trackArtist"}}]}]}]}""") as JsonObject
    private fun proof(tags: EmbeddedArtistTags, creditedByRecording: String = artist,
                      creditedByReleaseTrack: String = artist, releaseContains: String = recording): MusicBrainzCreditProofResult {
        val plan = ArtistStructuredCreditClaimPlanner.plan("Band", observation(tags)) as StructuredCreditPlan.Ready
        return MusicBrainzCreditProof.evaluate(plan.claim,
            MusicBrainzCreditJson.recording(recordingJson(creditedByRecording)),
            MusicBrainzCreditJson.release(releaseJson(creditedByReleaseTrack, releaseContains)))
    }
    @Test fun matchingTripleIsOnlyCorroboratedForReview() {
        assertEquals(MusicBrainzCreditProofResult.CorroboratedForReview(artist, VerifiedCreditRole.TRACK_ARTIST),
            proof(tags()))
    }
    @Test fun differentArtistOnReleaseTrackCannotBePublishedAsVerified() {
        assertEquals(MusicBrainzCreditProofResult.Blocked(MusicBrainzCreditProofResult.Reason.ARTIST_NOT_CREDITED),
            proof(tags(), creditedByReleaseTrack = other))
    }
    @Test fun sameRecordingAbsentFromReleaseBlocksEvenIfArtistMatches() {
        assertEquals(MusicBrainzCreditProofResult.Blocked(MusicBrainzCreditProofResult.Reason.RECORDING_NOT_ON_RELEASE),
            proof(tags(), releaseContains = other))
    }
    @Test fun recordingCreditedToDifferentArtistCannotCorroborateLocalTag() {
        assertEquals(MusicBrainzCreditProofResult.Blocked(MusicBrainzCreditProofResult.Reason.ARTIST_NOT_CREDITED),
            proof(tags(), creditedByRecording = other))
    }
    @Test fun localIdsFromDifferentFilesCannotReachMusicBrainz() {
        val a = tags().copy(releaseMbids = emptySet())
        val b = tags().copy(recordingMbids = emptySet())
        val review = EmbeddedArtistReview(2, 2, setOf(artist), emptySet(), listOf("Band"), emptyList(),
            listOf(EmbeddedTrackEvidence("content://a", a), EmbeddedTrackEvidence("content://b", b)))
        assertEquals(StructuredCreditPlan.Blocked(StructuredCreditPlan.Reason.NO_COMPLETE_PAIR),
            ArtistStructuredCreditClaimPlanner.plan("Band", review))
    }
    @Test fun proposalAndCorroborationDoNotReturnAnIdentityCommitCommand() {
        assertTrue(proof(tags()) is MusicBrainzCreditProofResult.CorroboratedForReview)
        // No such type as ConfirmedArtist or an identity-write method exists on this path.
    }
}
