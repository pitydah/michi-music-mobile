package org.michimusic.data.artist

/** D43: a separate, future IO approval that CANNOT be converted from the
 * D24 RESERVED_NO_IO consent. Not persisted or connected to HTTP in FIX2.
 * The original NO_IO flow remains fail-closed and fully local. */
data class ArtistBulkIndependentIoConsent(
    val ioConsentId: String,
    val explicitUserApproval: Boolean,
    val libraryId: String,
    val sourceId: String,
    val rootEpoch: Long,
    val catalogRevision: Long,
    val libraryRevision: Long,
    val artistMbid: String,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
)

/** A dedicated future IO lease; no D27 NO_IO lease implements this type. */
data class ArtistBulkIndependentIoLease(
    val ioConsentId: String,
    val ioLeaseId: String,
    val rootEpoch: Long,
    val expiresAtEpochMs: Long,
    val oneShot: Boolean,
)

enum class ArtistBulkFutureIoReason {
    DISABLED, MISSING_INDEPENDENT_CONSENT, UNVERIFIED_USER_APPROVAL,
    INVALID_SCOPE, INVALID_IDENTITY, EXPIRED, INVALID_LEASE,
    ELIGIBLE_BUT_NOT_DISPATCHED,
}

object ArtistBulkFutureIoAdmission {
    /** There is NO shipping network worker or HTTP entrypoint in ARTFIX2. */
    const val ENABLED_BY_DEFAULT: Boolean = false
    private val canonicalMbid=Regex("^[a-f0-9]{8}-[a-f0-9]{4}-[1-8][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$")

    /** An audit-only preview. Even an eligible result does not grant HTTP authority;
     * a later Android release needs separately persisted IO-CAS and a real transport. */
    fun inspect(
        enabled: Boolean = ENABLED_BY_DEFAULT,
        consent: ArtistBulkIndependentIoConsent?,
        lease: ArtistBulkIndependentIoLease?,
        scope: ArtistBulkTerminalScope,
        now: Long,
    ): ArtistBulkFutureIoReason {
        if (!enabled) return ArtistBulkFutureIoReason.DISABLED
        val c=consent ?: return ArtistBulkFutureIoReason.MISSING_INDEPENDENT_CONSENT
        if (!c.explicitUserApproval || !c.ioConsentId.startsWith("io:") ||
            c.ioConsentId.length !in 8..128) return ArtistBulkFutureIoReason.UNVERIFIED_USER_APPROVAL
        if (!scope.isValid() || c.libraryId == "SCOPE_ALL" ||
            c.libraryId != scope.libraryId || c.sourceId != scope.sourceId ||
            c.rootEpoch != scope.rootEpoch || c.catalogRevision != scope.catalogRevision ||
            c.libraryRevision != scope.libraryRevision)
            return ArtistBulkFutureIoReason.INVALID_SCOPE
        if (!canonicalMbid.matches(c.artistMbid)) return ArtistBulkFutureIoReason.INVALID_IDENTITY
        if (now <= 0 || c.issuedAtEpochMs <= 0 || now < c.issuedAtEpochMs ||
            now >= c.expiresAtEpochMs || c.expiresAtEpochMs - c.issuedAtEpochMs > 10 * 60 * 1000L)
            return ArtistBulkFutureIoReason.EXPIRED
        if (lease == null || lease.ioConsentId != c.ioConsentId ||
            !lease.ioLeaseId.startsWith("io-lease:") || lease.ioLeaseId.length > 128 ||
            !lease.oneShot || lease.rootEpoch != scope.rootEpoch ||
            lease.expiresAtEpochMs > c.expiresAtEpochMs || now >= lease.expiresAtEpochMs)
            return ArtistBulkFutureIoReason.INVALID_LEASE
        return ArtistBulkFutureIoReason.ELIGIBLE_BUT_NOT_DISPATCHED
    }
}
