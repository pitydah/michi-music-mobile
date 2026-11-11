package org.michimusic.mobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.core.models.Track
import org.michimusic.mobile.ui.components.AeroCard
import org.michimusic.mobile.ui.components.AeroSurface
import org.michimusic.mobile.ui.components.AeroEmptyEditorial
import org.michimusic.mobile.ui.components.EqualizerWaveBars
import org.michimusic.mobile.ui.components.premium.MichiIconAction
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.AeroFinish
import org.michimusic.mobile.ui.theme.AeroGraphiteRaised
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.GlassFillMid
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
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
) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    // Save across detail navigation; state inside the overview branch would reset.
    var viewMode by rememberSaveable { mutableStateOf(GenreViewMode.FEATURED) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // Both grids retain their independent positions when switching modes or opening details.
    val featuredGridState = rememberLazyGridState()
    val editorialGridState = rememberLazyGridState()
    // Preserve restored scroll at first composition, but reset on *every* edit,
    // including clearing the query after the user previously scrolled the results.
    var previousQuery by remember { mutableStateOf(searchQuery) }
    LaunchedEffect(searchQuery) {
        if (previousQuery != searchQuery) {
            previousQuery = searchQuery
            featuredGridState.scrollToItem(0)
            editorialGridState.scrollToItem(0)
        }
    }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val openGenre: (String) -> Unit = { id ->
        focusManager.clearFocus()
        keyboard?.hide()
        selectedId = id
    }
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
        val visibleGenres = remember(ordered, searchQuery) {
            filterLibraryGenres(ordered, searchQuery)
        }
        Column(Modifier.fillMaxSize().testTag("genres_overview")) {
            GenreModeToolbar(
                visibleGenres = visibleGenres.size,
                totalGenres = genres.size,
                currentMode = viewMode,
                onModeChange = { viewMode = it },
            )
            if (showGenreSearch(genres.size, searchQuery)) {
                GenreSearchField(query = searchQuery, onQueryChange = { searchQuery = it })
            }
            if (visibleGenres.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(20.dp).testTag("genres_search_empty"),
                    contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Sin coincidencias", style = MichiTypography.sectionTitle,
                            color = PureWhite)
                        Text("Ningún género de esta biblioteca coincide con la búsqueda.",
                            style = MichiTypography.metadata, color = TextSecondary)
                        AeroSurface(role = AeroSurfaceRole.Raised, onClick = { searchQuery = "" },
                            modifier = Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center,
                            contentPadding = 14.dp) {
                            Text("Limpiar búsqueda", color = PrimaryPinkContainer,
                                style = MichiTypography.metadata)
                        }
                    }
                }
            } else {
                // Available width, not entire device width: work in split/folded layouts.
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val config = LocalConfiguration.current
                    val columns = genreGridColumns(maxWidth.value.toInt(), config.fontScale)
                    when (viewMode) {
                        GenreViewMode.FEATURED -> {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(columns),
                                state = featuredGridState,
                                modifier = Modifier.fillMaxSize().testTag("genres_featured"),
                                contentPadding = PaddingValues(start = 20.dp, end = 20.dp,
                                    top = 10.dp, bottom = 28.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                item(key = "featured_${visibleGenres.first().id}",
                                    span = { GridItemSpan(columns) }) {
                                    GenreTile(genre = visibleGenres.first(), featured = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        onOpen = { openGenre(visibleGenres.first().id) })
                                }
                                gridItems(visibleGenres.drop(1), key = { it.id }) { genre ->
                                    GenreTile(genre = genre, featured = false,
                                        modifier = Modifier.fillMaxWidth(),
                                        onOpen = { openGenre(genre.id) })
                                }
                            }
                        }
                        GenreViewMode.EDITORIAL -> {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(columns),
                                state = editorialGridState,
                                modifier = Modifier.fillMaxSize().testTag("genres_editorial"),
                                contentPadding = PaddingValues(start = 20.dp, end = 20.dp,
                                    top = 10.dp, bottom = 28.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                visibleGenres.forEachIndexed { index, genre ->
                                    item(
                                        key = genre.id,
                                        span = { GridItemSpan(editorialSlot(index, columns).span) },
                                    ) {
                                        GenreEditorialTile(genre = genre,
                                            slot = editorialSlot(index, columns),
                                            onOpen = { openGenre(genre.id) })
                                    }
                                }
                            }
                        }
                    }
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
private fun genreAccent(genre: LibraryGenre): Color =
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

@Composable
private fun GenreModeToolbar(
    visibleGenres: Int,
    totalGenres: Int,
    currentMode: GenreViewMode,
    onModeChange: (GenreViewMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val highContrast = LocalVisualPreferences.current.highContrast
    val menuShape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("BIBLIOTECA / EXPLORAR", style = MichiTypography.microLabel,
                color = TextSecondary)
            Text("Géneros", style = MichiTypography.sectionTitle.copy(fontSize = 25.sp),
                color = PureWhite, maxLines = 1)
            Text(if (visibleGenres == totalGenres) "$totalGenres géneros"
                else "$visibleGenres de $totalGenres géneros",
                style = MichiTypography.metadata, color = TextSecondary)
            Text("Vista · ${currentMode.displayName}", style = MichiTypography.microLabel,
                color = if (highContrast) PureWhite else PrimaryPinkContainer,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("genre_view_current"))
        }
        Box {
            MichiIconAction(
                icon = genreModeIcon(currentMode),
                contentDescription = "Elegir vista de géneros. Actual: ${currentMode.displayName}",
                onClick = { expanded = true },
                tint = PrimaryPinkContainer,
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

/** Scoped in-memory filter. Its text never reaches SearchViewModel or external services. */
@Composable
private fun GenreSearchField(query: String, onQueryChange: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    AeroSurface(
        role = AeroSurfaceRole.Content,
        finish = AeroFinish.Inset,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
            .heightIn(min = 52.dp).testTag("genres_search_field"),
        shape = MichiShapes.md,
        borderStroke = BorderStroke(
            width = if (LocalVisualPreferences.current.highContrast) 1.2.dp else .65.dp,
            color = if (LocalVisualPreferences.current.highContrast) PureWhite
                else GlassBorderHigh,
        ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Search, contentDescription = null,
                tint = TextSecondary, modifier = Modifier.size(20.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MichiTypography.metadata.copy(color = PureWhite),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    focusManager.clearFocus()
                }),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .semantics { contentDescription = "Buscar géneros en esta biblioteca" }
                    .testTag("genres_search_input"),
                decorationBox = { input ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text("Buscar géneros", style = MichiTypography.metadata,
                                color = TextSecondary)
                        }
                        input()
                    }
                },
            )
            if (query.isNotEmpty()) {
                MichiIconAction(icon = Icons.Filled.Close,
                    contentDescription = "Borrar búsqueda de géneros",
                    onClick = { onQueryChange("") },
                    tint = TextSecondary, minTargetSize = MichiSpacing.minTouchTarget,
                    testTag = "genres_search_clear")
            }
        }
    }
}

