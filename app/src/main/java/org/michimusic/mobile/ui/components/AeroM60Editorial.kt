package org.michimusic.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.core.models.Track
import org.michimusic.mobile.ui.theme.AeroGraphite
import org.michimusic.mobile.ui.theme.AeroGraphiteRaised
import org.michimusic.mobile.ui.theme.InterFontFamily
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.mobile.ui.theme.TertiaryCyan

/** M60 editorial system: surfaces represent semantic hierarchy, not repeated glass cards. */
@Composable
fun AeroSectionHeading(
    title: String,
    eyebrow: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (eyebrow != null) {
            Text(eyebrow.uppercase(), fontFamily = InterFontFamily, color = TertiaryCyan,
                fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.15.sp)
        }
        Text(title, fontFamily = InterFontFamily, color = PureWhite,
            fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Responsive action layout is a deterministic rule, not a side-effect of playback state. */
fun aeroM60StackActions(availableWidthDp: Float, fontScale: Float): Boolean =
    availableWidthDp < 330f || fontScale >= 1.3f

/**
 * Actual artwork and actual library item. The selection is supplied by the screen,
 * and the featured action must resolve its exact queue position before playback.
 * No fabricated recommendations, mock covers, background blur or autoplay.
 */
@Composable
fun AeroLibraryFeature(
    featuredTrack: Track,
    trackCount: Int,
    onFeatured: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val highContrast = LocalVisualPreferences.current.highContrast
    val shape = RoundedCornerShape(25.dp)
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(org.michimusic.mobile.ui.theme.MichiSurfaces.hero))
            .border(if (highContrast) 1.4.dp else 0.75.dp,
                if (highContrast) Color.White.copy(alpha = 0.88f) else Color.White.copy(alpha = 0.20f), shape)
            .testTag("m60_library_feature"),
    ) {
        val compact = aeroM60StackActions(maxWidth.value, LocalConfiguration.current.fontScale)
        Column(
            modifier = Modifier.fillMaxWidth().padding(if (compact) 18.dp else 21.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.size(6.dp).background(TertiaryCyan, RoundedCornerShape(3.dp)))
                Text("SELECCIÓN DE TU BIBLIOTECA", color = TertiaryCyan,
                    fontFamily = InterFontFamily, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 17.dp),
            ) {
                val coverSize = if (compact) 100.dp else 128.dp
                AeroArtworkMat(modifier = Modifier.size(coverSize), cornerRadius = 13.dp, inset = 3.dp) {
                    AlbumArtView(
                        coverStyle = coverStyleFor(featuredTrack.coverId.ifEmpty { featuredTrack.title }),
                        imageModel = featuredTrack.coverId,
                        fallbackMediaUri = featuredTrack.filepath,
                        modifier = Modifier.matchParentSize(), cornerRadius = 13.dp, borderWidth = 0.dp,
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("UNA CANCIÓN PARA ESCUCHAR", color = TextSecondary,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp)
                    Text(featuredTrack.title, color = PureWhite, fontFamily = InterFontFamily,
                        fontSize = if (compact) 20.sp else 24.sp,
                        lineHeight = if (compact) 25.sp else 29.sp,
                        fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(featuredTrack.artist, color = TextSecondary,
                        fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$trackCount ${if (trackCount == 1) "canción" else "canciones"} en tu colección",
                        color = TertiaryCyan, fontSize = 12.sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    AeroActionButton("Escuchar selección", onFeatured, Modifier.fillMaxWidth(),
                        Icons.Filled.PlayArrow, AeroActionKind.Primary, testTag = "m60_feature_play")
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        AeroActionButton("Todo", onPlayAll, Modifier.weight(1f),
                            kind = AeroActionKind.Secondary, testTag = "quick_play_button")
                        AeroActionButton("Aleatorio", onShuffle, Modifier.weight(1f),
                            Icons.Filled.Shuffle, AeroActionKind.Quiet, testTag = "shuffle_button")
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    // An even three-way split, not 1.3 / 0.85 / 1.0: the narrowest
                    // button used to leave ~57dp for "Mezclar" and could only break
                    // the word or ellipsize it to "Mezc...". Equal weights give the
                    // longest label the room it needs at full 14.sp.
                    AeroActionButton("Escuchar", onFeatured, Modifier.weight(1f),
                        Icons.Filled.PlayArrow, AeroActionKind.Primary, testTag = "m60_feature_play")
                    AeroActionButton("Todo", onPlayAll, Modifier.weight(1f),
                        kind = AeroActionKind.Secondary, testTag = "quick_play_button")
                    AeroActionButton("Mezclar", onShuffle, Modifier.weight(1f),
                        Icons.Filled.Shuffle, AeroActionKind.Quiet, testTag = "shuffle_button")
                }
            }
        }
    }
}

/** True when the signal/output labels need separate rows rather than clipped 50/50 cells. */
fun aeroM60StackSignal(availableWidthDp: Float, fontScale: Float): Boolean =
    availableWidthDp < 340f || fontScale >= 1.4f

/** Public wording must never infer a lossless or bit-perfect path from having a song. */
fun aeroNowPlayingQualityBadge(pcmObserved: Boolean): String =
    if (pcmObserved) "PCM decodificado" else "Michi Music"

/**
 * Readable signal and requested output footer, never a bit-perfect claim.
 * Each action keeps an independent 48dp target at fontScale 200%.
 */
@Composable
fun AeroSignalRouteStrip(
    signalLabel: String,
    signalDescription: String,
    destination: String,
    onSignalClick: () -> Unit,
    onDestinationClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val highContrast = LocalVisualPreferences.current.highContrast
    val fontScale = LocalConfiguration.current.fontScale
    val shape = RoundedCornerShape(18.dp)
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val stacked = aeroM60StackSignal(maxWidth.value, fontScale)
        Column(
            modifier = Modifier.fillMaxWidth().clip(shape)
                .background(if (highContrast) Color(0xFF161D27) else org.michimusic.mobile.ui.theme.MichiSurfaces.baseSolid)
                .border(if (highContrast) 1.2.dp else 0.7.dp,
                    Color.White.copy(alpha = if (highContrast) 0.85f else 0.17f), shape)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val signal: @Composable (Modifier) -> Unit = { cellModifier ->
                Column(
                    modifier = cellModifier.clip(RoundedCornerShape(9.dp))
                        .clickable(role = Role.Button, onClick = onSignalClick)
                        .semantics { contentDescription = "$signalLabel. Señal decodificada, no salida física certificada. Abrir Signal Truth" }
                        .heightIn(min = 48.dp)
                        .testTag("signal_truth_button"),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("SEÑAL DECODIFICADA", color = TextSecondary,
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(signalLabel, color = TertiaryCyan,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = if (stacked) 3 else 2, overflow = TextOverflow.Ellipsis)
                }
            }
            val output: @Composable (Modifier) -> Unit = { cellModifier ->
                Column(
                    modifier = cellModifier.clip(RoundedCornerShape(9.dp))
                        .clickable(role = Role.Button, onClick = onDestinationClick)
                        .semantics { contentDescription = "Salida solicitada: $destination. Abrir selector de audio" }
                        .heightIn(min = 48.dp)
                        .testTag("audio_route_button"),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("SALIDA SOLICITADA", color = TextSecondary,
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(destination, color = PureWhite, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = if (stacked) 3 else 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (stacked) {
                signal(Modifier.fillMaxWidth())
                output(Modifier.fillMaxWidth())
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    signal(Modifier.weight(1f))
                    output(Modifier.weight(1f))
                }
            }
            Text(signalDescription, color = TextSecondary, fontSize = 12.sp,
                lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
