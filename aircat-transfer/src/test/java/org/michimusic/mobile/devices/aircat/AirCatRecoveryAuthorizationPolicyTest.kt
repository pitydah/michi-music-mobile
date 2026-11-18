package org.michimusic.mobile.devices.aircat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.PersistentCheckpoint
import org.michimusic.core.models.TransferStatus

class AirCatRecoveryAuthorizationPolicyTest {
    private val cp = PersistentCheckpoint(
        transferKey="p:lib:song:plan", transferId="plan", remoteDeviceId="p",
        sourceLibraryId="src", destinationLibraryId="lib", trackId="song",
        remoteTrackId="remote-id", tempFilePath="/music/a.part", finalFilePath="/music/a.flac",
        expectedSize=1024, receivedBytes=1024, verifiedBytes=1024,
        expectedSha256="a".repeat(64), transferState=TransferStatus.COMPLETED,
    )
    private fun allowed(
        source: String="p", tracks: Set<String>?=null,
        criteria: List<AuthorizedTransferCriteria>?=null,
    ) = AirCatRecoveryAuthorizationPolicy.permits(cp, source, tracks, criteria)

    @Test fun userInitiatedUnrestrictedRepairMayInspectOwnPeer() = assertTrue(allowed())
    @Test fun cannotInspectAnotherPeer() = assertFalse(allowed(source="intruder"))
    @Test fun cannotInspectEmptyPeer() = assertFalse(allowed(source=""))
    @Test fun allowedTrackCanBeInspected() = assertTrue(allowed(tracks=setOf("song")))
    @Test fun otherTrackCannotBeInspected() = assertFalse(allowed(tracks=setOf("other")))
    @Test fun emptyExplicitTrackSetDeniesAll() = assertFalse(allowed(tracks=emptySet()))
    @Test fun exactCompositeCriteriaPermitted() = assertTrue(allowed(criteria=listOf(
        AuthorizedTransferCriteria("song", "lib", 1024, "a".repeat(64), "plan"))))
    @Test fun otherDestinationLibraryDenied() = assertFalse(allowed(criteria=listOf(
        AuthorizedTransferCriteria("song", "other"))))
    @Test fun otherSizeDenied() = assertFalse(allowed(criteria=listOf(
        AuthorizedTransferCriteria("song", expectedSize=999))))
    @Test fun otherChecksumDenied() = assertFalse(allowed(criteria=listOf(
        AuthorizedTransferCriteria("song", expectedSha256="b".repeat(64)))))
    @Test fun otherPlanDenied() = assertFalse(allowed(criteria=listOf(
        AuthorizedTransferCriteria("song", planId="other"))))
    @Test fun otherLogicalTrackDenied() = assertFalse(allowed(criteria=listOf(
        AuthorizedTransferCriteria("other"))))
    @Test fun emptyCriteriaDeniesEvenWhenTrackSetContainsTrack() = assertFalse(allowed(tracks=setOf("song"), criteria=emptyList()))
    @Test fun compositeCriteriaOverridesBroadTrackSelection() = assertFalse(allowed(
        tracks=setOf("song"), criteria=listOf(AuthorizedTransferCriteria("song", "other"))))
    @Test fun hashCaseInsensitiveForExplicitMatching() = assertTrue(allowed(criteria=listOf(
        AuthorizedTransferCriteria("song", expectedSha256="A".repeat(64)))))
}
