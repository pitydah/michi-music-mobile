package org.michimusic.mobile.ui.screens

/** Preserve default and original order; Cards is an additive fourth presentation. */
enum class LibraryViewMode { LIST, MOSAIC, COVERFLOW, CARDS }

/** Keep the order stable: the UI and TalkBack describe the next, not the current, action. */
fun nextAlbumViewMode(current: LibraryViewMode): LibraryViewMode = when (current) {
    LibraryViewMode.MOSAIC -> LibraryViewMode.COVERFLOW
    LibraryViewMode.COVERFLOW -> LibraryViewMode.CARDS
    LibraryViewMode.CARDS -> LibraryViewMode.LIST
    LibraryViewMode.LIST -> LibraryViewMode.MOSAIC
}

fun albumViewModeLabel(mode: LibraryViewMode): String = when (mode) {
    LibraryViewMode.LIST -> "lista"
    LibraryViewMode.MOSAIC -> "mosaico"
    LibraryViewMode.COVERFLOW -> "CoverFlow"
    LibraryViewMode.CARDS -> "tarjetas"
}
