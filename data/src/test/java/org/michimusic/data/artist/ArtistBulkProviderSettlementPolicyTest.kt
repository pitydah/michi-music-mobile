package org.michimusic.data.artist

import org.junit.Test

/** D31: pure verification of authority, clock and immutable terminal replay. */
class ArtistBulkProviderSettlementPolicyTest {
    private val mbid = "6d09c431-e35a-47e5-9460-0a1dd05c6619"
    private val fp = "a".repeat(64)
    private val initial = ArtistBulkPermissionRecord(
        sessionId = "session", fingerprint = fp, permitId = "permit", libraryId = "lib",
        sourceId = "root", rootEpoch = 3, catalogRevision = 5, libraryRevision = 7,
        artistCredit = "Local Artist", mbid = mbid, associationId = "association",
        status = ArtistBulkPermissionRecord.RESERVED_NO_IO,
        issuedAtEpochMs = 1_000L, expiresAtEpochMs = 50_000L,
        updatedAtEpochMs = 1_010L, revision = 4L,
    )
    private val lease = ArtistBulkProviderLeaseRecord(
        leaseId = "lease", sessionId = "session", fingerprint = fp, permitId = "permit",
        provider = ArtistBulkProfileProvider.MUSICBRAINZ_ARTIST_BY_MBID.name, mbid = mbid,
        permissionRevision = initial.revision, libraryId = "lib", sourceId = "root",
        rootEpoch = 3L, catalogRevision = 5L, libraryRevision = 7L,
        issuedAtEpochMs = 2_000L, expiresAtEpochMs = 32_000L,
        updatedAtEpochMs = 2_000L, state = ArtistBulkProviderLeaseRecord.STAGED_NO_IO,
        revision = 0L,
    )
    private val scope = ArtistBulkTerminalScope("lib", "root", 3L, 5L, 7L)
    private fun permits(l: ArtistBulkProviderLeaseRecord = lease,
        p: ArtistBulkPermissionRecord = initial,
        actual: ArtistBulkPermissionRecord = p,
        observed: ArtistBulkTerminalScope = scope,
        outcome: ArtistBulkTerminalOutcome = ArtistBulkTerminalOutcome.CANCELLED,
        now: Long = 3_000L,
    ) = ArtistBulkProviderSettlementPolicy.mayClose(l, p, actual, observed, outcome, now)

    @Test fun cancelWithinLeaseAllowed() = check(permits())
    @Test fun staleChangedRootAllowed() = check(permits(observed = scope.copy(rootEpoch = 4L),
        outcome = ArtistBulkTerminalOutcome.STALE_SCOPE))
    @Test fun staleExpiredPermissionAllowed() = check(permits(now = 51_000L,
        outcome = ArtistBulkTerminalOutcome.STALE_SCOPE))
    @Test fun leaseExpiryAloneIsNotPermissionExpiry() = check(!permits(now = 33_000L,
        outcome = ArtistBulkTerminalOutcome.STALE_SCOPE))
    @Test fun bogusNetworkOutcomeRejected() = check(!permits(outcome = ArtistBulkTerminalOutcome.VERIFIED_PROFILE))
    @Test fun releasedPermissionRefused() = check(!permits(actual = initial.copy(
        status = ArtistBulkPermissionRecord.RELEASED_NO_IO)))
    @Test fun wrongPermitRefused() = check(!permits(l = lease.copy(permitId = "other")))
    @Test fun wrongGenerationRefused() = check(!permits(l = lease.copy(rootEpoch = 4L)))
    @Test fun wrongMbidRefused() = check(!permits(l = lease.copy(mbid = "b".repeat(36))))
    @Test fun staleLeaseRefused() = check(!permits(l = lease.copy(state = ArtistBulkProviderLeaseRecord.EXPIRED_NO_IO)))
    @Test fun clockRegressionRefused() = check(!permits(now = 1_999L))
    @Test fun hugeRevisionRefused() = check(!permits(l = lease.copy(revision = Long.MAX_VALUE)))

