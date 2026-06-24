package org.michimusic.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.GlowPink
import org.michimusic.mobile.ui.theme.SurfaceDark

@Composable
fun GlowPlayButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Int = 48,
) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(GlowPink, CircleShape)
            .background(AccentPink, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = "Play",
            tint = SurfaceDark,
            modifier = Modifier.size((size * 0.55).dp),
        )
    }
}
