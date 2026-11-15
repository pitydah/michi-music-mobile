package org.michimusic.mobile.artist

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicBrainzCreditProofTest {
    private val artist = "05072650-4b05-4e8b-83d0-dba2576e306a"
    private val impostor = "bd6d4f63-5cd0-4d78-a292-167997794c21"
    private val recording = "11111111-1111-4111-8111-111111111111"
    private val release = "22222222-2222-4222-8222-222222222222"
    private val rec = MusicBrainzRecordingCredit(recording, setOf(artist))
    private val rel = MusicBrainzReleaseCredit(release, setOf(artist), setOf(recording))
    private fun claim(role: VerifiedCreditRole = VerifiedCreditRole.TRACK_ARTIST) =
        MusicBrainzCreditClaim(artist, recording, release, role)
    private fun reason(reason: MusicBrainzCreditProofResult.Reason) = MusicBrainzCreditProofResult.Blocked(reason)

    @Test fun recordingAndReleaseBothProveTrackCreditForReview() =
        assertEquals(MusicBrainzCreditProofResult.CorroboratedForReview(artist, VerifiedCreditRole.TRACK_ARTIST),
            MusicBrainzCreditProof.evaluate(claim(), rec, rel))
    @Test fun albumArtistUsesAlbumCreditNotTrackCredit() =
        assertEquals(MusicBrainzCreditProofResult.CorroboratedForReview(artist, VerifiedCreditRole.ALBUM_ARTIST),
            MusicBrainzCreditProof.evaluate(claim(VerifiedCreditRole.ALBUM_ARTIST), rec, rel))
    @Test fun recordingTagAloneIsNeverEnough() =
        assertEquals(reason(MusicBrainzCreditProofResult.Reason.MISSING_STRUCTURED_IDS),
            MusicBrainzCreditProof.evaluate(claim().copy(releaseMbid = null), rec, rel))
    @Test fun releaseTagAloneIsNeverEnough() =
        assertEquals(reason(MusicBrainzCreditProofResult.Reason.MISSING_STRUCTURED_IDS),
            MusicBrainzCreditProof.evaluate(claim().copy(recordingMbid = null), rec, rel))
    @Test fun hostileTagIsRejected() =
        assertEquals(reason(MusicBrainzCreditProofResult.Reason.MALFORMED_MBID),
            MusicBrainzCreditProof.evaluate(claim().copy(recordingMbid = "../../recording"), rec, rel))
    @Test fun mismatchedRecordingResponseFails() =
        assertEquals(reason(MusicBrainzCreditProofResult.Reason.RESPONSE_ID_MISMATCH),
            MusicBrainzCreditProof.evaluate(claim(), rec.copy(recordingMbid = impostor), rel))
    @Test fun mismatchedReleaseResponseFails() =
        assertEquals(reason(MusicBrainzCreditProofResult.Reason.RESPONSE_ID_MISMATCH),
            MusicBrainzCreditProof.evaluate(claim(), rec, rel.copy(releaseMbid = impostor)))
    @Test fun recordingMustBeInExactRelease() =
        assertEquals(reason(MusicBrainzCreditProofResult.Reason.RECORDING_NOT_ON_RELEASE),
            MusicBrainzCreditProof.evaluate(claim(), rec, rel.copy(recordingMbids = setOf(impostor))))
    @Test fun homonymMbidIsNotCredited() =
        assertEquals(reason(MusicBrainzCreditProofResult.Reason.ARTIST_NOT_CREDITED),
            MusicBrainzCreditProof.evaluate(claim().copy(artistMbid = impostor), rec, rel))
    @Test fun compilationTrackCreditCannotPretendToBeAlbumArtist() =
        assertEquals(reason(MusicBrainzCreditProofResult.Reason.ARTIST_NOT_CREDITED),
            MusicBrainzCreditProof.evaluate(claim(VerifiedCreditRole.ALBUM_ARTIST), rec, rel.copy(albumArtistMbids = setOf(impostor))))
    @Test fun twoRecordingArtistsNeedJointCreditReview() =
        assertEquals(reason(MusicBrainzCreditProofResult.Reason.MULTIPLE_CREDITED_ARTISTS),
            MusicBrainzCreditProof.evaluate(claim(), rec.copy(artistMbids = setOf(artist, impostor)), rel))
    @Test fun twoAlbumArtistsNeedJointCreditReview() =
        assertEquals(reason(MusicBrainzCreditProofResult.Reason.MULTIPLE_CREDITED_ARTISTS),
            MusicBrainzCreditProof.evaluate(claim(VerifiedCreditRole.ALBUM_ARTIST), rec,
                rel.copy(albumArtistMbids = setOf(artist, impostor))))
    @Test fun emptyResponsesFailClosed() =
        assertEquals(reason(MusicBrainzCreditProofResult.Reason.MISSING_RESPONSE),
            MusicBrainzCreditProof.evaluate(claim(), null, rel))
    @Test fun caseCanonicalizationDoesNotCreateNewIdentity() =
        assertEquals(MusicBrainzCreditProofResult.CorroboratedForReview(artist, VerifiedCreditRole.TRACK_ARTIST),
            MusicBrainzCreditProof.evaluate(claim().copy(artistMbid = artist.uppercase()), rec, rel))

    @Test fun corruptExtraRecordingCreditCannotBeSilentlyIgnored() =
        assertEquals(reason(MusicBrainzCreditProofResult.Reason.INVALID_REMOTE_CREDIT),
            MusicBrainzCreditProof.evaluate(claim(), rec.copy(artistMbids = setOf(artist, "invalid")), rel))
    @Test fun corruptExtraAlbumCreditCannotBeSilentlyIgnored() =
        assertEquals(reason(MusicBrainzCreditProofResult.Reason.INVALID_REMOTE_CREDIT),
            MusicBrainzCreditProof.evaluate(claim(VerifiedCreditRole.ALBUM_ARTIST), rec,
                rel.copy(albumArtistMbids = setOf(artist, "invalid"))))
    @Test fun malformedOtherReleaseRecordingIdBlocksFullResponse() =
        assertEquals(reason(MusicBrainzCreditProofResult.Reason.INVALID_REMOTE_CREDIT),
            MusicBrainzCreditProof.evaluate(claim(), rec, rel.copy(recordingMbids = setOf(recording, "bad"))))
    @Test fun manyRemoteArtistsFailBudgetRatherThanTruncate() =
        assertEquals(reason(MusicBrainzCreditProofResult.Reason.BUDGET_EXCEEDED),
            MusicBrainzCreditProof.evaluate(claim(), rec.copy(artistMbids =
                (0..16).map { java.util.UUID.nameUUIDFromBytes("$it".toByteArray()).toString() }.toSet()), rel))
    @Test fun releaseTrackCreditDifferentFromRecordingArtistBlocksReview() =
        assertEquals(reason(MusicBrainzCreditProofResult.Reason.ARTIST_NOT_CREDITED),
            MusicBrainzCreditProof.evaluate(claim(), rec,
                rel.copy(trackArtistMbids = mapOf(recording to setOf(impostor)))))
    @Test fun explicitReleaseTrackCreditAgreesWithRecordingArtist() =
        assertEquals(MusicBrainzCreditProofResult.CorroboratedForReview(artist, VerifiedCreditRole.TRACK_ARTIST),
            MusicBrainzCreditProof.evaluate(claim(), rec,
                rel.copy(trackArtistMbids = mapOf(recording to setOf(artist)))))
    @Test fun fraudulentExtraTrackCreditIsNotSilentlyDropped() =
        assertEquals(reason(MusicBrainzCreditProofResult.Reason.INVALID_REMOTE_CREDIT),
            MusicBrainzCreditProof.evaluate(claim(), rec,
                rel.copy(trackArtistMbids = mapOf(recording to setOf(artist, "bad")))))
    @Test fun multiArtistReleaseTrackMustNotBeMisrepresentedAsSolo() =
        assertEquals(reason(MusicBrainzCreditProofResult.Reason.MULTIPLE_CREDITED_ARTISTS),
            MusicBrainzCreditProof.evaluate(claim(), rec,
                rel.copy(trackArtistMbids = mapOf(recording to setOf(artist, impostor)))))
    @Test fun uppercaseReleaseTrackMbidMatchesTheSameCanonicalRecording() =
        assertEquals(MusicBrainzCreditProofResult.CorroboratedForReview(artist, VerifiedCreditRole.TRACK_ARTIST),
            MusicBrainzCreditProof.evaluate(claim(), rec, rel.copy(trackArtistMbids =
                mapOf(recording.uppercase() to setOf(artist.uppercase())))))
    @Test fun duplicateNormalizedReleaseTrackKeysAreInvalid() =
        assertEquals(reason(MusicBrainzCreditProofResult.Reason.INVALID_REMOTE_CREDIT),
            MusicBrainzCreditProof.evaluate(claim(), rec, rel.copy(trackArtistMbids =
                mapOf("aaaabbbb-1111-4111-8111-111111111111" to setOf(artist),
                    "AAAABBBB-1111-4111-8111-111111111111" to setOf(artist)))))
    @Test fun duplicateCanonicalArtistIdsAreInvalid() =
        assertEquals(reason(MusicBrainzCreditProofResult.Reason.INVALID_REMOTE_CREDIT),
            MusicBrainzCreditProof.evaluate(claim(), rec.copy(artistMbids = setOf(artist, artist.uppercase())), rel))
}
