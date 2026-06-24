package org.michimusic.mobile.library.coverflow

data class CoverFlowAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val year: Int,
    val trackCount: Int,
    val hasArt: Boolean = true,
)
