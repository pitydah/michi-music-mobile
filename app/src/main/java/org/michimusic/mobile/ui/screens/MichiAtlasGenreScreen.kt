package org.michimusic.mobile.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.michimusic.core.models.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.michimusic.mobile.ui.components.premium.MichiIconAction
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceContainerLow
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * K03: bounded offline cultural traversal inside the existing UI7 genre panel.
 * Every historical assertion comes from a small source-audited static catalog;
 * unknown genres retain their honest local library projection.
 * No NavHost, network, model, scanner, history harvesting or Media3 mutation.
 */
@Composable
internal fun MichiAtlasGenreScreen(
    genre: LibraryGenre,
    viewMode: GenreViewMode,
    poster: EditorialPosterAsset?,
    mosaic: MosaicArtworkAsset?,
    scopedGenres: List<LibraryGenre>,
    playableTrackIds: Set<String>,
    globallyBlockedTrackIds: Set<String>,
    editorialAssets: Map<String, EditorialPosterAsset>,
    mosaicAssets: Map<String, MosaicArtworkAsset>,
    scopeIdentity: String,
    onBack: () -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    onOpenLibraryGenre: ((String) -> Unit)? = null,
    canPlayLocally: () -> Boolean = { true },
) {
    val originSnapshot = remember(genre.id, genre.tracks, genre.albumCount) {
        atlasGenreSnapshot(genre)
    } ?: return
    // Do not restore a prior route for a different library owner or generation.
    // Explicit hops only; no external nav graph or parallel playback authority.
    var trail by rememberSaveable(originSnapshot.genreId, scopeIdentity) {
        mutableStateOf(arrayListOf(originSnapshot.genreId))
    }
    val currentId = trail.lastOrNull().takeIf { it != null &&
        (it == originSnapshot.genreId || MichiAtlasEditorialCatalog.find(it) != null)
    } ?: originSnapshot.genreId
    val isOrigin = currentId == originSnapshot.genreId
    val editorial = remember(currentId) { MichiAtlasEditorialCatalog.find(currentId) }
    val currentScopedGenre = remember(currentId, scopedGenres) {
        atlasScopedGenre(currentId, scopedGenres)
    }
    val currentSnapshot = remember(currentScopedGenre) {
        currentScopedGenre?.let(::atlasGenreSnapshot)
    }
    // K07: compute the potentially expensive collision index only once per scope.
    // Every visited cultural page shares the result. A new library starts as null:
    // no former owner's playback index can authorize a tap during computation.
    val ambiguousIds by key(scopeIdentity, scopedGenres) {
        produceState<Set<String>?>(null, scopeIdentity, scopedGenres) {
            value = withContext(Dispatchers.Default) {
                atlasAmbiguousTrackIds(scopedGenres)
            }
        }
    }
    // Keep a distinct NOT-READY projection on every scope, page or permission change.
    val playbackState by key(currentId, scopeIdentity, scopedGenres,
        playableTrackIds, globallyBlockedTrackIds, ambiguousIds) {
        produceState<AtlasPlaybackSnapshot?>(null, currentId, scopeIdentity,
            scopedGenres, playableTrackIds, globallyBlockedTrackIds, ambiguousIds) {
            val authorizedCollisions = ambiguousIds ?: return@produceState
            value = withContext(Dispatchers.Default) {
                atlasBuildPlaybackSnapshot(currentId, scopedGenres,
                    playableTrackIds, globallyBlockedTrackIds, authorizedCollisions)
            }
        }
    }
    val playableTracks = playbackState?.tracks.orEmpty()
    val playbackReady = playbackState != null && playbackState.genreId == currentId
    val activeTitle = currentSnapshot?.title ?: editorial?.displayName ?: originSnapshot.title
    val activePoster = if (isOrigin) poster else currentScopedGenre?.let {
        editorialPosterForTitle(it.title, editorialAssets)
    }
    val activeMosaic = if (isOrigin) mosaic else currentScopedGenre?.let {
        mosaicArtworkForGenre(it.id, mosaicAssets)
    }
    val relations = remember(currentId, editorial) {
        atlasAcceptedRelations(currentId, editorial?.relations.orEmpty())
    }
    val uriHandler = LocalUriHandler.current
    val stateHolder = key(scopeIdentity) { rememberSaveableStateHolder() }
    val previousTrail = atlasPreviousRoute(trail)
    val handleBack = {
        if (previousTrail == null) onBack()
        else trail = ArrayList(previousTrail)
    }
    // Parent GenreLibraryPanel handles back only at the root Atlas page.
    BackHandler(enabled = previousTrail != null) { handleBack() }

    // Per-page LazyColumn scroll and expanded relation survive a round-trip
    // between connected pages, with no global navigation or playback stack.
    stateHolder.SaveableStateProvider("$scopeIdentity|$currentId") {
        val listState = rememberLazyListState()
        val navScope = rememberCoroutineScope()
        var selectedRelationId by rememberSaveable { mutableStateOf<String?>(null) }
        var availableOnly by rememberSaveable { mutableStateOf(false) }
        val previews = if (availableOnly) playbackState?.playablePreviews.orEmpty()
            else playbackState?.allPreviews.orEmpty()
        val selectedRelation = relations.firstOrNull { it.toGenreId == selectedRelationId }
        val targetPage = selectedRelation?.let { MichiAtlasEditorialCatalog.find(it.toGenreId) }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().background(SurfaceObsidian)
                .testTag("michi_atlas_genre_screen"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
        item(key = "atlas_toolbar") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.testTag("michi_atlas_toolbar"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    MichiIconAction(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = if (isOrigin) "Volver a ${originSnapshot.title}"
                            else "Volver al Atlas anterior",
                        onClick = handleBack,
                        tint = PureWhite,
                        minTargetSize = MichiSpacing.minTouchTarget,
                    )
                    Column {
                        Text("MICHI ATLAS", style = MichiTypography.microLabel,
                            color = PrimaryPinkContainer)
                        Text("Explorando $activeTitle", style = MichiTypography.cardTitle,
                            color = PureWhite, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (trail.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.testTag("michi_atlas_route_breadcrumbs")) {
                        items(trail, key = { it }) { id ->
                            TextButton(
                                onClick = {
                                    atlasRewindRoute(trail, id)?.let { trail = ArrayList(it) }
                                },
                                modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget)
                                    .testTag("michi_atlas_route_stop_$id"),
                            ) {
                                val title = if (id == originSnapshot.genreId) originSnapshot.title
                                    else MichiAtlasEditorialCatalog.find(id)?.displayName ?: id
                                Text(title, style = MichiTypography.microLabel,
                                    color = if (id == currentId) PureWhite else PrimaryPinkContainer)
                            }
                        }
                    }
                }
            }
        }
        item(key = "atlas_hero") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BoxWithConstraints(
                    Modifier.fillMaxWidth().height(144.dp)
                        .clip(RoundedCornerShape(20.dp)).background(SurfaceContainerLow)
                        .testTag("michi_atlas_genre_hero"),
                ) {
                    // Never borrow the origin cover for a different cultural page.
                    // Reuse only the approved UI7 asset for the exact in-scope destination.
                    GenreArtworkPlaceholder(currentScopedGenre?.let(::genreAccent)
                        ?: PrimaryPinkContainer)
                    when (viewMode) {
                        GenreViewMode.EDITORIAL -> if (activePoster != null) {
                            GenreEditorialPosterArtwork(
                                poster = activePoster, modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                alignment = Alignment.BottomCenter,
                            )
                            val guard = editorialTitleGuardDp(activePoster.ratio,
                                maxWidth.value, 144f)
                            if (guard > 0.5f) {
                                Box(Modifier.fillMaxWidth().height(guard.dp)
                                    .background(SurfaceObsidian))
                            }
                        }
                        GenreViewMode.FEATURED -> if (activeMosaic != null) {
                            GenreHeaderMosaicArtwork(asset = activeMosaic)
                        }
                    }
                }
                Text(activeTitle, style = MichiTypography.sectionTitle,
                    color = PureWhite, modifier = Modifier.testTag("michi_atlas_genre_title"))
                Text(
                    editorial?.overview ?: "Todavía no hay una ficha histórica revisada " +
                        "para este género. Puedes explorar tu colección local sin conexión.",
                    style = MichiTypography.metadata, color = TextSecondary,
                    modifier = Modifier.testTag("michi_atlas_overview"),
                )
            }
        }
        // Stable item indexes: 0 toolbar, 1 hero, 2 jumps, 3 map,
        // 4 library, 5 history, 6 scenes, 7 sources.
        item(key = "atlas_section_jumps") {
            LazyRow(
                modifier = Modifier.fillMaxWidth().testTag("michi_atlas_section_jumps"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(listOf(
                    Triple("map", "Mapa", 3),
                    Triple("library", "Biblioteca", 4),
                    Triple("history", "Historia", 5),
                    Triple("scenes", "Escenas", 6),
                    Triple("sources", "Fuentes", 7),
                ), key = { it.first }) { shortcut ->
                    TextButton(
                        onClick = { navScope.launch { listState.animateScrollToItem(shortcut.third) } },
                        modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget)
                            .testTag("michi_atlas_jump_${shortcut.first}"),
                    ) {
                        Text(shortcut.second, style = MichiTypography.microLabel,
                            color = PrimaryPinkContainer)
                    }
                }
            }
        }
        item(key = "atlas_connections") {
            AtlasSection(Modifier.testTag("michi_atlas_connections_section")) {
                Text("Mapa musical", style = MichiTypography.sectionTitle, color = PureWhite)
                Text("Conexiones documentadas, no recomendaciones calculadas desde artistas.",
                    style = MichiTypography.metadata, color = TextSecondary)
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(SurfaceObsidian).border(
                            1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp),
                        ).padding(vertical = 12.dp),
                ) {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(Modifier.clip(RoundedCornerShape(24.dp))
                            .background(PrimaryPinkContainer.copy(alpha = 0.15f))
                            .border(1.dp, PrimaryPinkContainer, RoundedCornerShape(24.dp))
                            .padding(horizontal = 22.dp, vertical = 12.dp)) {
                            Text(activeTitle, style = MichiTypography.cardTitle,
                                color = PureWhite)
                        }
                        if (relations.isEmpty()) {
                            Text("No hay conexiones verificadas para este género.",
                                style = MichiTypography.metadata, color = TextSecondary,
                                modifier = Modifier.padding(12.dp)
                                    .testTag("michi_atlas_connections_empty"))
                        } else {
                            Text("Selecciona una conexión para conocer su fundamento",
                                style = MichiTypography.microLabel, color = TextSecondary)
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(relations, key = { "${it.toGenreId}:${it.kind}" }) { edge ->
                                    val highlighted = edge.toGenreId == selectedRelationId
                                    val target = edge.targetDisplayName.ifBlank { edge.toGenreId }
                                    Column(
                                        Modifier.widthIn(min = 124.dp, max = 184.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(if (highlighted) PrimaryPinkContainer.copy(alpha = 0.15f)
                                                else SurfaceContainerLow)
                                            .border(1.dp, if (highlighted) PrimaryPinkContainer
                                                else Color.White.copy(alpha = 0.14f),
                                                RoundedCornerShape(14.dp))
                                            .clickable(role = Role.Button) {
                                                selectedRelationId = if (highlighted) null else edge.toGenreId
                                            }
                                            .semantics {
                                                contentDescription = "$target: ${atlasRelationLabel(edge.kind)}"
                                            }
                                            .padding(12.dp)
                                            .testTag("michi_atlas_relation_${edge.toGenreId}"),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(target, style = MichiTypography.cardTitle,
                                            color = PureWhite, maxLines = 2)
                                        Text(atlasRelationLabel(edge.kind),
                                            style = MichiTypography.microLabel,
                                            color = if (highlighted) PrimaryPinkContainer else TextSecondary,
                                            maxLines = 2)
                                    }
                                }
                            }
                            if (selectedRelation != null) {
                                Column(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                                        .testTag("michi_atlas_relation_detail"),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(atlasRelationLabel(selectedRelation.kind),
                                        style = MichiTypography.microLabel, color = PrimaryPinkContainer)
                                    Text(selectedRelation.explanation,
                                        style = MichiTypography.metadata, color = PureWhite)
                                    Text("Fuente: ${selectedRelation.sources.joinToString { it.title }}",
                                        style = MichiTypography.microLabel, color = TextSecondary)
                                    if (targetPage != null) {
                                        TextButton(
                                            onClick = {
                                                val next = atlasAdvanceRoute(
                                                    trail, currentId, selectedRelation,
                                                    destinationPublished = MichiAtlasEditorialCatalog.find(
                                                        selectedRelation.toGenreId) != null,
                                                )
                                                if (next != null) trail = ArrayList(next)
                                            },
                                            modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget)
                                                .testTag("michi_atlas_explore_${selectedRelation.toGenreId}"),
                                        ) {
                                            Text("Explorar ${targetPage.displayName}",
                                                color = PrimaryPinkContainer)
                                        }
                                    } else {
                                        Text("Ficha de destino en preparación. La relación puede " +
                                            "consultarse, pero aún no abre otra pantalla.",
                                            style = MichiTypography.microLabel,
                                            color = TextSecondary,
                                            modifier = Modifier.testTag("michi_atlas_target_not_published"))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item(key = "atlas_library") {
            AtlasSection(Modifier.testTag("michi_atlas_library_section")) {
                Text("TU BIBLIOTECA", style = MichiTypography.microLabel,
                    color = PrimaryPinkContainer)
                if (currentSnapshot == null) {
                    Text("No tienes pistas clasificadas en $activeTitle dentro de " +
                        "la biblioteca activa. Su historia puede explorarse igualmente.",
                        style = MichiTypography.metadata, color = TextSecondary,
                        modifier = Modifier.testTag("michi_atlas_external_library_notice"))
                } else {
                    Text("${currentSnapshot.songCount} " +
                        "${if (currentSnapshot.songCount == 1) "canción" else "canciones"}" +
                        " · ${currentSnapshot.albumCount} " +
                        "${if (currentSnapshot.albumCount == 1) "álbum" else "álbumes"}",
                        style = MichiTypography.cardTitle, color = PureWhite,
                        modifier = Modifier.testTag("michi_atlas_library_count"))
                    if (currentSnapshot.artists.isEmpty()) {
                        Text("No hay artistas identificados en este género.",
                            style = MichiTypography.metadata, color = TextSecondary)
                    } else {
                        Text("Artistas de tus archivos para $activeTitle",
                            style = MichiTypography.metadata, color = TextSecondary)
                        currentSnapshot.artists.forEach { artist ->
                            Text("• $artist", style = MichiTypography.metadata,
                                color = PureWhite, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (!playbackReady) {
                        Text("Verificando las pistas disponibles…",
                            style = MichiTypography.microLabel, color = TextSecondary,
                            modifier = Modifier.testTag("michi_atlas_playability_loading"))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MichiIconAction(
                            icon = Icons.Filled.PlayArrow,
                            contentDescription = "Reproducir música de $activeTitle desde Atlas",
                            onClick = {
                                val snapshot = playbackState
                                if (atlasMayDispatch(snapshot, currentId, 0, canPlayLocally())) {
                                    onPlay(requireNotNull(snapshot).tracks, 0)
                                }
                            },
                            enabled = playbackReady && playableTracks.isNotEmpty(),
                            tint = PrimaryPinkContainer,
                            minTargetSize = MichiSpacing.minTouchTarget,
                        )
                        Text(if (playableTracks.isNotEmpty()) "Escuchar este género"
                            else "Sin pistas reproducibles",
                            style = MichiTypography.metadata, color = TextSecondary)
                    }
                    if (playbackReady) {
                        Text("Canciones de tu colección", style = MichiTypography.cardTitle,
                            color = PureWhite)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { availableOnly = false },
                                modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget)
                                    .testTag("michi_atlas_filter_all"),
                            ) {
                                Text("Todas", style = MichiTypography.microLabel,
                                    color = if (!availableOnly) PureWhite else PrimaryPinkContainer)
                            }
                            TextButton(
                                onClick = { availableOnly = true },
                                modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget)
                                    .testTag("michi_atlas_filter_playable"),
                            ) {
                                Text("Reproducibles", style = MichiTypography.microLabel,
                                    color = if (availableOnly) PureWhite else PrimaryPinkContainer)
                            }
                        }
                        if (availableOnly && previews.isEmpty()) {
                            Text("No hay pistas reproducibles en este género.",
                                style = MichiTypography.metadata, color = TextSecondary,
                                modifier = Modifier.testTag("michi_atlas_playable_empty"))
                        }
                        previews.forEachIndexed { position, preview ->
                            val playableIndex = preview.playbackIndex
                            val title = preview.track.title.ifBlank { "Canción sin título" }
                            val artist = preview.track.artist.ifBlank { "Artista desconocido" }
                            Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .background(SurfaceObsidian)
                                    .clickable(enabled = playableIndex != null, role = Role.Button) {
                                        val snapshot = playbackState
                                        if (atlasMayDispatch(snapshot, currentId, playableIndex,
                                                canPlayLocally())) {
                                            onPlay(requireNotNull(snapshot).tracks,
                                                requireNotNull(playableIndex))
                                        }
                                    }
                                    .heightIn(min = MichiSpacing.minTouchTarget)
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .testTag("michi_atlas_track_$position")
                                    .semantics(mergeDescendants = true) {
                                        contentDescription = "$title, $artist"
                                        if (playableIndex == null) stateDescription = "No disponible"
                                    },
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(title, style = MichiTypography.cardTitle,
                                    color = if (playableIndex == null) TextSecondary else PureWhite,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(artist, style = MichiTypography.microLabel,
                                    color = TextSecondary, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (currentSnapshot.songCount > ATLAS_TRACK_PREVIEW_LIMIT) {
                            Text("Vista previa de hasta $ATLAS_TRACK_PREVIEW_LIMIT canciones " +
                                "en el filtro activo. Consulta el resto en Biblioteca.",
                                style = MichiTypography.microLabel, color = TextSecondary)
                        }
                    }
                    if (onOpenLibraryGenre != null) {
                        TextButton(
                            onClick = {
                                // The panel is the only authority allowed to switch library detail.
                                if (atlasScopedGenre(currentId, scopedGenres) != null) {
                                    onOpenLibraryGenre(currentId)
                                }
                            },
                            modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget)
                                .testTag("michi_atlas_open_library_${currentId}"),
                        ) {
                            Text("Ver canciones en Biblioteca", color = PrimaryPinkContainer)
                        }
                    }
                }
            }
        }
        item(key = "atlas_history") {
            AtlasSection(Modifier.testTag("michi_atlas_history_section")) {
                Text("Historia y evolución", style = MichiTypography.sectionTitle, color = PureWhite)
                if (editorial == null || editorial.milestones.isEmpty()) {
                    Text("Aún no hay una cronología revisada para este género.",
                        style = MichiTypography.metadata, color = TextSecondary)
                } else {
                    editorial.milestones.forEach { milestone ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(milestone.period, style = MichiTypography.microLabel,
                                color = PrimaryPinkContainer)
                            Text(milestone.title, style = MichiTypography.cardTitle,
                                color = PureWhite)
                            Text(milestone.description, style = MichiTypography.metadata,
                                color = TextSecondary)
                        }
                    }
                }
            }
        }
        item(key = "atlas_scenes") {
            AtlasSection(Modifier.testTag("michi_atlas_scenes_section")) {
                Text("Escenas musicales", style = MichiTypography.sectionTitle, color = PureWhite)
                if (editorial == null || editorial.scenes.isEmpty()) {
                    Text("No se han documentado escenas geográficas para esta ficha.",
                        style = MichiTypography.metadata, color = TextSecondary)
                } else {
                    editorial.scenes.forEach { scene ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(scene.place, style = MichiTypography.cardTitle, color = PureWhite)
                            Text(scene.period, style = MichiTypography.microLabel,
                                color = PrimaryPinkContainer)
                            Text(scene.context, style = MichiTypography.metadata,
                                color = TextSecondary)
                        }
                    }
                }
            }
        }
        item(key = "atlas_sources") {
            AtlasSection(Modifier.testTag("michi_atlas_sources_section")) {
                Text("Fuentes y procedencia", style = MichiTypography.sectionTitle,
                    color = PureWhite)
                if (editorial == null) {
                    Text("Todavía no hay referencias editoriales para esta clasificación.",
                        style = MichiTypography.metadata, color = TextSecondary)
                } else {
                    Text("Fichas originales y fuentes consultables. " +
                        "La biblioteca musical no se envía a estos sitios.",
                        style = MichiTypography.metadata, color = TextSecondary)
                    atlasPageSources(editorial).forEachIndexed { index, source ->
                        Text(
                            source.title + "  ↗",
                            style = MichiTypography.metadata,
                            color = PrimaryPinkContainer,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .clickable(role = Role.Button) {
                                    if (atlasSourceIsSafe(source)) {
                                        // System browser only after an explicit tap. No implicit fetch.
                                        runCatching { uriHandler.openUri(source.url) }
                                    }
                                }
                                .semantics { contentDescription = "Abrir fuente externa: ${source.title}" }
                                .padding(vertical = 10.dp)
                                .testTag("michi_atlas_source_$index"),
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun AtlasSection(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(SurfaceContainerLow).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}
