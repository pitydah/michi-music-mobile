package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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

/**
 * Finished poster card (`LEGACY_COMPLETE`).
 *
 * The approved WebP is the complete poster: its baked title and frame are the
 * only title and border, so Compose adds neither. The card keeps the whole
 * poster at its truthful aspect (`Fit`) and mounts it 8dp inside the card so the
 * rounded clip can never cut the baked frame. A compact count row follows the
 * poster after a 4dp association gap; the row shares the card's one continuous
 * surface, has no background/separator of its own, and cannot create the old
 * large empty band between a panoramic poster and its counter.
 *
 * The 192dp cover / 246dp portrait treatment heights are used only when the
 * approved asset is actually vertical. Historical panoramic sheets drive their
 * own compact card height, preserving the whole composition until an approved
 * vertical variant exists. That inventory gap is recorded as
 * [GenreArtworkPresentationSpec.pendingVerticalAsset] for tests/reporting and is
 * never rendered as visible technical text.
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
    val chipHeight = genreCounterHeightDp(fontScale)
    val vertical = poster.isVertical
    // Only an authentic vertical resource may consume a portrait/cover treatment
    // slot. A horizontal historical sheet keeps its own proportional height.
    val maxBandHeight = if (vertical) {
        editorialPosterTreatmentBandMaxDp(
            treatmentHeightDp = genreEditorialHeightDp(treatment, columns, fontScale).toFloat(),
            chipHeightDp = chipHeight,
        )
    } else {
        Float.MAX_VALUE
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val mount = EDITORIAL_POSTER_MOUNT_INSET_DP
        val innerWidth = (maxWidth.value - 2 * mount).coerceAtLeast(1f)
        val bandHeight = editorialPosterBandHeightDp(
            innerWidthDp = innerWidth,
            maxBandHeightDp = maxBandHeight,
            posterRatio = poster.ratio,
        )
        val bandWidth = minOf(innerWidth, bandHeight * poster.ratio)
        Box(
            Modifier.fillMaxWidth()
                .clip(MichiShapes.md)
                .background(SurfaceObsidian)
                .clickable(role = Role.Button, onClick = onOpen)
                .semantics(mergeDescendants = true) {
                    contentDescription = genreCardLabel(genre)
                }
                .testTag("genre_editorial_${genre.id}"),
        ) {
            Column(Modifier.fillMaxWidth().padding(mount.dp)) {
                Box(
                    Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(Modifier.width(bandWidth.dp).height(bandHeight.dp)) {
                        PosterBand(genre, poster, bandWidth, bandHeight, onArtworkLoad) {
                            artworkUnavailable = it
                        }
                        if (artworkUnavailable) {
                            FallbackPosterTitle(genre.title, Modifier.align(Alignment.Center))
                        }
                    }
                }
                Spacer(Modifier.height(EDITORIAL_POSTER_COUNTER_GAP_DP.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().height(chipHeight.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (
                        spec.counterAnchor == GenreOverlayAnchor.BOTTOM_END
                    ) Arrangement.End else Arrangement.Start,
                ) {
                    // The chip fills the documented counter row, so the 4dp gap
                    // above it and the 8dp mount below it are exact instead of
                    // drifting by the difference between the row height and the
                    // packed label height.
                    GenreCounterChip(
                        count = count,
                        testTag = "genre_counter_${genre.id}",
                        modifier = Modifier.fillMaxHeight(),
                    )
                }
            }
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
        textAlign = TextAlign.Center,
        modifier = modifier.padding(horizontal = 12.dp),
    )
}
