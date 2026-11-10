package org.michimusic.mobile.ui.screens

import java.security.MessageDigest
import java.util.Locale
import java.text.Normalizer

/** UI5-only presentation. No mutations to the Playlist/Track domain objects. */
internal enum class PlaylistCatalogSort { NAME, TRACKS }
internal enum class PlaylistTrackDensity { COMPACT, EXPANDED }

internal data class PlaylistCatalogEntry(
    val id: String,
    val name: String,
    val trackCount: Int,
)

internal fun orderedPlaylistCatalog(
    entries: List<PlaylistCatalogEntry>,
    pinnedIds: Set<String>,
    sort: PlaylistCatalogSort,
): List<PlaylistCatalogEntry> {
    fun normalizedName(name: String): String = Normalizer.normalize(
        name.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD,
    ).replace(Regex("\\p{M}+"), "")
    val comparator = when (sort) {
        PlaylistCatalogSort.NAME -> compareBy<PlaylistCatalogEntry> { normalizedName(it.name) }.thenBy { it.id }
        PlaylistCatalogSort.TRACKS -> compareByDescending<PlaylistCatalogEntry> { it.trackCount }
            .thenBy { normalizedName(it.name) }.thenBy { it.id }
    }
    // Pinning only changes presentation order, never playback queue / track order.
    return entries.sortedWith(compareBy<PlaylistCatalogEntry> { it.id !in pinnedIds }.then(comparator))
}

internal data class PlaylistTrackLayout(val artworkDp: Int, val verticalPaddingDp: Int, val gapDp: Int)

internal fun playlistTrackLayout(density: PlaylistTrackDensity): PlaylistTrackLayout = when (density) {
    PlaylistTrackDensity.COMPACT -> PlaylistTrackLayout(44, 10, 6)
    PlaylistTrackDensity.EXPANDED -> PlaylistTrackLayout(56, 12, 12)
}

/** Opaque per-id key avoids preferences-key injection, separators and raw names. */
internal fun playlistVisualKey(id: String, field: String): String {
    require(id.isNotBlank())
    require(field in setOf(
        "cover", "banner", "pinned", "focal",
        "cover_focal_x", "cover_focal_y", "cover_scale",
        "banner_focal_x", "banner_focal_y", "banner_scale",
    ))
    return "ui5_playlist_${playlistIdHash(id)}_$field"
}

internal fun nextPlaylistBannerFocal(current: Float): Float = when {
    !current.isFinite() || current < 0.35f -> 0.5f
    current < 0.65f -> 0.8f
    else -> 0.2f
}

/** Corrupt input must not become a persisted NaN or invalid drawing alignment. */
internal fun safePlaylistBannerFocal(value: Float): Float =
    value.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f

/** One alignment semantic for the hero and the editor preview. */
/** Shared by the final hero and the editor preview so focal/zoom crops match. */
internal const val PLAYLIST_HERO_MIN_HEIGHT_DP = 220

/** The independent cover is a focal anchor, not a sticker; shrink only on narrow screens. */
internal fun playlistHeroCoverSizeDp(screenWidthDp: Float): Float =
    if (!screenWidthDp.isFinite()) 104f
    else if (screenWidthDp < 340f) 92f else 104f

internal fun playlistFocalAlignment(focalX: Float, focalY: Float): androidx.compose.ui.BiasAlignment =
    androidx.compose.ui.BiasAlignment(
        safePlaylistBannerFocal(focalX) * 2f - 1f,
        safePlaylistBannerFocal(focalY) * 2f - 1f,
    )

/** UI5 may release only its own grants, never grants inherited from music imports. */
internal fun playlistOwnedGrantKey(uri: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray(Charsets.UTF_8))
        .take(16).joinToString("") { "%02x".format(it) }
    return "ui5_visual_owned_" + digest
}

/** Pure contract for shared grants: every remaining reference blocks release. */
internal fun playlistGrantReleaseAllowed(
    uri: String, owned: Set<String>, references: Collection<String?>,
): Boolean = uri in owned && references.none { it == uri }
