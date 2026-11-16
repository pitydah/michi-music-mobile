package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import org.michimusic.mobile.ui.components.premium.MichiIconAction
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * Genre detail header with the origin view carried explicitly by [viewMode].
 *
 * The artwork branch is selected by the same [GenreViewMode] that opened the
 * genre, never by a filename or by the current overview selection. Editorial
 * renders its approved complete poster (or text-free Editorial production art
 * when the poster is absent); Mosaico renders its approved cinematic crop. A
 * missing asset degrades to the Compose plane of that same view, so an
 * Editorial header can never borrow a Mosaico image or vice versa.
 *
 * The artwork fills the upper band with a width-led, bottom-anchored crop. The
 * approved poster bakes its own title/subtitle block; on a narrow phone that
 * crop reaches into it, so [editorialTitleGuardDp] draws an opaque matte over
 * exactly the exposed remainder of the block. The first visible artwork row is
 * then always below the baked title: no partial glyph, no partial frame corner,
 * and the live title in the lower band stays the only readable title.
 */
@Composable
internal fun GenreDetailHeader(
    genre: LibraryGenre,
    viewMode: GenreViewMode,
    poster: EditorialPosterAsset?,
    mosaic: MosaicArtworkAsset?,
    productionAssets: Set<String>,
    onArtworkLoad: ((GenreViewMode, String, Boolean) -> Unit)?,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onOpenAtlas: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontScale: Float = LocalConfiguration.current.fontScale
    val largeText: Boolean = fontScale >= 1.5f
    val highContrast: Boolean = LocalVisualPreferences.current.highContrast
    val accent: Color = if (highContrast) PureWhite else genreAccent(genre)
    // Metrics stay Int dp outside the BoxWithConstraints scope. Compose `Dp`
    // is a value class with no Float conversion, and the scope injects its own
    // `maxWidth`/`maxHeight`, so the artwork band is derived from unshadowed
    // Int values instead of `Dp` arithmetic inside the scope.
    val headerHeightDp: Int = genreDetailHeaderHeightDp(fontScale)
    val bandHeightDp: Int = genreDetailHeaderBandHeightDp(fontScale)
    val artworkBandHeightDp: Int = (headerHeightDp - bandHeightDp).coerceAtLeast(0)
    val headerHeight: Dp = headerHeightDp.dp
    val bandHeight: Dp = bandHeightDp.dp
    val artworkBandHeight: Dp = artworkBandHeightDp.dp
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(headerHeight)
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceObsidian)
            .testTag("genre_detail_header"),
    ) {
        // Re-key identity: a genre change must never keep the previous decoded
        // painter while the next header asset is still in flight.
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(artworkBandHeight)
                .clickable(enabled = atlasGenreIsEligible(genre), role = Role.Button, onClick = onOpenAtlas)
                .semantics { contentDescription = "Explorar Atlas de ${genre.title}" }
                .testTag("genre_detail_header_artwork"),
        ) {
            // `maxWidth` belongs to BoxWithConstraintsScope; these locals are
            // the explicitly typed band metrics the title guard consumes.
            val artworkBandWidthDp: Float = maxWidth.value
            val artworkBandHeightDpValue: Float = artworkBandHeightDp.toFloat()
            key(genre.id, viewMode) {
                when (viewMode) {
                    GenreViewMode.FEATURED -> {
                        if (mosaic != null) {
                            Box(
                                Modifier.fillMaxSize()
                                    .testTag("genre_detail_header_mosaico_${genre.id}"),
                            ) {
                                GenreArtworkPlaceholder(accent)
                                GenreHeaderMosaicArtwork(
                                    asset = mosaic,
                                    onImageResult = onArtworkLoad?.let { notify ->
                                        { ok -> notify(GenreViewMode.FEATURED, genre.id, ok) }
                                    },
                                )
                            }
                        } else {
                            Box(
                                Modifier.fillMaxSize()
                                    .testTag("genre_detail_header_mosaico_fallback_${genre.id}"),
                            ) {
                                GenreArtworkPlaceholder(accent)
                            }
                        }
                    }
                    GenreViewMode.EDITORIAL -> {
                        if (poster != null) {
                            Box(
                                Modifier.fillMaxSize()
                                    .testTag("genre_detail_header_poster_${genre.id}"),
                            ) {
                                // The approved poster is complete; the plane below is
                                // only a same-view fallback if its bytes are absent.
                                GenreArtworkPlaceholder(accent)
                                GenreEditorialPosterArtwork(
                                    poster = poster,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                    // The finished poster bakes its title near the
                                    // top; crop from the bottom to keep that text
                                    // out of the live header band.
                                    alignment = Alignment.BottomCenter,
                                    onImageResult = onArtworkLoad?.let { notify ->
                                        { ok -> notify(GenreViewMode.EDITORIAL, genre.id, ok) }
                                    },
                                )
                            }
                        } else {
                            Box(
                                Modifier.fillMaxSize()
                                    .testTag("genre_detail_header_editorial_fallback_${genre.id}"),
                            ) {
                                GenreArtworkPlaceholder(accent)
                                // Text-free production art is the approved Editorial
                                // fallback when the finished poster is not bundled.
                                GenreEditorialArtwork(
                                    genreTitle = genre.title,
                                    treatment = GenreEditorialTreatment.COVER,
                                    columns = 1,
                                    availableAssetNames = productionAssets,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
            // The approved poster bakes its title/subtitle block in its top
            // 0.50. A width-led crop on a narrow phone reaches into that block;
            // replace exactly the exposed remainder with the design-system
            // matte so no partial glyph or frame corner can ever be visible.
            if (viewMode == GenreViewMode.EDITORIAL && poster != null) {
                val titleGuardDp = editorialTitleGuardDp(
                    posterRatio = poster.ratio,
                    bandWidthDp = artworkBandWidthDp,
                    bandHeightDp = artworkBandHeightDpValue,
                )
                if (titleGuardDp > 0.5f) {
                    Box(
                        Modifier.fillMaxWidth().height(titleGuardDp.dp)
                            .background(SurfaceObsidian)
                            .testTag("genre_detail_header_title_matte"),
                    )
                }
            }
        }

        // Protect the back affordance only over the artwork band.
        Box(
            Modifier.fillMaxWidth().height(artworkBandHeight)
                .background(HeaderTopScrim),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MichiIconAction(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Volver a géneros",
                onClick = onBack,
                tint = PureWhite,
                minTargetSize = MichiSpacing.minTouchTarget,
            )
            Text("Géneros", style = MichiTypography.microLabel, color = PureWhite)
            Spacer(Modifier.weight(1f))
            if (atlasGenreIsEligible(genre)) {
                Box(
                    modifier = Modifier
                        .heightIn(min = MichiSpacing.minTouchTarget)
                        .clip(RoundedCornerShape(16.dp))
                        .background(SurfaceObsidian.copy(alpha = 0.88f))
                        .clickable(role = Role.Button, onClick = onOpenAtlas)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("michi_atlas_open_${genre.id}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (largeText) "Atlas" else "Explorar Atlas",
                        style = MichiTypography.microLabel,
                        color = PureWhite,
                        maxLines = 1,
                    )
                }
            }
        }

        // Opaque lower band: the real title/count replace the baked artwork text.
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(bandHeight)
                .background(SurfaceObsidian.copy(alpha = 0.97f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = genre.title,
                    style = MichiTypography.sectionTitle.copy(
                        fontSize = if (largeText) 22.sp else 26.sp,
                        lineHeight = if (largeText) 26.sp else 30.sp,
                    ),
                    color = PureWhite,
                    maxLines = if (largeText) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("genre_detail_title"),
                )
                Text(
                    text = genreDetailCountLabel(genre),
                    style = MichiTypography.metadata,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("genre_detail_count"),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MichiIconAction(
                    icon = Icons.Filled.PlayArrow,
                    contentDescription = "Reproducir género ${genre.title}",
                    onClick = onPlay,
                    enabled = genre.tracks.isNotEmpty(),
                    tint = PrimaryPinkContainer,
                    minTargetSize = MichiSpacing.minTouchTarget,
                )
                MichiIconAction(
                    icon = Icons.Filled.Shuffle,
                    contentDescription = "Aleatorio: ${genre.title}",
                    onClick = onShuffle,
                    enabled = genre.tracks.isNotEmpty(),
                    tint = PureWhite,
                    minTargetSize = MichiSpacing.minTouchTarget,
                )
            }
        }
    }
}

/** The approved Mosaico crop is drawn as the upper-band image, never stretched. */
@Composable
internal fun GenreHeaderMosaicArtwork(
    asset: MosaicArtworkAsset,
    onImageResult: ((Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val request = remember(context, asset.filename) {
        ImageRequest.Builder(context)
            .data("file:///android_asset/$mosaicFolder/${asset.filename}")
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        onSuccess = {
            GenreArtworkDiagnostics.imageSuccess("MOSAIC", asset.filename)
            onImageResult?.invoke(true)
        },
        onError = { result ->
            GenreArtworkDiagnostics.imageFailure("MOSAIC", asset.filename,
                result.result.throwable)
            onImageResult?.invoke(false)
        },
    )
}

internal fun genreDetailCountLabel(genre: LibraryGenre): String {
    val songs = genre.tracks.size
    val albums = genre.albumCount
    return "$songs ${if (songs == 1) "canción" else "canciones"}" +
        " · $albums ${if (albums == 1) "álbum" else "álbumes"}"
}

/**
 * Opaque lower band for the real title/count/actions. It grows with the font
 * scale so 2x text never intrudes into the artwork band.
 */
internal fun genreDetailHeaderBandHeightDp(fontScale: Float): Int {
    val scale = fontScale.takeIf { it.isFinite() }?.coerceIn(1f, 2f) ?: 1f
    return (68f + (scale - 1f) * 28f).toInt().coerceIn(68, 96)
}

private val HeaderTopScrim = Brush.verticalGradient(
    0.00f to SurfaceObsidian.copy(alpha = 0.58f),
    0.55f to Color.Transparent,
)
