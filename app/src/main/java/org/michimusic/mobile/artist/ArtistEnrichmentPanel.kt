package org.michimusic.mobile.artist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.Job
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.michimusic.mobile.artist.artwork.ArtistArtworkCandidate
import org.michimusic.mobile.artist.artwork.ArtistIdentityAnchor
import org.michimusic.mobile.artist.artwork.ArtistArtworkPersistence
import org.michimusic.mobile.artist.artwork.ArtistArtworkSelection
import org.michimusic.mobile.artist.artwork.ArtistArtworkService
import org.michimusic.mobile.artist.artwork.ArtistArtworkStore
import org.michimusic.mobile.artist.artwork.verifyCachedArtwork
import org.michimusic.mobile.artist.artwork.ArtworkFocus
import org.michimusic.mobile.artist.artwork.ArtworkLoadStatus
import org.michimusic.mobile.artist.artwork.ArtworkResolutionReport
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.artist.artwork.ProviderArtworkResult
import org.michimusic.mobile.artist.artwork.artistIdentityAnchor
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SecondaryPurple
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.aeroMaterialStyle
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.components.aeroFinishDrawing
import org.michimusic.mobile.ui.components.biographyStatusText
import org.michimusic.mobile.ui.components.portraitStatusText
import org.michimusic.mobile.ui.theme.AeroFinish
import org.michimusic.mobile.ui.theme.aeroFinishSpec
import org.michimusic.mobile.ui.theme.LocalVisualPreferences

/**
 * Optional remote artist facts. No request starts without a user action; a candidate must be
 * explicitly chosen before biography and portrait lookup. Presentation is independent of playback.
 */
