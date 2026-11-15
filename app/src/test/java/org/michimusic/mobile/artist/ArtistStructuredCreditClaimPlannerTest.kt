package org.michimusic.mobile.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistStructuredCreditClaimPlannerTest {
    private val artist = "05072650-4b05-4e8b-83d0-dba2576e306a"
    private val other = "bd6d4f63-5cd0-4d78-a292-167997794c21"
    private val recording = "11111111-1111-4111-8111-111111111111"
    private val release = "22222222-2222-4222-8222-222222222222"
    private fun receipt(vararg rows: EmbeddedArtistTags): EmbeddedArtistReview = EmbeddedArtistReview(
        inspectedFiles = rows.size, taggedFiles = rows.size,
        artistMbids = rows.flatMap { it.artistMbids }.toSet(),
        albumArtistMbids = rows.flatMap { it.albumArtistMbids }.toSet(),
        creditedNames = rows.flatMap { it.creditedNames }.distinct(),
        albumArtists = rows.flatMap { it.albumArtists }.distinct(),
        trackEvidence = rows.mapIndexed { i, tags -> EmbeddedTrackEvidence("content://local/$i", tags) },
    )
    private fun track() = EmbeddedArtistTags(artistMbids = setOf(artist),
        creditedNames = listOf("Band"), recordingMbids = setOf(recording), releaseMbids = setOf(release))
    private fun reason(r: StructuredCreditPlan.Reason) = StructuredCreditPlan.Blocked(r)

    @Test fun mostSupportedSameFileTripleWinsOverLexicographicId() {
        val earlier = track()
        val popular = track().copy(recordingMbids = setOf(other))
        val plan = ArtistStructuredCreditClaimPlanner.plan("Band", receipt(earlier, popular, popular))
        assertEquals(StructuredCreditPlan.Ready(
            MusicBrainzCreditClaim(artist, other, release, VerifiedCreditRole.TRACK_ARTIST),
            2, "content://local/1"), plan)
    }

    @Test fun oneExplicitCreditOutranksTwoNamelessMatches() {
        val explicit = track()
        val unnamed = track().copy(creditedNames = emptyList(), recordingMbids = setOf(other))
        val result = ArtistStructuredCreditClaimPlanner.plan("Band", receipt(unnamed, explicit, unnamed))
        assertEquals(StructuredCreditPlan.Ready(
            MusicBrainzCreditClaim(artist, recording, release, VerifiedCreditRole.TRACK_ARTIST),
            1, "content://local/1"), result)
    }

    @Test fun namedSourceStillWinsWhenSeveralDifferentRecordingsAreObserved() {
        val explicit = track().copy(recordingMbids = setOf(other))
        val unnamed = track().copy(creditedNames = emptyList())
        assertEquals(StructuredCreditPlan.Ready(
            MusicBrainzCreditClaim(artist, other, release, VerifiedCreditRole.TRACK_ARTIST),
            1, "content://local/1"),
            ArtistStructuredCreditClaimPlanner.plan("Band", receipt(unnamed, explicit)))
    }

    @Test fun exactSameFileTripleBuildsOneReviewOnlyRequest() =
        assertEquals(StructuredCreditPlan.Ready(
            MusicBrainzCreditClaim(artist, recording, release, VerifiedCreditRole.TRACK_ARTIST), 1, "content://local/0"),
            ArtistStructuredCreditClaimPlanner.plan("Band", receipt(track())))

    @Test fun cannotJoinRecordingFromOneFileWithReleaseFromAnother() {
        val first = track().copy(releaseMbids = emptySet())
        val second = track().copy(recordingMbids = emptySet())
        assertEquals(reason(StructuredCreditPlan.Reason.NO_COMPLETE_PAIR),
            ArtistStructuredCreditClaimPlanner.plan("Band", receipt(first, second)))
    }
    @Test fun duplicatedMatchingFilesDoNotCauseRedundantNetworkRequests() =
        assertEquals(StructuredCreditPlan.Ready(
            MusicBrainzCreditClaim(artist, recording, release, VerifiedCreditRole.TRACK_ARTIST), 2, "content://local/0"),
            ArtistStructuredCreditClaimPlanner.plan("Band", receipt(track(), track())))

    @Test fun albumArtistRoleUsesTheAlbumArtistMbidOnly() =
        assertEquals(StructuredCreditPlan.Ready(
            MusicBrainzCreditClaim(artist, recording, release, VerifiedCreditRole.ALBUM_ARTIST), 1, "content://local/0"),
            ArtistStructuredCreditClaimPlanner.plan("Band", receipt(EmbeddedArtistTags(
                albumArtistMbids = setOf(artist), albumArtists = listOf("Band"),
                recordingMbids = setOf(recording), releaseMbids = setOf(release)))))

    @Test fun conflictingArtistMbidsBlockVerification() =
        assertEquals(reason(StructuredCreditPlan.Reason.UNTRUSTED_TAG_RECEIPT),
            ArtistStructuredCreditClaimPlanner.plan("Band", receipt(
                track(), track().copy(artistMbids = setOf(other)))))

    @Test fun multipleRecordingIdsNeverSendAnArbitraryRequest() =
        assertEquals(reason(StructuredCreditPlan.Reason.AMBIGUOUS_ROLE),
            ArtistStructuredCreditClaimPlanner.plan("Band", receipt(
                track().copy(recordingMbids = setOf(recording, other)))))

    @Test fun invalidRecordingIdCannotBeSentToRemoteService() =
        assertEquals(reason(StructuredCreditPlan.Reason.BAD_IDS),
            ArtistStructuredCreditClaimPlanner.plan("Band", receipt(
                track().copy(recordingMbids = setOf("../../recording")))))

    @Test fun noArbitraryRoleWhenBothTagsArePresentWithoutCreditNames() =
        assertEquals(reason(StructuredCreditPlan.Reason.AMBIGUOUS_ROLE),
            ArtistStructuredCreditClaimPlanner.plan("Band", receipt(EmbeddedArtistTags(
                artistMbids = setOf(artist), albumArtistMbids = setOf(artist),
                recordingMbids = setOf(recording), releaseMbids = setOf(release)))))

    @Test fun absentStructuredRecordingAndReleaseNeverProduceRemoteClaim() =
        assertEquals(reason(StructuredCreditPlan.Reason.NO_COMPLETE_PAIR),
            ArtistStructuredCreditClaimPlanner.plan("Band", receipt(
                track().copy(recordingMbids = emptySet(), releaseMbids = emptySet()))))

    @Test fun conflictingPerFileAggregatesCannotBypassIdentityReview() {
        val forged = receipt(track()).copy(artistMbids = setOf(other))
        assertEquals(reason(StructuredCreditPlan.Reason.UNTRUSTED_TAG_RECEIPT),
            ArtistStructuredCreditClaimPlanner.plan("Band", forged))
    }

    @Test fun differentValidTrackTriplesAreNotMixed() {
        val second = track().copy(recordingMbids = setOf(other))
        val plan = ArtistStructuredCreditClaimPlanner.plan("Band", receipt(track(), second))
        assertTrue(plan is StructuredCreditPlan.Ready)
        assertEquals(1, (plan as StructuredCreditPlan.Ready).supportingFiles)
        assertEquals("content://local/0", plan.supportingUri)
    }
    @Test fun duplicateValidEvidenceCarriesOneDeterministicSourceUri() {
        val plan = ArtistStructuredCreditClaimPlanner.plan("Band", receipt(track(), track()))
        assertEquals("content://local/0", (plan as StructuredCreditPlan.Ready).supportingUri)
    }
}
