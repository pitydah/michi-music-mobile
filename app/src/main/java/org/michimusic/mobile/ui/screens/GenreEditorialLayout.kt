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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
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
import kotlin.math.min

/**
 * Editorial wall — a heterogeneous poster wall driven by the real file ratios.
 *
 * The historical `[1,2,2,1]` rhythm is a visual reference only. Here the shape of
 * the WebP decides its class (EditorialPosterClass): panoramic/fine banners take
 * one full-width row, compact posters form pairs of equal height and proportional
 * width, and a compact that cannot reach the legible minimum becomes a
 * full-width single. The order received from `genresByTrackCount` is preserved
 * and a genre without an asset emits its fallback in the correct position.
 *
 * No asset is ever promoted to a portrait: the approved pack contains only
 * horizontal finished posters.
 */
internal enum class EditorialPosterClass {
    RETRATO_FUTURO,
    COMPACTO_ALTO,
    COMPACTO_MEDIO,
    COMPACTO_ANCHO,
    INTERMEDIO_FUTURO,
    BANNER_PANORAMICO,
    BANNER_FINO,
}

/** Minimum legible compact width for a pair at the active font scale (doc E2). */
internal fun editorialPairMinWidthDp(fontScale: Float): Float =
    EDITORIAL_PAIR_MIN_WIDTH_DP +
        EDITORIAL_PAIR_MIN_WIDTH_GROWTH_DP * (fontScale.coerceIn(1f, 2f) - 1f)

/** Content width `U = A - 2*20dp`. */
internal fun editorialUsableWidthDp(availableWidthDp: Float): Float =
    (availableWidthDp - 2 * GENRE_GRID_SIDE_PADDING_DP).coerceAtLeast(1f)

/**
 * Normative class from the real source ratio. Bands are exactly those of the
 * visual contract; a source below 1.40:1 has no approved horizontal slot yet and
 * is recorded as RETRATO_FUTURO instead of inventing a portrait.
 */
internal fun editorialPosterClass(ratio: Float): EditorialPosterClass = when {
    ratio < EDITORIAL_COMPACT_MIN_RATIO -> EditorialPosterClass.RETRATO_FUTURO
    ratio < 1.60f -> EditorialPosterClass.COMPACTO_ALTO
    ratio < 1.90f -> EditorialPosterClass.COMPACTO_MEDIO
    ratio < EDITORIAL_COMPACT_MAX_RATIO -> EditorialPosterClass.COMPACTO_ANCHO
    ratio < 3.00f -> EditorialPosterClass.INTERMEDIO_FUTURO
    ratio < 3.70f -> EditorialPosterClass.BANNER_PANORAMICO
    else -> EditorialPosterClass.BANNER_FINO
}

/** One genre cell of the wall, already measured in dp. */
internal data class EditorialPiece(
    val genre: LibraryGenre,
    /** Null when the real genre has no approved poster: honest fallback cell. */
    val poster: EditorialPosterAsset?,
    val widthDp: Float,
    val heightDp: Float,
) {
    val isFallback: Boolean get() = poster == null
}

internal enum class EditorialRowKind { BANNER, SINGLE_COMPACT, PAIR, FALLBACK }

internal sealed interface EditorialRow {
    val key: String
    val kind: EditorialRowKind
    val heightDp: Float

    /** Full-width banner, full-width compact or full-width fallback. */
    data class Full(val piece: EditorialPiece) : EditorialRow {
        override val key: String get() = "single:${piece.genre.id}"
        override val kind: EditorialRowKind
            get() = when {
                piece.poster == null -> EditorialRowKind.FALLBACK
                piece.poster.ratio >= EDITORIAL_COMPACT_MAX_RATIO -> EditorialRowKind.BANNER
                else -> EditorialRowKind.SINGLE_COMPACT
            }
        override val heightDp: Float get() = piece.heightDp
    }

    data class Pair(val leading: EditorialPiece, val trailing: EditorialPiece) : EditorialRow {
        override val key: String get() = "pair:${leading.genre.id}:${trailing.genre.id}"
        override val kind: EditorialRowKind get() = EditorialRowKind.PAIR
        override val heightDp: Float get() = leading.heightDp
    }
}

/**
 * Deterministic planner (doc §3.6). Ratios come from the resolved asset, never
 * from the genre name, the alphabet or a fixed pattern.
 */
