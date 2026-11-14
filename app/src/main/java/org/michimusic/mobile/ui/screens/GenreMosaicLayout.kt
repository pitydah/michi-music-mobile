package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.mobile.ui.components.AeroSurface
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.PureWhite

/**
 * Mosaico destacado composition.
 *
 * One full-width focal card (218dp) followed by a compact grid of artwork-driven
 * cards. Both sizes share one approved crop and one continuous surface: no
 * footer band, no `SurfaceObsidian` caption strip, no separator, no eyebrow, no
 * arrow and no second title. The approved WebP already carries the genre title
 * and its frame, so Compose draws neither; the only live overlay is the real
 * scoped track count, placed inside the artwork when its verified top-right safe
 * region fits and on the card's continuous surface otherwise.
 *
 * The artwork is never stretched, cropped, zoomed or blurred: the whole approved
 * frame is mounted 8dp inside the rounded card (`Fit`) so the clip cannot cut the
 * baked frame, and the compact card takes the artwork's own proportional height
 * instead of reserving a bottom counter band. A missing or undecodable asset
 * keeps the accessible label/count and falls back to a live Compose title instead
 * of an unlabeled card.
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
    val chip = rememberGenreCounterMaxSize()
    val fontScale = LocalConfiguration.current.fontScale
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
        val hero = genres.first()
        item(key = "hero_${hero.id}", span = { GridItemSpan(columns) }) {
            GenreMosaicHero(
                genre = hero,
                mosaicAsset = mosaicArtworkForGenre(hero.id, artwork),
                chip = chip,
                fontScale = fontScale,
                onArtworkLoad = onArtworkLoad,
                onOpen = { onOpen(hero.id) },
            )
        }
        gridItems(genres.drop(1), key = { it.id }) { genre ->
            GenreMosaicSecondary(
                genre = genre,
                mosaicAsset = mosaicArtworkForGenre(genre.id, artwork),
                chip = chip,
                fontScale = fontScale,
                onArtworkLoad = onArtworkLoad,
                onOpen = { onOpen(genre.id) },
            )
        }
    }
}

/** Accessible name: one target, one action, real genre name and real count. */
internal fun genreCardLabel(genre: LibraryGenre): String =
    "Abrir género ${genre.title}, ${genreCounterLabel(genre.tracks.size)}"

@Composable
private fun GenreMosaicHero(
    genre: LibraryGenre,
    mosaicAsset: MosaicArtworkAsset?,
    chip: GenreCounterSize,
    fontScale: Float,
    onArtworkLoad: ((GenreViewMode, String, Boolean) -> Unit)?,
    onOpen: () -> Unit,
) {
    val highContrast = LocalVisualPreferences.current.highContrast
    val accent = if (highContrast) PureWhite else genreAccent(genre)
    val count = genre.tracks.size
    val spec = mosaicPresentationSpec(mosaicAsset)
    var artworkUnavailable by remember(mosaicAsset?.filename) {
        mutableStateOf(mosaicAsset == null)
    }
    val showComposeTitle = spec.title == GenreTitlePresentation.COMPOSE_TITLE || artworkUnavailable
    val height = genreMosaicHeroHeightDp(fontScale)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = maxWidth.value
        val cardHeight = height.toFloat()
        AeroSurface(
            role = AeroSurfaceRole.Raised,
            modifier = Modifier.fillMaxWidth().height(height.dp)
                .testTag("genre_tile_${genre.id}")
                .semantics(mergeDescendants = true) {
                    contentDescription = genreCardLabel(genre)
                },
            onClick = onOpen,
            // The approved bitmap owns the only visible frame. Suppress the idle
            // Aero hairline so the card cannot read as a second frame around the
            // baked one; the focus ring still appears for keyboard/accessibility.
            borderStroke = if (highContrast) null else BorderStroke(0.dp, Color.Transparent),
        ) {
            val mount = GENRE_ARTWORK_MOUNT_INSET_DP.toFloat()
            Box(Modifier.fillMaxSize().padding(mount.dp)) {
                GenreMosaicArtwork(
                    asset = mosaicAsset,
                    genreId = genre.id,
                    accent = accent,
                    onImageResult = { ok ->
                        artworkUnavailable = !ok
                        onArtworkLoad?.let { notify -> notify(GenreViewMode.FEATURED, genre.id, ok) }
                    },
                )
            }
            if (showComposeTitle) {
                Text(
                    genre.title,
                    style = MichiTypography.sectionTitle.copy(fontSize = 27.sp, lineHeight = 31.sp),
                    color = PureWhite,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart)
                        .padding(start = 18.dp, end = 18.dp, bottom = 18.dp)
                        .fillMaxWidth(GENRE_HERO_TITLE_WIDTH_FRACTION),
                )
            }
            val artworkRect = genreMosaicArtworkRect(
                containerWidthDp = cardWidth,
                containerHeightDp = cardHeight,
                assetRatio = mosaicAsset?.ratio,
            )
            val placement = genreCounterPlacement(
                anchor = spec.counterAnchor,
                containerWidthDp = cardWidth,
                containerHeightDp = cardHeight,
                artworkRect = artworkRect?.takeIf { rect ->
                    counterFitsSafeRegion(
                        spec = spec,
                        artworkWidthDp = rect.width,
                        artworkHeightDp = rect.height,
                        chipWidthDp = chip.widthDp,
                        chipHeightDp = chip.heightDp,
                    )
                },
                chip = chip,
            )
            GenreCounterChip(
                count = count,
                testTag = "genre_counter_${genre.id}",
                modifier = Modifier.align(spec.counterAnchor.toAlignment())
                    .padding(
                        start = placement.startDp.dp,
                        top = placement.topDp.dp,
                        end = placement.endDp.dp,
                        bottom = placement.bottomDp.dp,
                    ),
            )
        }
    }
}

