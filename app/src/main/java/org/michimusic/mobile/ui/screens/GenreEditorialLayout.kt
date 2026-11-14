package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.mobile.ui.components.AeroCard
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * One Editorial row: a full-width COVER or an asymmetric PORTRAIT_LEFT /
 * PORTRAIT_RIGHT pair.
 *
 * The grid is a grouped lazy list, not a generic masonry: two row shapes keep
 * stable keys, scroll restoration, lazy image composition, one-column
 * adaptation and per-card accessibility. Approved posters keep their own
 * proportional compact height, so a horizontal sheet is never stretched into a
 * 246dp portrait slot; explicit rows keep the cover/pair contract and the
 * promoted tail card unambiguous.
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

/**
 * Canonical COVER / pair / pair rhythm. A trailing group of two becomes a pair
 * instead of a cover plus an orphan, so the wall never ends on a half-empty
 * row. Single-column panels render every genre as its own cover.
 */
internal fun editorialRows(genres: List<LibraryGenre>, columns: Int): List<EditorialRow> {
    require(columns >= 1)
    if (genres.isEmpty()) return emptyList()
    if (columns == 1) return genres.map { EditorialRow.Cover(it) }
    val rows = ArrayList<EditorialRow>((genres.size + 1) / 2)
    var index = 0
    while (index < genres.size) {
        when (genres.size - index) {
            1 -> {
                rows += EditorialRow.Cover(genres[index])
                index += 1
            }
            2 -> {
                rows += EditorialRow.Pair(genres[index], genres[index + 1])
                index += 2
            }
            else -> {
                rows += EditorialRow.Cover(genres[index])
                rows += EditorialRow.Pair(genres[index + 1], genres[index + 2])
                index += 3
            }
        }
    }
    return rows
}

@Composable
internal fun GenreEditorialGrid(
    genres: List<LibraryGenre>,
    columns: Int,
    state: LazyListState,
    posterAssets: Map<String, EditorialPosterAsset>,
    productionAssets: Set<String>,
    onArtworkLoad: ((GenreViewMode, String, Boolean) -> Unit)?,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember(genres, columns) { editorialRows(genres, columns) }
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxSize().testTag("genres_editorial"),
        contentPadding = PaddingValues(
            start = GENRE_GRID_SIDE_PADDING_DP.dp,
            end = GENRE_GRID_SIDE_PADDING_DP.dp,
            top = 4.dp,
            bottom = 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(GENRE_GRID_GAP_DP.dp),
    ) {
        items(rows, key = { it.key }) { row ->
            when (row) {
                is EditorialRow.Cover -> EditorialCell(
                    genre = row.genre,
                    treatment = GenreEditorialTreatment.COVER,
                    columns = columns,
                    modifier = Modifier.fillMaxWidth(),
                    posterAssets = posterAssets,
                    productionAssets = productionAssets,
                    onArtworkLoad = onArtworkLoad,
                    onOpen = onOpen,
                )
                is EditorialRow.Pair -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(GENRE_GRID_GAP_DP.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    EditorialCell(
                        genre = row.leading,
                        treatment = GenreEditorialTreatment.PORTRAIT_LEFT,
                        columns = columns,
                        modifier = Modifier.weight(1f),
                        posterAssets = posterAssets,
                        productionAssets = productionAssets,
                        onArtworkLoad = onArtworkLoad,
                        onOpen = onOpen,
                    )
                    EditorialCell(
                        genre = row.trailing,
                        treatment = GenreEditorialTreatment.PORTRAIT_RIGHT,
                        columns = columns,
                        modifier = Modifier.weight(1f),
                        posterAssets = posterAssets,
                        productionAssets = productionAssets,
                        onArtworkLoad = onArtworkLoad,
                        onOpen = onOpen,
                    )
                }
            }
        }
    }
}

