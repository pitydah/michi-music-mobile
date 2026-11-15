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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
import org.michimusic.mobile.ui.components.AlbumArtView
import org.michimusic.mobile.ui.components.EqualizerWaveBars
import org.michimusic.mobile.ui.components.GenreTrackActionsSheet
import org.michimusic.mobile.ui.components.MichiGenreEditorOrigin
import org.michimusic.mobile.ui.components.MichiGenreNormalizationHost
import org.michimusic.mobile.ui.components.coverStyleFor
import org.michimusic.mobile.ui.components.premium.MichiIconAction
import org.michimusic.mobile.ui.motion.aeroTrackArtworkCoverModel
import org.michimusic.mobile.ui.motion.aeroTrackArtworkFallbackUri
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
    scopeKey: String = "",
    scopeGeneration: Long = 0L,
    canPlayLocally: () -> Boolean = { true },
    captureIntelligenceTicket: () -> org.michimusic.mobile.playback.PlaybackSessionManager.IntelligenceQueueTicket? = { null },
    playIntelligenceQueue: (List<Track>, Int,
        org.michimusic.mobile.playback.PlaybackSessionManager.IntelligenceQueueTicket) -> Boolean = { _, _, _ -> false },
    // Optional read-only load receipts for Android UI7 instrumented verification.
    onArtworkLoad: ((GenreViewMode, String, Boolean) -> Unit)? = null,
    onTrackArtworkLoad: ((String, Boolean) -> Unit)? = null,
) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    // Explicit origin of the open detail. It is captured when a card is opened,
    // so the header can never infer Editorial/Mosaico from a filename or from a
    // later mode selection.
    var detailMode by rememberSaveable { mutableStateOf<GenreViewMode?>(null) }
    // Save across detail navigation; state inside the overview branch would reset.
    var viewMode by rememberSaveable { mutableStateOf(GenreViewMode.FEATURED) }
    // Explicit user action only; nothing is built while the panel is idle.
    var showDiagnostic by rememberSaveable { mutableStateOf(false) }
    var diagnosticCsvVisible by rememberSaveable { mutableStateOf(false) }
    // NORM-M1: per-track actions and the single editor host live at panel level so a
    // row recomposition cannot lose the selection or the exact owner receipt.
    var actionsTrack by remember { mutableStateOf<Track?>(null) }
    var normalizationTrack by remember { mutableStateOf<Track?>(null) }
    val context = LocalContext.current
    // Both approved catalogs are read once, when the panel enters composition. They
    // are independent one-shot reads of a bounded manifest (never a media scan and
    // never 312 bitmap decodes) and they survive view switches/detail navigation, so
    // a late manifest can neither flash a false fallback wall nor lose scroll.
    val posterCatalog by produceState<Ui7Catalog<EditorialPosterAsset>>(Ui7Catalog.Loading) {
        value = withContext(Dispatchers.IO) { loadEditorialCatalog(context) }
    }
    val mosaicCatalog by produceState<Ui7Catalog<MosaicArtworkAsset>>(Ui7Catalog.Loading) {
        value = withContext(Dispatchers.IO) { loadMosaicCatalog(context) }
    }
    // Both views retain their independent positions when switching modes or opening details.
    val featuredGridState = rememberLazyGridState()
    val editorialListState = rememberLazyListState()
    val openGenre: (String) -> Unit = { id ->
        detailMode = viewMode
        selectedId = id
    }
    val selected = genres.firstOrNull { it.id == selectedId }
    // A deleted/retagged genre must never reopen from a stale saved ID.
    LaunchedEffect(selectedId, genres) {
        if (selectedId != null && selected == null) {
            selectedId = null
            detailMode = null
        }
    }
    BackHandler(enabled = selected != null) {
        selectedId = null
        detailMode = null
    }

    // MI-26 2.5: aggregated, on-demand receipt over the already-loaded index and
    // the approved catalogs. No file scan, no provider call, no path or history.
    if (showDiagnostic) {
        val diagnosticRows = remember(genres, mosaicCatalog, posterCatalog) {
            GenreLibraryDiagnosticReport.build(
                genres = genres,
                mosaicKeys = mosaicCatalog.entriesOrEmpty().keys,
                editorialSlugs = posterCatalog.entriesOrEmpty().keys,
            )
        }
        AlertDialog(
            onDismissRequest = {
                showDiagnostic = false
                diagnosticCsvVisible = false
            },
            title = { Text("Diagnóstico local de géneros") },
            text = {
                Column(Modifier.heightIn(max = 420.dp)) {
                    Text(
                        "Usa el índice ya cargado. No escanea archivos, no consulta " +
                            "proveedores y no incluye rutas ni historial.",
                    )
                    if (diagnosticCsvVisible) {
                        Text(
                            GenreLibraryDiagnosticReport.csv(diagnosticRows),
                            modifier = Modifier.verticalScroll(rememberScrollState())
                                .testTag("genres_diagnostic_csv"),
                        )
                    } else {
                        LazyColumn(Modifier.testTag("genres_diagnostic_rows")) {
                            items(diagnosticRows) { row ->
                                Text(
                                    "${row.originalLabel} · ${row.songCount} pistas · " +
                                        "${row.evidenceOrigin} · " +
                                        "Mosaico:${if (row.mosaicExact) "sí" else "no"} · " +
                                        "Editorial:${if (row.editorialExact) "sí" else "no"} · " +
                                        row.fallbackCause,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDiagnostic = false
                    diagnosticCsvVisible = false
                }) { Text("Cerrar") }
            },
            dismissButton = {
                TextButton(
                    onClick = { diagnosticCsvVisible = !diagnosticCsvVisible },
                    modifier = Modifier.testTag("genres_diagnostic_toggle_csv"),
                ) { Text(if (diagnosticCsvVisible) "Ver filas" else "Ver CSV agregado") }
            },
        )
    }
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
        // Observe the one-shot catalog states in the panel's own composition scope
        // too. BoxWithConstraints composes its content in a subcomposition, and a
        // load observed only there left the grid on the empty fail-closed map after
        // the 312-item catalog arrived (reproduced on emulator-5554), so artwork
        // never recomposed. Reading the states here restarts the panel reliably.
        val mosaicState = mosaicCatalog
        val posterState = posterCatalog
        Column(Modifier.fillMaxSize().testTag("genres_overview")) {
            GenreModeToolbar(
                totalGenres = genres.size,
                currentMode = viewMode,
                onModeChange = { viewMode = it },
                onDiagnostic = { showDiagnostic = true },
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
                        artwork = mosaicState.entriesOrEmpty(),
                        onArtworkLoad = onArtworkLoad,
                        onOpen = openGenre,
                        loading = mosaicState is Ui7Catalog.Loading,
                    )
                    GenreViewMode.EDITORIAL -> when (posterState) {
                        // The wall materialises only when the real ratios are known:
                        // never a full fallback wall that jumps when the manifest lands.
                        is Ui7Catalog.Loading -> GenreEditorialLoading(Modifier.fillMaxSize())
                        else -> GenreEditorialGrid(
                            genres = ordered,
                            columns = columns,
                            availableWidthDp = maxWidth.value,
                            state = editorialListState,
                            posterAssets = posterState.entriesOrEmpty(),
                            onArtworkLoad = onArtworkLoad,
                            onOpen = openGenre,
                        )
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
    val originMode = detailMode ?: viewMode
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("genre_detail"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "genre_header") {
            GenreDetailHeader(
                genre = selected,
                viewMode = originMode,
                poster = editorialPosterForTitle(selected.title, posterCatalog.entriesOrEmpty()),
                mosaic = mosaicArtworkForGenre(selected.id, mosaicCatalog.entriesOrEmpty()),
                productionAssets = emptySet(),
                onArtworkLoad = onArtworkLoad,
                onBack = {
                    selectedId = null
                    detailMode = null
                },
                onPlay = { if (playableTracks.isNotEmpty()) onPlay(playableTracks, 0) },
                onShuffle = {
                    if (playableTracks.isNotEmpty()) onPlay(playableTracks.shuffled(), 0)
                },
            )
            if (playableTracks.isEmpty()) {
                Text("No hay pistas reproducibles", style = MichiTypography.microLabel,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp))
            }
        }
        // MI-22: detail-only; preserve C14 Editorial/Mosaico layout and grid semantics.
        if (scopeKey.isNotBlank() && scopeGeneration > 0L) {
            item(key = "genre_intelligence") {
                MichiGenreContextRecommendations(
                    genreId = selected.id,
                    genreTitle = selected.title,
                    playableTracks = playableTracks,
                    scopeKey = scopeKey,
                    scopeGeneration = scopeGeneration,
                    currentTrack = currentTrack,
                    canPlayLocally = canPlayLocally,
                    captureIntelligenceTicket = captureIntelligenceTicket,
                    playIntelligenceQueue = playIntelligenceQueue,
                )
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
                // Same shared artwork seam as Now Playing: model cover, then the
                // real local file for embedded art, then the neutral placeholder.
                AlbumArtView(
                    coverStyle = coverStyleFor(track.coverId.ifBlank { track.title }),
                    imageModel = aeroTrackArtworkCoverModel(track.source, track.coverId),
                    fallbackMediaUri = aeroTrackArtworkFallbackUri(track.source, track.filepath),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("genre_track_art_${track.id}"),
                    cornerRadius = 9.dp,
                    onImageResult = onTrackArtworkLoad?.let { notify ->
                        { ok -> notify(track.id, ok) }
                    },
                )
                Column(Modifier.weight(1f)) {
                    Text(track.title, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (active) TertiaryCyan else PureWhite,
                        maxLines = if (largeText) 2 else 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("genre_track_title_${track.id}"))
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
                // Contextual actions are separate from the row tap: opening the menu
                // never starts playback and never depends on Media3 playability.
                MichiIconAction(
                    icon = Icons.Default.MoreVert,
                    contentDescription = "Más opciones de ${track.title}",
                    onClick = { actionsTrack = track },
                    testTag = "genre_track_more_${track.id}",
                    tint = TextSecondary,
                )
            }
        }
    }
    // The actions sheet is closed BEFORE the editor opens: never two modal
    // containers stacked over each other.
    actionsTrack?.let { track ->
        GenreTrackActionsSheet(
            track = track,
            onDismiss = { actionsTrack = null },
            onNormalize = {
                actionsTrack = null
                normalizationTrack = track
            },
        )
    }
    normalizationTrack?.let { track ->
        MichiGenreNormalizationHost(
            track = track,
            origin = MichiGenreEditorOrigin.GENRES,
            onDismiss = { normalizationTrack = null },
        )
    }
}

/** Stable presentation accent only; cyan stays reserved for technical/audio truth. */
internal fun genreAccent(genre: LibraryGenre): Color =
    if (genreIsUnclassified(genre.id)) TextSecondary
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
    onDiagnostic: () -> Unit = {},
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
        TextButton(
            onClick = onDiagnostic,
            modifier = Modifier.testTag("genres_diagnostic_open"),
        ) { Text("Diagnóstico", style = MichiTypography.microLabel, color = TextSecondary) }
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
 * Editorial loading surface: the wall is not materialised until the real poster
 * ratios are available, so a false fallback wall cannot shift under the user.
 * Neutral, no technical text, no invented genres.
 */
@Composable
private fun GenreEditorialLoading(modifier: Modifier = Modifier) {
    Box(modifier.testTag("genres_editorial_loading"))
}

/** One bounded read of the approved Mosaico catalog; never enumerates media. */
private fun loadMosaicCatalog(context: android.content.Context): Ui7Catalog<MosaicArtworkAsset> {
    GenreArtworkDiagnostics.catalogLoading("MOSAIC")
    return try {
        val files = context.assets.list(mosaicFolder).orEmpty().toSet()
        val text = context.assets.open("$mosaicFolder/manifest.tsv")
            .bufferedReader().use { it.readText() }
        val optical = runCatching {
            context.assets.open("$mosaicFolder/optical.tsv").bufferedReader().use {
                parseUi7OpticalMasks(it.readText()) { line, reason ->
                    GenreArtworkDiagnostics.opticalRowRejected("MOSAIC", line, reason)
                }
            }
        }.getOrDefault(emptyMap())
        var rejection: String? = null
        val approved = mosaicApprovedArtwork(
            manifest = text,
            installed = files,
            onRejected = { reason -> rejection = reason },
            opticalMasks = optical,
        )
        if (rejection != null || approved.isEmpty()) {
            GenreArtworkDiagnostics.catalogRejected("MOSAIC", rejection ?: "empty_catalog")
            Ui7Catalog.Rejected(rejection ?: "empty_catalog")
        } else {
            GenreArtworkDiagnostics.catalogLoaded(
                "MOSAIC", approved.size, files.count { it.endsWith(".webp") },
            )
            Ui7Catalog.Ready(approved)
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        GenreArtworkDiagnostics.catalogError("MOSAIC", error)
        Ui7Catalog.Rejected("manifest_read_failed")
    }
}

/** One bounded read of the approved Editorial poster catalog. */
private fun loadEditorialCatalog(
    context: android.content.Context,
): Ui7Catalog<EditorialPosterAsset> {
    GenreArtworkDiagnostics.catalogLoading("EDITORIAL")
    return try {
        val files = context.assets.list(editorialPosterFolder).orEmpty().toSet()
        val text = context.assets.open("$editorialPosterFolder/manifest.tsv")
            .bufferedReader().use { it.readText() }
        val optical = runCatching {
            context.assets.open("$editorialPosterFolder/optical.tsv").bufferedReader().use {
                parseUi7OpticalMasks(it.readText()) { line, reason ->
                    GenreArtworkDiagnostics.opticalRowRejected("EDITORIAL", line, reason)
                }
            }
        }.getOrDefault(emptyMap())
        var rejection: String? = null
        val approved = editorialApprovedPosters(
            manifest = text,
            installed = files,
            onRejected = { reason -> rejection = reason },
            opticalMasks = optical,
        )
        if (rejection != null || approved.isEmpty()) {
            GenreArtworkDiagnostics.catalogRejected("EDITORIAL", rejection ?: "empty_catalog")
            Ui7Catalog.Rejected(rejection ?: "empty_catalog")
        } else {
            GenreArtworkDiagnostics.catalogLoaded(
                "EDITORIAL", approved.size, files.count { it.endsWith(".webp") },
            )
            Ui7Catalog.Ready(approved)
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        GenreArtworkDiagnostics.catalogError("EDITORIAL", error)
        Ui7Catalog.Rejected("manifest_read_failed")
    }
}
