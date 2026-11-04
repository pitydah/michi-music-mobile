package org.michimusic.mobile.ui.components

import java.util.Locale
import org.michimusic.mobile.artist.ArtistProfileScope

/** Artist credits are local metadata, not verified MusicBrainz identities. */
data class ArtistMosaicEntry(
    val credit: String,
    val albumIds: List<String>,
    val albumCount: Int,
    val trackCount: Int,
    /** Validated membership scopes; used only for ALL with multiple libraries. */
    val profileScopes: List<ArtistProfileScope> = emptyList(),
)

/** No invented photographic identity: fallback is a stable typographic monogram. */
internal fun artistMosaicMonogram(credit: String): String = credit.trim()
    .split(Regex("\\s+"))
    .mapNotNull { word -> word.firstOrNull(Char::isLetter)?.uppercaseChar() }
    .take(2)
    .joinToString("")
    .ifBlank { "♫" }

/** Use larger tiles when Android accessibility font scaling grows. */
internal fun artistMosaicMinCellWidthDp(fontScale: Float): Float = when {
    fontScale >= 1.50f -> 240f
    fontScale >= 1.25f -> 168f
    else -> 132f
}

/** Deterministic order for local library credits, not a manufactured recents list. */
internal fun orderedArtistMosaicEntries(artists: List<ArtistMosaicEntry>): List<ArtistMosaicEntry> =
    artists.sortedWith(compareBy<ArtistMosaicEntry> { it.credit.lowercase(Locale.ROOT) }
        .thenBy { it.credit })
