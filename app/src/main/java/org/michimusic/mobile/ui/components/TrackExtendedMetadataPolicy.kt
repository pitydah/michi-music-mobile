package org.michimusic.mobile.ui.components

import org.michimusic.data.repository.ExtendedTrackMetadata

/** Rows for the opt-in, local-only track metadata panel. Never expose file URIs. */
internal data class TrackExtendedMetadataRow(val label: String, val value: String)

internal object TrackExtendedMetadataPolicy {
    private fun safeText(raw: String): String? = raw.trim().takeIf { text ->
        text.isNotEmpty() && text.length <= 2_048 && '\uFFFD' !in text &&
            text.none { it.isISOControl() }
    }

    private fun position(number: Int?, total: Int?): String? {
        val validNumber = number?.takeIf { it in 1..9_999 } ?: return null
        val validTotal = total?.takeIf { it in validNumber..9_999 }
        return if (validTotal == null) "$validNumber" else "$validNumber / $validTotal"
    }

    /** Strictly evidence-backed values; missing fields are omitted, not inferred. */
    fun rows(metadata: ExtendedTrackMetadata): List<TrackExtendedMetadataRow> = buildList {
        safeText(metadata.composer)?.let { add(TrackExtendedMetadataRow("Compositor", it)) }
        safeText(metadata.date)?.let { add(TrackExtendedMetadataRow("Fecha original", it)) }
        position(metadata.trackNumber, metadata.trackTotal)?.let {
            add(TrackExtendedMetadataRow("Pista", it))
        }
        position(metadata.discNumber, metadata.discTotal)?.let {
            add(TrackExtendedMetadataRow("Disco", it))
        }
        // A literal "Jazz; Fusion" remains one value, not two invented genres.
        // Reject the entire unsafe list instead of displaying a partial subset.
        val genres = metadata.genres
        if (genres.isNotEmpty() && genres.size <= 32 &&
            genres.sumOf(String::length) <= 8_192 && genres.all { safeText(it) != null }) {
            add(TrackExtendedMetadataRow("Género original", genres.joinToString(" · ") { it.trim() }))
        }
    }
}
