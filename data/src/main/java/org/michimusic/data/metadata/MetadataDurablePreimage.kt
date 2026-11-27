package org.michimusic.data.metadata

import org.michimusic.core.metadata.MetadataIndexPublicationPolicy

/**
 * The only publication authority for a selective CAS is the preimage captured
 * before the physical mutation. A live row may prove that this captured
 * preimage is still current, but it can never supply the previous values.
 */
internal fun MetadataIndexPreimageStore.durableFor(
    expected: MetadataIndexPublicationPolicy.IndexIdentity,
    permit: MetadataWritePermit?,
): MetadataIndexPreimage.Existing? {
    val scoped = permit ?: return null
    val durable = existingFor(
        scoped.transactionId, expected.sourceId, expected.trackId, expected.uri,
    ) ?: return null
    if (durable.sourceId != expected.sourceId ||
        durable.trackId != expected.trackId ||
        durable.uri != expected.uri ||
        durable.metadataRevision != expected.metadataRevision
    ) return null
    val verified = durable.verifiedAt > 0L &&
        durable.verifiedAt == expected.version &&
        durable.verifiedSize == durable.size &&
        durable.verifiedAlgorithm.equals("sha256", ignoreCase = true) &&
        durable.verifiedSha256 == expected.observedSourceSha256
    val unverified = durable.verifiedAt == 0L &&
        expected.version == 0L &&
        durable.verifiedSize == 0L &&
        durable.verifiedSha256.isEmpty() &&
        durable.verifiedAlgorithm.isEmpty() &&
        expected.observedSourceSha256.isEmpty()
    return if (verified || unverified) durable else null
}
