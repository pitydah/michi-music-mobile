package org.michimusic.mobile.ui.screens

/** The album library keeps one visual primary view and one compact alternative. */
enum class LibraryViewMode { LIST, MOSAIC }

/** Keep the order stable: the UI and TalkBack describe the next, not the current, action. */
fun nextAlbumViewMode(current: LibraryViewMode): LibraryViewMode = when (current) {
    LibraryViewMode.MOSAIC -> LibraryViewMode.LIST
    LibraryViewMode.LIST -> LibraryViewMode.MOSAIC
}

fun albumViewModeLabel(mode: LibraryViewMode): String = when (mode) {
    LibraryViewMode.LIST -> "lista"
    LibraryViewMode.MOSAIC -> "mosaico"
}
