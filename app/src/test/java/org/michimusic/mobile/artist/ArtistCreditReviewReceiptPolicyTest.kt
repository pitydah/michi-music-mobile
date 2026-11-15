package org.michimusic.mobile.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistCreditReviewReceiptPolicyTest {
    private val artist = "05072650-4b05-4e8b-83d0-dba2576e306a"
    private val recording = "11111111-1111-4111-8111-111111111111"
    private val release = "22222222-2222-4222-8222-222222222222"
    private val claim = MusicBrainzCreditClaim(artist, recording, release, VerifiedCreditRole.TRACK_ARTIST)
    private val scope = ArtistCreditReviewScope("device", "device_music_root", "midnight oil", "album-1", "track-1")
    private val verified = MusicBrainzCreditProofResult.CorroboratedForReview(artist, VerifiedCreditRole.TRACK_ARTIST)

    @Test fun exactEvidenceCanBeRetainedWithoutAuthorizingIdentity() {
        val receipt = ArtistCreditReviewReceiptPolicy.create(scope, claim, verified, 1_000L)
        assertNotNull(receipt)
        assertEquals(claim, receipt!!.claim)
        assertTrue(ArtistCreditReviewReceiptPolicy.isCurrent(receipt, scope, claim, 1_001L))
    }
    @Test fun uncorroboratedOrUnrelatedProofIsNeverSaved() {
        assertNull(ArtistCreditReviewReceiptPolicy.create(scope, claim,
            MusicBrainzCreditProofResult.Blocked(MusicBrainzCreditProofResult.Reason.MISSING_RESPONSE), 1_000L))
        assertNull(ArtistCreditReviewReceiptPolicy.create(scope, claim,
            verified.copy(artistMbid = release), 1_000L))
        assertNull(ArtistCreditReviewReceiptPolicy.create(scope, claim,
            verified.copy(role = VerifiedCreditRole.ALBUM_ARTIST), 1_000L))
    }
    @Test fun eachPartOfSourceAndLocalIdentityMustMatch() {
        val receipt = requireNotNull(ArtistCreditReviewReceiptPolicy.create(scope, claim, verified, 1_000L))
        for (other in listOf(scope.copy(libraryId = "other"), scope.copy(sourceId = "saf-2"),
            scope.copy(creditKey = "another"), scope.copy(albumKey = "album-2"),
            scope.copy(trackId = "track-2"))) {
            assertFalse(ArtistCreditReviewReceiptPolicy.isCurrent(receipt, other, claim, 1_010L))
        }
    }
    @Test fun recordingReleaseOrRoleChangeRevokesReceipt() {
        val receipt = requireNotNull(ArtistCreditReviewReceiptPolicy.create(scope, claim, verified, 1_000L))
        for (other in listOf(claim.copy(recordingMbid = release), claim.copy(releaseMbid = recording),
            claim.copy(role = VerifiedCreditRole.ALBUM_ARTIST))) {
            assertFalse(ArtistCreditReviewReceiptPolicy.isCurrent(receipt, scope, other, 1_010L))
        }
    }
    @Test fun receiptExpiresWithoutPeriodicWorkerOrNewFileAccess() {
        val receipt = requireNotNull(ArtistCreditReviewReceiptPolicy.create(scope, claim, verified, 1_000L))
        assertTrue(ArtistCreditReviewReceiptPolicy.isCurrent(receipt, scope, claim,
            1_000L + ArtistCreditReviewReceiptPolicy.TTL_MS))
        assertFalse(ArtistCreditReviewReceiptPolicy.isCurrent(receipt, scope, claim,
            1_001L + ArtistCreditReviewReceiptPolicy.TTL_MS))
        assertFalse(ArtistCreditReviewReceiptPolicy.isCurrent(receipt, scope, claim, 999L))
    }
    @Test fun globalScopeAndControlCharacterInjectionFailClosed() {
        for (bad in listOf(scope.copy(libraryId = "SCOPE_ALL"), scope.copy(albumKey = "a\u0000b"),
            scope.copy(sourceId = ""), scope.copy(creditKey = "Bad Caps"),
            scope.copy(trackId = ""))) {
            assertNull(ArtistCreditReviewReceiptPolicy.create(bad, claim, verified, 1_000L))
        }
    }
    @Test fun malformedIdsOrNoncanonicalIdsCannotBecomeDurableReceipt() {
        assertNull(ArtistCreditReviewReceiptPolicy.create(scope, claim.copy(recordingMbid = "garbage"), verified, 1_000L))
        assertNull(ArtistCreditReviewReceiptPolicy.create(scope, claim.copy(artistMbid = artist.uppercase()), verified, 1_000L))
        assertNull(ArtistCreditReviewReceiptPolicy.create(scope, claim.copy(releaseMbid = null), verified, 1_000L))
    }
    @Test fun sameArtistDifferentAlbumIsNotImplicitlyAuthorized() {
        val receipt = requireNotNull(ArtistCreditReviewReceiptPolicy.create(scope, claim, verified, 1_000L))
        assertFalse(ArtistCreditReviewReceiptPolicy.isCurrent(receipt, scope.copy(albumKey = "compilation"), claim, 1_001L))
    }
}
