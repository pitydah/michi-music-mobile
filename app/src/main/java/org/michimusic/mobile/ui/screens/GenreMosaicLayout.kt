package org.michimusic.mobile.ui.screens

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
import androidx.compose.ui.graphics.Color
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
 * Mosaico destacado composition — homogeneous two-column grid.
 *
 * There is no historical hero: the first genre occupies the first cell and every
 * cell shares the same treatment height (`round(width * 5 / 7)` in two columns,
 * `round(width / 1.6)` in one). The order by track count remains, without medals
 * or invented rankings.
 *
 * A compatible finished WebP is a single visual piece: its calibrated optical
 * outline is the only frame, its baked title is the only visible title and the
 * numeric chip is the only overlay. Incompatible sources use the view-specific
 * Compose fallback instead of being cropped or stretched.
 */
@Suppress("UNUSED_PARAMETER")
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
    treatment: Ui7Treatment = if (columns >= 2) Ui7Treatment.MOSAIC_GRID
    else Ui7Treatment.SINGLE_COLUMN,
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
                val heightDp = if (columns >= 2) widthDp * 5f / 7f else widthDp / 1.6f
                GenreMosaicCard(
                    genre = genre,
                    cardWidthDp = widthDp,
                    cardHeightDp = heightDp,
                    treatment = treatment,
                    mosaicAsset = mosaicArtworkForGenre(genre.id, artwork),
                    onArtworkLoad = onArtworkLoad,
                    onOpen = { onOpen(genre.id) },
                    modifier = Modifier.fillMaxWidth().height(heightDp.dp),
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
    cardWidthDp: Float,
    cardHeightDp: Float,
    treatment: Ui7Treatment,
    mosaicAsset: MosaicArtworkAsset?,
    onArtworkLoad: ((GenreViewMode, String, Boolean) -> Unit)?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val highContrast = LocalVisualPreferences.current.highContrast
    val spec = mosaicVisualSpec(mosaicAsset, treatment)
    val chip = rememberGenreCounterSize(genre.tracks.size)
    val outcome = ui7RenderOutcome(
        spec = spec,
        treatment = treatment,
        cardWidthDp = cardWidthDp,
        cardHeightDp = cardHeightDp,
        sourceRatio = mosaicAsset?.ratio ?: 1f,
        chipWidthDp = chip.widthDp,
        chipHeightDp = chip.heightDp,
    )
    val compatible = outcome == Ui7RenderOutcome.ASSET
    var decodeFailed by remember(mosaicAsset?.filename) { mutableStateOf(false) }
    LaunchedEffect(mosaicAsset?.filename, outcome) {
        if (mosaicAsset != null && !compatible) {
            GenreArtworkDiagnostics.unsupportedAspect(
                view = "MOSAIC",
                filename = mosaicAsset.filename,
                sourceRatio = mosaicAsset.ratio,
                targetWidthDp = cardWidthDp,
                targetHeightDp = cardHeightDp,
            )
            GenreArtworkDiagnostics.renderOutcome("MOSAIC", mosaicAsset.filename, outcome)
        }
    }
    val accent = if (highContrast) PureWhite else genreAccent(genre)
    if (!compatible || decodeFailed || mosaicAsset == null || spec == null) {
        GenreMosaicFallbackCard(
            genre = genre,
            accent = accent,
            onOpen = onOpen,
            modifier = modifier,
        )
    } else {
        val mask = spec.opticalMask
        val shape = remember(spec.fileName, mask) {
            mask?.let { Ui7OpticalFrameShape(spec.widthPx, spec.heightPx, it) }
        } ?: RoundedCornerShape(14.dp)
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
            GenreArtworkPlaceholder(accent)
            GenreMosaicArtwork(
                asset = mosaicAsset,
                genreId = genre.id,
                accent = accent,
                modifier = Modifier.fillMaxSize(),
                onImageResult = { ok ->
                    if (!ok) decodeFailed = true
                    onArtworkLoad?.let { notify -> notify(GenreViewMode.FEATURED, genre.id, ok) }
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
}

/** Live Compose fallback: one silhouette, real title bottom-start, number bottom-end. */
@Composable
private fun GenreMosaicFallbackCard(
    genre: LibraryGenre,
    accent: Color,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
