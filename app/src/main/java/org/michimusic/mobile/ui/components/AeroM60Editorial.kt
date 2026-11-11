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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import org.michimusic.mobile.R
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.core.models.Track
import org.michimusic.mobile.playback.PlaybackEndpoint
import org.michimusic.mobile.playback.RemoteEndpointPreview
import org.michimusic.mobile.playback.EndpointType
import org.michimusic.mobile.ui.theme.AeroGraphite
import org.michimusic.mobile.ui.theme.AeroGraphiteRaised
import org.michimusic.mobile.ui.theme.AeroFinish
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.aeroResolveMaterial
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.InterFontFamily
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.motion.AeroObservedText
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
            // Editorial eyebrow: pink identifies content sections. Cyan stays reserved
            // for technical evidence such as Signal Truth and audio routes.
            Text(eyebrow.uppercase(), fontFamily = InterFontFamily, color = PrimaryPinkContainer,
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
    val visual = LocalVisualPreferences.current
    val highContrast = visual.highContrast
    val material = aeroResolveMaterial(
        role = AeroSurfaceRole.Raised,
        requestedFinish = AeroFinish.Anodized,
        profile = visual.appearanceProfile,
        highContrast = highContrast,
        microtexturesEnabled = visual.microtexturesEnabled,
    )
    val shape = RoundedCornerShape(25.dp)
    // One opt-in machined finish over the EXISTING Aero hero gradient.
    // Colorful artwork remains the focal point; no animated blur and no second border.
    val grain = if (material.spec.grainAlpha > 0f) {
        ImageBitmap.imageResource(R.drawable.aero_graphite_grain)
    } else null
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth()
            .clip(shape)
            .background(
                org.michimusic.mobile.ui.theme.aeroVerticalGradientBrush(
                    org.michimusic.mobile.ui.theme.AeroGradientRole.Hero,
                    highContrast,
                ),
            )
            .aeroFinishDrawing(
                material.finish,
                material.spec,
                grain,
                shape,
            )
            .border(if (highContrast) 1.4.dp else 0.75.dp,
                if (highContrast) Color.White.copy(alpha = 0.88f) else Color.White.copy(alpha = 0.20f), shape)
            .testTag("m60_library_feature"),
    ) {
        val compact = aeroM60StackActions(maxWidth.value, LocalDensity.current.fontScale)
        Column(
            modifier = Modifier.fillMaxWidth().padding(if (compact) 18.dp else 21.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                // Editorial marker, not telemetry: the Aero identity speaks pink about
                // content, and reserves cyan for Signal Truth and audio routes.
                Box(Modifier.size(6.dp).background(PrimaryPinkContainer, RoundedCornerShape(3.dp)))
                Text("SELECCIÓN DE TU BIBLIOTECA", color = PrimaryPinkContainer,
                    fontFamily = InterFontFamily, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 17.dp),
            ) {
                val coverSize = if (compact) 100.dp else 128.dp
                AeroArtworkMat(
                    modifier = Modifier.size(coverSize),
                    cornerRadius = 13.dp,
                    inset = 3.dp,
                    emphasis = AeroArtworkEmphasis.Featured,
                ) {
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
                        color = PrimaryPinkContainer, fontSize = 12.sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    AeroActionButton("Escuchar selección", onFeatured, Modifier.fillMaxWidth(),
                        Icons.Filled.PlayArrow, AeroActionKind.Primary, testTag = "m60_feature_play")
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        AeroActionButton("Todo", onPlayAll, Modifier.weight(1f),
                            kind = AeroActionKind.Secondary, testTag = "quick_play_button",
                            accessibilityLabel = "Reproducir todas las canciones")
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
                        kind = AeroActionKind.Secondary, testTag = "quick_play_button",
                            accessibilityLabel = "Reproducir todas las canciones")
                    AeroActionButton("Mezclar", onShuffle, Modifier.weight(1f),
                        Icons.Filled.Shuffle, AeroActionKind.Quiet, testTag = "shuffle_button")
                }
            }
        }
    }
}

/** True when the signal/output labels need separate rows rather than clipped 50/50 cells. */
fun aeroM60StackSignal(availableWidthDp: Float, fontScale: Float): Boolean =
    availableWidthDp < 276f || fontScale >= 1.6f ||
        (fontScale >= 1.3f && availableWidthDp < 330f)

/** Public wording must never infer a lossless or bit-perfect path from having a song. */
fun aeroNowPlayingQualityBadge(pcmObserved: Boolean): String =
    if (pcmObserved) "PCM decodificado" else "Michi Music"

