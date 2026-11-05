package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.michimusic.core.models.Track
import org.michimusic.mobile.artist.ArtistExternalProfile
import org.michimusic.mobile.artist.CommonsPortrait
import org.michimusic.mobile.artist.allowedCommonsPortrait
import org.michimusic.mobile.ui.components.AlbumArtView
import org.michimusic.mobile.ui.components.artistMosaicMonogram
import org.michimusic.mobile.ui.components.biographyStatusText
import org.michimusic.mobile.ui.components.coverStyleFor
import org.michimusic.mobile.ui.components.portraitStatusText
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.InterFontFamily
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextSecondary

@Composable
internal fun ArtistDetailHero(
    artistCredit: String,
    portrait: CommonsPortrait?,
    albumCount: Int,
    trackCount: Int,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(24.dp)
    // At large font scales the 62.sp monogram and the artist name collide inside the
    // hero. The name is the information, so the decorative monogram steps aside and the
    // hero grows with its content instead of clipping or overlapping.
    val fontScale = LocalDensity.current.fontScale
    // The monogram is the fallback, not wallpaper: it stays visible while the
    // image loads and returns if Coil reports a decode/network error.
    var photoVisible by remember(portrait?.imageUrl) { mutableStateOf(false) }
    Box(
        modifier = modifier.fillMaxWidth().heightIn(min = 248.dp).clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF26364A), Color(0xFF302639))))
            .border(0.8.dp, Color.White.copy(alpha = 0.21f), shape)
            .testTag("artist_detail_hero"),
    ) {
        // Draw the monogram BELOW Coil regardless of URL availability: if the
        // verified remote image fails/offline, the hero still has an identity.
        if (fontScale < 1.6f && !photoVisible) {
            Text(
                artistMosaicMonogram(artistCredit),
                modifier = Modifier.align(Alignment.Center).testTag("artist_detail_hero_monogram"),
                color = PureWhite.copy(alpha = 0.72f),
                fontSize = 62.sp,
                fontFamily = InterFontFamily,
            )
        }
        if (portrait != null && allowedCommonsPortrait(portrait)) {
            AsyncImage(
                model = portrait.imageUrl,
                contentDescription = "Fotografía de $artistCredit. Autoría en sección de biografía",
                contentScale = ContentScale.Crop,
                onSuccess = { photoVisible = true },
                onError = { photoVisible = false },
                modifier = Modifier.fillMaxSize().testTag("artist_detail_hero_photo"),
            )
            // Distinguishes "URL present" from "bytes decoded" for screenshot
            // evidence without changing the visible layout.
            if (photoVisible) {
                Box(Modifier.fillMaxSize().testTag("artist_detail_hero_photo_loaded"))
            }
        }
        Box(
            modifier = Modifier.fillMaxWidth().height(132.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE7080D15)))),
        )
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(artistCredit.ifBlank { "Artista desconocido" }, color = PureWhite,
                fontFamily = InterFontFamily, fontWeight = FontWeight.Bold,
                fontSize = 27.sp, lineHeight = 32.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            Text(
                "${if (albumCount == 1) "1 álbum" else "$albumCount álbumes"}  ·  " +
                    if (trackCount == 1) "1 canción" else "$trackCount canciones",
                color = PureWhite.copy(alpha = 0.83f), fontSize = 12.sp,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArtistBiographySection(
    artistCredit: String,
    profile: ArtistExternalProfile?,
    isStale: Boolean,
    portrait: CommonsPortrait?,
) {
    val uriHandler = LocalUriHandler.current
    val bio = profile?.biography?.takeIf(String::isNotBlank)
    var expanded by rememberSaveable(artistCredit) { mutableStateOf(false) }
    var hasOverflow by remember(artistCredit, bio) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
        Text("SOBRE EL ARTISTA", color = PrimaryPinkContainer, fontSize = 11.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
        if (isStale && profile != null) {
            Text(
                "Datos externos guardados: requieren actualización. La asociación previa no demuestra actualidad.",
                color = TextSecondary, fontSize = 11.sp, lineHeight = 16.sp,
                modifier = Modifier.testTag("artist_detail_stale_notice"),
            )
        }
        if (bio != null) {
            Text(
                bio,
                color = PureWhite.copy(alpha = 0.89f), fontSize = 13.sp, lineHeight = 20.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 5,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { layout ->
                    if (!expanded) hasOverflow = layout.hasVisualOverflow
                },
                modifier = Modifier.testTag("artist_detail_biography"),
            )
            // The disclosure is only real when the intro actually exceeds the
            // five-line editorial window; a short verified intro shows no
            // inert "Leer más" control.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (hasOverflow || expanded) {
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (expanded) "Leer menos" else "Leer más", color = TertiaryCyan, fontSize = 12.sp)
                    }
                }
                profile.biographySourceUrl?.let { url ->
                    TextButton(onClick = { uriHandler.openUri(url) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Fuente: Wikipedia", color = TextSecondary, fontSize = 11.sp)
                    }
                }
            }
        } else {
            Text(
                if (profile == null) {
                    "No hay biografía verificable disponible. Confirma la identidad del artista en Enriquecer."
                } else {
                    biographyStatusText(profile.biographyOutcome)
                },
                color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp,
                modifier = Modifier.testTag("artist_detail_no_biography"),
            )
        }
        if (portrait != null) {
            TextButton(
                onClick = { uriHandler.openUri(portrait.commonsPageUrl) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("artist_detail_photo_attribution"),
            ) {
                Text("Foto: ${portrait.attribution} · ${portrait.license}",
                    fontSize = 10.sp, color = TextSecondary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Start)
            }
            TextButton(
                onClick = { uriHandler.openUri(portrait.licenseUrl) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("artist_detail_photo_license"),
            ) {
                Text("Consultar licencia de la fotografía", fontSize = 11.sp, color = TertiaryCyan)
            }
        } else if (profile != null && profile.portrait == null) {
            Text(
                portraitStatusText(profile.portraitOutcome),
                color = TextSecondary, fontSize = 11.sp, lineHeight = 16.sp,
                modifier = Modifier.testTag("artist_detail_portrait_status"),
            )
        }
    }
}

@Composable
internal fun ArtistDetailSectionHeader(name: String, count: Int? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, color = PureWhite, fontFamily = InterFontFamily,
            fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
        if (count != null) {
            Text(count.toString(), color = TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun ArtistDetailTrackRow(
    track: Track, albumTitle: String, isPlayable: Boolean, onClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = isPlayable, role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) {
                    contentDescription = if (isPlayable)
                        "Reproducir ${track.title} · ${albumTitle.ifBlank { "Álbum desconocido" }}"
                    else "${track.title} · no disponible para reproducir"
                }
                .padding(horizontal = 4.dp, vertical = 7.dp)
                .testTag("artist_detail_track_${track.id}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AlbumArtView(
                coverStyle = coverStyleFor(track.coverId.ifBlank { track.title }),
                imageModel = track.coverId,
                fallbackMediaUri = track.filepath,
                modifier = Modifier.size(51.dp),
                cornerRadius = 9.dp,
                borderWidth = 0.5.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(track.title, color = PureWhite, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Text(if (isPlayable) albumTitle else "$albumTitle · Sin acceso al audio",
                    color = TextSecondary, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            androidx.compose.material3.Icon(Icons.Filled.PlayArrow, contentDescription = null,
                tint = if (isPlayable) TertiaryCyan else TextSecondary.copy(alpha = 0.45f),
                modifier = Modifier.size(22.dp))
        }
        HorizontalDivider(color = GlassBorderLow.copy(alpha = 0.42f), thickness = 0.6.dp)
    }
}
