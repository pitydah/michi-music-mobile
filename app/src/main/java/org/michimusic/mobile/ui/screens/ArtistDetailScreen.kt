package org.michimusic.mobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.michimusic.core.models.TrackSource
import org.michimusic.data.repository.MusicLibraryRepository
import org.michimusic.mobile.artist.ArtistEnrichmentPanel
import org.michimusic.mobile.artist.ArtistEnrichmentController
import org.michimusic.mobile.artist.ArtistEnrichmentOrchestrator
import org.michimusic.mobile.artist.ArtistEnrichmentPersistence
import org.michimusic.mobile.artist.PersistedArtistProfile
import org.michimusic.mobile.artist.ArtistProfileStatus
import org.michimusic.mobile.artist.resolveArtistProfileConsensus
import org.michimusic.mobile.artist.allowedCommonsPortrait
import org.michimusic.mobile.artist.artwork.ArtistArtworkPersistence
import org.michimusic.mobile.artist.artwork.ArtistArtworkSelection
import org.michimusic.mobile.artist.artwork.ArtistArtworkService
import org.michimusic.mobile.artist.artwork.ArtworkLoadStatus
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.artist.artwork.artistIdentityAnchor
import org.michimusic.mobile.artist.artwork.verifyCachedArtwork
import org.michimusic.mobile.artist.scopedArtistCreditKey
import org.michimusic.mobile.library.LibraryScopeStore
import org.michimusic.mobile.library.rememberArtistScopedCollection
import org.michimusic.mobile.library.rememberArtistIdentityMemberships
import org.michimusic.mobile.library.artistQueueSelection
import org.michimusic.mobile.library.artistFirstPlayableSelection
import org.michimusic.mobile.playback.PlaybackSessionManager
import org.michimusic.mobile.screens.AlbumsViewModel
import org.michimusic.mobile.ui.components.AeroActionButton
import org.michimusic.mobile.ui.components.AeroActionKind
import org.michimusic.mobile.ui.components.AeroArtworkMat
import org.michimusic.mobile.ui.components.AeroPremiumBackdrop
import org.michimusic.mobile.ui.components.AlbumArtView
import org.michimusic.mobile.ui.components.coverStyleFor
import org.michimusic.mobile.ui.components.premium.MichiIconAction
import org.michimusic.mobile.ui.theme.InterFontFamily
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * Artist detail is a navigation destination, never a Dialog. It presents only albums and
 * tracks that belong to the visible local credit. Enrichment is opt-in and cannot change
 * playback metadata or silently associate homonymous performers.
 */