/**
 * A compact two-column signal strip, restoring the original Now Playing hierarchy.
 * ONE shared plum surface, TWO independent hit targets, ONE explanatory note.
 * Neither a header nor nested cards may take vertical space from the artwork.
 * File metadata remains in the click accessibility label and in Signal Truth;
 * it is never mislabeled as measured/bit-perfect output.
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
    val visual = LocalVisualPreferences.current
    val highContrast = visual.highContrast
    val fontScale = LocalDensity.current.fontScale
    val material = aeroResolveMaterial(
        role = AeroSurfaceRole.Content,
        requestedFinish = AeroFinish.Satin,
        profile = visual.appearanceProfile,
        highContrast = highContrast,
        microtexturesEnabled = visual.microtexturesEnabled,
    )
    val shape = RoundedCornerShape(18.dp)
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val stacked = aeroM60StackSignal(maxWidth.value, fontScale)
        // The original card sampled #211A30. Preserve its identity: a quiet plum
        // tonal mass with one hairline, not another generic gray equipment card.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (highContrast) Color(0xFF161D27) else Color(0xFF211A30))
                .aeroFinishDrawing(
                    material.finish,
                    material.spec.copy(grainAlpha = 0f),
                    null,
                    shape,
                )
                .border(
                    width = if (highContrast) 1.2.dp else 0.75.dp,
                    color = Color.White.copy(alpha = if (highContrast) 0.85f else 0.16f),
                    shape = shape,
                )
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val originalFile: @Composable (Modifier) -> Unit = { cellModifier ->
                Column(
                    modifier = cellModifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(role = Role.Button, onClick = onSignalClick)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Archivo original: $signalLabel. Abrir Signal Truth"
                        }
                        .testTag("signal_truth_button")
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "ARCHIVO ORIGINAL",
                        color = TextSecondary,
                        fontFamily = InterFontFamily,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Show actual source information. The entire cell opens Signal Truth.
                    Text(
                        text = signalLabel,
                        color = TertiaryCyan,
                        fontFamily = InterFontFamily,
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        // A 50/50 cell must never clip normal bitrate/quality metadata.
                        // Long filenames remain available in the accessible tap label.
                        maxLines = if (stacked) 4 else 3,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().testTag("signal_strip_source_value"),
                    )
                }
            }
            val requestedOutput: @Composable (Modifier) -> Unit = { cellModifier ->
                Column(
                    modifier = cellModifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(role = Role.Button, onClick = onDestinationClick)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Salida solicitada: $destination. Abrir ruta de audio"
                        }
                        .testTag("audio_route_button")
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "SALIDA SOLICITADA",
                        color = TextSecondary,
                        fontFamily = InterFontFamily,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AeroObservedText(
                        value = destination,
                        color = PureWhite,
                        style = androidx.compose.ui.text.TextStyle(
                            fontFamily = InterFontFamily,
                            fontSize = 15.sp,
                            lineHeight = 19.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = if (stacked) 3 else 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (stacked) {
                // Accessibility fallback: stacked only when the logical text width
                // really cannot sustain two legible equal-width columns.
                originalFile(Modifier.fillMaxWidth())
                requestedOutput(Modifier.fillMaxWidth())
            } else {
                // Every column owns exactly half the width. The visual axis is
                // independent of text length, font weight and output device name.
                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        originalFile(Modifier.weight(1f))
                        requestedOutput(Modifier.weight(1f))
                    }
                    // Centered separator: decorative only, not a third action.
                    // It fades at both ends and does not cross the shared note.
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(width = 1.dp, height = 36.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Transparent,
                                        TertiaryCyan.copy(alpha = if (highContrast) 0.82f else 0.50f),
                                        Color.Transparent,
                                    ),
                                ),
                            )
                            .testTag("signal_strip_center_divider"),
                    )
                }
            }
            if (signalDescription.isNotBlank()) {
                // A single centered, full-width note. No additional click target.
                // Center every visual line, including a wrapped second line;
                // the underlying signalDescription remains unmodified.
                AeroObservedText(
                    value = signalDescription,
                    modifier = Modifier.fillMaxWidth().testTag("signal_strip_footer"),
                    color = TextSecondary,
                    style = androidx.compose.ui.text.TextStyle(
                        fontFamily = InterFontFamily,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    ),
                    maxLines = if (stacked) 3 else 2,
                )
            }
        }
    }
}

/** Control is distinct from handoff: a stream-only receiver cannot be attached as a host. */
fun isMichiControlTargetAvailable(endpoint: PlaybackEndpoint): Boolean = endpoint.isLocal ||
    (endpoint.isConnected && endpoint.type != EndpointType.STREAM_RECEIVER &&
        "AUDIO_OUTPUT" !in endpoint.capabilities && "REMOTE_CONTROL" in endpoint.capabilities)

