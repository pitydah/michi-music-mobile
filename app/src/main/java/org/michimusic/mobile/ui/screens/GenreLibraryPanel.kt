package org.michimusic.mobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.core.models.Track
import org.michimusic.mobile.ui.components.AeroEmptyEditorial
import org.michimusic.mobile.ui.components.EqualizerWaveBars
import org.michimusic.mobile.ui.components.premium.MichiIconAction
import org.michimusic.mobile.ui.theme.AeroGraphiteRaised
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.GlassFillMid
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextSecondary

/** A library drill-down, not a second playback/navigation authority. */
@Composable
fun GenreLibraryPanel(
    genres: List<LibraryGenre>,
    playableTrackIds: Set<String>,
    currentTrack: Track?,
    isPlaying: Boolean,
    onPlay: (List<Track>, Int) -> Unit,
    globallyBlockedTrackIds: Set<String> = emptySet(),
    // Optional read-only load receipt for Android UI7 instrumented verification.
    onArtworkLoad: ((GenreViewMode, String, Boolean) -> Unit)? = null,
) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    // Save across detail navigation; state inside the overview branch would reset.
    var viewMode by rememberSaveable { mutableStateOf(GenreViewMode.FEATURED) }
    val context = LocalContext.current
    // Source Editorial posters already include titles and frames. Read their complete
    // bounded manifest once per Editorial entry; never enumerate media or decode 312 bitmaps.
    val posterAssets by produceState(
        initialValue = emptyMap<String, EditorialPosterAsset>(), key1 = viewMode,
    ) {
        if (viewMode == GenreViewMode.EDITORIAL) {
            value = withContext(Dispatchers.IO) {
                try {
                    val files = context.assets.list(editorialPosterFolder).orEmpty().toSet()
                    val text = context.assets.open("$editorialPosterFolder/manifest.tsv")
                        .bufferedReader().use { it.readText() }
                    val optical = runCatching {
                        context.assets.open("$editorialPosterFolder/optical.tsv")
                            .bufferedReader().use { parseUi7OpticalMasks(it.readText()) }
                    }.getOrDefault(emptyMap())
                    val approved = editorialApprovedPosters(
                        manifest = text,
                        installed = files,
                        onRejected = { reason ->
                            GenreArtworkDiagnostics.catalogRejected("EDITORIAL", reason)
                        },
                        opticalMasks = optical,
                    )
                    if (approved.isNotEmpty()) {
                        GenreArtworkDiagnostics.catalogLoaded("EDITORIAL", approved.size,
                            files.count { it.endsWith(".webp") })
                    }
                    approved
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    GenreArtworkDiagnostics.catalogError("EDITORIAL", error)
                    emptyMap()
                }
            }
        }
    }
    // FEATURED is the only consumer of the approved Mosaico pack: one bounded read
    // of its manifest while that view is visible, never a media scan or directory probe.
    val mosaicAssets by produceState(
        initialValue = emptyMap<String, MosaicArtworkAsset>(), key1 = viewMode,
    ) {
        if (viewMode == GenreViewMode.FEATURED) {
            value = withContext(Dispatchers.IO) {
                try {
                    val files = context.assets.list(mosaicFolder).orEmpty().toSet()
                    val text = context.assets.open("$mosaicFolder/manifest.tsv")
                        .bufferedReader().use { it.readText() }
                    val optical = runCatching {
                        context.assets.open("$mosaicFolder/optical.tsv")
                            .bufferedReader().use { parseUi7OpticalMasks(it.readText()) }
                    }.getOrDefault(emptyMap())
                    val approved = mosaicApprovedArtwork(
                        manifest = text,
                        installed = files,
                        onRejected = { reason ->
                            GenreArtworkDiagnostics.catalogRejected("MOSAIC", reason)
                        },
                        opticalMasks = optical,
                    )
                    if (approved.isNotEmpty()) {
                        GenreArtworkDiagnostics.catalogLoaded("MOSAIC", approved.size,
                            files.count { it.endsWith(".webp") })
                    }
                    approved
                } catch (cancel: CancellationException) {
                    throw cancel // Leaving Mosaico must stop its one-shot load.
                } catch (error: Exception) {
                    GenreArtworkDiagnostics.catalogError("MOSAIC", error)
                    emptyMap() // Keep production fail-closed without hiding the cause in Debug.
                }
            }
        }
    }
    // Both views retain their independent positions when switching modes or opening details.
    val featuredGridState = rememberLazyGridState()
    val editorialListState = rememberLazyListState()
    val openGenre: (String) -> Unit = { id -> selectedId = id }
    val selected = genres.firstOrNull { it.id == selectedId }
    // A deleted/retagged genre must never reopen from a stale saved ID.
    LaunchedEffect(selectedId, genres) {
        if (selectedId != null && selected == null) selectedId = null
    }
    BackHandler(enabled = selected != null) { selectedId = null }

    if (genres.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(20.dp).testTag("genres_empty"),
            contentAlignment = Alignment.TopCenter) {
            AeroEmptyEditorial(
                eyebrow = "TU COLECCIÓN",
                title = "No hay géneros disponibles",
                description = "Añade música a esta biblioteca o revisa el origen seleccionado.",
                icon = Icons.Filled.LibraryMusic,
            )
        }
        return
    }
    if (selected == null) {
        val ordered = remember(genres) { genresByTrackCount(genres) }
        // Observe the one-shot catalog loads in the panel's own composition scope
        // too. BoxWithConstraints composes its content in a subcomposition, and a
        // load observed only there left the grid on the empty fail-closed map after
        // the 312-item catalog arrived (reproduced on emulator-5554), so artwork
        // never recomposed. Reading the states here restarts the panel reliably.
        val mosaicArtwork = mosaicAssets
        val editorialPosters = posterAssets
        Column(Modifier.fillMaxSize().testTag("genres_overview")) {
            GenreModeToolbar(
                totalGenres = genres.size,
                currentMode = viewMode,
                onModeChange = { viewMode = it },
            )
            // Available width, not entire device width: work in split/folded layouts.
            // The grid starts immediately after the compact local header; there is no
            // second search row between them.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val columns = rememberGenreGridColumns(maxWidth.value)
                when (viewMode) {
                    GenreViewMode.FEATURED -> GenreMosaicGrid(
                        genres = ordered,
                        columns = columns,
                        availableWidthDp = maxWidth.value,
                        state = featuredGridState,
                        artwork = mosaicArtwork,
                        onArtworkLoad = onArtworkLoad,
                        onOpen = openGenre,
                    )
                    GenreViewMode.EDITORIAL -> GenreEditorialGrid(
                        genres = ordered,
                        columns = columns,
                        availableWidthDp = maxWidth.value,
                        state = editorialListState,
                        posterAssets = editorialPosters,
                        onArtworkLoad = onArtworkLoad,
                        onOpen = openGenre,
                    )
                }
            }
        }
        return
    }

    val playableTracks = remember(selected.tracks, playableTrackIds, globallyBlockedTrackIds) {
        canonicalGenrePlayableTracks(selected.tracks, playableTrackIds, globallyBlockedTrackIds)
    }
    // O(1) queue selection; avoid scanning a large genre on each click.
    val playableIndexByIdentity = remember(playableTracks) {
        playableTracks.withIndex().associate { (index, track) ->
            Triple(track.sourceId, track.id, track.filepath) to index
        }
    }
    val playableIdentities = remember(playableIndexByIdentity) { playableIndexByIdentity.keys }
    val largeText = LocalConfiguration.current.fontScale >= 1.3f
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("genre_detail"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "genre_header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MichiIconAction(
                        icon = Icons.Filled.ArrowBack,
                        contentDescription = "Volver a géneros",
                        onClick = { selectedId = null },
                        tint = PureWhite,
                        minTargetSize = MichiSpacing.minTouchTarget,
                    )
                    Text("Géneros", style = MichiTypography.microLabel, color = TextSecondary)
                }
                Text(
                    text = selected.title,
                    style = MichiTypography.sectionTitle.copy(fontSize = 22.sp),
                    color = PureWhite,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("genre_detail_title"),
                )
                Text(
                    "${selected.tracks.size} ${if (selected.tracks.size == 1) "canción" else "canciones"}" +
                        " · ${selected.albumCount} ${if (selected.albumCount == 1) "álbum" else "álbumes"}",
                    style = MichiTypography.metadata,
                    color = TextSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MichiIconAction(
                        icon = Icons.Filled.PlayArrow,
                        contentDescription = "Reproducir género ${selected.title}",
                        onClick = { if (playableTracks.isNotEmpty()) onPlay(playableTracks, 0) },
                        enabled = playableTracks.isNotEmpty(),
                        tint = PrimaryPinkContainer,
                        minTargetSize = MichiSpacing.minTouchTarget,
                    )
                    MichiIconAction(
                        icon = Icons.Filled.Shuffle,
                        contentDescription = "Aleatorio: ${selected.title}",
                        onClick = {
                            if (playableTracks.isNotEmpty()) onPlay(playableTracks.shuffled(), 0)
                        },
                        enabled = playableTracks.isNotEmpty(),
                        tint = PureWhite,
                        minTargetSize = MichiSpacing.minTouchTarget,
                    )
                    if (playableTracks.isEmpty()) {
                        Text("No hay pistas reproducibles", style = MichiTypography.microLabel,
                            color = TextMuted, modifier = Modifier.align(Alignment.CenterVertically))
                    }
                }
            }
        }
        items(selected.tracks, key = { "${it.sourceId}\u0000${it.id}\u0000${it.filepath}" }) { track ->
            val playable = Triple(track.sourceId, track.id, track.filepath) in playableIdentities
            val active = genrePlaybackIdentityMatches(track, currentTrack)
            Row(
                modifier = Modifier.fillMaxWidth().clip(MichiShapes.sm)
                    .background(if (active) TertiaryCyan.copy(alpha = 0.075f) else SurfaceObsidian)
                    .clickable(enabled = playable, role = Role.Button) {
                        playableIndexByIdentity[Triple(track.sourceId, track.id, track.filepath)]?.let { index ->
                            onPlay(playableTracks, index)
                        }
                    }
                    .heightIn(min = MichiSpacing.minTouchTarget)
                    .padding(horizontal = 10.dp, vertical = 7.dp)
                    .testTag("genre_track_${track.id}")
                    .semantics(mergeDescendants = true) {
                        contentDescription = listOf(track.title, track.artist, track.album)
                            .filter(String::isNotBlank).joinToString(", ")
                        if (!playable) stateDescription = "No disponible"
                        else if (active) stateDescription = if (isPlaying) "Reproduciendo" else "Pista actual en pausa"
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // UI7 NO-IMAGE phase: even detail rows avoid decoding artwork.
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(9.dp))
                        .background(AeroGraphiteRaised),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.LibraryMusic, contentDescription = null,
                        tint = TextSecondary, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(track.title, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (active) TertiaryCyan else PureWhite,
                        maxLines = if (largeText) 2 else 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(track.artist, track.album).filter(String::isNotBlank).joinToString(" · "),
                        style = MichiTypography.microLabel,
                        color = if (playable) TextSecondary else TextMuted,
                        maxLines = if (largeText) 2 else 1, overflow = TextOverflow.Ellipsis)
                }
                if (active && isPlaying) {
                    EqualizerWaveBars(isPlaying = true, barCount = 3, color = TertiaryCyan)
                } else if (!playable && !largeText) {
                    Text("No disponible", style = MichiTypography.microLabel, color = TextMuted)
                }
            }
        }
    }
}

