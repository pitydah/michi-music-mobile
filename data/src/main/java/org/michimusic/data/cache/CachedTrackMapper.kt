package org.michimusic.data.cache

import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource

/**
 * Mapping from the cache entity to the domain model.
 *
 * The cache entity belongs to this module, so the projection lives here instead of
 * inside one repository: the player module needs the same conversion to read the
 * original file facts for Signal Truth, and a private helper in
 * `PlaylistRepository` was invisible outside its own file.
 *
 * `sourceId` (owner) and `TrackSource` (where the bytes live) are preserved as
 * independent dimensions: a SAF root is still local, and a synced track can be
 * physically present while remaining owned by its paired server.
 */
fun CachedTrack.toTrack(): Track = Track(
    id = id,
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    duration = duration,
    size = size,
    format = format,
    bitrate = bitrate,
    sampleRate = sampleRate,
    channels = channels,
    coverId = coverId,
    trackNumber = trackNumber,
    year = year,
    filepath = filepath,
    source = when {
        // Owner scope wins over the legacy `downloaded` flag: historical SAF
        // rows were stored with downloaded=true and must stay local.
        isLocalLibrarySource(sourceId) -> TrackSource.LOCAL
        downloaded -> TrackSource.SYNCED
        sourceId.isNotEmpty() -> TrackSource.STREAMING
        else -> TrackSource.LOCAL
    },
    sourceId = sourceId,
)

/**
 * Local library scopes use the stable prefixes created by the SAF importer and
 * the MediaStore exclusion policy. Remote peers use their own device IDs.
 */
fun isLocalLibrarySource(sourceId: String): Boolean =
    sourceId.startsWith("safroot_") || sourceId.startsWith("mediastore:")
