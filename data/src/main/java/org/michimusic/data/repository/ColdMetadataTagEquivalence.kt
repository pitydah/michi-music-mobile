package org.michimusic.data.repository

import org.michimusic.data.cache.EmbeddedTagEvidence

/**
 * Detects stale extended tags despite a provider recycling an unchanged size/mtime.
 * Called only after an explicit user request to open details and a bounded tag read.
 * This proves current tag equivalence, NOT full audio byte-for-byte identity.
 */
internal object ColdMetadataTagEquivalence {
    fun matches(indexed: EmbeddedTagEvidence, current: EmbeddedTagFields): Boolean {
        val observed = TagEvidencePolicy.record(
            indexed.sourceId, indexed.trackId, indexed.sizeBytes, indexed.modifiedAt, current)
        return observed.genre == indexed.genre && observed.genreValues == indexed.genreValues &&
            observed.title == indexed.title && observed.artist == indexed.artist &&
            observed.album == indexed.album && observed.albumArtist == indexed.albumArtist &&
            observed.composer == indexed.composer && observed.date == indexed.date &&
            observed.trackNumber == indexed.trackNumber && observed.trackTotal == indexed.trackTotal &&
            observed.discNumber == indexed.discNumber && observed.discTotal == indexed.discTotal
    }
}