/** Only the active endpoint has a track observation in PlaybackSessionState. */
fun michiControlEndpointSubtitle(
    endpoint: PlaybackEndpoint,
    activeEndpointId: String,
    currentTrackTitle: String?,
    isPlaying: Boolean,
    preview: RemoteEndpointPreview? = null,
): String = when {
    !endpoint.isLocal && !endpoint.isConnected -> "Sin conexión"
    endpoint.id == activeEndpointId -> {
        val state = if (isPlaying) "Reproduciendo" else "Pausado"
        currentTrackTitle?.takeIf { it.isNotBlank() }?.let { "$state · $it" }
            ?: "Estado del reproductor activo · sin pista informada"
    }
    endpoint.type == EndpointType.STREAM_RECEIVER -> "Receptor de audio · cambiar desde Salida"
    preview != null && preview.fetchedAtMs > 0L -> {
        val state = when (preview.state.lowercase()) {
            "playing" -> "Reproduciendo"
            "paused" -> "Pausado"
            "stopped" -> "Detenido"
            else -> "Estado desconocido"
        }
        preview.title?.let { "$state · $it" } ?: "$state · sin pista informada"
    }
    preview != null -> "No se pudo consultar reproducción"
    else -> "Disponible · consultando reproducción"
}

/** Fast control selector; never claims simultaneous now-playing telemetry for all peers. */
@Composable
fun MichiControlBadge(
    activeEndpoint: PlaybackEndpoint,
    endpoints: List<PlaybackEndpoint>,
    currentTrackTitle: String?,
    isPlaying: Boolean,
    expanded: Boolean,
    errorMessage: String?,
    previews: Map<String, RemoteEndpointPreview>,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (PlaybackEndpoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = (listOf(PlaybackEndpoint.LocalPhone) + endpoints + activeEndpoint).distinctBy { it.id }
    Box(modifier = modifier) {
        // Keep a 48dp actionable target while drawing a lighter 34dp pill.
        // One click owner prevents nested pointer/semantics handlers.
        Box(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(MichiShapes.pill)
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics {
                    contentDescription = "Michi Control: ${activeEndpoint.name}. Abrir reproductores disponibles"
                }
                .testTag("michi_control_badge")
                .padding(vertical = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = MichiShapes.pill,
                color = AeroGraphiteRaised,
                border = androidx.compose.foundation.BorderStroke(0.65.dp, Color.White.copy(alpha = 0.16f)),
            ) {
                Row(
                    modifier = Modifier.heightIn(min = 34.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(4.dp).background(
                        if (activeEndpoint.isLocal || activeEndpoint.isConnected) TertiaryCyan
                        else Color(0xFF647181), CircleShape))
                    Icon(
                        imageVector = when (michiControlGlyph(activeEndpoint)) {
                            MichiControlGlyph.PHONE -> Icons.Filled.PhoneAndroid
                            MichiControlGlyph.DESKTOP -> Icons.Filled.Computer
                            MichiControlGlyph.SERVER -> Icons.Filled.Dns
                            MichiControlGlyph.RECEIVER -> Icons.Filled.Speaker
                            MichiControlGlyph.ROOM,
                            MichiControlGlyph.UNKNOWN,
                            -> Icons.Filled.DevicesOther
                        },
                        contentDescription = null,
                        tint = if (activeEndpoint.isLocal || activeEndpoint.isConnected) TertiaryCyan else TextSecondary,
                        modifier = Modifier.size(17.dp),
                    )
                    Text(activeEndpoint.name, color = PureWhite, fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 132.dp))
                    Icon(Icons.Filled.ExpandMore, contentDescription = null,
                        tint = TextSecondary, modifier = Modifier.size(13.dp))
                }
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            modifier = Modifier.widthIn(min = 240.dp, max = 320.dp)
                .background(AeroGraphiteRaised)
                .aeroMenuTopLight(LocalVisualPreferences.current.highContrast)
                .testTag("michi_control_menu"),
        ) {
            Text("MICHI CONTROL", color = TertiaryCyan, fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp))
            Text("Elegir controlador · no transfiere la cola", color = TextSecondary,
                fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            entries.forEach { endpoint ->
                val isActive = endpoint.id == activeEndpoint.id
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(endpoint.name, color = if (isActive) TertiaryCyan else PureWhite,
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(michiControlEndpointSubtitle(endpoint, activeEndpoint.id,
                                currentTrackTitle, isPlaying, previews[endpoint.id]), color = TextSecondary,
                                fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    onClick = { if (isActive) onDismiss() else onSelect(endpoint) },
                    enabled = isActive || isMichiControlTargetAvailable(endpoint),
                    leadingIcon = {
                        Box(Modifier.size(7.dp).background(
                            if (isActive) TertiaryCyan
                            else if (endpoint.isConnected) Color(0xFF96A8BC)
                            else Color(0xFF647181), CircleShape))
                    },
                    modifier = Modifier.testTag("michi_control_endpoint_${endpoint.id}"),
                )
            }
            if (errorMessage != null) {
                Text(errorMessage, color = Color(0xFFFFB4AB), fontSize = 11.sp,
                    modifier = Modifier.padding(12.dp))
            }
        }
    }
}
