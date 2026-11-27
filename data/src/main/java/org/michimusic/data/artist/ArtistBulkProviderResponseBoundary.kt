package org.michimusic.data.artist

import java.security.MessageDigest
import java.util.Locale

/** D28: immutable description of a POTENTIAL GET, never a dispatch token. */
data class ArtistBulkProviderRequestDescriptor(
    val provider: ArtistBulkProfileProvider,
    val method: String,
    val pathAndQuery: String,
    val requestedMbid: String,
    val maxResponseBytes: Long,
) {
    val mayDispatch: Boolean get() = false
}

/** Input from a future verified HTTP adapter, or an isolated test. NOT transport evidence. */
data class ArtistBulkProviderRawObservation(
    val status: Int,
    val body: ByteArray,
    val contentType: String?,
    /** Historical hint only; D30 NEVER trusts this instead of the actual JSON body. */
    val parsedMbidUntrusted: String?,
    val observedAtEpochMs: Long,
    val retryAfterHeader: String? = null,
)

/** No raw HTTP body, names, paths, private URLs or tokens are stored in this summary. */
data class ArtistBulkProviderBoundedObservation(
    val observation: ArtistBulkProviderObservation,
    val assessment: ArtistBulkProviderAssessment,
    val bodySha256: String?,
    val classifiedAtEpochMs: Long,
    val attempt: Int,
    val lastRequestAtEpochMs: Long?,
) {
    val mayPersistVerifiedProfile: Boolean get() = false
    val mayDispatch: Boolean get() = false
}

/** Pure parser boundary and wire budget. No HTTP client, coroutine, or storage dependency. */
object ArtistBulkProviderResponseBoundary {
    const val HTTP_METHOD = "GET"
    const val MAX_CT_CHARS = 96
    const val MAX_RETRY_HEADER_CHARS = 64
    private val mbidPattern = Regex("^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$")

    fun describe(preview: ArtistBulkProfileLookupPreview, lease: ArtistBulkProviderLeaseRecord,
        nowEpochMs: Long): ArtistBulkProviderRequestDescriptor? {
        if (nowEpochMs <= 0 || nowEpochMs < lease.issuedAtEpochMs ||
            nowEpochMs >= minOf(preview.expiresAtEpochMs, lease.expiresAtEpochMs) ||
            preview.mayDispatch || lease.mayDispatch || !ArtistBulkProviderLeasePolicy.matches(lease, preview) ||
            !mbidPattern.matches(preview.mbid) ||
            preview.provider != ArtistBulkProfileProvider.MUSICBRAINZ_ARTIST_BY_MBID
        ) return null
        // Endpoint is fixed. No free-form URL, artist name, album, path or library ID.
        return ArtistBulkProviderRequestDescriptor(preview.provider, HTTP_METHOD,
            "/ws/2/artist/${preview.mbid}?fmt=json", preview.mbid,
            ArtistBulkProviderResultPolicy.MAX_BODY_BYTES)
    }

    fun review(
        preview: ArtistBulkProfileLookupPreview,
        lease: ArtistBulkProviderLeaseRecord,
        request: ArtistBulkProviderRequestDescriptor,
        raw: ArtistBulkProviderRawObservation,
        currentScope: ArtistBulkTerminalScope,
        nowEpochMs: Long,
        attempt: Int,
        lastRequestAtEpochMs: Long?,
    ): ArtistBulkProviderBoundedObservation? {
        if (describe(preview, lease, nowEpochMs) != request || request.mayDispatch ||
            currentScope != preview.expectedScope || !currentScope.isValid() ||
            raw.observedAtEpochMs < lease.issuedAtEpochMs || raw.observedAtEpochMs > nowEpochMs ||
            raw.body.size.toLong() > request.maxResponseBytes ||
            raw.contentType?.let { it.length > MAX_CT_CHARS || it.any(Char::isISOControl) } == true ||
            raw.retryAfterHeader?.let {
                it.length > MAX_RETRY_HEADER_CHARS || it.any(Char::isISOControl)
            } == true || raw.status !in 100..599 ||
            raw.parsedMbidUntrusted?.let { it.length > 64 || it.any(Char::isWhitespace) } == true
        ) return null
        // D30: only the real bytes, decoded as strict UTF-8 and full JSON, may provide an
        // observed id. A caller-provided hint can only DOWNGRADE a conflicting response;
        // it can never turn a missing/malformed/mismatched JSON id into a match.
        val idFromJson = if (raw.status == 200 &&
            raw.contentType?.substringBefore(';')?.trim()?.equals("application/json", true) == true
        ) ArtistBulkJsonIdentity.artistMbid(raw.body) else null
        val callerHint = raw.parsedMbidUntrusted?.lowercase(Locale.ROOT)
        val observedId = idFromJson.takeIf { callerHint == null || callerHint == it }
        val observation = ArtistBulkProviderObservation(preview.provider, raw.status, raw.contentType,
            raw.body.size.toLong(), observedId, raw.observedAtEpochMs, raw.retryAfterHeader)
        val classification = ArtistBulkProviderResultPolicy.classify(preview, observation,
            currentScope, nowEpochMs, attempt, lastRequestAtEpochMs)
        val digest = raw.body.takeIf { it.isNotEmpty() }?.let { bytes ->
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
        }
        return ArtistBulkProviderBoundedObservation(observation, classification, digest,
            nowEpochMs, attempt, lastRequestAtEpochMs)
    }
}