/** Stable presentation accent only; cyan stays reserved for technical/audio truth. */
internal fun genreAccent(genre: LibraryGenre): Color =
    if (genre.id == "__ui7_unknown_genre__") TextSecondary
    else when ((genre.id.hashCode() and Int.MAX_VALUE) % 3) {
        0 -> PrimaryPinkContainer
        1 -> Color(0xFFDDB7FF)
        else -> Color(0xFFBEA992)
    }

@Composable
private fun genreModeIcon(mode: GenreViewMode) = when (mode) {
    GenreViewMode.FEATURED -> Icons.Default.ViewCarousel
    GenreViewMode.EDITORIAL -> Icons.Default.Style
}

/**
 * Compact local header: the view name, the real scoped genre count and the
 * Editorial/Mosaico selector. No catalog eyebrow and no duplicated "Vista"
 * line, so the grid starts directly below it. Selection stays in
 * rememberSaveable at panel level and survives opening/returning from a genre.
 */
@Composable
private fun GenreModeToolbar(
    totalGenres: Int,
    currentMode: GenreViewMode,
    onModeChange: (GenreViewMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val highContrast = LocalVisualPreferences.current.highContrast
    val menuShape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = GENRE_GRID_SIDE_PADDING_DP.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("Géneros", style = MichiTypography.sectionTitle.copy(fontSize = 25.sp),
                color = PureWhite, maxLines = 1,
                modifier = Modifier.testTag("genres_header_title"))
            Text("$totalGenres ${if (totalGenres == 1) "género" else "géneros"}",
                style = MichiTypography.metadata, color = TextSecondary,
                maxLines = 1,
                modifier = Modifier.testTag("genres_header_count"))
        }
        Box {
            MichiIconAction(
                icon = genreModeIcon(currentMode),
                contentDescription = "Elegir vista de géneros. Actual: ${currentMode.displayName}",
                onClick = { expanded = true },
                tint = if (highContrast) PureWhite else PrimaryPinkContainer,
                minTargetSize = MichiSpacing.minTouchTarget,
                testTag = "genre_view_selector",
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.clip(menuShape).background(SurfaceObsidian)
                    .border(if (highContrast) 1.5.dp else 0.8.dp,
                        if (highContrast) PureWhite else GlassBorderHigh, menuShape),
            ) {
                GenreViewMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.displayName, style = MichiTypography.cardTitle,
                            color = if (mode == currentMode) PrimaryPinkContainer else PureWhite) },
                        onClick = { expanded = false; onModeChange(mode) },
                        leadingIcon = { Icon(genreModeIcon(mode), contentDescription = null,
                            tint = if (mode == currentMode) PrimaryPinkContainer else PureWhite) },
                        trailingIcon = if (mode == currentMode) {
                            { Icon(Icons.Default.Check, contentDescription = null,
                                tint = PrimaryPinkContainer) }
                        } else null,
                        modifier = Modifier.background(
                            if (mode == currentMode) GlassFillMid else SurfaceObsidian,
                            RoundedCornerShape(10.dp),
                        ).semantics { selected = mode == currentMode }
                            .testTag("genre_view_${mode.name.lowercase()}"),
                    )
                }
            }
        }
    }
}

