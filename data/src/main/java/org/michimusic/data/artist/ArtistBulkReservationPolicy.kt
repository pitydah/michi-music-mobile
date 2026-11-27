package org.michimusic.data.artist

/**
 * D24 - fail-closed, deterministic, no-network eligibility for ONE reservation.
 * This is NOT execution authority: callers must not use it to invoke providers.
 */
object ArtistBulkReservationPolicy {
    fun mayReserve(
        permission: ArtistBulkPermissionRecord,
        session: ArtistBulkSessionRecord,
        receipt: ArtistBulkReceiptRecord,
        expectedPermissionRevision: Long,
        expectedSessionRevision: Long,
        now: Long,
    ): Boolean =
        now > 0L && permission.isCurrent(now) &&
            permission.allowedOperation == ArtistBulkPermissionRecord.PROFILE_LOOKUP_ONLY &&
            permission.revision == expectedPermissionRevision && permission.revision >= 0 &&
            session.revision == expectedSessionRevision && session.revision >= 0 &&
            session.status == ArtistBulkSessionRecord.PREPARED_NOT_SUBMITTED &&
            receipt.status == ArtistBulkSessionRecord.PREPARED_NOT_SUBMITTED && receipt.revision == 0L &&
            session.receiptCount in 1..64 &&
            permission.sessionId.isNotBlank() && permission.sessionId == session.sessionId &&
            receipt.sessionId == session.sessionId && permission.fingerprint == receipt.fingerprint &&
            permission.fingerprint.length == 64 &&
            permission.fingerprint.all { it in '0'..'9' || it in 'a'..'f' } &&
            permission.permitId.isNotBlank() && permission.permitId.length <= 128 &&
            permission.permitId.none(Char::isWhitespace) &&
            session.libraryId.isNotBlank() && session.libraryId != "SCOPE_ALL" &&
            permission.libraryId == session.libraryId && receipt.libraryId == session.libraryId &&
            permission.sourceId == session.sourceId && session.sourceId.isNotBlank() &&
            permission.rootEpoch == session.rootEpoch && session.rootEpoch >= 0L &&
            permission.catalogRevision == session.catalogRevision && session.catalogRevision >= 0L &&
            permission.libraryRevision == session.libraryRevision && session.libraryRevision >= 0L &&
            permission.artistCredit.isNotBlank() && permission.artistCredit == receipt.artistCredit &&
            permission.mbid.isNotBlank() && permission.mbid == receipt.mbid &&
            permission.associationId.isNotBlank() && permission.associationId == receipt.associationId &&
            now >= session.createdAtEpochMs &&
            now - session.createdAtEpochMs <= ArtistBulkPermissionRecord.MAX_LIFETIME_MS &&
            permission.expiresAtEpochMs > permission.issuedAtEpochMs &&
            permission.expiresAtEpochMs - permission.issuedAtEpochMs <=
                ArtistBulkPermissionRecord.MAX_LIFETIME_MS
}
