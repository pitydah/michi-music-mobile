package org.michimusic.core.metadata

/**
 * ME-10O: compares two independently decoded PRIVATE documents to the exact
 * field-value intent saved in journal V1. This is NOT an opaque-frame / audio
 * preservation certificate, nor permission to mutate a provider.
 *
 * A field the cold reader cannot prove absent is never equivalent to an empty
 * list. Non-edited observable fields must remain identical, including the
 * position of repeated values. No string splitting or normalisation occurs.
 */
object MetadataCandidateSemanticPolicy {
    enum class Refusal {
        INVALID_INTENT, PARSER_VERSION_UNTRUSTED, CONTAINER_MISMATCH,
        ORIGINAL_CHANGED_FIELD_UNVERIFIABLE, CANDIDATE_CHANGED_FIELD_UNVERIFIABLE,
        ORIGINAL_INTENT_MISMATCH, CANDIDATE_INTENT_MISMATCH,
        UNEDITED_FIELD_UNVERIFIABLE, UNEDITED_FIELD_CHANGED,
        INVALID_OBSERVATION,
    }

    sealed interface Verdict {
        /** A readback of supported, structured tags ONLY. No physical-write grant. */
        data class ObservedFieldsMatch(val comparedUneditedFields: Int) : Verdict {
            val sourceWriteCertified: Boolean get() = false
            val unknownTagPreservationCertified: Boolean get() = false
        }
        data class Refused(val reason: Refusal, val field: MetadataField? = null) : Verdict
    }

    private fun containerOf(format: MetadataAudioContainer): MetadataPhysicalContainer? = when (format) {
        MetadataAudioContainer.FLAC -> MetadataPhysicalContainer.FLAC
        MetadataAudioContainer.WAV -> MetadataPhysicalContainer.WAV
        MetadataAudioContainer.AIFF -> MetadataPhysicalContainer.AIFF
        MetadataAudioContainer.OGG_OPUS -> MetadataPhysicalContainer.OGG_OPUS
        MetadataAudioContainer.OGG_VORBIS -> MetadataPhysicalContainer.OGG_VORBIS
        MetadataAudioContainer.MP4_M4A -> MetadataPhysicalContainer.MP4_M4A
        MetadataAudioContainer.ID3_V23 -> MetadataPhysicalContainer.ID3_V23
        MetadataAudioContainer.ID3_V24 -> MetadataPhysicalContainer.ID3_V24
        else -> null
    }

    /** null means *unverifiable*, not physically absent. */
    private fun observedValues(report: MetadataFieldReadReport, field: MetadataField): List<String>? =
        when (val observation = report.observation(field)) {
            is MetadataFieldObservation.Present -> observation.values
            MetadataFieldObservation.VerifiedAbsent -> emptyList()
            is MetadataFieldObservation.Conflicted, is MetadataFieldObservation.Unverifiable -> null
        }

    fun compare(
        intent: MetadataCommitIntentManifest.Receipt,
        original: MetadataFieldReadReport,
        candidate: MetadataFieldReadReport,
    ): Verdict {
        if (!MetadataCommitIntentManifest.valid(intent))
            return Verdict.Refused(Refusal.INVALID_INTENT)
        val expected = containerOf(intent.container)
            ?: return Verdict.Refused(Refusal.INVALID_INTENT)
        if (original.container != expected || candidate.container != expected)
            return Verdict.Refused(Refusal.CONTAINER_MISMATCH)
        // Parser versions are part of evidence. A historical decoder cannot
        // certify that it observed every field of a newer staging engine.
        if (original.parserVersion != MetadataReadFieldCatalog.PARSER_VERSION ||
            candidate.parserVersion != MetadataReadFieldCatalog.PARSER_VERSION)
            return Verdict.Refused(Refusal.PARSER_VERSION_UNTRUSTED)

        try {
            for (change in intent.changes) {
                val before = observedValues(original, change.field)
                    ?: return Verdict.Refused(Refusal.ORIGINAL_CHANGED_FIELD_UNVERIFIABLE, change.field)
                val after = observedValues(candidate, change.field)
                    ?: return Verdict.Refused(Refusal.CANDIDATE_CHANGED_FIELD_UNVERIFIABLE, change.field)
                if (before.size != change.beforeCount ||
                    MetadataCommitIntentManifest.valuesDigest(before) != change.beforeSha256)
                    return Verdict.Refused(Refusal.ORIGINAL_INTENT_MISMATCH, change.field)
                if (after.size != change.afterCount ||
                    MetadataCommitIntentManifest.valuesDigest(after) != change.afterSha256)
                    return Verdict.Refused(Refusal.CANDIDATE_INTENT_MISMATCH, change.field)
            }
            val editedFields = intent.changes.mapTo(hashSetOf()) { it.field }
            // Include all fields known to the reader AND any extra observation
            // supplied by either decoder. An omitted observation is unknown.
            val unedited = (MetadataReadFieldCatalog.primary + original.observations.keys +
                candidate.observations.keys).filterNot { it in editedFields }.toSet()
            for (field in unedited.sortedBy { it.name }) {
                // RIFF/INFO only proves positive existing values. A field outside
                // this legacy chunk vocabulary remains UNKNOWN on both sides;
                // do not treat it as absent or claim physical preservation.
                // The RIFF private writer separately copies all opaque chunks
                // and verifies every unchanged top-level byte range.
                if (intent.container in setOf(MetadataAudioContainer.WAV,
                        MetadataAudioContainer.AIFF, MetadataAudioContainer.OGG_OPUS,
                        MetadataAudioContainer.OGG_VORBIS, MetadataAudioContainer.MP4_M4A)) {
                    val oldObservation = original.observation(field)
                    val newObservation = candidate.observation(field)
                    if (oldObservation is MetadataFieldObservation.Unverifiable &&
                        newObservation == oldObservation) continue
                }
                val before = observedValues(original, field)
                    ?: return Verdict.Refused(Refusal.UNEDITED_FIELD_UNVERIFIABLE, field)
                val after = observedValues(candidate, field)
                    ?: return Verdict.Refused(Refusal.UNEDITED_FIELD_UNVERIFIABLE, field)
                if (before != after)
                    return Verdict.Refused(Refusal.UNEDITED_FIELD_CHANGED, field)
            }
            return Verdict.ObservedFieldsMatch(unedited.size)
        } catch (_: IllegalArgumentException) {
            return Verdict.Refused(Refusal.INVALID_OBSERVATION)
        }
    }
}
