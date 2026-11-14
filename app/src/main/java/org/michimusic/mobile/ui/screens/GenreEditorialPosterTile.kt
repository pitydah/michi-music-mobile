package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.PureWhite

/**
 * Finished Editorial poster whose source already contains the title and the only
 * frame. Used only when the manifest/optical metadata approves that exact
 * treatment and the source fills the target without a destructive crop.
 *
 * There is no Compose title, no second border, no mount, no footer and no
 * separate counter row: the numeric chip is overlaid bottom-end, 12dp inside the
 * artwork contour.
 */
@Composable
internal fun GenreEditorialPosterTile(
    genre: LibraryGenre,
    poster: EditorialPosterAsset,
    spec: Ui7VisualAssetSpec,
    treatment: GenreEditorialTreatment,
    columns: Int = 2,
    modifier: Modifier = Modifier,
    onArtworkLoad: ((GenreViewMode, String, Boolean) -> Unit)? = null,
    onOpen: () -> Unit,
) {
    var decodeFailed by remember(poster.filename) { mutableStateOf(false) }
    if (decodeFailed || !spec.hasVerifiedContour) {
        GenreEditorialFallbackTile(
            genre = genre,
            treatment = treatment,
            columns = columns,
            modifier = modifier,
            onOpen = onOpen,
        )
        return
    }
    val mask = spec.opticalMask ?: return
    val shape = remember(spec.fileName, mask) {
        Ui7OpticalFrameShape(spec.widthPx, spec.heightPx, mask)
    }
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .clip(shape)
            .clickable(role = Role.Button, onClick = onOpen)
            .onFocusChanged { focused = it.isFocused }
            .semantics(mergeDescendants = true) {
                contentDescription = genreCardLabel(genre)
            }
            .testTag("genre_editorial_${genre.id}"),
    ) {
        GenreEditorialPosterArtwork(
            poster = poster,
            modifier = Modifier.fillMaxSize()
                .testTag("genre_editorial_poster_${genre.id}"),
            onImageResult = { ok ->
                if (!ok) decodeFailed = true
                onArtworkLoad?.let { notify -> notify(GenreViewMode.EDITORIAL, genre.id, ok) }
            },
        )
        GenreCounterChip(
            count = genre.tracks.size,
            testTag = "genre_counter_${genre.id}",
            modifier = Modifier.align(Alignment.BottomEnd).padding(
                end = GENRE_COUNTER_INSET_DP.dp,
                bottom = GENRE_COUNTER_INSET_DP.dp,
            ),
        )
        if (focused) {
            Box(
                Modifier.fillMaxSize().border(
                    width = 2.dp,
                    color = PureWhite.copy(alpha = 0.92f),
                    shape = shape,
                ),
            )
        }
    }
}
