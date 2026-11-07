package org.michimusic.mobile.ui.screens

/** All three library album presentations are selectable from a single control. */
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