/**
 * Pure Compose ornamental ink. No assets, image loaders, animated gradients,
 * per-frame allocations or fake genre artwork. The lines are decoration only.
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
 * Single future-artwork insertion seam for both approved views. Today this plane
 * renders ONLY a static Compose material/geometry, with no Image, Coil, bitmaps
 * or drawable lookup. A future approved image renderer must remain beneath the
 * live Compose label and must never own genre identity, count or click handling.
 */
@Composable
private fun GenreVisualPlane(
    genreId: String,
    accent: Color,
    mode: GenreViewMode,
    treatment: GenreEditorialTreatment? = null,
) {
    val editorial = mode == GenreViewMode.EDITORIAL
    val backgroundBrush = if (!editorial)
        Brush.linearGradient(listOf(AeroGraphiteRaised, SurfaceObsidian, accent.copy(alpha = .11f)))
    else if (treatment == GenreEditorialTreatment.COVER)
        Brush.horizontalGradient(listOf(AeroGraphiteRaised, SurfaceObsidian))
    else Brush.verticalGradient(listOf(AeroGraphiteRaised, SurfaceObsidian))
    Box(Modifier.fillMaxSize().background(backgroundBrush).clearAndSetSemantics {}) {
        GenreGeometry(genreId, accent, editorial)
    }
}

