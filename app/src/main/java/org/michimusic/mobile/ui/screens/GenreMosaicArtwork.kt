package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import org.michimusic.mobile.ui.theme.SurfaceObsidian

/**
 * Visual-only layer for FEATURED focal and secondary cards.
 *
 * The Compose plane is always painted first, so a missing, unapproved or
 * undecodable asset degrades to the pre-existing geometry instead of an empty
 * card. The approved crop is then drawn with [ContentScale.Fit]: the source bytes
 * are never cropped, scaled unevenly or re-encoded. Compose still owns the genre
 * name, the track count and the click target; a scrim keeps that live label
 * legible over the artwork's own baked lettering.
 */
@Composable
internal fun GenreMosaicArtwork(
    asset: MosaicArtworkAsset?,
    genreId: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onImageResult: ((Boolean) -> Unit)? = null,
) {
    Box(modifier.fillMaxSize()) {
        GenreVisualPlane(genreId, accent, mode = GenreViewMode.FEATURED)
        if (asset != null) {
            val context = LocalContext.current
            val request = remember(context, asset.filename) {
                ImageRequest.Builder(context)
                    .data("file:///android_asset/$mosaicFolder/${asset.filename}")
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null, // The parent card supplies the real scoped name/count.
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
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
            // Top scrim protects the eyebrow row; the lower ramp hides the crop's own
            // baked label so a stale or differently spelled name never competes with
            // the dynamic Compose title.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0.00f to SurfaceObsidian.copy(alpha = 0.46f),
                        0.22f to Color.Transparent,
                        0.52f to Color.Transparent,
                        0.70f to SurfaceObsidian.copy(alpha = 0.72f),
                        0.84f to SurfaceObsidian.copy(alpha = 0.94f),
                        1.00f to SurfaceObsidian,
                    ),
                ),
            )
        }
    }
}
