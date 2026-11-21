package org.michimusic.core.metadata

/**
 * ME-10T: post-provider-write observation of the ORIGINAL document, not the
 * private stage. This compares all explicitly edited field values to their
 * journal-bound digests and does not infer absent values from a parser miss.
 * It cannot issue a SAVED/index-publication receipt.
 */
object MetadataPostSourceFieldPolicy {
    enum class Refusal {
        INTENT_UNTRUSTED, CONTAINER_MISMATCH, PARSER_UNTRUSTED,
        CHANGED_FIELD_UNVERIFIABLE, CHANGED_FIELD_MISMATCH, INVALID_FIELD_OBSERVATION,
    }
    sealed interface Verdict {
        data class Compared(val verifiedChanges: Int) : Verdict {
            val indexPublicationAllowed: Boolean get() = false
        }
        data class Blocked(val reason: Refusal, val field: MetadataField? = null) : Verdict
    }
    fun compare(receipt: MetadataCommitIntentManifest.Receipt,
                source: MetadataFieldReadReport): Verdict {
        if (!MetadataCommitIntentManifest.valid(receipt))
            return Verdict.Blocked(Refusal.INTENT_UNTRUSTED)
        val format = when (receipt.container) {
            MetadataAudioContainer.ID3_V23 -> MetadataPhysicalContainer.ID3_V23
            MetadataAudioContainer.ID3_V24 -> MetadataPhysicalContainer.ID3_V24
            MetadataAudioContainer.FLAC -> MetadataPhysicalContainer.FLAC
            MetadataAudioContainer.WAV -> MetadataPhysicalContainer.WAV
            MetadataAudioContainer.AIFF -> MetadataPhysicalContainer.AIFF
            MetadataAudioContainer.OGG_OPUS -> MetadataPhysicalContainer.OGG_OPUS
            MetadataAudioContainer.OGG_VORBIS -> MetadataPhysicalContainer.OGG_VORBIS
            MetadataAudioContainer.MP4_M4A -> MetadataPhysicalContainer.MP4_M4A
            else -> return Verdict.Blocked(Refusal.CONTAINER_MISMATCH)
        }
        if (source.container != format) return Verdict.Blocked(Refusal.CONTAINER_MISMATCH)
        if (source.parserVersion != MetadataReadFieldCatalog.PARSER_VERSION)
            return Verdict.Blocked(Refusal.PARSER_UNTRUSTED)
        for (change in receipt.changes) {
            val values = when (val observed = source.observation(change.field)) {
                is MetadataFieldObservation.Present -> observed.values
                MetadataFieldObservation.VerifiedAbsent -> emptyList()
                is MetadataFieldObservation.Conflicted, is MetadataFieldObservation.Unverifiable ->
                    return Verdict.Blocked(Refusal.CHANGED_FIELD_UNVERIFIABLE, change.field)
            }
            try {
                if (values.size != change.afterCount ||
                    MetadataCommitIntentManifest.valuesDigest(values) != change.afterSha256)
                    return Verdict.Blocked(Refusal.CHANGED_FIELD_MISMATCH, change.field)
            } catch (_: IllegalArgumentException) {
                return Verdict.Blocked(Refusal.INVALID_FIELD_OBSERVATION, change.field)
            }
        }
        return Verdict.Compared(receipt.changes.size)
    }
}
