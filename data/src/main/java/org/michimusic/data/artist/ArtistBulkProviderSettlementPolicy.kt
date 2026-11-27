package org.michimusic.data.artist

/**
 * D31: exact state transition for retiring a STAGED_NO_IO lease together with its
 * D24 permission and D25 terminal receipt. This policy can never authorize HTTP,
 * WorkManager, artist data, file mutations or an inferred provider outcome.
 */
object ArtistBulkProviderSettlementPolicy {
    fun mayClose(
        lease: ArtistBulkProviderLeaseRecord,
        expected: ArtistBulkPermissionRecord,
        current: ArtistBulkPermissionRecord,
        observed: ArtistBulkTerminalScope,
        outcome: ArtistBulkTerminalOutcome,
        now: Long,
    ): Boolean {
        if (lease.state != ArtistBulkProviderLeaseRecord.STAGED_NO_IO ||
            lease.mayDispatch || current != expected ||
            lease.sessionId != expected.sessionId ||
            lease.fingerprint != expected.fingerprint ||
            lease.permitId != expected.permitId ||
            lease.permissionRevision != expected.revision ||
            lease.libraryId != expected.libraryId || lease.sourceId != expected.sourceId ||
            lease.rootEpoch != expected.rootEpoch ||
            lease.catalogRevision != expected.catalogRevision ||
            lease.libraryRevision != expected.libraryRevision ||
            !lease.mbid.equals(expected.mbid, ignoreCase = true) ||
            lease.provider != ArtistBulkProfileProvider.MUSICBRAINZ_ARTIST_BY_MBID.name ||
            lease.issuedAtEpochMs <= 0L || lease.expiresAtEpochMs <= lease.issuedAtEpochMs ||
            lease.expiresAtEpochMs - lease.issuedAtEpochMs > ArtistBulkProviderLeasePolicy.LEASE_TTL_MS ||
            lease.updatedAtEpochMs < lease.issuedAtEpochMs ||
            now < lease.updatedAtEpochMs || now < lease.issuedAtEpochMs ||
            lease.revision < 0L || lease.revision == Long.MAX_VALUE ||
            expected.revision == Long.MAX_VALUE ||
            !ArtistBulkTerminalPolicy.mayCloseWithoutIo(expected, observed, outcome, now)
        ) return false
        return true
    }

    /** D27 may already have abandoned the lease after an A->B->A snapshot failure.
     * Retire the remaining D24 permission without resurrecting the spent lease.
     */
    fun mayFinalizeRetired(
        lease: ArtistBulkProviderLeaseRecord,
        expected: ArtistBulkPermissionRecord,
        current: ArtistBulkPermissionRecord,
        observed: ArtistBulkTerminalScope,
        outcome: ArtistBulkTerminalOutcome,
        now: Long,
    ): Boolean = lease.state in setOf(ArtistBulkProviderLeaseRecord.ABANDONED_NO_IO,
            ArtistBulkProviderLeaseRecord.EXPIRED_NO_IO) &&
        lease.revision == 1L && lease.updatedAtEpochMs <= now &&
        mayClose(lease.copy(state = ArtistBulkProviderLeaseRecord.STAGED_NO_IO,
            revision = 0L), expected, current, observed, outcome, now)

    /** An already closed lease is idempotent only for the *identical* terminal. */
    fun isExactReplay(
        lease: ArtistBulkProviderLeaseRecord,
        expected: ArtistBulkPermissionRecord,
        current: ArtistBulkPermissionRecord,
        terminal: ArtistBulkTerminalReceiptRecord,
        outcome: ArtistBulkTerminalOutcome,
    ): Boolean {
        if (lease.state !in setOf(ArtistBulkProviderLeaseRecord.ABANDONED_NO_IO,
                ArtistBulkProviderLeaseRecord.EXPIRED_NO_IO) ||
            !ArtistBulkTerminalPolicy.authentic(terminal) ||
            terminal.sessionId != expected.sessionId ||
            terminal.fingerprint != expected.fingerprint ||
            terminal.permitId != expected.permitId ||
            terminal.reservationRevision != expected.revision ||
            terminal.outcome != outcome.name ||
            terminal.libraryId != expected.libraryId || terminal.sourceId != expected.sourceId ||
            terminal.rootEpoch != expected.rootEpoch ||
            terminal.catalogRevision != expected.catalogRevision ||
            terminal.libraryRevision != expected.libraryRevision ||
            lease.leaseId.isBlank() || lease.provider != ArtistBulkProfileProvider.MUSICBRAINZ_ARTIST_BY_MBID.name ||
            lease.permitId != expected.permitId || lease.sessionId != expected.sessionId ||
            lease.fingerprint != expected.fingerprint ||
            lease.permissionRevision != expected.revision ||
            lease.libraryId != expected.libraryId || lease.sourceId != expected.sourceId ||
            lease.rootEpoch != expected.rootEpoch ||
            lease.catalogRevision != expected.catalogRevision ||
            lease.libraryRevision != expected.libraryRevision ||
            !lease.mbid.equals(expected.mbid, ignoreCase = true) ||
            lease.revision != 1L || lease.updatedAtEpochMs < lease.issuedAtEpochMs ||
            lease.updatedAtEpochMs > terminal.completedAtEpochMs ||
            expected.revision == Long.MAX_VALUE ||
            current != expected.copy(status = ArtistBulkPermissionRecord.RELEASED_NO_IO,
                revision = expected.revision + 1L,
                updatedAtEpochMs = terminal.completedAtEpochMs)
        ) return false
        return true
    }
}