@Composable
fun ArtistDetailScreen(
    artistCredit: String,
    onBack: () -> Unit,
    initialEnrichment: Boolean = false,
    sessionManager: PlaybackSessionManager = koinInject(),
    modifier: Modifier = Modifier,
) {
    val viewModel: AlbumsViewModel = koinViewModel()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val mediaLoadError by viewModel.error.collectAsStateWithLifecycle()
    val scopeStore: LibraryScopeStore = koinInject()
    val libraryRepo: MusicLibraryRepository = koinInject()
    val activeScope by scopeStore.activeScope.collectAsStateWithLifecycle()
    val availableLibraries by libraryRepo.observeLibraries().collectAsStateWithLifecycle(initialValue = emptyList())

    LaunchedEffect(viewModel) { viewModel.loadMedia() }

    // EXACT source used by Library's artist mosaic: visible tracks, albums and
    // enrichment key all belong to the same snapshot / membership set.
    var scopeRetry by remember { mutableIntStateOf(0) }
    val scoped = rememberArtistScopedCollection(
        localAlbums = albums,
        scope = activeScope,
        libraries = availableLibraries,
        repository = libraryRepo,
        retryKey = scopeRetry,
    )
    var membershipRetry by remember { mutableIntStateOf(0) }
    val memberships = rememberArtistIdentityMemberships(
        aggregate = scoped, localAlbums = albums, libraries = availableLibraries,
        repository = libraryRepo, retryKey = membershipRetry,
    )
    val profileScopes = if (scoped.identityLibraryId == null && memberships.ready && !memberships.error)
        memberships.byCredit[artistCredit].orEmpty() else emptyList()
    val localAlbums = remember(scoped, artistCredit) {
        if (!scoped.ready) emptyList()
        else scoped.albums.filter { it.album.artist == artistCredit }
    }
    val tracks = remember(localAlbums) { localAlbums.flatMap { it.tracks }.distinctBy { it.id } }
    val playableTrackIds = scoped.playableTrackIds
    val unavailableCount = tracks.count { it.id !in playableTrackIds }
    val collectionPhase = artistDetailCollectionPhase(
        scopeReady = scoped.ready, scopeError = scoped.errorMessage,
        mediaLoading = isLoading, mediaError = mediaLoadError,
        hasAlbums = localAlbums.isNotEmpty(),
    )
    var playbackNotice by remember(artistCredit, activeScope) { mutableStateOf<String?>(null) }

    fun playEligible(selection: org.michimusic.mobile.library.ArtistQueueSelection?) {
        if (selection == null) {
            playbackNotice = "No hay audio accesible para esta selección. Conecta el origen o revisa los permisos."
            return
        }
        playbackNotice = null
        // A failed engine/remote command is reported by PlaybackSessionManager;
        // never claim successful playback merely because we submitted the queue.
        try {
            sessionManager.playQueue(selection.tracks, selection.index)
        } catch (_: Exception) {
            playbackNotice = "No se pudo enviar la cola. Comprueba el destino de reproducción."
        }
    }
    val trackToAlbumTitle = remember(localAlbums) {
        localAlbums.flatMap { group -> group.tracks.map { it.id to group.album.title } }.toMap()
    }
    val libraryId = scoped.identityLibraryId
    val albumIds = remember(localAlbums) { localAlbums.map { it.album.id } }
    val context = LocalContext.current
    val store = remember(context) { ArtistEnrichmentPersistence.from(context) }
    val artworkStore = remember(context) { ArtistArtworkPersistence.from(context) }
    var showEnrichment by rememberSaveable(artistCredit) { mutableStateOf(initialEnrichment) }
    var cacheRevision by remember { mutableIntStateOf(0) }
    // Keep the stale bit: a saved association is not evidence of fresh external data.
    var savedRecord by remember(artistCredit, libraryId, albumIds, profileScopes) {
        mutableStateOf<PersistedArtistProfile?>(null)
    }
    var profileStatus by remember(artistCredit, libraryId, albumIds, profileScopes) {
        mutableStateOf(ArtistProfileStatus.UNRESOLVED)
    }
    var profileReadError by remember(artistCredit, libraryId, albumIds, profileScopes) {
        mutableStateOf(false)
    }
    var profileReading by remember(artistCredit, libraryId, albumIds, profileScopes) {
        mutableStateOf(true)
    }
    LaunchedEffect(store, artistCredit, libraryId, albumIds, profileScopes, cacheRevision) {
        savedRecord = null
        profileStatus = ArtistProfileStatus.UNRESOLVED
        profileReadError = false
        profileReading = true
        try {
            if (libraryId == null) {
                val result = resolveArtistProfileConsensus(profileScopes, store)
                currentCoroutineContext().ensureActive()
                savedRecord = result.saved
                profileStatus = result.status
                profileReadError = result.status == ArtistProfileStatus.ERROR
            } else if (scopedArtistCreditKey(libraryId, artistCredit, albumIds) != null) {
                val loaded = store.load(libraryId, artistCredit, albumIds)
                currentCoroutineContext().ensureActive()
                savedRecord = loaded
                profileStatus = if (loaded != null) ArtistProfileStatus.CONFIRMED else ArtistProfileStatus.MISSING
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // External metadata errors are explicit; local playback remains usable.
            profileReadError = true
        } finally {
            profileReading = false
        }
    }
    val savedProfile = savedRecord?.profile
    val cachedProfileIsStale = savedRecord?.stale == true
    var heroArtwork by remember(artistCredit, libraryId, albumIds) {
        mutableStateOf<ArtistArtworkSelection?>(null)
    }
    var artworkReadError by remember(artistCredit, libraryId, albumIds) { mutableStateOf(false) }
    LaunchedEffect(artworkStore, artistCredit, libraryId, albumIds,
        savedProfile?.mbid, savedProfile?.wikidataQid, cacheRevision) {
        heroArtwork = null
        artworkReadError = false
        if (libraryId == null) return@LaunchedEffect
        try {
            val anchor = artistIdentityAnchor(libraryId, artistCredit, albumIds, savedProfile)
                ?: return@LaunchedEffect
            val selections = artworkStore.load(anchor)
            currentCoroutineContext().ensureActive()
            heroArtwork = selections.firstOrNull {
                it.slot == ArtworkSlot.ARTIST_HERO &&
                    it.status == ArtworkLoadStatus.READY &&
                    it.confirmedMbid == anchor.confirmedMbid
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Artwork cache failure is independent from the verified biography and
            // local playback; the hero falls back to the legacy portrait or monogram.
            artworkReadError = true
        }
    }
    // One controller owns identity, biography and artwork for both this ficha and
    // the Enriquecer panel. The cached profile stays visible while it refreshes.
    val controllerScope = rememberCoroutineScope()
    val enrichmentController = remember(context, store, artworkStore) {
        ArtistEnrichmentController(
            orchestrator = ArtistEnrichmentOrchestrator(
                artwork = ArtistArtworkService.from(context),
                store = store,
            ),
            profileStore = store,
            artworkStore = artworkStore,
            verifySelection = { verifyCachedArtwork(context, it) },
            scope = controllerScope,
        )
    }
    LaunchedEffect(enrichmentController, artistCredit, libraryId, albumIds) {
        enrichmentController.bind(libraryId, artistCredit, albumIds)
    }
    DisposableEffect(enrichmentController) {
        onDispose { enrichmentController.release() }
    }
    val enrichmentState by enrichmentController.state.collectAsStateWithLifecycle()
    val effectiveProfile = enrichmentState.profile ?: savedProfile
    val effectiveStale = enrichmentState.stale || cachedProfileIsStale
    val effectivePortrait = effectiveProfile?.portrait?.takeIf(::allowedCommonsPortrait)
    val effectiveHeroArtwork = enrichmentState.selections[ArtworkSlot.ARTIST_HERO]
        ?.takeIf { it.status == ArtworkLoadStatus.READY } ?: heroArtwork
    // State lives above LazyColumn so every remote discography row is its own lazy item.
    fun retryLibrary() {
        scopeRetry++
        membershipRetry++
        viewModel.loadMedia()
    }
    fun returnToOverview() {
        showEnrichment = false
        cacheRevision++ // A confirmed portrait or deleted association is reflected on return.
    }
    fun handleBack() {
        when (artistDetailBackDestination(showEnrichment)) {
            ArtistDetailBackDestination.OVERVIEW -> returnToOverview()
            ArtistDetailBackDestination.LIBRARY -> onBack()
        }
    }
    BackHandler(onBack = ::handleBack)

    Box(modifier = modifier.fillMaxSize().background(SurfaceObsidian).testTag("artist_detail_screen")) {
        AeroPremiumBackdrop(accent = PrimaryPinkContainer)
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MichiIconAction(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = if (showEnrichment) "Volver a la ficha" else "Volver a artistas",
                    onClick = ::handleBack,
                    tint = PureWhite,
                    iconSize = 22.dp,
                    minTargetSize = MichiSpacing.minTouchTarget,
                    testTag = "artist_detail_back",
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("BIBLIOTECA / ARTISTA", color = PrimaryPinkContainer, fontSize = 10.sp,
                        fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Text(artistCredit.ifBlank { "Artista desconocido" }, color = PureWhite,
                        fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(
                    onClick = { if (showEnrichment) returnToOverview() else showEnrichment = true },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("artist_detail_enrichment_toggle"),
                ) {
                    Text(if (showEnrichment) "Ver ficha" else "Enriquecer",
                        color = TertiaryCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (showEnrichment) {
                if (collectionPhase == ArtistDetailCollectionPhase.SOURCE_ERROR) {
                    Column(Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(scoped.errorMessage.orEmpty(), color = TextSecondary)
                        AeroActionButton("Reintentar biblioteca", onClick = ::retryLibrary,
                            testTag = "artist_detail_scope_retry")
                    }
                } else if (collectionPhase == ArtistDetailCollectionPhase.LOADING) {
                    Column(Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(color = TertiaryCyan)
                        AeroActionButton("Reintentar carga", onClick = ::retryLibrary,
                            testTag = "artist_enrichment_wait_retry")
                    }
                } else if (collectionPhase == ArtistDetailCollectionPhase.MEDIA_ERROR) {
                    Column(Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("No se pudieron cargar los datos locales. " + mediaLoadError.orEmpty(),
                            color = TextSecondary, modifier = Modifier.testTag("artist_media_error"))
                        AeroActionButton("Reintentar carga local", onClick = ::retryLibrary,
                            testTag = "artist_media_retry")
                    }
                } else if (libraryId == null) {
                    Column(Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Selecciona una biblioteca para confirmar esta identidad. " +
                            "«Todas» no puede guardar asociaciones de artistas homónimos.",
                            color = TextSecondary)
                        if (!memberships.ready) {
                            CircularProgressIndicator(color = TertiaryCyan)
                        } else {
                            val eligible = profileScopes.map { it.libraryId }.toSet()
                            if (memberships.error) {
                                Text("No se pudieron consultar todas las membresías. " +
                                    "Puedes elegir una biblioteca individual y reintentar.",
                                    color = TextSecondary)
                                AeroActionButton("Reintentar membresías",
                                    onClick = { membershipRetry++ },
                                    testTag = "artist_memberships_retry")
                            }
                            availableLibraries.filter { it.id in eligible || memberships.error }.forEach { lib ->
                                TextButton(
                                    onClick = { scopeStore.setScope(org.michimusic.core.models.LibraryScope.One(lib.id)) },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                        .testTag("artist_enrichment_choose_library_${lib.id}"),
                                ) { Text("Usar ${lib.name}", color = TertiaryCyan) }
                            }
                            if (eligible.isEmpty() && !memberships.error) {
                                Text("No hay una membresía verificable para este crédito. " +
                                    "Selecciona primero una biblioteca desde Biblioteca.", color = TextSecondary)
                            }
                        }
                    }
                } else if (localAlbums.isEmpty()) {
                    Box(Modifier.fillMaxSize().testTag("artist_enrichment_missing_collection"),
                        contentAlignment = Alignment.Center) {
                        Text("Este artista no pertenece a la biblioteca seleccionada.",
                            color = TextSecondary, modifier = Modifier.padding(20.dp))
                    }
                } else {
                    // The enrichment key must NEVER be generated before the selected
                    // library and its album fingerprint are both stable and observed.
                    val localTracks = remember(tracks) {
                        tracks.filter { it.source == TrackSource.LOCAL && it.filepath.isNotBlank() }
                    }
                    ArtistEnrichmentPanel(
                        artistCredit = artistCredit,
                        libraryId = libraryId,
                        albumIds = albumIds,
                        controller = enrichmentController,
                        artworkStore = artworkStore,
                        localAudioUris = localTracks.map { it.filepath },
                        localTrackIds = localTracks.map { it.id },
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("artist_detail_collection"),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (collectionPhase == ArtistDetailCollectionPhase.SOURCE_ERROR) {
                        item("artist_scope_error") {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(scoped.errorMessage.orEmpty(), color = TextSecondary)
                                AeroActionButton("Reintentar biblioteca", onClick = ::retryLibrary,
                                    testTag = "artist_detail_scope_retry")
                            }
                        }
                    } else if (collectionPhase == ArtistDetailCollectionPhase.LOADING) {
                        item("artist_loading") {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = TertiaryCyan)
                                }
                                AeroActionButton("Reintentar carga", onClick = ::retryLibrary,
                                    testTag = "artist_scope_wait_retry")
                            }
                        }
                    } else if (collectionPhase == ArtistDetailCollectionPhase.EMPTY ||
                        collectionPhase == ArtistDetailCollectionPhase.MEDIA_ERROR) {
                        item("artist_missing") {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(if (mediaLoadError != null)
                                    "No se pudo cargar la colección local. ${mediaLoadError}"
                                    else "Este artista ya no está disponible en la biblioteca seleccionada.",
                                    color = TextSecondary, modifier = Modifier.padding(vertical = 12.dp))
                                AeroActionButton("Reintentar", onClick = ::retryLibrary,
                                    testTag = "artist_missing_retry")
                            }
                        }
                    } else {
                        if (mediaLoadError != null) {
                            item("artist_media_warning") {
                                Text("No fue posible actualizar la música local. Los archivos ya registrados siguen visibles.",
                                    color = TextSecondary, modifier = Modifier.testTag("artist_media_warning"))
                            }
                        }
                        if (playbackNotice != null) {
                            item("artist_playback_notice") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(requireNotNull(playbackNotice), color = TextSecondary,
                                        modifier = Modifier.testTag("artist_playback_notice"))
                                    AeroActionButton("Actualizar disponibilidad", onClick = ::retryLibrary,
                                        testTag = "artist_playback_refresh")
                                }
                            }
                        }
                        item("artist_hero") {
                            ArtistDetailHero(
                                artistCredit = artistCredit,
                                portrait = effectivePortrait,
                                albumCount = localAlbums.size,
                                trackCount = tracks.size,
                                artwork = effectiveHeroArtwork,
                            )
                        }
                        if (profileStatus == ArtistProfileStatus.CONFLICT) {
                            item("artist_profile_ambiguous") {
                                Text("Hay identidades MusicBrainz diferentes para este crédito " +
                                    "en las bibliotecas seleccionadas. Selecciona una biblioteca " +
                                    "y revisa el artista antes de mostrar su fotografía.",
                                    color = TextSecondary,
                                    modifier = Modifier.testTag("artist_identity_conflict"))
                            }
                        }
                        if (profileReadError) {
                            item("artist_profile_cache_error") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("No se pudieron cargar los datos enriquecidos. Tu colección está disponible.",
                                        color = TextSecondary,
                                        modifier = Modifier.testTag("artist_profile_cache_error"))
                                    AeroActionButton("Reintentar datos del artista",
                                        onClick = { cacheRevision++ },
                                        testTag = "artist_profile_cache_retry")
                                }
                            }
                        } else if (profileReading) {
                            item("artist_profile_loading") {
                                Text("Consultando datos guardados del artista…", color = TextSecondary,
                                    modifier = Modifier.testTag("artist_profile_loading"))
                            }
                        }
                        if (memberships.error && libraryId == null) {
                            item("artist_memberships_error") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("No se pudieron verificar identidades de todas las bibliotecas. " +
                                        "Se ocultan retratos ambiguos.", color = TextSecondary)
                                    AeroActionButton("Reintentar identidades",
                                        onClick = { membershipRetry++ }, testTag = "artist_memberships_retry")
                                }
                            }
                        }
                        item("artist_bio") {
                            ArtistBiographySection(
                                artistCredit = artistCredit,
                                profile = effectiveProfile,
                                isStale = effectiveStale,
                                portrait = effectivePortrait,
                                artwork = effectiveHeroArtwork,
                            )
                        }
                        if (artworkReadError) {
                            item("artist_artwork_cache_error") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("No se pudo leer la caché local de retratos. La biografía y tu colección siguen disponibles.",
                                        color = TextSecondary,
                                        modifier = Modifier.testTag("artist_artwork_cache_error"))
                                    AeroActionButton("Reintentar retratos",
                                        onClick = { cacheRevision++ },
                                        testTag = "artist_artwork_cache_retry")
                                }
                            }
                        }
                        // Discography is exactly the albums present in the local library.
                        // Catalogue-only releases are not shown here: this list must reflect
                        // the files the user actually has, not what MusicBrainz published.
                        item("artist_discography_heading") {
                            ArtistDetailSectionHeader("DISCOGRAFÍA")
                        }
                        item("artist_discography_row") {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(end = 4.dp),
                                modifier = Modifier.fillMaxWidth().testTag("artist_discography"),
                            ) {
                                items(localAlbums, key = { it.album.id }) { group ->
                                    Column(
                                        modifier = Modifier.width(142.dp)
                                            .clip(RoundedCornerShape(13.dp))
                                            .clickable(role = Role.Button,
                                                enabled = group.tracks.any { it.id in playableTrackIds }) {
                                                playEligible(artistFirstPlayableSelection(group.tracks, playableTrackIds))
                                            }
                                            .semantics(mergeDescendants = true) {
                                                contentDescription = if (group.tracks.any { it.id in playableTrackIds })
                                                    "Reproducir álbum ${group.album.title}"
                                                else "Álbum ${group.album.title}, audio no disponible"
                                            }
                                            .padding(bottom = 6.dp)
                                            .testTag("artist_discography_album_${group.album.id}"),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        AeroArtworkMat(
                                            modifier = Modifier.size(142.dp),
                                            cornerRadius = 13.dp,
                                            inset = 3.dp,
                                        ) {
                                            AlbumArtView(
                                                coverStyle = coverStyleFor(group.album.coverId.ifBlank { group.album.title }),
                                                imageModel = group.album.coverId,
                                                fallbackMediaUri = group.tracks.firstOrNull()?.filepath,
                                                modifier = Modifier.fillMaxSize(),
                                                cornerRadius = 13.dp,
                                                borderWidth = 0.dp,
                                            )
                                        }
                                        Text(group.album.title, color = PureWhite, fontSize = 13.sp,
                                            fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        val songCount = if (group.tracks.size == 1) "1 canción" else "${group.tracks.size} canciones"
                                        Text(
                                            if (group.album.year > 0) "${group.album.year} · $songCount" else songCount,
                                            color = TextSecondary, fontSize = 11.sp,
                                        )
                                    }
                                }
                            }
                        }
                        item("artist_tracks_heading") {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                ArtistDetailSectionHeader("CANCIONES", tracks.size)
                                if (unavailableCount > 0) {
                                    Text("$unavailableCount ${if (unavailableCount == 1) "pista no disponible" else "pistas no disponibles"}. " +
                                        "Se omitirán al reproducir.", color = TextSecondary,
                                        fontSize = 12.sp, modifier = Modifier.testTag("artist_unavailable_count"))
                                }
                                AeroActionButton(
                                    label = "Reproducir todo",
                                    onClick = { playEligible(artistFirstPlayableSelection(tracks, playableTrackIds)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    icon = Icons.Filled.PlayArrow,
                                    kind = AeroActionKind.Primary,
                                    enabled = tracks.any { it.id in playableTrackIds },
                                    testTag = "artist_detail_play_all",
                                )
                            }
                        }
                        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                            ArtistDetailTrackRow(
                                track = track,
                                albumTitle = trackToAlbumTitle[track.id].orEmpty(),
                                isPlayable = track.id in playableTrackIds,
                                onClick = {
                                    playEligible(artistQueueSelection(tracks, track.id, playableTrackIds))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
