package org.michimusic.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.mobile.ui.theme.AeroGraphite
import org.michimusic.mobile.ui.theme.AeroGraphiteRaised
import org.michimusic.mobile.ui.theme.AeroHairline
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * M52 visual foundation. Restrained light, editorial typography and musical
 * hierarchy: no repeated blur passes, infinite glows or stock glass cards.
 * Purely decorative: no playback state, no touch handling, no fake telemetry.
 */
@Composable
fun AeroPremiumBackdrop(
    accent: Color = TertiaryCyan,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to AeroGraphite.copy(alpha = 0.54f),
                0.36f to SurfaceObsidian,
                1f to SurfaceObsidian,
            ),
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(150.dp).background(
                Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.045f), Color.Transparent),
                ),
            ),
        )
    }
}

/** Real library count, real playback action. No recommendation-engine claims. */
@Composable
fun AeroEditorialHero(
    trackCount: Int,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier.fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(AeroGraphiteRaised, AeroGraphite)),
                shape,
            )
            .border(0.8.dp, AeroHairline, shape)
            .padding(horizontal = 23.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(7.dp).background(TertiaryCyan, RoundedCornerShape(4.dp)))
            Text("TU UNIVERSO MUSICAL", color = TertiaryCyan, fontSize = 10.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.7.sp)
        }
        Text(
            "Escucha a tu manera.",
            fontSize = 29.sp,
            lineHeight = 35.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.75).sp,
            color = PureWhite,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text("$trackCount ${if (trackCount == 1) "canción" else "canciones"} en tu biblioteca",
            color = TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(3.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onPlayAll,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("quick_play_button"),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryPinkContainer),
                shape = RoundedCornerShape(13.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(19.dp))
                Text("Reproducir", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onShuffle,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("shuffle_button"),
                shape = RoundedCornerShape(13.dp),
            ) {
                Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(17.dp))
                Text("Aleatorio", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Editorial empty state: cause first, actions outside. No decorative fullscreen icon. */
@Composable
fun AeroEmptyEditorial(
    eyebrow: String,
    title: String,
    description: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(AeroGraphiteRaised, AeroGraphite)), shape)
            .border(0.7.dp, AeroHairline, shape)
            .padding(24.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier.size(44.dp)
                    .background(TertiaryCyan.copy(alpha = 0.12f), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = TertiaryCyan, modifier = Modifier.size(23.dp))
            }
            Text(eyebrow, color = TertiaryCyan, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.25.sp)
        }
        Text(title, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold,
            color = PureWhite, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(description, fontSize = 13.sp, lineHeight = 19.sp, color = TextSecondary)
        content()
    }
}
