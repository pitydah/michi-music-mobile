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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.michimusic.mobile.artist.artwork.ArtistArtworkCoverageState
import org.michimusic.mobile.artist.artwork.ArtistArtworkSlotCoverage
import org.michimusic.mobile.artist.artwork.ArtistArtworkCoverageUiState
import org.michimusic.mobile.artist.artwork.ArtistEnrichmentPrimaryAction
import org.michimusic.mobile.artist.artwork.ArtistArtworkUiState
import org.michimusic.mobile.artist.artwork.ArtistArtworkPersistence
import org.michimusic.mobile.artist.artwork.ArtistArtworkSlotState
import org.michimusic.mobile.artist.artwork.ArtistArtworkService
import org.michimusic.mobile.artist.artwork.ArtistArtworkStore
import org.michimusic.mobile.artist.artwork.verifyCachedArtwork
import org.michimusic.mobile.artist.artwork.ArtworkFocus
import org.michimusic.mobile.artist.artwork.ArtworkLoadStatus
import org.michimusic.mobile.artist.artwork.ArtworkResolutionReport
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.artist.artwork.ProviderArtworkResult
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SecondaryPurple
import org.michimusic.mobile.ui.theme.SurfaceObsidian
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
    /** K47-ART-05: human album labels; raw MediaStore ids never become a visible CTA. */
    albumLabels: Map<String, String> = emptyMap(),
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
    var creditProof by remember(artistCredit, libraryId, stableGroup, localEvidenceScope) { mutableStateOf<MusicBrainzCreditProofResult?>(null) }
    var creditVerificationBusy by remember(artistCredit, libraryId, stableGroup, localEvidenceScope) { mutableStateOf(false) }
    var creditProofSaved by remember(artistCredit, libraryId, stableGroup, localEvidenceScope) { mutableStateOf(false) }
    var advancedOpen by remember(artistCredit, libraryId, stableGroup) { mutableStateOf(false) }
    // K48-S7: the artwork slots are the first thing the screen answers. Identity, MBID
    // and association evidence stay one tap away instead of filling the first screen.
    var identityExpanded by remember(artistCredit, libraryId, stableGroup) { mutableStateOf(false) }
    var artworkPickerOpen by remember(artistCredit, libraryId, stableGroup) { mutableStateOf(false) }
    var creditVerificationJob by remember(artistCredit, libraryId, stableGroup, localEvidenceScope) { mutableStateOf<Job?>(null) }
    val reviewReceipts = remember(context) { ArtistCreditReviewReceiptPersistence(context) }
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
        onDispose { tagJob?.cancel(); creditVerificationJob?.cancel() }
    }
    val profile = uiState.profile
    // K48-S7: one coverage projection computed once, rendered at the top of the screen.
    val panelArtworkUi = ArtistArtworkUiState(
        thumb = ArtistArtworkSlotState(
            slot = ArtworkSlot.ARTIST_THUMB,
            selection = uiState.selections[ArtworkSlot.ARTIST_THUMB],
            status = uiState.selections[ArtworkSlot.ARTIST_THUMB]?.status
                ?: ArtworkLoadStatus.NOT_REQUESTED,
        ),
        hero = ArtistArtworkSlotState(
            slot = ArtworkSlot.ARTIST_HERO,
            selection = uiState.selections[ArtworkSlot.ARTIST_HERO],
            status = uiState.selections[ArtworkSlot.ARTIST_HERO]?.status
                ?: ArtworkLoadStatus.NOT_REQUESTED,
        ),
    )
    val panelCoverage = ArtistArtworkCoverageUiState.from(
        uiState = panelArtworkUi,
        report = uiState.artworkReport,
        identityConfirmed = uiState.profile?.mbid?.isNotBlank() == true,
        verifiedQid = uiState.profile?.wikidataQid,
    )

    fun confirmExplicitly(id: String) {
        val verifiedId = canonicalArtistMbid(id) ?: return
        activeController.confirm(artistCredit, libraryId, albumIds, verifiedId)
    }

    val presentation = ArtistEnrichmentUiState.from(
        artistCredit = artistCredit,
        state = uiState,
        coverage = panelCoverage,
        canPersist = canPersist,
        albumCount = albumIds.distinct().size,
        section = when {
            advancedOpen -> ArtistEnrichmentSection.ADVANCED
            identityExpanded -> ArtistEnrichmentSection.IDENTITY
            else -> ArtistEnrichmentSection.NONE
        },
    )

    /**
     * Opening the picker never searches. Downloading bytes is a separate, explicit
     * user request made inside the picker.
     */
    fun openPicker() {
        artworkPickerOpen = true
    }

    fun openPickerAndSearch() {
        artworkPickerOpen = true
        activeController.searchPortraits(artistCredit, libraryId, albumIds)
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .testTag("artist_enrichment_panel"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ArtistSummaryHeader(presentation)
        // §9.9: two columnas solo cuando caben miniatura, estado y acción sin recorte.
        if (artworkSlotsInline()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ArtworkSlotCard(
                    slot = presentation.mosaic,
                    artistName = presentation.artistName,
                    onChoose = { openPicker() },
                    modifier = Modifier.weight(1f),
                )
                ArtworkSlotCard(
                    slot = presentation.card,
                    artistName = presentation.artistName,
                    onChoose = { openPicker() },
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            ArtworkSlotCard(
                slot = presentation.mosaic,
                artistName = presentation.artistName,
                onChoose = { openPicker() },
            )
            ArtworkSlotCard(
                slot = presentation.card,
                artistName = presentation.artistName,
                onChoose = { openPicker() },
            )
        }
        ArtistPrimaryAction(
            action = presentation.primaryAction,
            label = presentation.primaryActionLabel,
            busy = presentation.isBusy,
            onAction = { action ->
                // K48-R03: the label comes from the action, so tapping it always runs
                // exactly the operation the user read.
                when (action) {
                    ArtistEnrichmentPrimaryAction.ReviewIdentity,
                    ArtistEnrichmentPrimaryAction.VerifyWikidataIdentity,
                    -> identityExpanded = true
                    ArtistEnrichmentPrimaryAction.SearchMissingPhotos,
                    ArtistEnrichmentPrimaryAction.RetryProvider,
                    -> openPickerAndSearch()
                    ArtistEnrichmentPrimaryAction.ReviewLicense,
                    ArtistEnrichmentPrimaryAction.ViewPhotos,
                    -> openPicker()
                    ArtistEnrichmentPrimaryAction.None -> Unit
                }
            },
        )
        TextButton(
            onClick = { identityExpanded = !identityExpanded },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("artist_identity_accordion"),
        ) {
            Text(
                if (identityExpanded) "Ocultar identidad y álbumes" else "Identidad y álbumes",
                color = TextSecondary, fontSize = 12.sp,
            )
        }
        if (identityExpanded) {
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
                    creditVerificationJob?.cancel()
                    creditProof = null // a new tag inspection invalidates previous external proof
                    creditProofSaved = false
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
            // A file may credit multiple performers. Review structured identifiers
            // per file and role instead of trusting a flattened set of tag values.
            when (val proposal = EmbeddedArtistIdentityReview.evaluate(artistCredit, evidence)) {
                is EmbeddedArtistIdentityProposal.Candidate -> {
                    Text("MBID encontrado en ${proposal.supportingFiles} archivo(s) (sin confirmar): ${proposal.mbid}",
                        color = TertiaryCyan, fontSize = 12.sp)
                    // Only one exact per-file recording/release pairing can be verified.
                    // Text, filename, and album name never substitute for an MBID.
                    val pairing = ArtistStructuredCreditClaimPlanner.plan(artistCredit, evidence)
                    val claim = (pairing as? StructuredCreditPlan.Ready)?.claim
                    if (claim != null && pairing is StructuredCreditPlan.Ready) {
                        // File URI stays in Compose; only a stable local track ID reaches
                        // the private Room receipt. No implicit album ownership.
                        val reviewTrackId = localAudioUris.zip(localTrackIds)
                            .filter { it.first == pairing.supportingUri }
                            .map { it.second }.distinct().singleOrNull()
                        val reviewAlbumKey = albumIds.distinct().singleOrNull()
                        LaunchedEffect(claim, reviewTrackId, reviewAlbumKey, libraryId, evidence) {
                            if (creditProof != null || creditVerificationBusy) return@LaunchedEffect
                            val reviewScope = reviewReceipts.scopeFor(libraryId, artistCredit,
                                reviewAlbumKey, reviewTrackId) ?: return@LaunchedEffect
                            val restored = try { reviewReceipts.load(reviewScope, claim) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { false }
                            if (restored && !creditVerificationBusy && creditProof == null) {
                                creditProof = MusicBrainzCreditProofResult.CorroboratedForReview(
                                    claim.artistMbid, claim.role)
                                creditProofSaved = true
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                if (creditVerificationBusy) return@OutlinedButton
                                creditVerificationBusy = true
                                creditProofSaved = false
                                creditVerificationJob = scope.launch {
                                    try {
                                        // Capture the root before HTTP; a root change during
                                        // the request makes the durable review write fail closed.
                                        val reviewScope = reviewReceipts.scopeFor(libraryId,
                                            artistCredit, reviewAlbumKey, reviewTrackId)
                                        val result = gateway.corroborateStructuredCredit(claim)
                                        creditProof = result
                                        if (reviewScope != null && result is
                                            MusicBrainzCreditProofResult.CorroboratedForReview) {
                                            creditProofSaved = try {
                                                reviewReceipts.save(reviewScope, claim, result)
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) { false }
                                        }
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) {
                                        creditProof = MusicBrainzCreditProofResult.Blocked(
                                            MusicBrainzCreditProofResult.Reason.MISSING_RESPONSE)
                                    } finally { creditVerificationBusy = false }
                                }
                            },
                            enabled = !creditVerificationBusy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .testTag("artist_credit_corroborate_musicbrainz"),
                        ) { Text(if (creditVerificationBusy) "Verificando créditos…" else "Corroborar créditos en MusicBrainz") }
                        when (creditProof) {
                            is MusicBrainzCreditProofResult.CorroboratedForReview -> Text(
                                if (creditProofSaved) "Créditos corroborados y guardados localmente para este archivo y álbum. Confirma la identidad manualmente."
                                else "Créditos corroborados para revisión, pero sin recibo durable; confirma la identidad manualmente.",
                                color = TertiaryCyan, fontSize = 12.sp)
                            is MusicBrainzCreditProofResult.Blocked -> Text(
                                "No se pudieron corroborar todos los identificadores. Revisar manualmente.",
                                color = TextSecondary, fontSize = 12.sp)
                            null -> Unit
                        }
                    }
                    if (pairing is StructuredCreditPlan.Blocked) {
                        Text("No hay un par grabación/lanzamiento inequívoco para corroborar en línea.",
                            color = TextSecondary, fontSize = 12.sp)
                    }
                    // Only this explicit button can invoke identity persistence. A tag
                    // suggestion never binds a new album or confirms a homonym.
                    OutlinedButton(
                        onClick = { confirmExplicitly(proposal.mbid) },
                        enabled = canPersist && !uiState.refreshing && uiState.profile == null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("artist_embedded_mbid_confirm"),
                    ) { Text("Confirmar identidad y consultar MusicBrainz") }
                }
                is EmbeddedArtistIdentityProposal.Blocked -> Text(
                    when (proposal.reason) {
                        EmbeddedArtistIdentityProposal.Reason.CONFLICTING_IDS ->
                            "Los archivos contienen identificadores MusicBrainz incompatibles; elige explícitamente el correcto."
                        EmbeddedArtistIdentityProposal.Reason.AMBIGUOUS_CREDIT ->
                            "Hay varios artistas en la etiqueta y no se puede asignar un MBID a este crédito."
                        EmbeddedArtistIdentityProposal.Reason.WRONG_CREDIT ->
                            "Los identificadores corresponden a otro crédito o artista de álbum."
                        EmbeddedArtistIdentityProposal.Reason.NO_EVIDENCE ->
                            "No hay un MBID inequívoco en la evidencia disponible. Puedes buscarlo manualmente."
                    }, color = TextSecondary, fontSize = 12.sp,
                )
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
                uiState.persistenceError?.let {
                    Text(it, color = TextSecondary, fontSize = 11.sp,
                        modifier = Modifier.testTag("artist_enrichment_persistence_error"))
                }
                if (uiState.needsConfirmation) {
                    Text("Hay álbumes sin una asociación confirmada. Comprueba cada uno " +
                        "antes de vincularlo al MBID mostrado; no se cambiarán otros artistas.",
                        color = TextSecondary, fontSize = 11.sp,
                        modifier = Modifier.testTag("artist_enrichment_needs_confirmation"))
                    val pending = uiState.unboundAlbumIds.distinct()
                    if (pending.isEmpty()) {
                        Text("No hay detalles de pertenencia verificables. Vuelve a abrir la ficha.",
                            color = TextSecondary, fontSize = 11.sp)
                    } else {
                        pending.forEachIndexed { index, albumId ->
                            OutlinedButton(
                                onClick = {
                                    activeController.bindVisibleAlbums(
                                        artistCredit, libraryId, albumIds, albumIdToBind = albumId,
                                    )
                                },
                                enabled = canPersist && !result.refreshing,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .testTag(if (pending.size == 1) "artist_enrichment_bind_albums"
                                        else "artist_enrichment_bind_album_$index"),
                                colors = secondaryColors,
                                border = secondaryBorder,
                            ) {
                                Text(
                                    "Confirmar vínculo con " +
                                        (albumLabels[albumId]?.takeIf { it.isNotBlank() } ?: "este álbum"),
                                )
                            }
                        }
                    }
                }
                // The visible action is optional and never claims another association.
                // Even a stale UI snapshot cannot bypass the exact binding check in Room.
                if (canPersist && result.persisted && uiState.profilePersisted &&
                    uiState.associationGroupKey != null
                ) {
                    val boundVisible = albumIds.distinct().filter { albumId ->
                        albumId.isNotBlank() && albumId !in uiState.unboundAlbumIds
                    }.take(8)
                    if (boundVisible.isNotEmpty()) {
                        Text("Si un álbum antiguo no permite recuperar fotografías, " +
                            "puedes reconfirmar explícitamente su vínculo con este artista.",
                            color = TextSecondary, fontSize = 11.sp)
                        boundVisible.forEachIndexed { index, albumId ->
                            OutlinedButton(
                                onClick = {
                                    activeController.reconfirmVisibleAlbum(
                                        artistCredit, libraryId, albumIds, albumId,
                                    )
                                },
                                enabled = !uiState.refreshing,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .testTag("artist_enrichment_reconfirm_bound_album_$index"),
                                colors = secondaryColors,
                                border = secondaryBorder,
                            ) {
                                Text(
                                    "Reconfirmar vínculo con " +
                                        (albumLabels[albumId]?.takeIf { it.isNotBlank() } ?: "este álbum"),
                                )
                            }
                        }
                    }
                }
                if (canPersist && result.persisted) {
                    // K47-ART-03: deletion is destructive and irreversible for this
                    // association, so it lives behind an advanced section and an explicit
                    // confirmation that states exactly what is removed and what remains.
                    TextButton(
                        onClick = { advancedOpen = !advancedOpen },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("artist_enrichment_advanced"),
                    ) {
                        Text(
                            if (advancedOpen) "Ocultar opciones avanzadas" else "Opciones avanzadas",
                            color = PureWhite, fontSize = 12.sp,
                        )
                    }
                    if (advancedOpen) {
                        // K48-S7 design E: the external refresh is an advanced action,
                        // not the primary affordance of the summary.
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
                                if (result.refreshing) "Actualizando…" else "Actualizar información externa",
                                color = TertiaryCyan, fontSize = 12.sp,
                            )
                        }
                        val delete = uiState.delete
                        if (delete is EnrichmentDeleteState.Confirming) {
                            Column(
                                modifier = Modifier.fillMaxWidth()
                                    .testTag("artist_enrichment_confirm_delete"),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    "Se quitarán de $artistCredit los datos enriquecidos guardados " +
                                        "(MBID ${profile.mbid}, biografía, fotografías y vínculos de " +
                                        "${albumIds.distinct().size} álbum(es)). Tus archivos de audio, " +
                                        "etiquetas y la biblioteca local no se modifican.",
                                    color = PureWhite, fontSize = 12.sp,
                                )
                                Text(
                                    "Esta acción no se puede deshacer desde aquí.",
                                    color = TextSecondary, fontSize = 11.sp,
                                )
                                TextButton(
                                    onClick = { activeController.cancelDelete() },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                        .testTag("artist_enrichment_delete_cancel"),
                                ) { Text("Cancelar", color = SecondaryPurple) }
                                OutlinedButton(
                                    onClick = {
                                        activeController.confirmDelete(
                                            artistCredit, libraryId, albumIds,
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                        .testTag("artist_enrichment_delete_confirm"),
                                    colors = secondaryColors,
                                    border = secondaryBorder,
                                ) { Text("Quitar datos enriquecidos") }
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    activeController.requestDelete(
                                        artistCredit, libraryId, albumIds,
                                    )
                                },
                                enabled = delete !is EnrichmentDeleteState.Deleting,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .testTag("artist_enrichment_clear"),
                                colors = secondaryColors,
                                border = secondaryBorder,
                            ) {
                                Text(
                                    if (delete is EnrichmentDeleteState.Deleting) "Borrando…"
                                    else "Quitar datos enriquecidos…",
                                )
                            }
                        }
                    }
                }
                Text(listOf(profile.type, profile.area, profile.disambiguation)
                    .filter(String::isNotBlank).joinToString(" · ").ifBlank { "Identidad registrada en MusicBrainz" },
                    color = TextSecondary, fontSize = 12.sp)
                profile.portrait?.let { portrait ->
                    // The remote URL is provenance only. Confirmed artwork is shown by
                    // ArtistArtworkPickerContent from filesDir after Room verification.
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
        } // K48-S7: end of the collapsed identity section
        uiState.profile?.let { artworkProfile ->
            if (artworkPickerOpen) {
                ArtistArtworkPickerContent(
                    profile = artworkProfile,
                    artistCredit = artistCredit,
                    libraryId = libraryId,
                    albumIds = albumIds,
                    initialSelections = uiState.selections,
                    pendingCandidates = uiState.pendingPortraits,
                    searching = uiState.searchingPortraits,
                    onSearch = {
                        activeController.searchPortraits(artistCredit, libraryId, albumIds)
                    },
                    associationGroupKey = uiState.associationGroupKey,
                    onSelectionsChanged = { selections ->
                        activeController.applySelections(
                            libraryId = libraryId,
                            artistCredit = artistCredit,
                            albumIds = albumIds,
                            mbid = artworkProfile.mbid,
                            selections = selections,
                        )
                    },
                )
            }
            ArtistArtworkCoverageSection(
                uiState = panelArtworkUi,
                report = uiState.artworkReport,
                identityConfirmed = artworkProfile.mbid.isNotBlank(),
                verifiedQid = artworkProfile.wikidataQid,
            )
        }
    }
}


/**
 * K47-ART-01 / K48-R07: the causal diagnosis is a pure content component. It is
 * collapsed by default so the summary stays the primary task, and each slot and each
 * provider reports its own state instead of being flattened into one sentence.
 */
@Composable
internal fun ArtistArtworkCoverageSection(
    uiState: ArtistArtworkUiState,
    report: ArtworkResolutionReport?,
    identityConfirmed: Boolean,
    verifiedQid: String?,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val coverage = remember(uiState, report, identityConfirmed, verifiedQid) {
        ArtistArtworkCoverageUiState.from(
            uiState = uiState,
            report = report,
            identityConfirmed = identityConfirmed,
            verifiedQid = verifiedQid,
        )
    }
    Column(modifier.fillMaxWidth().testTag("artist_artwork_coverage")) {
        ArtistSectionDivider()
        ArtistExpandableSectionToggle(
            // K48-S7 / Design D: the user-facing question first; the provider
            // table stays inside as the technical diagnosis.
            title = if (expanded) "Ocultar diagnóstico" else "¿Por qué faltan fotografías?",
            expanded = expanded,
            onToggle = { expanded = !expanded },
            tag = "artist_artwork_coverage_toggle",
        )
        if (expanded) {
            ArtistCoverageDiagnosticContent(
                rows = coverage.slots.map { slot ->
                    ArtistCoverageRow(
                        title = "${slot.slotLabel} · ${slot.statusLabel}",
                        detail = slot.reason,
                        nextAction = slot.nextAction,
                    )
                },
                providers = coverage.providers.map { provider ->
                    ArtistCoverageRow(
                        title = "${provider.label} · ${provider.statusLabel}",
                        detail = provider.reason,
                    )
                },
                diagnosed = coverage.diagnosed,
            )
        }
    }
}
