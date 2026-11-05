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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Job
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import org.michimusic.mobile.artist.artwork.ArtistArtworkPersistence
import org.michimusic.mobile.artist.artwork.ArtistArtworkSelection
import org.michimusic.mobile.artist.artwork.ArtistArtworkService
import org.michimusic.mobile.artist.artwork.ArtworkLoadStatus
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

private sealed interface ArtistEnrichmentUi {
    data object Idle : ArtistEnrichmentUi
    data object LoadingCandidates : ArtistEnrichmentUi
    data class Candidates(val found: List<ArtistLookupCandidate>) : ArtistEnrichmentUi
    data object LoadingProfile : ArtistEnrichmentUi
    data class Verified(val profile: ArtistExternalProfile, val persisted: Boolean = false,
        val stale: Boolean = false, val refreshFailed: Boolean = false) : ArtistEnrichmentUi
    data object Failed : ArtistEnrichmentUi
}

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
    localAudioUris: List<String> = emptyList(),
    localTrackIds: List<String> = emptyList(),
    tagProbe: ArtistTagProbe? = null,
) {
    val context = LocalContext.current
    val store = remember(context, selectionStore) { selectionStore ?: ArtistEnrichmentPersistence.from(context) }
    val artworkCoordinator = remember(context) { ArtistArtworkService.from(context) }
    val embeddedTags = remember(context, tagProbe) { tagProbe ?: LocalArtistTagProbe(context) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val stableGroup = remember(albumIds) { albumIds.toSortedSet().joinToString("\u0000") }
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
    var state by remember(artistCredit, libraryId, stableGroup) {
        mutableStateOf<ArtistEnrichmentUi>(ArtistEnrichmentUi.Idle)
    }
    var requestJob by remember(artistCredit, libraryId, stableGroup) { mutableStateOf<Job?>(null) }
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
        onDispose { requestJob?.cancel(); tagJob?.cancel() }
    }
    LaunchedEffect(artistCredit, libraryId, stableGroup, store) {
        if (canPersist && state == ArtistEnrichmentUi.Idle) {
            try {
                val cached = store.load(requireNotNull(libraryId), artistCredit, albumIds)
                if (state == ArtistEnrichmentUi.Idle && cached != null) {
                    state = ArtistEnrichmentUi.Verified(cached.profile, persisted = true, stale = cached.stale)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { /* cache failure must not block local browsing */ }
        }
    }

    fun lookup() {
        if (state == ArtistEnrichmentUi.LoadingCandidates || state == ArtistEnrichmentUi.LoadingProfile) return
        state = ArtistEnrichmentUi.LoadingCandidates
        requestJob = scope.launch {
            try {
                state = ArtistEnrichmentUi.Candidates(gateway.findCandidates(artistCredit))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state = ArtistEnrichmentUi.Failed
            }
        }
    }

    fun confirmExplicitly(id: String) {
        val verifiedId = canonicalArtistMbid(id) ?: return
        if (requestJob?.isActive == true) return
        state = ArtistEnrichmentUi.LoadingProfile
        requestJob = scope.launch {
            try {
                val profile = gateway.confirmedProfile(verifiedId)
                require(canonicalArtistMbid(profile.mbid) == verifiedId)
                val persisted = if (canPersist) {
                    try {
                        store.save(requireNotNull(libraryId), artistCredit, albumIds, profile)
                        true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { false }
                } else false
                state = ArtistEnrichmentUi.Verified(profile, persisted = persisted)
                if (persisted) {
                    val anchor = artistIdentityAnchor(libraryId, artistCredit, albumIds, profile)
                    if (anchor != null) {
                        try {
                            artworkCoordinator.resolve(anchor)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // The verified identity and biography remain valid even when
                            // optional artwork providers fail.
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state = ArtistEnrichmentUi.Failed
            }
        }
    }

    fun refreshVerified(previous: ArtistEnrichmentUi.Verified) {
        val verifiedId = canonicalArtistMbid(previous.profile.mbid) ?: return
        if (requestJob?.isActive == true) return
        state = ArtistEnrichmentUi.LoadingProfile
        requestJob = scope.launch {
            try {
                val profile = gateway.refreshProfile(verifiedId)
                require(canonicalArtistMbid(profile.mbid) == verifiedId)
                val persisted = if (canPersist) {
                    try {
                        store.save(requireNotNull(libraryId), artistCredit, albumIds, profile)
                        true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { false }
                } else false
                state = ArtistEnrichmentUi.Verified(profile, persisted = persisted)
                if (persisted) {
                    val anchor = artistIdentityAnchor(libraryId, artistCredit, albumIds, profile)
                    if (anchor != null) {
                        try {
                            artworkCoordinator.resolve(anchor)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // Optional artwork failure never masks the refreshed profile.
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A failed refresh keeps the last verified profile visible and
                // records that an update is still pending.
                state = previous.copy(refreshFailed = true)
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .testTag("artist_enrichment_panel"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("IDENTIDAD Y DATOS EXTERNOS", color = TertiaryCyan,
            fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text("La búsqueda no vincula automáticamente identidades. Selecciona la coincidencia correcta antes de consultar biografía y retrato.",
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
                        enabled = state != ArtistEnrichmentUi.LoadingProfile,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("artist_embedded_mbid_confirm"),
                    ) { Text("Confirmar identidad y consultar MusicBrainz") }
                }
                else -> Text("Estos archivos no contienen un MBID de artista inequívoco. Puedes buscarlo manualmente.",
                    color = TextSecondary, fontSize = 12.sp)
            }
        }
        when (val result = state) {
            ArtistEnrichmentUi.Idle -> OutlinedButton(
                onClick = ::lookup, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("artist_enrichment_search"),
                colors = secondaryColors, border = secondaryBorder,
            ) { Text("Buscar en MusicBrainz") }
            ArtistEnrichmentUi.LoadingCandidates, ArtistEnrichmentUi.LoadingProfile -> Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = TertiaryCyan)
                Text("Consultando datos verificados…", color = TextSecondary, fontSize = 12.sp)
            }
            ArtistEnrichmentUi.Failed -> {
                Text("No fue posible obtener los datos. Tu biblioteca permanece disponible sin conexión.",
                    color = TextSecondary, fontSize = 12.sp)
                OutlinedButton(
                    onClick = ::lookup,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = secondaryColors,
                    border = secondaryBorder,
                ) {
                    Text("Reintentar consulta")
                }
            }
            is ArtistEnrichmentUi.Candidates -> {
                if (result.found.isEmpty()) {
                    Text("Sin coincidencias confiables. No se ha asociado ningún artista externo.",
                        color = TextSecondary, fontSize = 12.sp)
                } else {
                    Text("Selecciona el artista correcto · ${result.found.size} candidatos",
                        color = PureWhite, fontSize = 13.sp)
                    // One selectable group, Plain rows and a hairline between them: the plan
                    // rejects a locally invented card per candidate.
                    Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
                        result.found.forEachIndexed { index, candidate ->
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
                                        val verifiedId = selectedArtistMbid(result.found, candidate.mbid)
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
            is ArtistEnrichmentUi.Verified -> {
                val profile = result.profile
                Text(profile.name, color = PureWhite, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("MBID confirmado por selección: ${profile.mbid}", color = TextSecondary, fontSize = 11.sp)
                Text(when {
                    result.refreshFailed -> "No fue posible actualizar los datos externos. Se muestran los últimos datos verificados."
                    result.stale -> "Datos guardados: revisión vencida. Confirma nuevamente para actualizar."
                    result.persisted -> "Datos guardados para este crédito y biblioteca."
                    canPersist -> "Datos consultados, pero no guardados; revisa disponibilidad del almacenamiento."
                    else -> "Solo esta consulta. Cambia a una biblioteca individual para guardar."
                }, color = TextSecondary, fontSize = 11.sp)
                if (result.stale) {
                    OutlinedButton(
                        onClick = { state = ArtistEnrichmentUi.Idle },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors = secondaryColors,
                        border = secondaryBorder,
                    ) {
                        Text("Buscar datos actualizados")
                    }
                }
                if (canPersist && result.persisted) {
                    OutlinedButton(
                        onClick = {
                            if (requestJob?.isActive == true) return@OutlinedButton
                            requestJob = scope.launch {
                                try {
                                    store.clear(requireNotNull(libraryId), artistCredit, albumIds)
                                    state = ArtistEnrichmentUi.Idle
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    state = ArtistEnrichmentUi.Failed
                                }
                            }
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
                    AsyncImage(
                        model = portrait.imageUrl,
                        contentDescription = "Fotografía de ${profile.name}; fuente y licencia en atribución",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 172.dp)
                            .clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Fit,
                    )
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
                    Text(portraitStatusText(profile.portraitOutcome),
                        color = TextSecondary, fontSize = 11.sp)
                    OutlinedButton(
                        onClick = { refreshVerified(result) },
                        enabled = requestJob?.isActive != true,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("artist_enrichment_retry_portrait"),
                        colors = secondaryColors,
                        border = secondaryBorder,
                    ) { Text("Reintentar retrato y datos") }
                }
                ArtistArtworkManualSection(
                    profile = profile,
                    artistCredit = artistCredit,
                    libraryId = libraryId,
                    albumIds = albumIds,
                )
                Text("Los álbumes de catálogos externos no se agregan a tu colección local.",
                    color = TextSecondary, fontSize = 11.sp)
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
private fun ArtistArtworkManualSection(
    profile: ArtistExternalProfile,
    artistCredit: String,
    libraryId: String?,
    albumIds: List<String>,
) {
    if (libraryId == null) return
    val context = LocalContext.current
    val coordinator = remember(context) { ArtistArtworkService.from(context) }
    val store = remember(context) { ArtistArtworkPersistence.from(context) }
    val anchor = remember(libraryId, artistCredit, albumIds, profile.mbid,
        profile.wikidataQid) {
        artistIdentityAnchor(libraryId, artistCredit, albumIds, profile)
    }
    if (anchor == null) return
    val scope = rememberCoroutineScope()
    val secondaryColors = ButtonDefaults.outlinedButtonColors(contentColor = SecondaryPurple)
    val secondaryBorder = BorderStroke(0.8.dp, SecondaryPurple.copy(alpha = 0.5f))
    var selections by remember(anchor) {
        mutableStateOf<Map<ArtworkSlot, ArtistArtworkSelection>>(emptyMap())
    }
    var candidates by remember(anchor) {
        mutableStateOf<List<ArtistArtworkCandidate>>(emptyList())
    }
    var busy by remember(anchor) { mutableStateOf(false) }
    var message by remember(anchor) { mutableStateOf<String?>(null) }
    LaunchedEffect(anchor, store) {
        selections = try {
            store.load(anchor).associateBy { it.slot }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }
    }
    Text("Retratos alternativos", color = PureWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    Text("Sólo se consultan si lo pides. La imagen elegida se descarga y se guarda para este artista.",
        color = TextSecondary, fontSize = 11.sp)
    selections.values.sortedBy { it.slot.ordinal }.forEach { selection ->
        val file = selection.localImagePath?.takeIf { it.isNotBlank() }?.let(::File)
        if (file != null && selection.status == ArtworkLoadStatus.READY) {
            AsyncImage(
                model = file,
                contentDescription = if (selection.slot == ArtworkSlot.ARTIST_THUMB)
                    "Retrato alternativo para el mosaico de $artistCredit"
                else "Retrato alternativo para la ficha de $artistCredit",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .testTag("artist_artwork_saved_${selection.slot.name}"),
            )
            Text("${if (selection.slot == ArtworkSlot.ARTIST_THUMB) "Mosaico" else "Ficha"}: " +
                "${selection.attribution.orEmpty()} · ${selection.licenseId.orEmpty()}",
                color = TextSecondary, fontSize = 10.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        }
    }
    OutlinedButton(
        onClick = {
            if (busy) return@OutlinedButton
            busy = true
            message = null
            scope.launch {
                try {
                    when (val result = coordinator.searchManual(anchor, artistCredit)) {
                        is ProviderArtworkResult.Available -> {
                            candidates = result.items
                            if (result.items.isEmpty()) message = "Sin retratos alternativos verificables."
                        }
                        ProviderArtworkResult.NoMatch -> message = "Sin retratos alternativos verificables."
                        is ProviderArtworkResult.Disabled -> message = "Proveedor deshabilitado: ${result.reason}"
                        is ProviderArtworkResult.Failure -> message = "No fue posible consultar Openverse: ${result.type}"
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    message = "No fue posible consultar Openverse: ${error.message ?: "error de red"}"
                } finally {
                    busy = false
                }
            }
        },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .testTag("artist_artwork_openverse_search"),
        colors = secondaryColors,
        border = secondaryBorder,
    ) { Text(if (busy) "Consultando Openverse…" else "Buscar retrato alternativo (Openverse)") }
    message?.let { Text(it, color = TextSecondary, fontSize = 11.sp,
        modifier = Modifier.testTag("artist_artwork_openverse_status")) }
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
                                    val report = coordinator.chooseManual(anchor, candidate, slot)
                                    selections = report.selections
                                    message = when (report.status) {
                                        ArtworkLoadStatus.READY -> "Retrato guardado para $label."
                                        ArtworkLoadStatus.REJECTED -> "El origen no confirma una licencia admitida."
                                        else -> "No fue posible guardar el retrato (${report.status})."
                                    }
                                    if (report.status == ArtworkLoadStatus.READY) candidates = emptyList()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    message = "No fue posible descargar el retrato: ${error.message ?: "error"}"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp)
                            .testTag("artist_artwork_choose_${slot.name}_$index"),
                        colors = secondaryColors,
                        border = secondaryBorder,
                    ) { Text("Usar en $label") }
                }
        }
    }
}