internal fun editorialPlan(
    genres: List<LibraryGenre>,
    posters: Map<String, EditorialPosterAsset>,
    availableWidthDp: Float,
    columns: Int,
    fontScale: Float,
): List<EditorialRow> {
    require(columns >= 1)
    if (genres.isEmpty()) return emptyList()
    val usable = editorialUsableWidthDp(availableWidthDp)
    val gap = GENRE_GRID_GAP_DP.toFloat()
    val minWidth = editorialPairMinWidthDp(fontScale)
    val rows = ArrayList<EditorialRow>(genres.size)
    var index = 0
    while (index < genres.size) {
        val genre = genres[index]
        val poster = editorialPosterForTitle(genre.title, posters)
        if (poster == null) {
            rows += EditorialRow.Full(
                EditorialPiece(
                    genre = genre,
                    poster = null,
                    widthDp = usable,
                    heightDp = usable / EDITORIAL_FALLBACK_RATIO,
                ),
            )
            index += 1
            continue
        }
        val ra = poster.ratio
        val compactA = ra >= EDITORIAL_COMPACT_MIN_RATIO && ra < EDITORIAL_COMPACT_MAX_RATIO
        if (columns >= 2 && compactA) {
            val nextGenre = genres.getOrNull(index + 1)
            val nextPoster = nextGenre?.let { editorialPosterForTitle(it.title, posters) }
            val rb = nextPoster?.ratio
            if (nextGenre != null && nextPoster != null && rb != null &&
                rb >= EDITORIAL_COMPACT_MIN_RATIO && rb < EDITORIAL_COMPACT_MAX_RATIO
            ) {
                val height = (usable - gap) / (ra + rb)
                val leadingWidth = ra * height
                val trailingWidth = rb * height
                if (min(leadingWidth, trailingWidth) >= minWidth) {
                    rows += EditorialRow.Pair(
                        leading = EditorialPiece(genre, poster, leadingWidth, height),
                        trailing = EditorialPiece(nextGenre, nextPoster, trailingWidth, height),
                    )
                    index += 2
                    continue
                }
            }
        }
        rows += EditorialRow.Full(
            EditorialPiece(
                genre = genre,
                poster = poster,
                widthDp = usable,
                heightDp = usable / ra,
            ),
        )
        index += 1
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
    val fontScale = LocalConfiguration.current.fontScale
    val plan = remember(genres, posterAssets, availableWidthDp, columns, fontScale) {
        editorialPlan(genres, posterAssets, availableWidthDp, columns, fontScale)
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
        // `contentType` includes the viewport decisions and the row treatment so a
        // width/font-scale change rebuilds the row content instead of reusing the
        // previous geometry. Item keys are stable per genre, never per asset.
        items(plan, key = { it.key }, contentType = { "${it.kind}:$columns:$fontScale" }) { row ->
            when (row) {
                is EditorialRow.Full -> EditorialPieceTile(
                    piece = row.piece,
                    onArtworkLoad = onArtworkLoad,
                    onOpen = onOpen,
                    modifier = Modifier.fillMaxWidth().height(row.heightDp.dp),
                )
                is EditorialRow.Pair -> Row(
                    modifier = Modifier.fillMaxWidth().height(row.heightDp.dp),
                    horizontalArrangement = Arrangement.spacedBy(GENRE_GRID_GAP_DP.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    EditorialPieceTile(
                        piece = row.leading,
                        onArtworkLoad = onArtworkLoad,
                        onOpen = onOpen,
                        modifier = Modifier
                            .width(row.leading.widthDp.dp)
                            .fillMaxHeight(),
                    )
                    EditorialPieceTile(
                        piece = row.trailing,
                        onArtworkLoad = onArtworkLoad,
                        onOpen = onOpen,
                        modifier = Modifier
                            .width(row.trailing.widthDp.dp)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorialPieceTile(
    piece: EditorialPiece,
    onArtworkLoad: ((GenreViewMode, String, Boolean) -> Unit)?,
    onOpen: (String) -> Unit,
    modifier: Modifier,
) {
    val poster = piece.poster
    val spec = remember(poster) { editorialVisualSpec(poster) }
    var decodeFailed by remember(poster?.filename) { mutableStateOf(false) }
    if (poster == null || spec == null || decodeFailed) {
        GenreEditorialFallbackTile(
            genre = piece.genre,
            modifier = modifier,
            onOpen = { onOpen(piece.genre.id) },
        )
        return
    }
    GenreArtworkSurface(
        spec = spec,
        genre = piece.genre,
        count = piece.genre.tracks.size,
        view = "EDITORIAL",
        testTag = "genre_editorial_${piece.genre.id}",
        modifier = modifier,
        onOpen = { onOpen(piece.genre.id) },
    ) { artModifier ->
        GenreEditorialPosterArtwork(
            poster = poster,
            modifier = artModifier.testTag("genre_editorial_poster_${piece.genre.id}"),
            onImageResult = { ok ->
                if (!ok) decodeFailed = true
                onArtworkLoad?.let { notify ->
                    notify(GenreViewMode.EDITORIAL, piece.genre.id, ok)
                }
            },
        )
    }
}

/**
 * Compose fallback for a real genre whose approved poster is absent or failed to
 * decode. One surface, the real title and the real numeric chip; it never claims
 * a vertical artwork and never shows technical text.
 */
@Composable
internal fun GenreEditorialFallbackTile(
    genre: LibraryGenre,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    val highContrast = LocalVisualPreferences.current.highContrast
    val accent = if (highContrast) PureWhite else genreAccent(genre)
    val shape = RoundedCornerShape(14.dp)
    var focused by remember { mutableStateOf(false) }
    BoxWithConstraints(
        modifier
            .clip(shape)
            .clickable(role = Role.Button, onClick = onOpen)
            .onFocusChanged { focused = it.isFocused }
            .semantics(mergeDescendants = true) {
                contentDescription = genreCardLabel(genre)
            }
            .testTag("genre_editorial_${genre.id}"),
    ) {
        val wide = maxWidth.value >= 300f
        GenreArtworkPlaceholder(
            accent,
            Modifier.fillMaxSize().testTag("genre_editorial_fallback_${genre.id}"),
        )
        Box(Modifier.fillMaxSize().border(1.dp, accent.copy(alpha = 0.62f), shape))
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = if (wide) 22.dp else 16.dp, end = 14.dp, top = 18.dp,
                    bottom = 16.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Box(Modifier.width(30.dp).height(2.dp).background(accent.copy(alpha = 0.85f)))
            Text(
                text = genre.title,
                style = MichiTypography.sectionTitle.copy(
                    fontSize = if (wide) 28.sp else 22.sp,
                    lineHeight = if (wide) 32.sp else 26.sp,
                ),
                color = PureWhite,
                maxLines = if (wide) 2 else 3,
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
