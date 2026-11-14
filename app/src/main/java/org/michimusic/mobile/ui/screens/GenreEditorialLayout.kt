package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.mobile.ui.theme.AeroGraphiteRaised
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * Editorial wall: `COVER(1) → PAIR(2) → PAIR(2) → COVER(1)` and repeat.
 *
 * Each card is independent and has one click target, one title and one numeric
 * chip. A trailing single genre is promoted to COVER instead of leaving a half
 * empty row. Pairs always share the same exterior height; no asset ratio can make
 * one half taller than the other.
 */
internal sealed interface EditorialRow {
    val key: String

    data class Cover(val genre: LibraryGenre) : EditorialRow {
        override val key: String get() = "cover:${genre.id}"
    }

    data class Pair(val leading: LibraryGenre, val trailing: LibraryGenre) : EditorialRow {
        override val key: String get() = "pair:${leading.id}:${trailing.id}"
    }
}

/** Canonical `[1,2,2,1]` rhythm. One-column panels show every genre as a cover. */
internal fun editorialRows(genres: List<LibraryGenre>, columns: Int): List<EditorialRow> {
    require(columns >= 1)
    if (genres.isEmpty()) return emptyList()
    if (columns == 1) return genres.map { EditorialRow.Cover(it) }
    val pattern = intArrayOf(1, 2, 2, 1)
    val rows = ArrayList<EditorialRow>((genres.size + 1) / 2)
    var genreIndex = 0
    var phase = 0
    while (genreIndex < genres.size) {
        val wanted = pattern[phase % pattern.size]
        val remaining = genres.size - genreIndex
        val take = minOf(wanted, remaining)
        if (take == 2) {
            rows += EditorialRow.Pair(genres[genreIndex], genres[genreIndex + 1])
        } else {
            rows += EditorialRow.Cover(genres[genreIndex])
        }
        genreIndex += take
        phase++
    }
    return rows
}

