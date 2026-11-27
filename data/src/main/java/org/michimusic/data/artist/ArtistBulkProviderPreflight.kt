package org.michimusic.data.artist

/**
 * D26 / S4-G1. An inspection-only description, NOT a network capability.
 * The NO_IO permission cannot be used to dispatch a provider call. A future
 * Android executor needs a separate, certified, transactional IO lease.
 * The outbound projection is limited to the MBID; local artist credit, album
 * names, file paths, library id and playback history are never an HTTP payload.
 */
data class ArtistBulkProfileLookupPreview(
    val provider: ArtistBulkProfileProvider,
    val mbid: String,
    val sessionId: String,
    val fingerprint: String,
    val permitId: String,
    val permissionRevision: Long,
    val expectedScope: ArtistBulkTerminalScope,
    val expiresAtEpochMs: Long,
) {
    /** Explicit negative guarantee: a preview does not authorize IO. */
    val mayDispatch: Boolean get() = false
}

enum class ArtistBulkProfileProvider { MUSICBRAINZ_ARTIST_BY_MBID }

/** Environment observations are advisory and must be independently reread at dispatch. */
data class ArtistBulkProviderConditions(
    val unmeteredNetwork: Boolean,
    val batteryNotLow: Boolean,
    val storageNotLow: Boolean,
    val userInitiatedReview: Boolean,
)

object ArtistBulkProviderPreflight {
    private val artistMbid = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
    )
    private val fingerprint = Regex("^[0-9a-f]{64}$")

    /**
     * Denies unverified identity, stale root (including A->B->A), expiry,
     * malformed permission, missing device constraints and synthetic SCOPE_ALL.
     * No HTTP URL, client, WorkManager or permission for data writes is produced.
     */
    fun inspect(
        permit: ArtistBulkPermissionRecord,
        current: ArtistBulkTerminalScope,
        conditions: ArtistBulkProviderConditions,
        nowEpochMs: Long,
    ): ArtistBulkProfileLookupPreview? {
        if (nowEpochMs <= 0L || !current.isValid() || !current.matches(permit)) return null
        if (!conditions.userInitiatedReview || !conditions.unmeteredNetwork ||
            !conditions.batteryNotLow || !conditions.storageNotLow
        ) return null
        if (permit.status != ArtistBulkPermissionRecord.RESERVED_NO_IO ||
            permit.allowedOperation != ArtistBulkPermissionRecord.PROFILE_LOOKUP_ONLY ||
            permit.revision <= 0L || permit.sessionId.isBlank() || permit.sessionId.length > 128 ||
            !fingerprint.matches(permit.fingerprint) || permit.permitId.isBlank() ||
            permit.permitId.length > 128 || permit.permitId.any(Char::isWhitespace) ||
            permit.artistCredit.isBlank() || permit.associationId.isBlank() ||
            permit.sourceId.isBlank() || permit.libraryId == "SCOPE_ALL"
        ) return null
        val mbid = permit.mbid.lowercase(java.util.Locale.ROOT)
        if (!artistMbid.matches(mbid) || mbid == "00000000-0000-0000-0000-000000000000") return null
        if (permit.issuedAtEpochMs <= 0L || permit.updatedAtEpochMs < permit.issuedAtEpochMs ||
            permit.updatedAtEpochMs > nowEpochMs ||
            permit.expiresAtEpochMs <= permit.issuedAtEpochMs ||
            permit.expiresAtEpochMs - permit.issuedAtEpochMs > ArtistBulkPermissionRecord.MAX_LIFETIME_MS ||
            nowEpochMs < permit.issuedAtEpochMs || nowEpochMs >= permit.expiresAtEpochMs
        ) return null
        return ArtistBulkProfileLookupPreview(
            ArtistBulkProfileProvider.MUSICBRAINZ_ARTIST_BY_MBID, mbid,
            permit.sessionId, permit.fingerprint, permit.permitId, permit.revision,
            current, permit.expiresAtEpochMs,
        )
    }
}