/**
 * Pure Compose ornamental ink. No assets, image loaders, animated gradients,
 * per-frame allocations or fake genre artwork. The lines are decoration only and
 * are hidden whenever approved artwork covers the plane.
 */
@Composable
private fun GenreGeometry(genreId: String, accent: Color, editorial: Boolean) {
    val variant = remember(genreId) { (genreId.hashCode() and Int.MAX_VALUE) % 3 }
    Canvas(Modifier.fillMaxSize()) {
        val center = Offset(size.width * (if (editorial) .82f else .87f), size.height * .39f)
        val radius = size.minDimension * (if (editorial) .29f else .36f)
        val stroke = (if (editorial) 1.1.dp else 1.4.dp).toPx()
        drawCircle(accent.copy(alpha = .20f), radius, center, style = Stroke(stroke))
        drawCircle(accent.copy(alpha = .12f), radius * .62f, center, style = Stroke(stroke))
        val y0 = size.height * (.2f + variant * .085f)
        repeat(3) { n ->
            val y = y0 + n * 13.dp.toPx()
            drawLine(accent.copy(alpha = .14f),
                start = Offset(size.width * .55f, y),
                end = Offset(size.width, y + size.height * .13f),
                strokeWidth = stroke)
        }
    }
}

/**
 * Single artwork seam for both approved views. Editorial renders ONLY a static
 * Compose material/geometry; FEATURED may place one approved Mosaico crop above it.
 * Either way the image layer stays beneath the live Compose label and never owns
 * genre identity, count or click handling.
 */
@Composable
internal fun GenreVisualPlane(
    genreId: String,
    accent: Color,
    mode: GenreViewMode,
    treatment: GenreEditorialTreatment? = null,
    modifier: Modifier = Modifier,
) {
    val editorial = mode == GenreViewMode.EDITORIAL
    val backgroundBrush = if (!editorial)
        Brush.linearGradient(listOf(AeroGraphiteRaised, SurfaceObsidian, accent.copy(alpha = .11f)))
    else if (treatment == GenreEditorialTreatment.COVER)
        Brush.horizontalGradient(listOf(AeroGraphiteRaised, SurfaceObsidian))
    else Brush.verticalGradient(listOf(AeroGraphiteRaised, SurfaceObsidian))
    Box(modifier.fillMaxSize().background(backgroundBrush).clearAndSetSemantics {}) {
        GenreGeometry(genreId, accent, editorial)
    }
}