@Composable
fun ArtistEnrichmentPanel(
    artistCredit: String,
    modifier: Modifier = Modifier,
    gateway: ArtistEnrichmentGateway = ArtistEnrichmentService.repository,
    libraryId: String? = null,
    albumIds: List<String> = emptyList(),
    selectionStore: ArtistEnrichmentSelectionStore? = null,
    artworkStore: ArtistArtworkStore? = null,
    controller: ArtistEnrichmentController? = null,
    localAudioUris: List<String> = emptyList(),
    localTrackIds: List<String> = emptyList(),
    tagProbe: ArtistTagProbe? = null,
) {
    val context = LocalContext.current
    val store = remember(context, selectionStore) { selectionStore ?: ArtistEnrichmentPersistence.from(context) }
    val artworkCoordinator = remember(context) { ArtistArtworkService.from(context) }
    val artworkCache = remember(context, artworkStore) {
        artworkStore ?: ArtistArtworkPersistence.from(context)
    }
    val orchestrator = remember(context, store, artworkCoordinator, gateway) {
        ArtistEnrichmentOrchestrator(gateway = gateway, artwork = artworkCoordinator, store = store)
    }
    val embeddedTags = remember(context, tagProbe) { tagProbe ?: LocalArtistTagProbe(context) }
    val scope = rememberCoroutineScope()
    val stableGroup = remember(albumIds) { albumIds.toSortedSet().joinToString("\u0000") }
    val ownsController = controller == null
    val ownedController = remember(
        context, gateway, store, artworkCoordinator, artworkCache,
    ) {
        ArtistEnrichmentController(
            orchestrator = orchestrator,
            profileStore = store,
            artworkStore = artworkCache,
            verifySelection = { verifyCachedArtwork(context, it) },
            scope = scope,
        )
    }
    val activeController = controller ?: ownedController
    LaunchedEffect(activeController, artistCredit, libraryId, stableGroup) {
        activeController.bind(libraryId, artistCredit, albumIds)
    }
    DisposableEffect(activeController) {
        onDispose { if (ownsController) activeController.release() }
    }
    val uiState by activeController.state.collectAsState()
    val uriHandler = LocalUriHandler.current
    // Scanning a tag is user-initiated and must never initiate a remote provider call.
    // A URI alone is not a stable track/library identity: never reuse old tag evidence when
    // a library switches or the same URI is re-associated with another local track.
    val localEvidenceScope = remember(libraryId, localAudioUris, localTrackIds) {
        localAudioUris.zip(localTrackIds).sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("\u0000") { "${it.first}\u0001${it.second}" }
    }
    var tags by remember(artistCredit, libraryId, stableGroup, localEvidenceScope) { mutableStateOf<EmbeddedArtistReview?>(null) }
    var tagReading by remember(artistCredit, libraryId, stableGroup, localEvidenceScope) { mutableStateOf(false) }
    var tagJob by remember(artistCredit, libraryId, stableGroup, localEvidenceScope) { mutableStateOf<Job?>(null) }
    val canPersist = scopedArtistCreditKey(libraryId, artistCredit, albumIds) != null
    // Aero Content/Plain for the candidate rows: the same fill and hairline the rest of
    // the system uses, instead of a locally invented white alpha that read as a card.
    val candidateSurface = aeroMaterialStyle(
        AeroSurfaceRole.Content,
        LocalVisualPreferences.current.highContrast,
    )
    val secondaryColors = ButtonDefaults.outlinedButtonColors(contentColor = SecondaryPurple)
    val secondaryBorder = BorderStroke(0.8.dp, SecondaryPurple.copy(alpha = 0.5f))
    DisposableEffect(artistCredit, libraryId, stableGroup, localEvidenceScope) {
        onDispose { tagJob?.cancel() }
    }
    val profile = uiState.profile

    fun confirmExplicitly(id: String) {
        val verifiedId = canonicalArtistMbid(id) ?: return
        activeController.confirm(artistCredit, libraryId, albumIds, verifiedId)
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .testTag("artist_enrichment_panel"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("IDENTIDAD Y DATOS EXTERNOS", color = TertiaryCyan,
            fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text("La identidad solo se vincula con un MBID confirmado. Si hay varias coincidencias, elige la correcta.",
            color = TextSecondary, fontSize = 12.sp)
        if (!canPersist) {
            Text("Selecciona una biblioteca individual para guardar esta asociación. La vista Todas puede mezclar artistas homónimos.",
                color = TextSecondary, fontSize = 12.sp)
        }
        if (localAudioUris.isNotEmpty()) {
            OutlinedButton(
                onClick = {
                    if (tagJob?.isActive == true) return@OutlinedButton
                    tagReading = true
                    tagJob = scope.launch {
                        try {
                            val found = embeddedTags.inspect(localAudioUris)
                            tags = found
                            if (canPersist) {
                                val trackByUri = localAudioUris.zip(localTrackIds).toMap()
                                found.trackEvidence.forEach { file ->
                                    val trackId = trackByUri[file.uri] ?: return@forEach
                                    try {
                                        store.saveLocalTagEvidence(requireNotNull(libraryId), trackId, file.tags)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        // The user can still inspect tag evidence even if Room is unavailable.
                                    }
                                }
                            }
                        }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { tags = null }
                        finally { tagReading = false }
                    }
                },
                enabled = !tagReading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("artist_embedded_tags_scan"),
            ) { Text(if (tagReading) "Leyendo etiquetas locales…" else "Examinar MBID en mis archivos") }
        }
        tags?.let { evidence ->
            Text("Etiquetas examinadas: ${evidence.inspectedFiles} archivos; ${evidence.taggedFiles} contienen identidad estructurada.",
                color = TextSecondary, fontSize = 12.sp)
            if (evidence.creditedNames.isNotEmpty()) {
                Text("Artistas acreditados en etiquetas: ${evidence.creditedNames.take(8).joinToString(" · ")}",
                    color = TextSecondary, fontSize = 12.sp)
            }
            if (evidence.albumArtists.isNotEmpty()) {
                Text("Artista(s) de álbum: ${evidence.albumArtists.take(8).joinToString(" · ")}",
                    color = TextSecondary, fontSize = 12.sp)
            }
            when {
                evidence.hasConflict -> Text(
                    "Etiquetas ambiguas: aparecen varios MBID. No se vinculará ningún artista automáticamente.",
                    color = TextSecondary, fontSize = 12.sp)
                evidence.suggestedMbid != null -> {
                    Text("MBID encontrado en archivos (sin confirmar): ${evidence.suggestedMbid}",
                        color = TertiaryCyan, fontSize = 12.sp)
                    OutlinedButton(
                        onClick = { confirmExplicitly(requireNotNull(evidence.suggestedMbid)) },
                        enabled = !uiState.refreshing && uiState.profile == null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("artist_embedded_mbid_confirm"),
                    ) { Text("Confirmar identidad y consultar MusicBrainz") }
                }
                else -> Text("Estos archivos no contienen un MBID de artista inequívoco. Puedes buscarlo manualmente.",
                    color = TextSecondary, fontSize = 12.sp)
            }
        }
        when {
            uiState.status == ArtistEnrichmentStatus.CHOOSING -> {
                val found = uiState.candidates
                val historical = uiState.associationCandidates
                if (historical.isNotEmpty()) {
                    Text(
                        "Elige una identidad histórica para este crédito y biblioteca · ${historical.size} alternativas",
                        color = PureWhite,
                        fontSize = 13.sp,
                    )
                    Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
                        historical.forEachIndexed { index, candidate ->
                            if (index > 0) {
                                HorizontalDivider(
                                    color = candidateSurface.outline.copy(alpha = 0.55f),
                                    thickness = 0.5.dp,
                                )
                            }
                            Column(
                                modifier = Modifier.fillMaxWidth()
                                    .background(candidateSurface.fill)
                                    .border(
                                        candidateSurface.borderWidth,
                                        candidateSurface.outline,
                                        RoundedCornerShape(12.dp),
                                    )
                                    .selectable(selected = false, role = Role.Button) {
                                        activeController.resolveAssociation(
                                            artistCredit = artistCredit,
                                            libraryId = libraryId,
                                            albumIds = albumIds,
                                            associationGroupKey = candidate.associationGroupKey,
                                            mbid = candidate.confirmedMbid,
                                        )
                                    }
                                    .heightIn(min = 48.dp)
                                    .padding(12.dp)
                                    .testTag("artist_association_candidate_${candidate.associationGroupKey}"),
                            ) {
                                Text(candidate.displayName, color = PureWhite, fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium)
                                Text("MBID: ${candidate.confirmedMbid}", color = TertiaryCyan, fontSize = 10.sp)
                                Text(
                                    "Grupo histórico: ${candidate.associationGroupKey} · " +
                                        candidate.albumIds.joinToString().ifBlank { "sin álbumes legibles" },
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                } else if (found.isEmpty()) {
                    Text("Sin coincidencias confiables. No se ha asociado ningún artista externo.",
                        color = TextSecondary, fontSize = 12.sp)
                } else {
                    Text("Selecciona el artista correcto · ${found.size} candidatos",
                        color = PureWhite, fontSize = 13.sp)
                    // One selectable group, Plain rows and a hairline between them: the plan
                    // rejects a locally invented card per candidate.
                    Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
                        found.forEachIndexed { index, candidate ->
                            if (index > 0) {
                                HorizontalDivider(
                                    color = candidateSurface.outline.copy(alpha = 0.55f),
                                    thickness = 0.5.dp,
                                )
                            }
                            Column(
                                modifier = Modifier.fillMaxWidth()
                                    .background(candidateSurface.fill)
                                    .border(
                                        candidateSurface.borderWidth,
                                        candidateSurface.outline,
                                        RoundedCornerShape(12.dp),
                                    )
                                    .selectable(
                                        selected = false,
                                        role = Role.Button,
                                    ) {
                                        val verifiedId = selectedArtistMbid(uiState.candidates, candidate.mbid)
                                        if (verifiedId != null) confirmExplicitly(verifiedId)
                                    }
                                    .heightIn(min = 48.dp)
                                    .padding(12.dp)
                                    .testTag("artist_candidate_${candidate.mbid}"),
                            ) {
                                Text(candidate.name, color = PureWhite, fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium)
                                Text(listOf(candidate.type, candidate.area, candidate.disambiguation)
                                    .filter(String::isNotBlank).joinToString(" · ").ifBlank { "Sin desambiguador" },
                                    color = TextSecondary, fontSize = 12.sp, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                                Text("MusicBrainz · ${candidate.mbid}", color = TertiaryCyan, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
            profile != null -> {
                val result = uiState
                Text(profile.name, color = PureWhite, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("MBID confirmado por selección: ${profile.mbid}", color = TextSecondary, fontSize = 11.sp)
                Text(when {
                    result.refreshFailed -> "No fue posible actualizar los datos externos. Se muestran los últimos datos verificados."
                    result.stale -> "Datos guardados: revisión vencida. Confirma nuevamente para actualizar."
                    result.profilePersisted -> "Datos guardados para este crédito y biblioteca."
                    result.persisted -> "Identidad guardada para este crédito y biblioteca."
                    canPersist -> "Datos consultados, pero no guardados; revisa disponibilidad del almacenamiento."
                    else -> "Solo esta consulta. Cambia a una biblioteca individual para guardar."
                }, color = TextSecondary, fontSize = 11.sp)
                if (result.stale) {
                    Text("Datos guardados: revisión vencida. Actualiza solo cuando lo necesites.",
                        color = TextSecondary, fontSize = 11.sp)
                }
                TextButton(
                    onClick = {
                        activeController.refresh(
                            artistCredit, libraryId, albumIds, force = true,
                        )
                    },
                    enabled = !result.refreshing,
                    modifier = Modifier.heightIn(min = 48.dp)
                        .testTag("artist_enrichment_refresh"),
                ) {
                    Text(
                        if (result.refreshing) "Actualizando…" else "Actualizar datos externos",
                        color = TertiaryCyan, fontSize = 12.sp,
                    )
                }
                uiState.persistenceError?.let {
                    Text(it, color = TextSecondary, fontSize = 11.sp,
                        modifier = Modifier.testTag("artist_enrichment_persistence_error"))
                }
                if (uiState.needsConfirmation) {
                    Text("Algunos álbumes visibles todavía no pertenecen a esta asociación. " +
                        "Vincúlalos solo si corresponden al MBID mostrado.",
                        color = TextSecondary, fontSize = 11.sp,
                        modifier = Modifier.testTag("artist_enrichment_needs_confirmation"))
                    OutlinedButton(
                        onClick = {
                            activeController.bindVisibleAlbums(artistCredit, libraryId, albumIds)
                        },
                        enabled = canPersist && !result.refreshing,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("artist_enrichment_bind_albums"),
                        colors = secondaryColors,
                        border = secondaryBorder,
                    ) {
                        Text("Vincular estos álbumes al MBID confirmado")
                    }
                }
                if (canPersist && result.persisted) {
                    OutlinedButton(
                        onClick = {
                            activeController.clear(artistCredit, libraryId, albumIds)
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("artist_enrichment_clear"),
                        colors = secondaryColors,
                        border = secondaryBorder,
                    ) { Text("Borrar enriquecimiento guardado") }
                }
                Text(listOf(profile.type, profile.area, profile.disambiguation)
                    .filter(String::isNotBlank).joinToString(" · ").ifBlank { "Identidad registrada en MusicBrainz" },
                    color = TextSecondary, fontSize = 12.sp)
                profile.portrait?.let { portrait ->
                    // The remote URL is provenance only. Confirmed artwork is shown by
                    // ArtistArtworkManualSection from filesDir after Room verification.
                    Text("Fotografía: ${portrait.attribution} · ${portrait.license}",
                        color = TextSecondary, fontSize = 11.sp)
                    Text("Ver fotografía y atribución en Commons", color = TertiaryCyan, fontSize = 12.sp,
                        modifier = Modifier.clickable(role = Role.Button) { uriHandler.openUri(portrait.commonsPageUrl) }
                            .heightIn(min = 48.dp).testTag("artist_commons_source"))
                    Text("Ver condiciones de la licencia", color = TertiaryCyan, fontSize = 12.sp,
                        modifier = Modifier.clickable(role = Role.Button) { uriHandler.openUri(portrait.licenseUrl) }
                            .heightIn(min = 48.dp))
                }
                Text(profile.biography ?: biographyStatusText(profile.biographyOutcome),
                    color = PureWhite, fontSize = 12.sp)
                profile.biographySourceUrl?.let { source ->
                    Text("Texto: Wikipedia · CC BY-SA · Ver fuente", color = TertiaryCyan, fontSize = 12.sp,
                        modifier = Modifier.clickable(role = Role.Button) { uriHandler.openUri(source) }
                            .heightIn(min = 48.dp).testTag("artist_biography_source"))
                }
                if (profile.portrait == null) {
                    Text(result.message ?: "Ficha actualizada.",
                        color = TextSecondary, fontSize = 11.sp,
                        modifier = Modifier.testTag("artist_enrichment_status"))
                } else {
                    result.message?.let {
                        Text(it, color = TextSecondary, fontSize = 11.sp,
                            modifier = Modifier.testTag("artist_enrichment_status"))
                    }
                }
                ArtistArtworkManualSection(
                    profile = profile,
                    artistCredit = artistCredit,
                    libraryId = libraryId,
                    albumIds = albumIds,
                    initialSelections = uiState.selections,
                    pendingCandidates = result.pendingPortraits,
                    associationGroupKey = uiState.associationGroupKey,
                    onSelectionsChanged = { selections ->
                        activeController.applySelections(
                            libraryId = libraryId,
                            artistCredit = artistCredit,
                            albumIds = albumIds,
                            mbid = profile.mbid,
                            selections = selections,
                        )
                    },
                )
                Text("Los álbumes de catálogos externos no se agregan a tu colección local.",
                    color = TextSecondary, fontSize = 11.sp)
            }
            uiState.status == ArtistEnrichmentStatus.FAILED -> {
                Text(uiState.message ?: "No fue posible completar la ficha ahora. Tu biblioteca permanece disponible sin conexión.",
                    color = TextSecondary, fontSize = 12.sp)
                OutlinedButton(
                    onClick = { activeController.discover(artistCredit) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("artist_enrichment_search"),
                ) { Text("Buscar en MusicBrainz") }
            }
            uiState.message != null -> {
                Text(uiState.message.orEmpty(), color = TextSecondary, fontSize = 12.sp)
            }
            uiState.status == ArtistEnrichmentStatus.IDLE -> {
                OutlinedButton(
                    onClick = { activeController.discover(artistCredit) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("artist_enrichment_search"),
                ) { Text("Buscar en MusicBrainz") }
            }
            else -> Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = TertiaryCyan)
                Text("Consultando datos verificados…", color = TextSecondary, fontSize = 12.sp)
            }
        }
    }
}

/**
 * Optional, explicitly requested Openverse discovery. It never runs on composition:
 * the provider query starts only from [artist_artwork_openverse_search], and a chosen
 * candidate must pass the Flickr origin-licence check before bytes are downloaded.
 */
@Composable
fun ArtistArtworkManualSection(
    profile: ArtistExternalProfile,
    artistCredit: String,
    libraryId: String?,
    albumIds: List<String>,
    initialSelections: Map<ArtworkSlot, ArtistArtworkSelection>,
    pendingCandidates: List<ArtistArtworkCandidate>,
    associationGroupKey: String?,
    onSelectionsChanged: (Map<ArtworkSlot, ArtistArtworkSelection>) -> Unit,
    chooser: (suspend (ArtistIdentityAnchor, ArtistArtworkCandidate, ArtworkSlot) -> ArtworkResolutionReport)? = null,
) {
    if (libraryId == null) return
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val coordinator = remember(context) { ArtistArtworkService.from(context) }
    val choose: suspend (ArtistIdentityAnchor, ArtistArtworkCandidate, ArtworkSlot) -> ArtworkResolutionReport =
        chooser ?: { anchor, candidate, slot -> coordinator.chooseManual(anchor, candidate, slot) }
    val anchor = remember(libraryId, artistCredit, albumIds, profile.mbid,
        profile.wikidataQid, associationGroupKey) {
        artistIdentityAnchor(libraryId, artistCredit, albumIds, profile, associationGroupKey)
    }
    if (anchor == null) return
    val scope = rememberCoroutineScope()
    val secondaryColors = ButtonDefaults.outlinedButtonColors(contentColor = SecondaryPurple)
    val secondaryBorder = BorderStroke(0.8.dp, SecondaryPurple.copy(alpha = 0.5f))
    var selections by remember(anchor) { mutableStateOf(initialSelections) }
    LaunchedEffect(initialSelections) {
        if (initialSelections.isNotEmpty()) selections = initialSelections
    }
    var busy by remember(anchor) { mutableStateOf(false) }
    var confirmedIdentity by remember(anchor, profile.mbid) { mutableStateOf(false) }
    var message by remember(anchor) { mutableStateOf<String?>(null) }
    val candidates = pendingCandidates
    Text("Retratos alternativos", color = PureWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    Text("Se buscan automáticamente cuando hacen falta. La imagen elegida se descarga y se guarda para este artista.",
        color = TextSecondary, fontSize = 11.sp)
    selections.values.sortedBy { it.slot.ordinal }.forEach { selection ->
        val file = selection.localImagePath?.takeIf { it.isNotBlank() }?.let(::File)
        if (file != null && selection.status == ArtworkLoadStatus.READY) {
            val live = selections[selection.slot] ?: selection
            AsyncImage(
                model = file,
                contentDescription = if (selection.slot == ArtworkSlot.ARTIST_THUMB)
                    "Retrato alternativo para el mosaico de $artistCredit"
                else "Retrato alternativo para la ficha de $artistCredit",
                contentScale = ContentScale.Crop,
                alignment = BiasAlignment(live.focus.x * 2f - 1f, live.focus.y * 2f - 1f),
                modifier = if (selection.slot == ArtworkSlot.ARTIST_THUMB) {
                    Modifier.size(144.dp).clip(CircleShape)
                } else {
                    Modifier.fillMaxWidth().height(248.dp)
                        .clip(RoundedCornerShape(24.dp))
                }.testTag("artist_artwork_saved_${selection.slot.name}"),
            )
            Text("${if (selection.slot == ArtworkSlot.ARTIST_THUMB) "Mosaico" else "Ficha"}: " +
                "${selection.attribution.orEmpty()} · ${selection.licenseId.orEmpty()}",
                color = TextSecondary, fontSize = 10.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            // The regular artist page stays clean; source and licence remain accessible here.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                live.sourcePageUrl?.let { url ->
                    TextButton(
                        onClick = { uriHandler.openUri(url) },
                        modifier = Modifier.heightIn(min = 48.dp)
                            .testTag("artist_artwork_source_${selection.slot.name}"),
                    ) { Text("Origen de la foto", color = TertiaryCyan, fontSize = 11.sp) }
                }
                live.licenseUrl?.let { url ->
                    TextButton(
                        onClick = { uriHandler.openUri(url) },
                        modifier = Modifier.heightIn(min = 48.dp)
                            .testTag("artist_artwork_license_${selection.slot.name}"),
                    ) { Text("Licencia", color = TertiaryCyan, fontSize = 11.sp) }
                }
            }
            Text("Encuadre ${selection.slot.name.lowercase().replace('_', ' ')}: " +
                "${(live.focus.x * 100).toInt()}% / ${(live.focus.y * 100).toInt()}%",
                color = TextSecondary, fontSize = 10.sp)
            Slider(
                value = live.focus.x,
                onValueChange = { value ->
                    val current = selections[selection.slot] ?: selection
                    selections = selections + (selection.slot to
                        current.copy(focus = current.focus.copy(x = value)))
                },
                onValueChangeFinished = {
                    val current = selections[selection.slot] ?: selection
                    scope.launch {
                        try {
                            if (coordinator.updateFocus(anchor, selection.slot, current.focus)) {
                                onSelectionsChanged(selections)
                            } else {
                                val persisted = coordinator.loadSelections(anchor)
                                    .filter { it.status == ArtworkLoadStatus.READY }
                                    .associateBy { it.slot }
                                selections = persisted
                                onSelectionsChanged(persisted)
                                message = "No fue posible guardar el encuadre; se restauró el valor persistido."
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            message = "No fue posible guardar el encuadre: ${error.message ?: "error"}"
                        }
                    }
                },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth()
                    .testTag("artist_artwork_focus_x_${selection.slot.name}"),
            )
            Slider(
                value = live.focus.y,
                onValueChange = { value ->
                    val current = selections[selection.slot] ?: selection
                    selections = selections + (selection.slot to
                        current.copy(focus = current.focus.copy(y = value)))
                },
                onValueChangeFinished = {
                    val current = selections[selection.slot] ?: selection
                    scope.launch {
                        try {
                            if (coordinator.updateFocus(anchor, selection.slot, current.focus)) {
                                onSelectionsChanged(selections)
                            } else {
                                val persisted = coordinator.loadSelections(anchor)
                                    .filter { it.status == ArtworkLoadStatus.READY }
                                    .associateBy { it.slot }
                                selections = persisted
                                onSelectionsChanged(persisted)
                                message = "No fue posible guardar el encuadre; se restauró el valor persistido."
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            message = "No fue posible guardar el encuadre: ${error.message ?: "error"}"
                        }
                    }
                },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth()
                    .testTag("artist_artwork_focus_y_${selection.slot.name}"),
            )
        }
    }
    Text("Los retratos alternativos se consultan automáticamente cuando las fuentes verificadas no cubren el mosaico y la ficha.",
        color = TextSecondary, fontSize = 11.sp)
    message?.let { Text(it, color = TextSecondary, fontSize = 11.sp,
        modifier = Modifier.testTag("artist_artwork_openverse_status")) }
    if (candidates.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable {
                confirmedIdentity = !confirmedIdentity
            },
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(
                checked = confirmedIdentity,
                onCheckedChange = { confirmedIdentity = it },
                modifier = Modifier.testTag("artist_artwork_identity_confirm"),
            )
            Text(
                "Confirmo que la foto elegida corresponde a $artistCredit " +
                    "(MBID ${profile.mbid}). La identidad no se deduce por nombre.",
                color = TextSecondary, fontSize = 11.sp,
            )
        }
    }
    candidates.take(4).forEachIndexed { index, candidate ->
        AsyncImage(
            model = candidate.imageUrl,
            contentDescription = "Candidato de retrato ${index + 1} para $artistCredit",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().heightIn(max = 130.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
        Text(candidate.attribution.orEmpty(), color = PureWhite, fontSize = 12.sp)
        Text("${candidate.licenseId.orEmpty()} · revisar origen Flickr",
            color = TextSecondary, fontSize = 10.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ArtworkSlot.ARTIST_THUMB to "mosaico", ArtworkSlot.ARTIST_HERO to "ficha")
                .forEach { (slot, label) ->
                    OutlinedButton(
                        onClick = {
                            if (busy) return@OutlinedButton
                            busy = true
                            message = null
                            scope.launch {
                                try {
                                    val report = choose(anchor, candidate, slot)
                                    selections = report.selections
                                    onSelectionsChanged(report.selections)
                                    message = when (report.status) {
                                        ArtworkLoadStatus.READY -> "Retrato guardado para $label."
                                        ArtworkLoadStatus.REJECTED -> "El origen no confirma una licencia admitida."
                                        else -> "No fue posible guardar el retrato (${report.status})."
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    message = "No fue posible descargar el retrato: ${error.message ?: "error"}"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy && confirmedIdentity,
                        modifier = Modifier.heightIn(min = 48.dp)
                            .testTag("artist_artwork_choose_${slot.name}_$index"),
                        colors = secondaryColors,
                        border = secondaryBorder,
                    ) { Text("Usar en $label") }
                }
        }
    }
}
