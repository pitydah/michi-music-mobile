package org.michimusic.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaMetadata
import org.michimusic.core.models.Track

/**
 * Single authority for the metadata that reaches Android Media3, System UI,
 * notifications and the lock screen.
 *
 * Artwork is not guessed from a bare numeric ID when the track belongs to a
 * remote library: a numeric `coverId` is only a MediaStore album ID for the
 * local device library. SAF and synchronized files use their embedded picture
 * (supplied by [EmbeddedArtwork]) instead.
 */
internal object PlaybackMediaMetadata {
    const val EXTRA_TRACK_ID = "michi_track_id"
    const val EXTRA_SOURCE_ID = "michi_source_id"
    const val EXTRA_ORIGIN = "michi_origin"

    fun build(track: Track, artworkBytes: ByteArray? = null): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setAlbumArtist(track.albumArtist.ifBlank { track.artist })
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setExtras(
                Bundle().apply {
                    putString(EXTRA_TRACK_ID, track.id)
                    putString(EXTRA_SOURCE_ID, track.sourceId)
                    putString(EXTRA_ORIGIN, track.source.name)
                },
            )
            .apply {
                if (track.trackNumber > 0) setTrackNumber(track.trackNumber)
                if (track.year in 1..9999) setReleaseYear(track.year)
                if (track.duration > 0) setDurationMs(track.duration)
            }

        if (artworkBytes != null && artworkBytes.isNotEmpty()) {
            builder.setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        } else {
            artworkUriFor(track)?.let(builder::setArtworkUri)
        }
        return builder.build()
    }

    /**
     * A numeric cover ID is a MediaStore album ID only for the local device
     * library. SAF roots have their own `sourceId`; synced libraries keep the
     * server owner, so their numeric IDs must never become a local album-art URI.
     */
    fun artworkUriFor(track: Track): Uri? {
        val cover = track.coverId.trim()
        if (cover.isEmpty()) return null
        if (cover.startsWith("content://") || cover.startsWith("file://")) {
            return Uri.parse(cover)
        }
        if (!isLocalDeviceLibrary(track)) return null

        val numeric = when {
            cover.all(Char::isDigit) -> cover
            cover.startsWith("mediastore:") ->
                cover.substringAfter("mediastore:").substringAfterLast(':')
            else -> null
        } ?: return null
        if (numeric.isEmpty() || numeric.toLongOrNull()?.let { it > 0 } != true) return null
        return Uri.parse("content://media/external/audio/albumart/$numeric")
    }

    private fun isLocalDeviceLibrary(track: Track): Boolean =
        track.sourceId.isEmpty() || track.sourceId.startsWith("mediastore:")
}