/** One full-width focal genre and a calm compact secondary grid. No images. */
@Composable
private fun GenreTile(genre: LibraryGenre, featured: Boolean, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val accent = if (LocalVisualPreferences.current.highContrast) PureWhite else genreAccent(genre)
    val count = genre.tracks.size
    val height = genreMosaicHeightDp(featured, LocalConfiguration.current.fontScale)
    val itemLabel = "Abrir género ${genre.title}, $count ${if (count == 1) "pista" else "pistas"}"
    AeroCard(
        modifier = modifier.testTag("genre_tile_${genre.id}")
            .semantics { contentDescription = itemLabel },
        onClick = onOpen,
    ) {
        Box(Modifier.fillMaxWidth().height(height.dp).clearAndSetSemantics {}) {
            GenreVisualPlane(genre.id, accent, mode = GenreViewMode.FEATURED)
            Column(
                modifier = Modifier.fillMaxSize().padding(if (featured) 20.dp else 14.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (featured) "EN ESTA BIBLIOTECA" else "GÉNERO",
                        style = MichiTypography.microLabel,
                        color = TextSecondary, maxLines = 1)
                    Text("↗", color = accent, fontSize = 20.sp)
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(genre.title,
                        style = MichiTypography.sectionTitle.copy(
                            fontSize = if (featured) 27.sp else 19.sp,
                        ),
                        color = PureWhite, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = if (featured) Modifier.fillMaxWidth(.82f) else Modifier.fillMaxWidth())
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(width = 19.dp, height = 2.dp).background(accent))
                        Text("$count ${if (count == 1) "pista" else "pistas"}",
                            style = MichiTypography.metadata, color = TextSecondary)
                    }
                }
            }
        }
    }
}

/** Three different editorial compositions; no image slots and no false rankings. */
@Composable
private fun GenreEditorialTile(genre: LibraryGenre, slot: GenreEditorialSlot, onOpen: () -> Unit) {
    val accent = if (LocalVisualPreferences.current.highContrast) PureWhite else genreAccent(genre)
    val count = genre.tracks.size
    val itemLabel = "Abrir género ${genre.title}, $count ${if (count == 1) "pista" else "pistas"}"
    val treatment = slot.treatment
    val height = genreEditorialHeightDp(slot, LocalConfiguration.current.fontScale)
    AeroCard(
        modifier = Modifier.fillMaxWidth().height(height.dp)
            .testTag("genre_editorial_${genre.id}")
            .semantics { contentDescription = itemLabel },
        onClick = onOpen,
    ) {
        Box(Modifier.fillMaxSize().clearAndSetSemantics {}) {
            GenreVisualPlane(genre.id, accent, mode = GenreViewMode.EDITORIAL,
                treatment = treatment)
            // Marginal ink and typographic color blocks distinguish this from Mosaico.
            when (treatment) {
                GenreEditorialTreatment.COVER -> {
                    Box(Modifier.align(Alignment.CenterStart).width(3.dp).fillMaxHeight()
                        .background(accent.copy(alpha = .72f)))
                    Column(Modifier.fillMaxSize().padding(start = 22.dp, end = 20.dp,
                        top = 21.dp, bottom = 19.dp),
                        verticalArrangement = Arrangement.SpaceBetween) {
                        Text("MICHI   /   ARCHIVO MUSICAL", style = MichiTypography.microLabel,
                            color = TextSecondary, maxLines = 1)
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(genre.title, style = MichiTypography.sectionTitle.copy(fontSize = 30.sp,
                                lineHeight = 33.sp), color = PureWhite,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.width(28.dp).height(2.dp).background(accent))
                                Text("$count ${if (count == 1) "pista" else "pistas"}",
                                    style = MichiTypography.metadata, color = TextSecondary)
                            }
                        }
                    }
                }
                GenreEditorialTreatment.PORTRAIT_LEFT -> {
                    Column(Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween) {
                        Text("MICHI / GÉNEROS", style = MichiTypography.microLabel,
                            color = TextSecondary, maxLines = 1)
                        Box(Modifier.fillMaxWidth().height(2.dp).background(accent.copy(alpha = .8f)))
                        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                            Text(genre.title, style = MichiTypography.sectionTitle.copy(
                                fontSize = 22.sp, lineHeight = 26.sp), color = PureWhite,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("$count ${if (count == 1) "PISTA" else "PISTAS"}",
                                style = MichiTypography.microLabel,
                                color = TextSecondary, maxLines = 1)
                        }
                    }
                }
                GenreEditorialTreatment.PORTRAIT_RIGHT -> {
                    Column(Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Box(Modifier.width(26.dp).height(3.dp).background(accent))
                            Text("M / M", style = MichiTypography.microLabel,
                                color = TextSecondary)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(genre.title, style = MichiTypography.sectionTitle.copy(
                                fontSize = 22.sp, lineHeight = 26.sp), color = PureWhite,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Box(Modifier.fillMaxWidth().height(1.dp)
                                .background(accent.copy(alpha = .35f)))
                            Text("$count ${if (count == 1) "pista" else "pistas"}",
                                style = MichiTypography.metadata, color = TextSecondary,
                                maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