@Composable
internal fun GenreEditorialGrid(
    genres: List<LibraryGenre>,
    columns: Int,
    availableWidthDp: Float,
    state: LazyListState,
    posterAssets: Map<String, EditorialPosterAsset>,
    onArtworkLoad: ((GenreViewMode, String, Boolean) -> Unit)?,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember(genres, columns) { editorialRows(genres, columns) }
    val fontScale = LocalConfiguration.current.fontScale
    val sizes = remember(availableWidthDp, columns, fontScale) {
        editorialCardSize(availableWidthDp, columns, fontScale)
    }
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxSize().testTag("genres_editorial"),
        contentPadding = PaddingValues(
            start = GENRE_GRID_SIDE_PADDING_DP.dp,
            end = GENRE_GRID_SIDE_PADDING_DP.dp,
            top = GENRE_GRID_TOP_PADDING_DP.dp,
            bottom = GENRE_GRID_BOTTOM_PADDING_DP.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(GENRE_GRID_GAP_DP.dp),
    ) {
        // `contentType` includes the viewport decisions that change the item
        // lambda, so a split-screen/font-scale change rebuilds the row content
        // instead of reusing the previous card geometry.
        items(rows, key = { it.key }, contentType = { "$columns:$fontScale" }) { row ->
            when (row) {
                is EditorialRow.Cover -> BoxWithConstraints(
                    Modifier
                        .fillMaxWidth()
                        .height(
                            if (columns == 1) sizes.singleHeightDp.dp else sizes.coverHeightDp.dp,
                        ),
                ) {
                    EditorialCell(
                        genre = row.genre,
                        treatment = GenreEditorialTreatment.COVER,
                        columns = columns,
                        cardWidthDp = maxWidth.value,
                        cardHeightDp = maxHeight.value,
                        posterAssets = posterAssets,
                        onArtworkLoad = onArtworkLoad,
                        onOpen = onOpen,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                is EditorialRow.Pair -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(sizes.portraitHeightDp.dp),
                    horizontalArrangement = Arrangement.spacedBy(GENRE_GRID_GAP_DP.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                        EditorialCell(
                            genre = row.leading,
                            treatment = GenreEditorialTreatment.PORTRAIT_LEFT,
                            columns = columns,
                            cardWidthDp = maxWidth.value,
                            cardHeightDp = maxHeight.value,
                            posterAssets = posterAssets,
                                onArtworkLoad = onArtworkLoad,
                            onOpen = onOpen,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                        EditorialCell(
                            genre = row.trailing,
                            treatment = GenreEditorialTreatment.PORTRAIT_RIGHT,
                            columns = columns,
                            cardWidthDp = maxWidth.value,
                            cardHeightDp = maxHeight.value,
                            posterAssets = posterAssets,
                                onArtworkLoad = onArtworkLoad,
                            onOpen = onOpen,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorialCell(
    genre: LibraryGenre,
    treatment: GenreEditorialTreatment,
    columns: Int,
    cardWidthDp: Float,
    cardHeightDp: Float,
    posterAssets: Map<String, EditorialPosterAsset>,
    onArtworkLoad: ((GenreViewMode, String, Boolean) -> Unit)?,
    onOpen: (String) -> Unit,
    modifier: Modifier,
) {
    val chip = rememberGenreCounterSize(genre.tracks.size)
    val poster = editorialPosterForTitle(genre.title, posterAssets)
    val spec = editorialVisualSpec(poster, treatment)
    val treatmentKey = treatment.toUi7Treatment()
    val outcome = ui7RenderOutcome(
        spec = spec,
        treatment = treatmentKey,
        cardWidthDp = cardWidthDp,
        cardHeightDp = cardHeightDp,
        sourceRatio = poster?.ratio ?: 1f,
        chipWidthDp = chip.widthDp,
        chipHeightDp = chip.heightDp,
    )
    val compatible = outcome == Ui7RenderOutcome.ASSET
    LaunchedEffect(poster?.filename, outcome, treatmentKey) {
        if (poster != null && !compatible) {
            GenreArtworkDiagnostics.unsupportedAspect(
                view = "EDITORIAL",
                filename = poster.filename,
                sourceRatio = poster.ratio,
                targetWidthDp = cardWidthDp,
                targetHeightDp = cardHeightDp,
            )
            GenreArtworkDiagnostics.renderOutcome("EDITORIAL", poster.filename, outcome)
        }
    }
    if (!compatible || poster == null || spec == null) {
        GenreEditorialFallbackTile(
            genre = genre,
            treatment = treatment,
            columns = columns,
            modifier = modifier,
            onOpen = { onOpen(genre.id) },
        )
    } else {
        GenreEditorialPosterTile(
            genre = genre,
            poster = poster,
            spec = spec,
            treatment = treatment,
            columns = columns,
            modifier = modifier,
            onArtworkLoad = onArtworkLoad,
            onOpen = { onOpen(genre.id) },
        )
    }
}

/**
 * Compose fallback for an Editorial slot without an approved vertical/cover
 * variant. It keeps the treatment geometry (192/246/200), uses one Compose
 * border because no rasterized border exists, and shows the real title plus the
 * numeric chip only. No horizontal historical poster is forced into the slot and
 * no "PENDIENTE" text is ever visible.
 */
@Composable
internal fun GenreEditorialFallbackTile(
    genre: LibraryGenre,
    treatment: GenreEditorialTreatment,
    columns: Int,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
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
            .testTag("genre_editorial_${genre.id}"),
    ) {
        GenreArtworkPlaceholder(
            accent,
            Modifier.fillMaxSize().testTag("genre_editorial_fallback_${genre.id}"),
        )
        Box(Modifier.fillMaxSize().border(1.dp, accent.copy(alpha = 0.62f), shape))
        val titleSize = when (treatment) {
            GenreEditorialTreatment.COVER -> 28.sp
            GenreEditorialTreatment.PORTRAIT_LEFT,
            GenreEditorialTreatment.PORTRAIT_RIGHT -> 22.sp
        }
        val titleLine = when (treatment) {
            GenreEditorialTreatment.COVER -> 32.sp
            else -> 26.sp
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    start = if (columns == 1) 22.dp else 16.dp,
                    end = 14.dp,
                    top = 18.dp,
                    bottom = 16.dp,
                ),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Box(Modifier.width(30.dp).height(2.dp).background(accent.copy(alpha = 0.85f)))
            Text(
                text = genre.title,
                style = MichiTypography.sectionTitle.copy(
                    fontSize = titleSize,
                    lineHeight = titleLine,
                ),
                color = PureWhite,
                // A portrait cell is 170dp wide: three lines keep the real name
                // ("Afro-Cuban Jazz") visible instead of truncating it to two.
                maxLines = when (treatment) {
                    GenreEditorialTreatment.COVER -> 2
                    else -> 3
                },
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp, end = 52.dp),
            )
        }
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