    @Test fun previouslyAbandonedLeaseMayRetirePermission() = check(
        ArtistBulkProviderSettlementPolicy.mayFinalizeRetired(
            lease.copy(state = ArtistBulkProviderLeaseRecord.ABANDONED_NO_IO,
                updatedAtEpochMs = 2_100L, revision = 1L), initial, initial, scope,
            ArtistBulkTerminalOutcome.CANCELLED, 3_000L))
    @Test fun previouslyExpiredLeaseMayRetireStalePermission() = check(
        ArtistBulkProviderSettlementPolicy.mayFinalizeRetired(
            lease.copy(state = ArtistBulkProviderLeaseRecord.EXPIRED_NO_IO,
                updatedAtEpochMs = 32_000L, revision = 1L), initial, initial, scope,
            ArtistBulkTerminalOutcome.STALE_SCOPE, 51_000L))
    @Test fun exhaustedLeaseStillCannotPublishProviderProfile() = check(
        !ArtistBulkProviderSettlementPolicy.mayFinalizeRetired(
            lease.copy(state = ArtistBulkProviderLeaseRecord.ABANDONED_NO_IO,
                updatedAtEpochMs = 2_100L, revision = 1L), initial, initial, scope,
            ArtistBulkTerminalOutcome.VERIFIED_PROFILE, 3_000L))

    private fun terminal(): ArtistBulkTerminalReceiptRecord {
        val row = ArtistBulkTerminalReceiptRecord(
            sessionId = initial.sessionId, fingerprint = initial.fingerprint,
            permitId = initial.permitId, outcome = ArtistBulkTerminalOutcome.CANCELLED.name,
            evidenceKind = ArtistBulkTerminalReceiptRecord.NO_IO_RECOVERY,
            reservationRevision = initial.revision, libraryId = initial.libraryId,
            sourceId = initial.sourceId, rootEpoch = initial.rootEpoch,
            catalogRevision = initial.catalogRevision, libraryRevision = initial.libraryRevision,
            completedAtEpochMs = 3_000L, receiptDigest = "",
        )
        return row.copy(receiptDigest = ArtistBulkTerminalPolicy.digest(row))
    }
    private fun replay(record: ArtistBulkTerminalReceiptRecord = terminal(),
        l: ArtistBulkProviderLeaseRecord = lease.copy(state = ArtistBulkProviderLeaseRecord.ABANDONED_NO_IO,
            revision = 1L, updatedAtEpochMs = 3_000L),
        p: ArtistBulkPermissionRecord = initial.copy(status = ArtistBulkPermissionRecord.RELEASED_NO_IO,
            revision = 5L, updatedAtEpochMs = 3_000L),
        outcome: ArtistBulkTerminalOutcome = ArtistBulkTerminalOutcome.CANCELLED,
    ): Boolean = ArtistBulkProviderSettlementPolicy.isExactReplay(l, initial, p, record, outcome)

    @Test fun exactReplayAccepted() = check(replay())
    @Test fun replayOfExpiredLeaseAccepted() = check(replay(l = lease.copy(
        state = ArtistBulkProviderLeaseRecord.EXPIRED_NO_IO, revision = 1L, updatedAtEpochMs = 3_000L)))
    @Test fun replayAfterPreviousAbandonmentAllowed() = check(replay(l = lease.copy(
        state = ArtistBulkProviderLeaseRecord.ABANDONED_NO_IO,
        updatedAtEpochMs = 2_100L, revision = 1L)))
    @Test fun conflictingReplayOutcomeRejected() = check(!replay(outcome = ArtistBulkTerminalOutcome.STALE_SCOPE))
    @Test fun forgedDigestRejected() = check(!replay(record = terminal().copy(receiptDigest = "0".repeat(64))))
    @Test fun releasedPermissionRevisionMustBeExact() = check(!replay(p = initial.copy(
        status = ArtistBulkPermissionRecord.RELEASED_NO_IO, revision = 6L, updatedAtEpochMs = 3_000L)))
    @Test fun leaseClosureTimeMustMatchReceipt() = check(!replay(l = lease.copy(
        state = ArtistBulkProviderLeaseRecord.ABANDONED_NO_IO, revision = 1L, updatedAtEpochMs = 3_001L)))
    @Test fun missingTerminalNeverReplayed() = check(!replay(record = terminal().copy(permitId = "other")))
}
