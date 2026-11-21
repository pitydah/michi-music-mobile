package org.michimusic.data.repository

import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataFieldObservation
import org.michimusic.core.metadata.MetadataFieldReadReport
import org.michimusic.core.metadata.MetadataObservationIssue
import org.michimusic.core.metadata.MetadataReadFieldCatalog

/**
 * ME-04 READ. Reconcile two decoders of the SAME authorized cold descriptor:
 * the legacy cache-equivalence reader and the strict field-completeness parser.
 * No display data is ever promoted to physical evidence. A disagreement is NOT absence.
 */
internal object MetadataEditorFieldEvidenceMerge {
    data class Result(
        val known: Map<MetadataField, List<String>>,
        val verified: Set<MetadataField>,
        val observations: Map<MetadataField, MetadataFieldObservation>,
    )

    fun merge(legacy: Map<MetadataField, List<String>>,
              report: MetadataFieldReadReport?): Result {
        if (report == null) {
            // Nonseekable SAF fallback / older format: positive-only evidence, plus
            // the original genre-only NoGenreTag policy, with NO general absence proof.
            return Result(legacy.toMap(), legacy.keys + MetadataField.GENRE, emptyMap())
        }
        val all = legacy.toMutableMap()
        val verified = legacy.keys.filterTo(mutableSetOf()) {
            it !in MetadataReadFieldCatalog.primary
        }
        val findings = linkedMapOf<MetadataField, MetadataFieldObservation>()
        for (field in MetadataReadFieldCatalog.primary) {
            val physical = report.observation(field)
            val cache = legacy[field].orEmpty()
            val resolved = when (physical) {
                is MetadataFieldObservation.Present -> {
                    val candidates = physical.values
                    val agrees = when {
                        // Strict reader can expose v2.4 multivalues the legacy reader skipped.
                        cache.isEmpty() -> true
                        field in setOf(MetadataField.ARTIST, MetadataField.ALBUM_ARTIST,
                            MetadataField.COMPOSER) -> cache.firstOrNull() == candidates.firstOrNull()
                        else -> cache == candidates
                    }
                    if (agrees) physical else MetadataFieldObservation.Conflicted(
                        MetadataObservationIssue.LEGACY_READER_DISAGREES)
                }
                MetadataFieldObservation.VerifiedAbsent -> {
                    if (cache.isEmpty()) physical else MetadataFieldObservation.Conflicted(
                        MetadataObservationIssue.LEGACY_READER_DISAGREES)
                }
                else -> physical
            }
            findings[field] = resolved
            when (resolved) {
                is MetadataFieldObservation.Present -> {
                    all[field] = resolved.values.toList(); verified += field
                }
                MetadataFieldObservation.VerifiedAbsent -> {
                    all.remove(field); verified += field
                }
                else -> all.remove(field) // cannot edit a guessed/display value
            }
        }
        return Result(all.toMap(), verified.toSet(), findings.toMap())
    }
}
