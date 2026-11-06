package org.michimusic.mobile.ui.screens

import org.michimusic.mobile.artist.artwork.ArtistArtworkSelection
import org.michimusic.mobile.artist.artwork.ArtworkLoadStatus

/**
 * The hero must never show a persisted selection whose identity does not match the
 * profile currently rendered. State and Room are loaded by independent effects, so a
 * fallback is only valid when it carries the same confirmed MBID.
 */
internal fun selectEffectiveHeroArtwork(
    profileMbid: String?,
    fromController: ArtistArtworkSelection?,
    fromRoom: ArtistArtworkSelection?,
): ArtistArtworkSelection? {
    val expected = profileMbid?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    fun valid(selection: ArtistArtworkSelection?): ArtistArtworkSelection? =
        selection?.takeIf {
            it.status == ArtworkLoadStatus.READY &&
                it.confirmedMbid.equals(expected, ignoreCase = true)
        }
    // Evaluate each candidate independently: an invalid controller value must not
    // shadow a valid persisted Room value for the same identity.
    return valid(fromController) ?: valid(fromRoom)
}
