package org.michimusic.data.repository

const val DEFAULT_LOCAL_CATALOG_SOURCE = "device_music_root"

/** Data comes only from a successful existing catalog read, never a second media query. */
data class LocalCatalogRow(
    val trackId: String,
    val albumKey: String,
    val creditedArtist: String,
    /** Unverified MBID evidence from an ALREADY-performed metadata read only. */
    val observedArtistMbids: List<String> = emptyList(),
    val observedAlbumArtistMbids: List<String> = emptyList(),
    val observedRecordingMbids: List<String> = emptyList(),
    val observedReleaseMbids: List<String> = emptyList(),
    /** True only when this track was parsed successfully in the existing read. */
    val structuredTagsRead: Boolean = false,
)

data class LocalCatalogObservation(
    val sourceId: String,
    val rows: List<LocalCatalogRow>,
)

/** Existing read only, one source-specific track. Never grants an identity binding. */
data class LocalStructuredTagEvidence(
    val trackId: String,
    val artistMbids: List<String> = emptyList(),
    val albumArtistMbids: List<String> = emptyList(),
    val recordingMbids: List<String> = emptyList(),
    val releaseMbids: List<String> = emptyList(),
)

/** Post-query, outside the repository cache mutex. Non-authoritative, best-effort handoff. */
fun interface LocalCatalogListener {
    suspend fun onCatalogRead(observation: LocalCatalogObservation)
    /** SAF callback is invoked only AFTER its authorized index transaction commits. */
    suspend fun onStructuredTagsIndexed(sourceId: String, tags: List<LocalStructuredTagEvidence>) {}
    fun isEnabled(): Boolean = true
}
