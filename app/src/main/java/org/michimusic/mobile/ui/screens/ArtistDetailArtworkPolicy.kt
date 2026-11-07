package org.michimusic.mobile.ui.screens

import org.michimusic.mobile.artist.artwork.ArtistArtworkSelection
import org.michimusic.mobile.artist.artwork.ArtworkLoadStatus

/**
 * The hero must never show a persisted selection whose identity does not match the
 * profile currently rendered. State and Room are loaded by independent effects, so a
 * fallback is only valid when it carries the same confirmed MBID.
 *
 * Contract for two valid candidates: the highest persisted revision wins. Manual
 * choices and focus updates bump that revision inside the Room transaction, so a newer
 * external replacement is never shadowed by an older in-memory controller value. Ties
 * keep the controller as the live primary.
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
    val controller = valid(fromController)
    val room = valid(fromRoom)
    return when {
        controller == null -> room
        room == null -> controller
        room.revision > controller.revision -> room
        else -> controller
    }
}
