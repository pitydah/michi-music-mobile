package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * Mosaico destacado composition.
 *
 * A single full-width focal card followed by a calm compact grid. Both sizes
 * share one approved Mosaico asset each; the artwork is never stretched, never
 * FillBounds, and never re-encoded. The baked title in the approved WebP is the
 * only visible title on secondary cards (no duplicate Compose title, no eyebrow,
 * no arrow, no ornamental rule, no full-card dark ramp). The focal card keeps a
 * live Compose title because it is the view's primary affordance; its scrim is
 * localized to the text band so fur, eyes and instruments stay untouched.
 */
@Composable
internal fun GenreMosaicGrid(
    genres: List<LibraryGenre>,
    columns: Int,
    state: LazyGridState,
    artwork: Map<String, MosaicArtworkAsset>,
    onArtworkLoad: ((GenreViewMode, String, Boolean) -> Unit)?,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (genres.isEmpty()) return
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        modifier = modifier.fillMaxSize().testTag("genres_featured"),
        contentPadding = PaddingValues(
            start = GENRE_GRID_SIDE_PADDING_DP.dp,
            end = GENRE_GRID_SIDE_PADDING_DP.dp,
            top = 4.dp,
            bottom = 28.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(GENRE_GRID_GAP_DP.dp),
        verticalArrangement = Arrangement.spacedBy(GENRE_GRID_GAP_DP.dp),
    ) {
        val hero = genres.first()
        item(key = "hero_${hero.id}", span = { GridItemSpan(columns) }) {
            GenreMosaicHero(
                genre = hero,
                mosaicAsset = mosaicArtworkForGenre(hero.id, artwork),
                onArtworkLoad = onArtworkLoad,
                onOpen = { onOpen(hero.id) },
            )
        }
        gridItems(genres.drop(1), key = { it.id }) { genre ->
            GenreMosaicSecondary(
                genre = genre,
                mosaicAsset = mosaicArtworkForGenre(genre.id, artwork),
                onArtworkLoad = onArtworkLoad,
                onOpen = { onOpen(genre.id) },
            )
        }
    }
}

@Composable
private fun GenreMosaicHero(
    genre: LibraryGenre,
    mosaicAsset: MosaicArtworkAsset?,
    onArtworkLoad: ((GenreViewMode, String, Boolean) -> Unit)?,
    onOpen: () -> Unit,
) {
    val highContrast = LocalVisualPreferences.current.highContrast
    val accent = if (highContrast) PureWhite else genreAccent(genre)
    val count = genre.tracks.size
    val label = "Abrir género ${genre.title}, $count ${if (count == 1) "pista" else "pistas"}"
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val height = genreMosaicHeroHeightDp(
            maxWidth.value,
            LocalConfiguration.current.fontScale,
        )
        AeroCard(
            modifier = Modifier.fillMaxWidth().height(height.dp)
                .testTag("genre_tile_${genre.id}")
                .semantics { contentDescription = label },
            onClick = onOpen,
        ) {
            Box(Modifier.fillMaxSize()) {
                GenreMosaicArtwork(
                    asset = mosaicAsset,
                    genreId = genre.id,
                    accent = accent,
                    onImageResult = onArtworkLoad?.let { notify ->
                        { ok -> notify(GenreViewMode.FEATURED, genre.id, ok) }
                    },
                )
                // One localized ramp for the live title/count only. The upper two
                // thirds of the artwork (fur, eyes, instrument) stay untouched
                // because the first gradient stop is fully transparent.
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxSize()
                        .background(GenreTextScrim),
                )
                Column(
                    Modifier.align(Alignment.BottomStart)
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        genre.title,
                        style = MichiTypography.sectionTitle.copy(fontSize = 27.sp,
                            lineHeight = 31.sp),
                        color = PureWhite,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "$count ${if (count == 1) "pista" else "pistas"}",
                        style = MichiTypography.metadata,
                        color = TextSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun GenreMosaicSecondary(
    genre: LibraryGenre,
    mosaicAsset: MosaicArtworkAsset?,
    onArtworkLoad: ((GenreViewMode, String, Boolean) -> Unit)?,
    onOpen: () -> Unit,
) {
    val highContrast = LocalVisualPreferences.current.highContrast
    val accent = if (highContrast) PureWhite else genreAccent(genre)
    val count = genre.tracks.size
    val label = "Abrir género ${genre.title}, $count ${if (count == 1) "pista" else "pistas"}"
    val footerHeight = genreMosaicFooterHeightDp(LocalConfiguration.current.fontScale)
    AeroCard(
        modifier = Modifier.fillMaxWidth()
            .testTag("genre_tile_${genre.id}")
            .semantics { contentDescription = label },
        onClick = onOpen,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(GENRE_ARTWORK_FRAME_RATIO)
                    .testTag("genre_artwork_${genre.id}"),
            ) {
                GenreMosaicArtwork(
                    asset = mosaicAsset,
                    genreId = genre.id,
                    accent = accent,
                    onImageResult = onArtworkLoad?.let { notify ->
                        { ok -> notify(GenreViewMode.FEATURED, genre.id, ok) }
                    },
                )
            }
            // Integrated caption: the real count of this library genre. The
            // approved artwork already carries the baked genre title, so a second
            // Compose title would duplicate it. If the pack has no asset at all
            // (fail-closed catalog), the card falls back to the live title.
            Box(
                Modifier.fillMaxWidth().height(footerHeight.dp)
                    .background(SurfaceObsidian)
                    .padding(horizontal = GENRE_CARD_TEXT_PADDING_DP.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    if (mosaicAsset == null) {
                        Text(
                            genre.title,
                            style = MichiTypography.cardTitle,
                            color = PureWhite,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        "$count ${if (count == 1) "pista" else "pistas"}",
                        style = MichiTypography.metadata,
                        color = TextSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Bottom-anchored ramp used only behind the focal card's live text.
 * Fixed stops keep a 0.94+ obsidian backing under white 27sp text (>= 15:1) and
 * under 12sp TextSecondary (>= 8:1), while the top of the artwork stays clear.
 */
private val GenreTextScrim = Brush.verticalGradient(
    0.00f to Color.Transparent,
    0.42f to SurfaceObsidian.copy(alpha = 0.18f),
    0.68f to SurfaceObsidian.copy(alpha = 0.72f),
    0.86f to SurfaceObsidian.copy(alpha = 0.94f),
    1.00f to SurfaceObsidian.copy(alpha = 0.97f),
)