/** Approved finished poster wins; the Compose-drawn treatment is the fallback. */
@Composable
private fun EditorialCell(
    genre: LibraryGenre,
    treatment: GenreEditorialTreatment,
    columns: Int,
    modifier: Modifier,
    posterAssets: Map<String, EditorialPosterAsset>,
    productionAssets: Set<String>,
    onArtworkLoad: ((GenreViewMode, String, Boolean) -> Unit)?,
    onOpen: (String) -> Unit,
) {
    val poster = editorialPosterForTitle(genre.title, posterAssets)
    if (poster != null) {
        GenreEditorialPosterTile(
            genre = genre,
            poster = poster,
            treatment = treatment,
            columns = columns,
            modifier = modifier,
            onArtworkLoad = onArtworkLoad,
            onOpen = { onOpen(genre.id) },
        )
    } else {
        GenreEditorialDrawnTile(
            genre = genre,
            treatment = treatment,
            columns = columns,
            modifier = modifier,
            productionAssetNames = productionAssets,
            onOpen = { onOpen(genre.id) },
        )
    }
}

/** Three different editorial compositions; no false rankings and no invented art. */
@Composable
private fun GenreEditorialDrawnTile(
    genre: LibraryGenre,
    treatment: GenreEditorialTreatment,
    columns: Int,
    modifier: Modifier,
    productionAssetNames: Set<String>,
    onOpen: () -> Unit,
) {
    val accent = if (LocalVisualPreferences.current.highContrast) PureWhite else genreAccent(genre)
    val count = genre.tracks.size
    val itemLabel = "Abrir género ${genre.title}, $count ${if (count == 1) "pista" else "pistas"}"
    val height = genreEditorialHeightDp(treatment, columns, LocalConfiguration.current.fontScale)
    AeroCard(
        modifier = modifier.height(height.dp)
            .testTag("genre_editorial_${genre.id}")
            .semantics { contentDescription = itemLabel },
        onClick = onOpen,
    ) {
        Box(Modifier.fillMaxSize()) {
            GenreVisualPlane(genre.id, accent, mode = GenreViewMode.EDITORIAL,
                treatment = treatment)
            // The source keys never drive tile creation. Only validated,
            // text-free and frame-free derived assets may cover this plane.
            // Missing/decoding-failed images leave the original geometry below.
            GenreEditorialArtwork(genre.title, treatment, columns, productionAssetNames)
            when (treatment) {
                GenreEditorialTreatment.COVER -> {
                    Box(Modifier.align(Alignment.CenterStart).width(3.dp).fillMaxHeight()
                        .background(accent.copy(alpha = .72f)))
                    Column(Modifier.fillMaxSize().padding(start = 22.dp, end = 20.dp,
                        top = 21.dp, bottom = 19.dp),
                        verticalArrangement = Arrangement.SpaceBetween) {
                        Text("MICHI   /   ARCHIVO MUSICAL", style = MichiTypography.microLabel,
                            color = TextSecondary, maxLines = 1)
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(genre.title, style = MichiTypography.sectionTitle.copy(fontSize = 30.sp,
                                lineHeight = 33.sp), color = PureWhite,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.width(28.dp).height(2.dp).background(accent))
                                Text("$count ${if (count == 1) "pista" else "pistas"}",
                                    style = MichiTypography.metadata, color = TextSecondary)
                            }
                        }
                    }
                }
                GenreEditorialTreatment.PORTRAIT_LEFT -> {
                    Column(Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween) {
                        Text("MICHI / GÉNEROS", style = MichiTypography.microLabel,
                            color = TextSecondary, maxLines = 1)
                        Box(Modifier.fillMaxWidth().height(2.dp).background(accent.copy(alpha = .8f)))
                        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                            Text(genre.title, style = MichiTypography.sectionTitle.copy(
                                fontSize = 22.sp, lineHeight = 26.sp), color = PureWhite,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("$count ${if (count == 1) "PISTA" else "PISTAS"}",
                                style = MichiTypography.microLabel,
                                color = TextSecondary, maxLines = 1)
                        }
                    }
                }
                GenreEditorialTreatment.PORTRAIT_RIGHT -> {
                    Column(Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Box(Modifier.width(26.dp).height(3.dp).background(accent))
                            Text("M / M", style = MichiTypography.microLabel,
                                color = TextSecondary)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(genre.title, style = MichiTypography.sectionTitle.copy(
                                fontSize = 22.sp, lineHeight = 26.sp), color = PureWhite,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Box(Modifier.fillMaxWidth().height(1.dp)
                                .background(accent.copy(alpha = .35f)))
                            Text("$count ${if (count == 1) "pista" else "pistas"}",
                                style = MichiTypography.metadata, color = TextSecondary,
                                maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
