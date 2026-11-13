package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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

/** Finished poster is the complete image. Compose draws only dynamic track count OUTSIDE. */
@Composable
internal fun GenreEditorialPosterTile(
    genre: LibraryGenre,
    poster: EditorialPosterAsset,
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
        Box(Modifier.fillMaxWidth().aspectRatio(poster.ratio)) {
            GenreEditorialPosterArtwork(poster, Modifier.fillMaxSize())
        }
        Text(
            text = "$count ${if (count == 1) "pista" else "pistas"}",
            style = MichiTypography.metadata,
            color = TextSecondary,
            modifier = Modifier.align(Alignment.End).clearAndSetSemantics {},
        )
    }
}