@Composable
private fun GenreMosaicSecondary(
    genre: LibraryGenre,
    mosaicAsset: MosaicArtworkAsset?,
    chip: GenreCounterSize,
    fontScale: Float,
    onArtworkLoad: ((GenreViewMode, String, Boolean) -> Unit)?,
    onOpen: () -> Unit,
) {
    val highContrast = LocalVisualPreferences.current.highContrast
    val accent = if (highContrast) PureWhite else genreAccent(genre)
    val count = genre.tracks.size
    val spec = mosaicPresentationSpec(mosaicAsset)
    var artworkUnavailable by remember(mosaicAsset?.filename) {
        mutableStateOf(mosaicAsset == null)
    }
    val showComposeTitle = spec.title == GenreTitlePresentation.COMPOSE_TITLE || artworkUnavailable
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = maxWidth.value
        val mount = GENRE_ARTWORK_MOUNT_INSET_DP.toFloat()
        val innerWidth = (cardWidth - 2 * mount).coerceAtLeast(1f)
        // The compact card takes the artwork's proportional height. There is no
        // reserved counter band and no tall fixed card with the image confined to
        // a top strip: the crop fills the visual area minus its 8dp mounts.
        val cardHeight = if (mosaicAsset != null) {
            (2 * mount + innerWidth / mosaicAsset.ratio)
                .coerceAtLeast(GENRE_ARTWORK_MIN_CARD_HEIGHT_DP.toFloat())
        } else {
            genreMosaicSecondaryHeightDp(fontScale).toFloat()
        }
        AeroSurface(
            role = AeroSurfaceRole.Raised,
            modifier = Modifier.fillMaxWidth().height(cardHeight.dp)
                .testTag("genre_tile_${genre.id}")
                .semantics(mergeDescendants = true) {
                    contentDescription = genreCardLabel(genre)
                },
            onClick = onOpen,
            borderStroke = if (highContrast) null else BorderStroke(0.dp, Color.Transparent),
        ) {
            Box(Modifier.fillMaxSize().padding(mount.dp)) {
                GenreMosaicArtwork(
                    asset = mosaicAsset,
                    genreId = genre.id,
                    accent = accent,
                    onImageResult = { ok ->
                        artworkUnavailable = !ok
                        onArtworkLoad?.let { notify ->
                            notify(GenreViewMode.FEATURED, genre.id, ok)
                        }
                    },
                )
            }
            if (showComposeTitle) {
                Text(
                    genre.title,
                    style = MichiTypography.cardTitle,
                    color = PureWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart)
                        .padding(
                            start = GENRE_GRID_SIDE_PADDING_DP.dp / 2,
                            end = (chip.widthDp + 2 * GENRE_COUNTER_INSET_DP).dp,
                            bottom = GENRE_COUNTER_INSET_DP.dp,
                        ),
                )
            }
            val artworkRect = genreMosaicArtworkRect(
                containerWidthDp = cardWidth,
                containerHeightDp = cardHeight,
                assetRatio = mosaicAsset?.ratio,
            )
            val placement = genreCounterPlacement(
                anchor = spec.counterAnchor,
                containerWidthDp = cardWidth,
                containerHeightDp = cardHeight,
                artworkRect = artworkRect?.takeIf { rect ->
                    counterFitsSafeRegion(
                        spec = spec,
                        artworkWidthDp = rect.width,
                        artworkHeightDp = rect.height,
                        chipWidthDp = chip.widthDp,
                        chipHeightDp = chip.heightDp,
                    )
                },
                chip = chip,
            )
            GenreCounterChip(
                count = count,
                testTag = "genre_counter_${genre.id}",
                modifier = Modifier.align(spec.counterAnchor.toAlignment())
                    .padding(
                        start = placement.startDp.dp,
                        top = placement.topDp.dp,
                        end = placement.endDp.dp,
                        bottom = placement.bottomDp.dp,
                    ),
            )
        }
    }
}

/** Artwork rect in card coordinates after the 8dp unclipped matte mount. */
private fun genreMosaicArtworkRect(
    containerWidthDp: Float,
    containerHeightDp: Float,
    assetRatio: Float?,
): GenreArtworkRect? {
    val mount = GENRE_ARTWORK_MOUNT_INSET_DP.toFloat()
    val innerWidth = (containerWidthDp - 2 * mount).coerceAtLeast(1f)
    val innerHeight = (containerHeightDp - 2 * mount).coerceAtLeast(1f)
    return genreArtworkFitRect(innerWidth, innerHeight, assetRatio)
        ?.offsetBy(mount, mount)
}

/** The focal title never spans the whole card: it leaves the count chip clear. */
private const val GENRE_HERO_TITLE_WIDTH_FRACTION = 0.78f
