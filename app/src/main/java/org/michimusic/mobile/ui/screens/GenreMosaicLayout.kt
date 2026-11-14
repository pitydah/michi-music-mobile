package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian

/**
 * Mosaico destacado composition — one homogeneous grid.
 *
 * Every visible card of a breakpoint shares exactly the same exterior width and
 * height ([GENRE_MOSAIC_ASPECT_RATIO] = 1.42). There is no hero: the first genre
 * occupies the first cell like any other, ordered by real track count.
 *
 * The WebP keeps its own proportions inside the cell (Fit); the measured optical
 * contour is applied to the painted rect and is the only visible frame. A source
 * whose ratio differs from 1.42 still paints — the residual area is recorded as
 * aspect debt, never replaced by a generic card and never turned into a footer.
 */
@Composable
internal fun GenreMosaicGrid(
    genres: List<LibraryGenre>,
    columns: Int,
    availableWidthDp: Float,
    state: LazyGridState,
    artwork: Map<String, MosaicArtworkAsset>,
    onArtworkLoad: ((GenreViewMode, String, Boolean) -> Unit)?,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    if (genres.isEmpty()) return
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        modifier = modifier.fillMaxSize().testTag("genres_featured"),
        contentPadding = PaddingValues(
            start = GENRE_GRID_SIDE_PADDING_DP.dp,
            end = GENRE_GRID_SIDE_PADDING_DP.dp,
            top = GENRE_GRID_TOP_PADDING_DP.dp,
            bottom = GENRE_GRID_BOTTOM_PADDING_DP.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(GENRE_GRID_GAP_DP.dp),
        verticalArrangement = Arrangement.spacedBy(GENRE_GRID_GAP_DP.dp),
    ) {
        // `contentType` depends on the column count so a split-screen width change
        // rebuilds the item lambdas; the size itself is read from the real item
        // constraints inside `BoxWithConstraints`, never captured from the parent.
        gridItems(genres, key = { it.id }, contentType = { columns }) { genre ->
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val widthDp = maxWidth.value
                val heightDp = widthDp / GENRE_MOSAIC_ASPECT_RATIO
                GenreMosaicCard(
                    genre = genre,
                    mosaicAsset = if (loading) null else mosaicArtworkForGenre(genre.id, artwork),
                    onArtworkLoad = onArtworkLoad,
                    onOpen = { onOpen(genre.id) },
                    modifier = Modifier.fillMaxWidth().height(heightDp.dp),
                    loading = loading,
                )
            }
        }
    }
}

/** Accessible name: one target, one action, real genre name and real count. */
internal fun genreCardLabel(genre: LibraryGenre): String =
    "Abrir género ${genre.title}, ${genreCounterLabel(genre.tracks.size)}"

@Composable
private fun GenreMosaicCard(
    genre: LibraryGenre,
    mosaicAsset: MosaicArtworkAsset?,
    onArtworkLoad: ((GenreViewMode, String, Boolean) -> Unit)?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    val spec = remember(mosaicAsset) { mosaicVisualSpec(mosaicAsset) }
    var decodeFailed by remember(mosaicAsset?.filename) { mutableStateOf(false) }
    if (loading) {
        GenreMosaicSkeletonCard(genre = genre, onOpen = onOpen, modifier = modifier)
        return
    }
    if (spec == null || mosaicAsset == null || decodeFailed) {
        LaunchedEffect(mosaicAsset?.filename, decodeFailed) {
            if (mosaicAsset == null) GenreArtworkDiagnostics.assetUnmapped("MOSAIC")
        }
        GenreMosaicFallbackCard(
            genre = genre,
            onOpen = onOpen,
            modifier = modifier,
        )
        return
    }
    GenreArtworkSurface(
        spec = spec,
        genre = genre,
        count = genre.tracks.size,
        view = "MOSAIC",
        testTag = "genre_tile_${genre.id}",
        modifier = modifier,
        onOpen = onOpen,
    ) { artModifier ->
        GenreMosaicArtwork(
            asset = mosaicAsset,
            genreId = genre.id,
            accent = if (LocalVisualPreferences.current.highContrast) PureWhite
            else genreAccent(genre),
            modifier = artModifier,
            onImageResult = { ok ->
                if (!ok) decodeFailed = true
                onArtworkLoad?.let { notify -> notify(GenreViewMode.FEATURED, genre.id, ok) }
            },
        )
    }
}

/**
 * Transient loading cell: the real homogeneous geometry and real count, neutral
 * art plane and no invented title. It exists only until the one-shot manifest
 * read finishes and never publishes an empty catalog as final.
 */
@Composable
private fun GenreMosaicSkeletonCard(
    genre: LibraryGenre,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = if (LocalVisualPreferences.current.highContrast) PureWhite else genreAccent(genre)
    Box(
        modifier
            .background(SurfaceObsidian)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) {
                contentDescription = genreCardLabel(genre)
            }
            .testTag("genre_tile_${genre.id}"),
    ) {
        GenreArtworkPlaceholder(accent, Modifier.fillMaxSize())
        GenreCounterChip(
            count = genre.tracks.size,
            testTag = "genre_counter_${genre.id}",
            modifier = Modifier.align(Alignment.BottomEnd).padding(
                end = GENRE_COUNTER_INSET_DP.dp,
                bottom = GENRE_COUNTER_INSET_DP.dp,
            ),
        )
    }
}

/**
 * Live Compose fallback for a genre with no packaged artwork (or a real decode
 * failure). One silhouette, real title bottom-start, number bottom-end, no
 * technical label and the same exterior cell as every other card.
 */
@Composable
private fun GenreMosaicFallbackCard(
    genre: LibraryGenre,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val highContrast = LocalVisualPreferences.current.highContrast
    val accent = if (highContrast) PureWhite else genreAccent(genre)
    val shape = RoundedCornerShape(14.dp)
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .clip(shape)
            .clickable(role = Role.Button, onClick = onOpen)
            .onFocusChanged { focused = it.isFocused }
            .semantics(mergeDescendants = true) {
                contentDescription = genreCardLabel(genre)
            }
            .testTag("genre_tile_${genre.id}"),
    ) {
        GenreArtworkPlaceholder(
            accent,
            Modifier.fillMaxSize().testTag("genre_mosaic_fallback_${genre.id}"),
        )
        Box(
            Modifier
                .fillMaxSize()
                .border(1.dp, accent.copy(alpha = 0.55f), shape),
        )
        Text(
            text = genre.title,
            style = MichiTypography.sectionTitle.copy(fontSize = 20.sp, lineHeight = 24.sp),
            color = PureWhite,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 14.dp, end = 58.dp, bottom = 12.dp),
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
            Box(Modifier.fillMaxSize().border(2.dp, PureWhite, shape))
        }
    }
}
