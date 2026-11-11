package org.michimusic.player

import androidx.media3.common.MediaItem

/**
 * Strong identity for one artwork request. A track ID alone is not enough:
 * the same ID can exist in two libraries, and the same ID can appear again
 * after a queue reorder.
 */
internal data class ArtworkOwner(
    val sourceId: String,
    val trackId: String,
    val canonicalUri: String,
    val queueGeneration: Long,
    val position: Int,
)

/**
 * Checks the media item that is actually at the captured position. The extras
 * are written by PlaybackMediaMetadata; missing extras fail closed instead of
 * allowing a cover from a different owner.
 */
internal fun MediaItem.belongsTo(owner: ArtworkOwner): Boolean {
    val metadata = mediaMetadata
    val itemSource = metadata.extras?.getString(PlaybackMediaMetadata.EXTRA_SOURCE_ID) ?: return false
    val itemTrack = metadata.extras?.getString(PlaybackMediaMetadata.EXTRA_TRACK_ID) ?: return false
    if (itemSource != owner.sourceId || itemTrack != owner.trackId) return false
    val mediaTrack = LibraryProvider.extractSongId(mediaId)?.toString()
    if (mediaId != owner.trackId && mediaTrack != owner.trackId) return false
    val itemUri = localConfiguration?.uri?.toString().orEmpty()
    return itemUri == owner.canonicalUri
}
