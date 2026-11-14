package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * Finished poster card.
 *
 * The approved WebP is the complete poster: its baked title and frame are the
 * only title/border, so Compose adds neither. The cell takes the approved aspect
 * (no crop, no stretch) and the real track counter is integrated inside the
 * poster as a small scrim-backed chip instead of floating below it with an
 * external margin. The whole card stays one click target with accessible
 * semantics. Paired portrait counters are mirrored so the pair reads as a
 * deliberate composition.
 *
 * The approved pack also contains panoramic sheets (>= 3.6:1). In a half-width
 * portrait slot their truthful aspect is only ~38-45dp tall, below the 48dp
 * touch-target floor, so the cell is raised to 48dp (see
 * [editorialPosterCellHeightDp]): the poster is still drawn at its own aspect
 * with ContentScale.Fit, and the extra surface is the card's designed obsidian
 * background, never a stretched or cropped bitmap.
 */
@Composable
internal fun GenreEditorialPosterTile(
    genre: LibraryGenre,
    poster: EditorialPosterAsset,
    treatment: GenreEditorialTreatment,
    modifier: Modifier = Modifier,
    onArtworkLoad: ((GenreViewMode, String, Boolean) -> Unit)? = null,
    onOpen: () -> Unit,
) {
    val count = genre.tracks.size
    val label = "Abrir género ${genre.title}, $count ${if (count == 1) "pista" else "pistas"}"
    val counterAlignment = if (treatment == GenreEditorialTreatment.PORTRAIT_RIGHT) {
        Alignment.BottomStart
    } else {
        Alignment.BottomEnd
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Panoramic sheets (~4:1) in a half-width portrait slot would be only
        // 38-45dp tall, so the cell takes the larger of the truthful asset
        // height and the 48dp touch floor. `aspectRatio` cannot express this:
        // it falls back to the unconstrained ratio size when the minimum height
        // conflicts with the measured width. The poster keeps `Fit`, so the
        // bitmap is never stretched or cropped; the remainder is the card's
        // designed obsidian surface.
        val cellHeight = editorialPosterCellHeightDp(maxWidth.value, poster.ratio).dp
        Box(
            Modifier.fillMaxWidth().height(cellHeight)
                .clip(MichiShapes.md)
                .background(SurfaceObsidian)
                .clickable(role = Role.Button, onClick = onOpen)
                .semantics(mergeDescendants = true) { contentDescription = label }
                .testTag("genre_editorial_${genre.id}"),
        ) {
            GenreEditorialPosterArtwork(
                poster = poster,
                modifier = Modifier.fillMaxSize()
                    .testTag("genre_editorial_poster_${genre.id}"),
                onImageResult = onArtworkLoad?.let { notify ->
                    { ok -> notify(GenreViewMode.EDITORIAL, genre.id, ok) }
                },
            )
            Box(
                Modifier.align(counterAlignment).padding(8.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(SurfaceObsidian.copy(alpha = 0.86f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = "$count ${if (count == 1) "pista" else "pistas"}",
                    style = MichiTypography.metadata,
                    color = TextSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}
