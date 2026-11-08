package org.michimusic.mobile.ui.screens

/** Mosaic stays the default; CoverFlow and List are equal explicit alternatives. */
enum class LibraryViewMode { LIST, MOSAIC, COVERFLOW }

/** Keep the order stable: the UI and TalkBack describe the next, not the current, action. */
fun nextAlbumViewMode(current: LibraryViewMode): LibraryViewMode = when (current) {
    LibraryViewMode.MOSAIC -> LibraryViewMode.COVERFLOW
    LibraryViewMode.COVERFLOW -> LibraryViewMode.LIST
    LibraryViewMode.LIST -> LibraryViewMode.MOSAIC
}

fun albumViewModeLabel(mode: LibraryViewMode): String = when (mode) {
    LibraryViewMode.LIST -> "lista"
    LibraryViewMode.MOSAIC -> "mosaico"
    LibraryViewMode.COVERFLOW -> "CoverFlow"
}
