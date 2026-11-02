package org.michimusic.mobile.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How a playlist row paints its artwork.
 *
 * A playlist is a collection, not an album, so it has no single cover of its own.
 * Rather than showing a name-derived gradient for every list (which reads as a
 * missing image), the row shows what the list actually contains: nothing, one
 * cover, or a mosaic of its first covers.
 */
enum class PlaylistCoverLayout { Placeholder, Single, Mosaic }

fun playlistCoverLayout(coverCount: Int): PlaylistCoverLayout = when {
    coverCount <= 0 -> PlaylistCoverLayout.Placeholder
    coverCount == 1 -> PlaylistCoverLayout.Single
    else -> PlaylistCoverLayout.Mosaic
}

/**
 * The first [limit] distinct, non-blank covers, one per mosaic quadrant.
 * Duplicates are dropped because four copies of the same album would look like
 * a rendering bug rather than a playlist.
 */
fun playlistCoverIds(covers: List<String>, limit: Int = 4): List<String> =
    covers.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(limit)
        .toList()

/**
 * Caller-placeable playlist artwork: placeholder, single cover, or a 2x2 mosaic.
 *
 * Missing quadrants fall back to the editorial placeholder so two- and
 * three-track playlists never show a hole.
 */
@Composable
fun PlaylistCoverMosaic(
    coverIds: List<String>,
    coverStyle: CoverStyle,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    cornerRadius: Dp = 12.dp,
) {
    val covers = playlistCoverIds(coverIds)
    when (playlistCoverLayout(covers.size)) {
        PlaylistCoverLayout.Placeholder -> AlbumArtView(
            coverStyle = coverStyle,
            modifier = modifier.size(size).clip(RoundedCornerShape(cornerRadius)),
            cornerRadius = cornerRadius,
        )

        PlaylistCoverLayout.Single -> AlbumArtView(
            imageModel = covers.first(),
            coverStyle = coverStyle,
            modifier = modifier.size(size).clip(RoundedCornerShape(cornerRadius)),
            cornerRadius = cornerRadius,
        )

        PlaylistCoverLayout.Mosaic -> {
            // Two passes over four slots: quadrants without a cover keep the
            // placeholder instead of collapsing the grid.
            val cell = size / 2
            Column(modifier = modifier.size(size).clip(RoundedCornerShape(cornerRadius))) {
                repeat(2) { row ->
                    Row {
                        repeat(2) { column ->
                            AlbumArtView(
                                imageModel = covers.getOrNull(row * 2 + column),
                                coverStyle = coverStyle,
                                modifier = Modifier.size(cell),
                                cornerRadius = 0.dp,
                                borderWidth = 0.dp,
                            )
                        }
                    }
                }
            }
        }
    }
}
