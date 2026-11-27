package org.michimusic.data.artist

/**
 * D26/S4-G2. Parse strictly bounded *observations* prepared for future use.
 * This classifier DOES NOT authenticate the transport and cannot persist a
 * D25 VERIFIED_PROFILE or NO_MATCH terminal. No bytes or profile are stored.
 */
enum class ArtistBulkProviderObservationKind {
    IDENTITY_MATCH_UNTRUSTED,
    NOT_FOUND_UNTRUSTED,
    RETRYABLE_FAILURE,
    RATE_LIMITED,
    ACCESS_DENIED,
    INVALID_RESPONSE,
    STALE_SCOPE,
}

data class ArtistBulkProviderObservation(
    val provider: ArtistBulkProfileProvider,
    val httpStatus: Int,
    val contentType: String?,
    val responseByteCount: Long,
    val parsedArtistMbid: String?,
    val observedAtEpochMs: Long,
    val retryAfterHeader: String? = null,
)

data class ArtistBulkProviderAssessment(
    val kind: ArtistBulkProviderObservationKind,
    val retryAtEpochMs: Long? = null,
) {
    val mayPersistVerifiedProfile: Boolean get() = false
    val mayModifyArtwork: Boolean get() = false
}

object ArtistBulkProviderResultPolicy {
    const val MAX_BODY_BYTES = 256 * 1024L

    fun classify(
        preview: ArtistBulkProfileLookupPreview,
        observation: ArtistBulkProviderObservation,
        currentScope: ArtistBulkTerminalScope,
        nowEpochMs: Long,
        attempt: Int,
        lastRequestAtEpochMs: Long?,
    ): ArtistBulkProviderAssessment {
        fun invalid() = ArtistBulkProviderAssessment(ArtistBulkProviderObservationKind.INVALID_RESPONSE)
        if (preview.mayDispatch || observation.provider != preview.provider ||
            nowEpochMs <= 0 || observation.observedAtEpochMs <= 0 ||
            observation.observedAtEpochMs > nowEpochMs || observation.observedAtEpochMs > preview.expiresAtEpochMs ||
            observation.responseByteCount < 0 || observation.responseByteCount > MAX_BODY_BYTES ||
            attempt !in 1..ArtistBulkProviderRetryPolicy.MAX_ATTEMPTS
        ) return invalid()
        if (!currentScope.isValid() || currentScope != preview.expectedScope ||
            nowEpochMs >= preview.expiresAtEpochMs
        ) return ArtistBulkProviderAssessment(ArtistBulkProviderObservationKind.STALE_SCOPE)
        val status = observation.httpStatus
        if (status == 429) {
            val retry = ArtistBulkProviderRetryPolicy.nextRetryAt(
                nowEpochMs, attempt, lastRequestAtEpochMs,
                observation.retryAfterHeader,
            )?.takeIf { it < preview.expiresAtEpochMs }
            return ArtistBulkProviderAssessment(ArtistBulkProviderObservationKind.RATE_LIMITED, retry)
        }
        if (status == 408 || status in 500..599) {
            return ArtistBulkProviderAssessment(ArtistBulkProviderObservationKind.RETRYABLE_FAILURE,
                ArtistBulkProviderRetryPolicy.nextRetryAt(nowEpochMs, attempt, lastRequestAtEpochMs, null)
                    ?.takeIf { it < preview.expiresAtEpochMs })
        }
        if (status == 401 || status == 403) {
            return ArtistBulkProviderAssessment(ArtistBulkProviderObservationKind.ACCESS_DENIED)
        }
        if (status == 404 && observation.responseByteCount <= MAX_BODY_BYTES) {
            // NOT a network-authenticated terminal. A future transport bridge will decide.
            return ArtistBulkProviderAssessment(ArtistBulkProviderObservationKind.NOT_FOUND_UNTRUSTED)
        }
        if (status != 200 || observation.contentType?.substringBefore(';')?.trim()
                ?.equals("application/json", ignoreCase = true) != true ||
            observation.responseByteCount == 0L ||
            observation.parsedArtistMbid?.lowercase(java.util.Locale.ROOT) != preview.mbid
        ) return invalid()
        return ArtistBulkProviderAssessment(ArtistBulkProviderObservationKind.IDENTITY_MATCH_UNTRUSTED)
    }
}
