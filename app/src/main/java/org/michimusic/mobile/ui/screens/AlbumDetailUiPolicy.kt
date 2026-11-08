package org.michimusic.mobile.ui.screens

internal enum class AlbumDetailModal { MENU, QUALITY, HISTORY, PLAYLIST }

internal enum class AlbumMenuAction { QUALITY, HISTORY, CLOSE }

internal fun albumModalForMenuAction(action: AlbumMenuAction): AlbumDetailModal? = when (action) {
    AlbumMenuAction.QUALITY -> AlbumDetailModal.QUALITY
    AlbumMenuAction.HISTORY -> AlbumDetailModal.HISTORY
    AlbumMenuAction.CLOSE -> null
}

internal fun albumDetailTrackHeading(): String = "Pistas"
