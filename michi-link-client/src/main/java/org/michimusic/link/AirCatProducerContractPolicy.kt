package org.michimusic.link

/**
 * A64/A65: explicit producer representation negotiation at the pinned HTTP
 * boundary, not inferred from a 200 response or a legacy /download route.
 * The canonical header is part of the cross-repository handoff to Micro.
 */
internal object AirCatProducerContractPolicy {
    const val HEADER = "X-Michi-AirCat-Protocol"
    const val VERSION = "michi.aircat.original.v1"

    enum class Phase { MANIFEST, AUTHORIZE, ORIGINAL }

    /** Single values only. Metadata must not be a cached or transformed grant. */
    fun refusal(
        phase: Phase,
        protocolHeaders: List<String>?,
        contentTypes: List<String>?,
        encodings: List<String>?,
        cacheControls: List<String>?,
    ): String? {
        if (protocolHeaders?.size != 1 || protocolHeaders.single() != VERSION)
            return "AIRC_PRODUCER_PROFILE_NOT_ADVERTISED"
        if ((encodings?.size ?: 0) > 1 ||
            encodings?.any { !it.equals("identity", ignoreCase = true) } == true)
            return "AIRC_PRODUCER_ENCODING_TRANSFORMED"
        if (contentTypes?.size != 1) return "AIRC_PRODUCER_CONTENT_TYPE_REQUIRED"
        val mediaType = contentTypes.single().substringBefore(';').trim().lowercase()
        if (phase == Phase.ORIGINAL) {
            if (mediaType != "application/octet-stream") return "AIRC_ORIGINAL_CONTENT_TYPE"
        } else {
            if (mediaType != "application/json") return "AIRC_METADATA_CONTENT_TYPE"
            // An authorization reply must never be stored by an HTTP cache.
            // A manifest may be re-requested, but still must be non-cacheable
            // because Home member state and source revisions can change.
            val directives = cacheControls.orEmpty()
                .flatMap { it.split(',') }.map { it.trim().lowercase() }
            if ("no-store" !in directives) return "AIRC_METADATA_NO_STORE_REQUIRED"
        }
        return null
    }
}
