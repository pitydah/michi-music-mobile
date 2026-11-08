package org.michimusic.mobile.ui.screens

import org.michimusic.core.models.Track

internal data class AlbumTrackPosition(
    val discNumber: Int?,
    val trackNumber: Int,
)

internal data class PresentedAlbumTrack(
    val track: Track,
    val originalIndex: Int,
    val position: AlbumTrackPosition?,
)

internal data class AlbumTrackPresentation(
    val tracks: List<PresentedAlbumTrack>,
    val showDiscHeaders: Boolean,
)

internal fun decodeAlbumTrackPosition(track: Track): AlbumTrackPosition? {
    val raw = track.trackNumber
    if (raw <= 0) return null
    if (!track.trackNumberFromMediaStore) return AlbumTrackPosition(discNumber = null, trackNumber = raw)
    if (raw < MEDIASTORE_DISC_FACTOR) return AlbumTrackPosition(discNumber = 1, trackNumber = raw)

    val disc = raw / MEDIASTORE_DISC_FACTOR
    val number = raw % MEDIASTORE_DISC_FACTOR
    return if (disc in 1..MAX_DISC_NUMBER && number in 1 until MEDIASTORE_DISC_FACTOR) {
        AlbumTrackPosition(discNumber = disc, trackNumber = number)
    } else null
}

internal fun albumTrackPresentation(tracks: List<Track>): AlbumTrackPresentation {
    val presented = tracks.mapIndexed { index, track ->
        PresentedAlbumTrack(track, index, decodeAlbumTrackPosition(track))
    }.sortedWith(
        compareBy<PresentedAlbumTrack> { it.position == null }
            .thenBy { it.position?.discNumber ?: 1 }
            .thenBy { it.position?.trackNumber ?: Int.MAX_VALUE }
            .thenBy { it.originalIndex },
    )
    val explicitDiscs = presented.mapNotNull { it.position?.discNumber }.distinct()
    return AlbumTrackPresentation(presented, showDiscHeaders = explicitDiscs.size > 1)
}

/** One canonical order for every album surface and order-sensitive action. */
internal fun canonicalAlbumTracks(tracks: List<Track>): List<Track> =
    albumTrackPresentation(tracks).tracks.map(PresentedAlbumTrack::track)

/** Availability is applied after ordering so every surface retains the same sequence. */
internal fun canonicalPlayableAlbumTracks(
    tracks: List<Track>,
    playableTrackIds: Set<String>,
): List<Track> = canonicalAlbumTracks(tracks).filter { it.id in playableTrackIds }

private const val MEDIASTORE_DISC_FACTOR = 1000
private const val MAX_DISC_NUMBER = 99
