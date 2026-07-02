package org.michimusic.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.ArtworkGold
import org.michimusic.mobile.ui.theme.ArtworkOverlay
import org.michimusic.mobile.ui.theme.GlassBorder
import org.michimusic.mobile.ui.theme.MichiRadius
import org.michimusic.mobile.ui.theme.SynthwaveEnd
import org.michimusic.mobile.ui.theme.SynthwaveMid
import org.michimusic.mobile.ui.theme.SynthwaveStart

@Composable
fun MichiArtworkCard(
    coverUri: String?,
    modifier: Modifier = Modifier,
) {
    val synthwaveGradient = Brush.verticalGradient(
        colors = listOf(SynthwaveStart, SynthwaveMid, SynthwaveEnd),
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(MichiRadius.artwork))
            .background(synthwaveGradient)
            .border(1.dp, GlassBorder, RoundedCornerShape(MichiRadius.artwork)),
        contentAlignment = Alignment.Center,
    ) {
        if (coverUri != null) {
            AsyncImage(
                model = coverUri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .offset(y = 20.dp)
                    .clip(CircleShape)
                    .background(Brush.verticalGradient(listOf(ArtworkGold, AccentPink))),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, ArtworkOverlay))),
            )
        }
    }
}
