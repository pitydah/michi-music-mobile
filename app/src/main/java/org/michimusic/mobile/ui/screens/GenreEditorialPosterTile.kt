package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TextMuted

/**
 * Finished poster card (`LEGACY_COMPLETE`).
 *
 * The approved WebP is the complete poster: its baked title and frame are the
 * only title and border, so Compose adds neither. The card height comes from the
 * treatment — 192dp cover, 246dp portrait pair, 200dp single column — never from
 * the poster's own panorama ratio, so the two cards of a pair always share one
 * exterior height and align top/bottom. The poster keeps its whole frame
 * (`Fit`) mounted 8dp inside the card; the area under it is the card's one
 * continuous surface, where the real track count stays integrated instead of
 * floating in a detached footer.
 *
 * The approved pack has no vertical artwork, so a portrait slot is explicitly
 * marked `PENDIENTE DE RECURSO VERTICAL APROBADO` rather than faked with a crop.
 * No image is ever mirrored.
 */
@Composable
internal fun GenreEditorialPosterTile(
    genre: LibraryGenre,
    poster: EditorialPosterAsset,
    treatment: GenreEditorialTreatment,
    columns: Int,
    modifier: Modifier = Modifier,
    onArtworkLoad: ((GenreViewMode, String, Boolean) -> Unit)? = null,
    onOpen: () -> Unit,
) {
    val count = genre.tracks.size
    val fontScale = LocalConfiguration.current.fontScale
    val spec = editorialPresentationSpec(poster, treatment)
    var artworkUnavailable by remember(poster.filename) { mutableStateOf(false) }
    val cardHeight = genreEditorialHeightDp(treatment, columns, fontScale)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val mount = EDITORIAL_POSTER_MOUNT_INSET_DP.dp
        val reserve = genreCounterReserveDp(fontScale)
        val innerWidth = (maxWidth.value - 2 * EDITORIAL_POSTER_MOUNT_INSET_DP).coerceAtLeast(1f)
        val availableHeight = (cardHeight - EDITORIAL_POSTER_MOUNT_INSET_DP - reserve)
            .coerceAtLeast(24f)
        val bandHeight = editorialPosterBandHeightDp(innerWidth, availableHeight, poster.ratio)
        val bandWidth = minOf(innerWidth, bandHeight * poster.ratio)
        val cover = treatment == GenreEditorialTreatment.COVER
        Box(
            Modifier.fillMaxWidth().height(cardHeight.dp)
                .clip(MichiShapes.md)
                .background(SurfaceObsidian)
                .clickable(role = Role.Button, onClick = onOpen)
                .semantics(mergeDescendants = true) {
                    contentDescription = genreCardLabel(genre)
                }
                .testTag("genre_editorial_${genre.id}"),
        ) {
            // The mount is inset enough that the card clip never cuts the
            // approved baked frame, and the reserved band keeps the count clear
            // of the artwork.
            Box(
                Modifier.fillMaxSize().padding(
                    start = mount,
                    end = mount,
                    top = mount,
                    bottom = reserve.dp,
                ),
            ) {
                if (cover) {
                    Box(Modifier.align(Alignment.Center)) {
                        PosterBand(genre, poster, bandWidth, bandHeight, onArtworkLoad) {
                            artworkUnavailable = it
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            PosterBand(genre, poster, bandWidth, bandHeight, onArtworkLoad) {
                                artworkUnavailable = it
                            }
                        }
                        if (spec.pendingVerticalAsset) {
                            Box(
                                Modifier.weight(1f).fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    EDITORIAL_PENDING_VERTICAL_LABEL,
                                    style = MichiTypography.microLabel,
                                    color = TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.testTag(
                                        "genre_editorial_pending_vertical_${genre.id}",
                                    ),
                                )
                            }
                        } else if (artworkUnavailable) {
                            Box(
                                Modifier.weight(1f).fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) { FallbackPosterTitle(genre.title) }
                        }
                    }
                }
                if (cover && artworkUnavailable) {
                    FallbackPosterTitle(genre.title, Modifier.align(Alignment.Center))
                }
            }
            GenreCounterChip(
                count = count,
                testTag = "genre_counter_${genre.id}",
                modifier = Modifier.align(spec.counterAnchor.toAlignment())
                    .padding(GENRE_COUNTER_INSET_DP.dp),
            )
        }
    }
}

/** The complete poster at its truthful aspect: `Fit`, never a false crop. */
@Composable
private fun PosterBand(
    genre: LibraryGenre,
    poster: EditorialPosterAsset,
    bandWidth: Float,
    bandHeight: Float,
    onArtworkLoad: ((GenreViewMode, String, Boolean) -> Unit)?,
    onUnavailable: (Boolean) -> Unit,
) {
    Box(
        Modifier.width(bandWidth.dp).height(bandHeight.dp)
            .testTag("genre_editorial_poster_${genre.id}"),
    ) {
        GenreEditorialPosterArtwork(
            poster = poster,
            modifier = Modifier.fillMaxSize(),
            onImageResult = { ok ->
                onUnavailable(!ok)
                onArtworkLoad?.let { notify -> notify(GenreViewMode.EDITORIAL, genre.id, ok) }
            },
        )
    }
}

/** Live title only when the approved poster provably has none / cannot decode. */
@Composable
private fun FallbackPosterTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MichiTypography.sectionTitle.copy(fontSize = 26.sp, lineHeight = 30.sp),
        color = PureWhite,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(horizontal = 12.dp),
    )
}

/** Honest state for a portrait slot with no approved vertical artwork yet. */
internal const val EDITORIAL_PENDING_VERTICAL_LABEL = "PENDIENTE DE RECURSO VERTICAL APROBADO"
