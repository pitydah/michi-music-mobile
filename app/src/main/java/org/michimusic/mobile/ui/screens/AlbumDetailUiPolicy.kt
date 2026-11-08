package org.michimusic.mobile.ui.screens

internal enum class AlbumDetailModal { MENU, QUALITY, HISTORY, PLAYLIST }

internal enum class AlbumMenuAction { QUALITY, HISTORY, CLOSE }

internal fun albumModalForMenuAction(action: AlbumMenuAction): AlbumDetailModal? = when (action) {
    AlbumMenuAction.QUALITY -> AlbumDetailModal.QUALITY
    AlbumMenuAction.HISTORY -> AlbumDetailModal.HISTORY
    AlbumMenuAction.CLOSE -> null
}

internal fun albumDetailTrackHeading(): String = "Pistas"

internal data class AlbumPlaylistActionState(
    val enabled: Boolean,
    val unavailableMessage: String?,
)

internal fun albumPlaylistActionState(
    totalTrackCount: Int,
    eligibleTrackCount: Int,
): AlbumPlaylistActionState = AlbumPlaylistActionState(
    enabled = eligibleTrackCount > 0,
    unavailableMessage = if (totalTrackCount > 0 && eligibleTrackCount == 0) {
        "No hay pistas disponibles para añadir a una lista en esta biblioteca."
    } else {
        null
    },
)
