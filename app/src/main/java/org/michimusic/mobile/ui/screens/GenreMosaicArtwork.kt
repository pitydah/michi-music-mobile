package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import org.michimusic.mobile.ui.theme.AeroGraphiteRaised
import org.michimusic.mobile.ui.theme.SurfaceObsidian

/**
 * Visual-only layer for a compatible Mosaico crop.
 *
 * The bitmap is drawn exactly edge-to-edge inside the card's calibrated optical
 * outline ([Ui7OpticalFrameShape]). It is never cropped, stretched, zoomed or
 * blurred; the card adds no second border and no matte. The parent owns the
 * click target, accessible name and numeric chip.
 */
@Composable
internal fun GenreMosaicArtwork(
    asset: MosaicArtworkAsset,
    genreId: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onImageResult: ((Boolean) -> Unit)? = null,
) {
    var decodeFailed by remember(asset.filename) { mutableStateOf(false) }
    Box(modifier.fillMaxSize()) {
        if (decodeFailed) GenreArtworkPlaceholder(accent)
        else {
            val context = LocalContext.current
            val request = remember(context, asset.filename) {
                ImageRequest.Builder(context)
                    .data("file:///android_asset/$mosaicFolder/${asset.filename}")
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null, // The parent card supplies the real name/count.
                modifier = Modifier.fillMaxSize().testTag("genre_artwork_$genreId"),
                contentScale = ContentScale.Fit,
                onSuccess = {
                    GenreArtworkDiagnostics.imageSuccess("MOSAIC", asset.filename)
                    onImageResult?.invoke(true)
                },
                onError = { result ->
                    decodeFailed = true
                    GenreArtworkDiagnostics.imageFailure(
                        "MOSAIC", asset.filename, result.result.throwable,
                    )
                    onImageResult?.invoke(false)
                },
            )
        }
    }
}

/**
 * One neutral silhouette, no fake cat, no second border and no visible technical
 * text. Used while an approved asset decodes and by both view fallbacks.
 */
@Composable
internal fun GenreArtworkPlaceholder(
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(SurfaceObsidian)) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width * 0.66f, size.height * 0.42f)
            val radius = size.minDimension * 0.28f
            drawCircle(
                color = accent.copy(alpha = 0.16f),
                radius = radius,
                center = center,
                style = Stroke(width = 1.2.dp.toPx()),
            )
            drawCircle(
                color = accent.copy(alpha = 0.09f),
                radius = radius * 0.58f,
                center = center,
            )
        }
        Box(Modifier.fillMaxSize().background(AeroGraphiteRaised.copy(alpha = 0.18f)))
    }
}
