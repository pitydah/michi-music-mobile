package org.michimusic.data.cache

import org.michimusic.core.models.Track

/**
 * Mapping from the cache entity to the domain model.
 *
 * The cache entity belongs to this module, so the projection lives here instead of
 * inside one repository: the player module needs the same conversion to read the
 * original file facts for Signal Truth, and a private helper in
 * `PlaylistRepository` was invisible outside its own file.
 */
fun CachedTrack.toTrack(): Track = Track(
    id = id,
    title = title,
    artist = artist,
    album = album,
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
)
