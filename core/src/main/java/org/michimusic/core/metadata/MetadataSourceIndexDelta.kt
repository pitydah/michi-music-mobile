package org.michimusic.core.metadata

/**
 * ME-10V: immutable, structured Room-reconciliation *proposal* derived from
 * a freshly re-read, exact candidate source. It never writes a cache or infers
 * unsupported metadata from its absence in a decoder. No field is flattened.
 */
object MetadataSourceIndexDelta {
    data class Delta(
        val target: MetadataTarget,
        val transactionId: String,
        val sourceSha256: String,
        val observedValues: Map<MetadataField, List<String>>,
        /** Fields not parseable by this reader must not be treated as empty. */
        val untouchedUnknown: Set<MetadataField>,
        /** Exact candidate length from independent provider cold readback. */
        val candidateBytes: Long = 0L,
    ) {
        val authorizedForRoom: Boolean get() = false
    }
    enum class Reason {
        INVALID_INTENT, WRONG_OWNER, WRONG_SOURCE_HASH,
        SOURCE_FIELDS_NOT_VERIFIED, UNCHANGED_FIELD_DRIFT,
        INVALID_RECONCILIATION,
    }
    sealed interface Result {
        data class PreparedForExternalCas(val delta: Delta) : Result
        data class Blocked(val reason: Reason, val field: MetadataField? = null) : Result
    }

    fun propose(original: MetadataSnapshot,
                intent: MetadataCommitIntentManifest.Receipt,
                actualSha256: String,
                source: MetadataFieldReadReport,
                candidateBytes: Long = 0L): Result {
        if (!MetadataCommitIntentManifest.valid(intent))
            return Result.Blocked(Reason.INVALID_INTENT)
        if (MetadataPrivateSessionIdentity.digest(original.target) != intent.ownerSha256)
            return Result.Blocked(Reason.WRONG_OWNER)
        if (actualSha256 != intent.candidateSha256)
            return Result.Blocked(Reason.WRONG_SOURCE_HASH)
        if (MetadataPostSourceFieldPolicy.compare(intent,source)
            !is MetadataPostSourceFieldPolicy.Verdict.Compared)
            return Result.Blocked(Reason.SOURCE_FIELDS_NOT_VERIFIED)
        val changes=intent.changes.mapTo(hashSetOf()) { it.field }
        // A described-text resource is verified by its exact identity digest in
        // MetadataResourceSemanticPolicy.compareCandidate, not by the scalar
        // alias reader. Its field may therefore legitimately gain, change or
        // lose a scalar alias without that being unselected drift.
        val resourceFields=intent.resources.mapNotNullTo(hashSetOf()) {
            (it as? MetadataCommitIntentManifest.ResourceChange.DescribedText)?.kind?.field
        }
        val values=linkedMapOf<MetadataField,List<String>>()
        val unknown=linkedSetOf<MetadataField>()
        // Only compare already-proven fields from the ORIGINAL snapshot; a field
        // the source reader does not understand is never guessed or deleted.
        for (field in (original.verifiedFields + source.observations.keys).sortedBy { it.name }) {
            when (val observed=source.observation(field)) {
                is MetadataFieldObservation.Present -> values[field]=observed.values.toList()
                MetadataFieldObservation.VerifiedAbsent -> values[field]=emptyList()
                is MetadataFieldObservation.Conflicted, is MetadataFieldObservation.Unverifiable -> {
                    if (field in changes || (field in original.verifiedFields && field !in resourceFields))
                        return Result.Blocked(Reason.INVALID_RECONCILIATION,field)
                    unknown += field
                }
            }
            if (field !in changes && field !in resourceFields && field in original.verifiedFields &&
                values[field] != original.known[field].orEmpty())
                return Result.Blocked(Reason.UNCHANGED_FIELD_DRIFT,field)
        }
        if (!values.keys.containsAll(changes))
            return Result.Blocked(Reason.SOURCE_FIELDS_NOT_VERIFIED)
        return Result.PreparedForExternalCas(Delta(original.target,intent.transactionId,
            actualSha256,values.toMap(),unknown.toSet(),candidateBytes))
    }
}
