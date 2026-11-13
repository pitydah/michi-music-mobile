package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
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
    slot: GenreEditorialSlot,
    onArtworkLoad: ((GenreViewMode, String, Boolean) -> Unit)? = null,
    onOpen: () -> Unit,
) {
    val count = genre.tracks.size
    val label = "Abrir género ${genre.title}, $count ${if (count == 1) "pista" else "pistas"}"
    val height = genreEditorialHeightDp(slot, LocalConfiguration.current.fontScale)
    Column(
        modifier = Modifier.fillMaxWidth().height(height.dp)
            .testTag("genre_editorial_${genre.id}")
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Source posters are horizontal, including those displayed in portrait slots.
        // Fit the whole approved poster inside the fixed canonical slot; never crop
        // cats, instruments or baked lettering to mimic an absent vertical asset.
        Box(Modifier.fillMaxWidth().weight(1f).background(SurfaceObsidian),
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
