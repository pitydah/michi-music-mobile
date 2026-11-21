package org.michimusic.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import org.koin.compose.koinInject
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.data.cache.MetadataPeopleDisplayTarget
import org.michimusic.data.cache.MetadataPeopleIndexLabel
import org.michimusic.data.cache.MetadataPeoplePresentationPolicy
import org.michimusic.data.cache.MetadataPersonEvidenceDao

private fun Track.peopleTarget() = MetadataPeopleDisplayTarget(
    sourceId = sourceId, trackId = id, uri = filepath, size = size,
    local = source == TrackSource.LOCAL,
)

/** Exactly ONE bounded Flow for a visible single-owner album. No per-row query,
 * scanner, MediaStore access, or file descriptor. A changed selection resets the
 * previous indexed labels before collecting new values. */
@Composable
internal fun rememberIndexedAlbumPeople(tracks: List<Track>): Map<String, MetadataPeopleIndexLabel> {
    val dao: MetadataPersonEvidenceDao = koinInject()
    val labels by produceState<Map<String, MetadataPeopleIndexLabel>>(
        initialValue = emptyMap(), key1 = tracks,
    ) {
        value = emptyMap()
        if (tracks.isEmpty() || tracks.size > 128 ||
            tracks.any { it.source != TrackSource.LOCAL || !it.filepath.startsWith("content://") ||
                it.size <= 0L || it.id.isBlank() } ||
            tracks.map { it.sourceId }.distinct().size != 1 ||
            tracks.map { it.id }.distinct().size != tracks.size) return@produceState
        val sourceId = tracks.first().sourceId
        val byId = tracks.associateBy { it.id }
        try {
            dao.observeIndexedForAlbum(sourceId, tracks.map { it.id }).collect { rows ->
                value = rows.mapNotNull { row ->
                    val current = byId[row.evidence.trackId] ?: return@mapNotNull null
                    MetadataPeoplePresentationPolicy.forTarget(row, current.peopleTarget())
                        ?.let { row.evidence.trackId to it }
                }.toMap()
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { value = emptyMap() }
    }
    return labels
}

/** Only the currently selected local URI gets an observer. Queue/Media3 objects
 * remain immutable: this is presentation of an indexed sidecar, never mutation. */
@Composable
internal fun rememberIndexedNowPlayingPeople(
    track: Track?, isRemoteSession: Boolean,
): MetadataPeopleIndexLabel? {
    val dao: MetadataPersonEvidenceDao = koinInject()
    val indexed by produceState<MetadataPeopleIndexLabel?>(
        initialValue = null, key1 = track, key2 = isRemoteSession,
    ) {
        value = null
        val current = track ?: return@produceState
        if (isRemoteSession || current.source != TrackSource.LOCAL ||
            !current.filepath.startsWith("content://") || current.size <= 0L) return@produceState
        try {
            dao.observeIndexedForTrack(current.sourceId, current.id).collect { row ->
                value = MetadataPeoplePresentationPolicy.forTarget(row, current.peopleTarget())
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { value = null }
    }
    return indexed
}

/** Never infer album-wide credits from one song; no cross-owner substitution. */
internal fun indexedAlbumArtistLabel(
    tracks: List<Track>, labels: Map<String, MetadataPeopleIndexLabel>,
): String? = MetadataPeoplePresentationPolicy.commonAlbumArtists(tracks.map { it.id }, labels)
