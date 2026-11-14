package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.mobile.ui.theme.SurfaceObsidian

/** Finished poster is the complete image. Compose draws only dynamic track count OUTSIDE. */
@Composable
internal fun GenreEditorialPosterTile(
    genre: LibraryGenre,
    poster: EditorialPosterAsset,
    onArtworkLoad: ((GenreViewMode, String, Boolean) -> Unit)? = null,
    onOpen: () -> Unit,
) {
    val count = genre.tracks.size
    val label = "Abrir género ${genre.title}, $count ${if (count == 1) "pista" else "pistas"}"
    Column(
        modifier = Modifier.fillMaxWidth()
            .testTag("genre_editorial_${genre.id}")
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Completed posters are horizontal, including those shown in portrait slots.
        // Derive the cell height from the approved asset ratio so ContentScale.Fit
        // fills it exactly; a fixed COVER/PORTRAIT height letterboxed the bitmap and
        // left large empty bands. The grid starts from the canonical COVER /
        // PORTRAIT_LEFT / PORTRAIT_RIGHT rhythm and widens the cell when this same
        // approved ratio is >= 2.4:1; the full poster (cats, instruments, baked
        // lettering) is never cropped or deformed.
        Box(Modifier.fillMaxWidth().aspectRatio(poster.ratio)
                .background(SurfaceObsidian)
                .testTag("genre_editorial_poster_${genre.id}"),
            contentAlignment = Alignment.Center) {
            GenreEditorialPosterArtwork(poster, Modifier.fillMaxSize(),
                onImageResult = onArtworkLoad?.let { notify ->
                    { ok -> notify(GenreViewMode.EDITORIAL, genre.id, ok) }
                })
        }
        Text(
            text = "$count ${if (count == 1) "pista" else "pistas"}",
            style = MichiTypography.metadata,
            color = TextSecondary,
            modifier = Modifier.align(Alignment.End).padding(end = 8.dp, top = 2.dp,
                bottom = 2.dp).clearAndSetSemantics {},
        )
    }
}
