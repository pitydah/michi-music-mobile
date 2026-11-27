package org.michimusic.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.michimusic.core.models.LibraryScope
import org.michimusic.data.repository.MusicLibraryRepository
import org.michimusic.mobile.artist.ArtistBulkDisposition
import org.michimusic.mobile.artist.decideArtistBulkHeaderEntry
import org.michimusic.mobile.artist.ArtistBulkPagedDryRun
import org.michimusic.mobile.artist.ArtistBulkDryRun
import org.michimusic.mobile.artist.ArtistBulkDraftResult
import org.michimusic.mobile.artist.ArtistBulkPreparedSession
import org.michimusic.mobile.artist.ArtistBulkDurableSessionStore
import org.michimusic.mobile.artist.ArtistEnrichmentPersistence
import org.michimusic.mobile.artist.ArtistBulkPlannedStatus
import org.michimusic.mobile.artist.artistBulkSelectableFingerprint
import org.michimusic.mobile.artist.prepareArtistBulkSession
import org.michimusic.mobile.artist.artistBulkCanRestoreDraft
import java.util.UUID
import org.michimusic.mobile.artist.ArtistBulkReviewFilter
import org.michimusic.mobile.artist.artistBulkReviewFilterCount
import org.michimusic.mobile.artist.artistBulkFilteredRows
import org.michimusic.mobile.artist.ArtistBulkPreview
import org.michimusic.mobile.artist.ArtistBulkPreviewBlock
import org.michimusic.mobile.artist.ArtistBulkPreviewRow
import org.michimusic.mobile.artist.ArtistEnrichmentSelectionStore
import org.michimusic.mobile.artist.ArtistMembershipOccurrence
import org.michimusic.mobile.library.ArtistBulkLibrarySnapshotReader
import org.michimusic.mobile.library.LibraryScopeStore
import org.michimusic.mobile.library.MusicRootAuthority
import org.michimusic.mobile.library.authority
import org.michimusic.mobile.library.authorizedScope
import org.michimusic.mobile.ui.components.premium.MichiBottomSheet
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextSecondary

private sealed interface ArtistBulkPanelState {
    data object Idle : ArtistBulkPanelState
    data object Reading : ArtistBulkPanelState
    data class Complete(val preview: ArtistBulkPreview) : ArtistBulkPanelState
    data class Failed(val reason: String) : ArtistBulkPanelState
}

/** D19: presentation strings, never network/storage outcomes. */
internal fun artistBulkDispositionLabel(disposition: ArtistBulkDisposition): String = when (disposition) {
    ArtistBulkDisposition.ELIGIBLE_FOR_EXPLICIT_REVIEW -> "Identidad confirmada; requiere autorización individual"
    ArtistBulkDisposition.IDENTITY_REQUIRED -> "Identidad pendiente de verificar"
    ArtistBulkDisposition.MEMBERSHIP_RECONFIRMATION -> "Revisar pertenencia de álbumes"
    ArtistBulkDisposition.PROFILE_MISSING -> "Faltan datos externos del perfil"
    ArtistBulkDisposition.IDENTITY_CONFLICT -> "Conflicto de identidad: revisión manual obligatoria"
    ArtistBulkDisposition.INVALID_SCOPE -> "Biblioteca o pertenencia inválida"
    ArtistBulkDisposition.DUPLICATE_SCOPE -> "Pertenencia duplicada, sin fusión automática"
    ArtistBulkDisposition.READ_ERROR -> "No se pudo comprobar Room"
}

internal fun artistBulkBlockLabel(block: ArtistBulkPreviewBlock): String = when (block) {
    ArtistBulkPreviewBlock.INVALID_SNAPSHOT -> "No se pudo confirmar la biblioteca y su raíz."
    ArtistBulkPreviewBlock.SCOPE_CHANGED -> "La biblioteca cambió durante la lectura; descarta esta previsualización."
    ArtistBulkPreviewBlock.BUDGET_EXCEEDED -> "Hay más de 2.048 créditos. Selecciona una biblioteca más acotada."
    ArtistBulkPreviewBlock.SNAPSHOT_UNAVAILABLE -> "No se pudo leer una revisión fiable de la biblioteca."
    ArtistBulkPreviewBlock.DUPLICATE_INPUT -> "Hay asociaciones repetidas entre páginas; deben revisarse por separado."
}

/**
 * One-library, read-only bridge from the real Biblioteca/Artistas screen to S4-A1.
 * Opening this sheet runs NO HTTP, WorkManager, profile writes or tag changes. The
 * only side effect is an explicit navigation to the ordinary artist detail page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistBulkPreviewHost(
    libraryId: String?,
    occurrences: List<ArtistMembershipOccurrence>,
    scopeStore: LibraryScopeStore,
    rootAuthority: MusicRootAuthority,
    repository: MusicLibraryRepository,
    selectionStore: ArtistEnrichmentSelectionStore,
    onOpenArtist: (String) -> Unit,
    openRequestId: Int = 0,
    onOpenRequestConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val journal = remember(context) { ArtistBulkDurableSessionStore(ArtistEnrichmentPersistence.database(context)) }
    val resolution by scopeStore.resolution.collectAsStateWithLifecycle()
    val rootStamp by rootAuthority.reviewStamp.collectAsStateWithLifecycle()
    val catalogRevision by rootAuthority.revision.collectAsStateWithLifecycle()
    val authorized = libraryId != null &&
        resolution.authority().authorizedScope() == LibraryScope.One(libraryId)
    val inputs = remember(libraryId, occurrences) {
        libraryId?.let { artistBulkCreditsFromOccurrences(it, occurrences) }
            ?: ArtistBulkObservedInputs.InvalidMembership
    }
    val reader = remember(libraryId, scopeStore, rootAuthority, repository) {
        libraryId?.let { ArtistBulkLibrarySnapshotReader(it, scopeStore, rootAuthority, repository) }
    }
    val coroutineScope = rememberCoroutineScope()
    var sheetOpen by remember(libraryId) { mutableStateOf(false) }
    var panel by remember(libraryId) { mutableStateOf<ArtistBulkPanelState>(ArtistBulkPanelState.Idle) }
    var visibleRows by remember(libraryId) { mutableIntStateOf(12) }
    var statusFilter by remember(libraryId) { mutableStateOf(ArtistBulkReviewFilter.ALL) }
    var pending by remember(libraryId) { mutableStateOf<Job?>(null) }
    var selectedFingerprints by remember(libraryId) { mutableStateOf<Set<String>>(emptySet()) }
    var acknowledgedPreparationOnly by remember(libraryId) { mutableStateOf(false) }
    var preparedSession by remember(libraryId) { mutableStateOf<ArtistBulkPreparedSession?>(null) }
    var preparing by remember(libraryId) { mutableStateOf(false) }
    var sessionMessage by remember(libraryId) { mutableStateOf<String?>(null) }

    // Dismiss stale proposal authority when any relevant observed context changes.
    LaunchedEffect(libraryId, occurrences, rootStamp, catalogRevision, resolution) {
        pending?.cancel()
        pending = null
        visibleRows = 12
        statusFilter = ArtistBulkReviewFilter.ALL
        selectedFingerprints = emptySet()
        acknowledgedPreparationOnly = false
        preparedSession = null
        preparing = false
        sessionMessage = null
        if (panel !is ArtistBulkPanelState.Idle) {
            panel = ArtistBulkPanelState.Failed("El contexto cambió. Solicita una nueva lectura local.")
        }
    }

    fun requestPreview() {
        if (!authorized || reader == null || inputs !is ArtistBulkObservedInputs.Ready) return
        pending?.cancel()
        panel = ArtistBulkPanelState.Reading
        visibleRows = 12
        statusFilter = ArtistBulkReviewFilter.ALL
        selectedFingerprints = emptySet()
        acknowledgedPreparationOnly = false
        preparedSession = null
        sessionMessage = null
        preparing = false
        val captured = inputs.credits
        pending = coroutineScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    withTimeout(60_000L) {
                        val expected = reader.current()
                        ArtistBulkPagedDryRun(selectionStore, reader).preview(expected, captured)
                    }
                }
                // Restore only a draft proven against the same live snapshot as this preview.
                // A stale draft remains historical NO_IO; never advertise current consent.
                val restored = if (result.blocked == null) withContext(Dispatchers.IO) {
                    withTimeout(15_000L) {
                        journal.latest(result.snapshot.libraryId)?.let { old ->
                            if (artistBulkCanRestoreDraft(old, result, reader.current())) old else null
                        }
                    }
                } else null
                if (sheetOpen) {
                    preparedSession = restored
                    panel = ArtistBulkPanelState.Complete(result)
                }
            } catch (cancelled: CancellationException) {
                // The screen or the context was invalidated; never publish a partial list.
                if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
                panel = ArtistBulkPanelState.Failed("La lectura tardó demasiado. Intenta nuevamente.")
            } catch (_: Exception) {
                panel = ArtistBulkPanelState.Failed("No se pudo completar la lectura local sin riesgo de mezclar bibliotecas.")
            }
        }
    }

    fun prepareSelection(preview: ArtistBulkPreview) {
        if (preparing || pending?.isActive == true || preparedSession != null ||
            !authorized || reader == null || preview.blocked != null ||
            selectedFingerprints.isEmpty() || !acknowledgedPreparationOnly
        ) return
        val captured = selectedFingerprints.toSet()
        var durableSessionId: String? = null
        preparing = true
        sessionMessage = null
        pending = coroutineScope.launch {
            try {
                val fresh = withContext(Dispatchers.IO) { reader.current() }
                if (fresh != preview.snapshot) {
                    panel = ArtistBulkPanelState.Failed("La biblioteca cambió; la selección ya no es válida.")
                    selectedFingerprints = emptySet()
                    acknowledgedPreparationOnly = false
                    return@launch
                }
                // Room identity can change while the catalog hash stays identical.
                // Reread only selected exact credits before persisting a local draft.
                val pickedRows = preview.rows.filter { artistBulkSelectableFingerprint(it) in captured }
                val pickedCredits = pickedRows.map { row ->
                    org.michimusic.mobile.artist.ArtistBulkCredit(preview.snapshot.libraryId,
                        row.credit, row.albumIds, row.trackCount)
                }
                val verified = withContext(Dispatchers.IO) {
                    withTimeout(30_000L) { ArtistBulkDryRun(selectionStore, reader).preview(fresh, pickedCredits) }
                }
                if (verified.blocked != null || verified.rows.size != captured.size ||
                    verified.rows.any { row ->
                        val old = pickedRows.singleOrNull { it.diagnosticFingerprint == row.diagnosticFingerprint }
                        old == null || artistBulkSelectableFingerprint(row) == null ||
                            old.mbid != row.mbid || old.associationId != row.associationId ||
                            old.credit != row.credit || old.albumIds.sorted() != row.albumIds.sorted()
                    }) {
                    panel = ArtistBulkPanelState.Failed("Cambió la identidad o pertenencia de la selección. Solicita una nueva revisión.")
                    selectedFingerprints = emptySet()
                    acknowledgedPreparationOnly = false
                    return@launch
                }
                when (val result = prepareArtistBulkSession(
                    verified, captured, acknowledgedPreparationOnly = true,
                    receiptId = UUID.randomUUID().toString(),
                    createdAtEpochMs = System.currentTimeMillis(),
                )) {
                    is ArtistBulkDraftResult.Prepared -> {
                        // Persist all receipts as one Room transaction. An I/O failure is
                        // not a prepared session; never publish success optimistically.
                        val stored = withContext(Dispatchers.IO) { journal.persist(result.session) }
                        durableSessionId = stored.sessionId
                        val stillCurrent = withContext(Dispatchers.IO) {
                            kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                                val after = ArtistBulkDryRun(selectionStore, reader).preview(fresh, pickedCredits)
                                artistBulkCanRestoreDraft(stored, after, reader.current())
                            } == true
                        }
                        if (!stillCurrent) {
                            val cancelled = withContext(Dispatchers.IO) { journal.cancel(stored.sessionId) }
                            panel = ArtistBulkPanelState.Failed(
                                if (cancelled?.cancelled == true)
                                    "La biblioteca cambió: sesión cancelada en Room. Recalcula los créditos."
                                else "La biblioteca cambió y la cancelación no fue confirmada. " +
                                    "Reabre la revisión y comprueba los recibos persistidos.",
                            )
                        } else {
                            preparedSession = stored
                            selectedFingerprints = emptySet()
                            acknowledgedPreparationOnly = false
                        }
                    }
                    is ArtistBulkDraftResult.Rejected -> {
                        sessionMessage = "La preparación se bloqueó: ${result.reason.name}. Revisa los créditos."
                    }
                }
            } catch (cancelled: CancellationException) {
                if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
                panel = ArtistBulkPanelState.Failed("La comprobación local tardó demasiado. Reabre la revisión.")
            } catch (_: Exception) {
                if (durableSessionId != null) {
                    panel = ArtistBulkPanelState.Failed(
                        "No se pudo confirmar el estado posterior al guardado. " +
                            "Puede haber un recibo en Room: reabre la revisión para comprobarlo.",
                    )
                } else {
                    sessionMessage = "No se pudo guardar ni verificar esta sesión."
                }
            } finally {
                preparing = false
            }
        }
    }

    // D32: the ONLY entrypoint is the compact Biblioteca -> Artistas header action.
    // No automatic Room read happens when the artist grid is merely composed.
    LaunchedEffect(openRequestId) {
        when (decideArtistBulkHeaderEntry(
            openRequestId, libraryId, authorized && reader != null,
            inputs is ArtistBulkObservedInputs.Ready,
        )) {
            org.michimusic.mobile.artist.ArtistBulkHeaderEntry.IGNORE -> Unit
            org.michimusic.mobile.artist.ArtistBulkHeaderEntry.READ_LOCAL -> {
                onOpenRequestConsumed()
                if (!sheetOpen) {
                    sheetOpen = true
                    requestPreview()
                }
            }
            org.michimusic.mobile.artist.ArtistBulkHeaderEntry.SELECT_ONE_LIBRARY -> {
                onOpenRequestConsumed()
                sheetOpen = true
                panel = ArtistBulkPanelState.Failed(
                    "Selecciona una biblioteca concreta. El ámbito Todas no permite atribuir " +
                        "identidades ni preparar sesiones de forma conjunta.",
                )
            }
            org.michimusic.mobile.artist.ArtistBulkHeaderEntry.INVALID_MEMBERSHIP -> {
                onOpenRequestConsumed()
                sheetOpen = true
                panel = ArtistBulkPanelState.Failed(
                    "Las pertenencias de artistas son incompletas o contradictorias. " +
                        "Revisa la biblioteca antes de preparar una sesión.",
                )
            }
        }
    }
    if (!sheetOpen) return
    MichiBottomSheet(onDismissRequest = {
        pending?.cancel()
        pending = null
        sheetOpen = false
        panel = ArtistBulkPanelState.Idle
        selectedFingerprints = emptySet()
        acknowledgedPreparationOnly = false
        preparedSession = null // D22 closes UI only; durable receipts remain in Room.
        sessionMessage = null
        preparing = false
    }) {
        Text("Revisión de artistas", color = PureWhite,
            fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
            modifier = Modifier.padding(horizontal = 20.dp).testTag("artist_bulk_sheet_title"))
        Text("Previsualización local. Ninguna selección ejecuta consultas externas ni modifica etiquetas, fotos o identidades.",
            color = TextSecondary, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        when (val current = panel) {
            ArtistBulkPanelState.Idle -> Text("Selecciona Revisar para analizar los créditos locales.",
                color = TextSecondary, modifier = Modifier.padding(20.dp))
            ArtistBulkPanelState.Reading -> {
                CircularProgressIndicator(color = TertiaryCyan,
                    modifier = Modifier.padding(20.dp).testTag("artist_bulk_reading"))
                Text("Verificando Room y el contexto actual…", color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 20.dp))
            }
            is ArtistBulkPanelState.Failed -> Text(current.reason,
                color = TextSecondary, modifier = Modifier.padding(20.dp).testTag("artist_bulk_error"))
            is ArtistBulkPanelState.Complete -> {
                val preview = current.preview
                if (preview.blocked != null) {
                    Text(artistBulkBlockLabel(preview.blocked), color = TextSecondary,
                        modifier = Modifier.padding(20.dp).testTag("artist_bulk_blocked"))
                } else {
                    Text("${preview.rows.size} créditos · ${preview.eligibleCount} con identidad confirmada · " +
                        "${preview.requiresReviewCount} pendientes o bloqueados",
                        color = PureWhite, fontWeight = FontWeight.Medium, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                            .testTag("artist_bulk_summary"))
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            .testTag("artist_bulk_filters"),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(ArtistBulkReviewFilter.entries, key = { it.name }) { filter ->
                            OutlinedButton(
                                onClick = { statusFilter = filter; visibleRows = 12 },
                                modifier = Modifier.heightIn(min = 48.dp)
                                    .testTag("artist_bulk_filter_${filter.name.lowercase()}"),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = if (statusFilter == filter) TertiaryCyan else TextSecondary,
                                ),
                            ) {
                                Text("${filter.title} · ${artistBulkReviewFilterCount(preview.rows, filter)}",
                                    fontSize = 11.sp)
                            }
                        }
                    }
                    if (preparedSession == null) {
                        Text("Marca cada artista que deseas incluir en un borrador de sesión. " +
                            "No se selecciona nadie automáticamente. Máximo 64 créditos.",
                            color = TextSecondary, fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp)
                                .testTag("artist_bulk_consent_help"))
                    }
                    val filteredRows = artistBulkFilteredRows(preview.rows, statusFilter)
                    if (filteredRows.isEmpty()) {
                        Text("No hay artistas para este filtro.", color = TextSecondary,
                            modifier = Modifier.padding(20.dp).testTag("artist_bulk_filter_empty"))
                    }
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 450.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(filteredRows.take(visibleRows), key = { index, row ->
                            "${index}:${row.diagnosticFingerprint ?: row.credit}"
                        }) { _, row ->
                            ArtistBulkPreviewRowContent(
                                row = row,
                                selected = artistBulkSelectableFingerprint(row)?.let { it in selectedFingerprints } == true,
                                canSelect = preparedSession == null && !preparing,
                                onToggleSelection = { checked ->
                                    val id = artistBulkSelectableFingerprint(row)
                                    if (id != null && preparedSession == null && !preparing) {
                                        selectedFingerprints = if (checked) selectedFingerprints + id
                                            else selectedFingerprints - id
                                        acknowledgedPreparationOnly = false
                                        sessionMessage = null
                                    }
                                },
                                onReview = review@{
                                // Never cancel a durable preparation merely to navigate.
                                if (preparing || pending?.isActive == true) return@review
                                pending = coroutineScope.launch {
                                    try {
                                        val fresh = withContext(Dispatchers.IO) { reader?.current() }
                                        if (fresh != preview.snapshot) {
                                            panel = ArtistBulkPanelState.Failed("La biblioteca cambió; vuelve a previsualizar.")
                                        } else {
                                            sheetOpen = false
                                            panel = ArtistBulkPanelState.Idle
                                            onOpenArtist(row.credit) // Navigation only; no implicit enrichment.
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        panel = ArtistBulkPanelState.Failed("No fue posible comprobar de nuevo el artista.")
                                    }
                                }
                            },
                            )
                        }
                    }
                    if (preparedSession == null) {
                        Text("${selectedFingerprints.size} artistas seleccionados individualmente",
                            color = PureWhite, fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                                .testTag("artist_bulk_selected_count"))
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Checkbox(
                                checked = acknowledgedPreparationOnly,
                                enabled = selectedFingerprints.isNotEmpty() && !preparing,
                                onCheckedChange = { acknowledgedPreparationOnly = it },
                                modifier = Modifier.testTag("artist_bulk_prepare_acknowledge"),
                            )
                            Text("Confirmo únicamente la preparación local de este borrador. " +
                                "No autorizo descargas, modificaciones ni ejecución masiva.",
                                color = TextSecondary, fontSize = 11.sp,
                                modifier = Modifier.weight(1f))
                        }
                        OutlinedButton(
                            onClick = { prepareSelection(preview) },
                            enabled = selectedFingerprints.isNotEmpty() && selectedFingerprints.size <= 64 &&
                                acknowledgedPreparationOnly && !preparing,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                                .heightIn(min = 48.dp).testTag("artist_bulk_prepare_session"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TertiaryCyan),
                        ) { Text(if (preparing) "Verificando contexto…" else "Preparar borrador · Sin ejecutar") }
                    }
                    preparedSession?.let { session ->
                        Text("Sesión ${session.sessionId.take(8)} · ${session.receipts.size} recibos Room · " +
                            "No hay trabajos programados ni cambios en la música.",
                            color = PureWhite, fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 20.dp)
                                .testTag("artist_bulk_prepared_receipts"))
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 190.dp)
                                .testTag("artist_bulk_receipt_list"),
                        ) {
                            items(session.receipts, key = { it.fingerprint }) { receipt ->
                                Text("${receipt.artistCredit}: " +
                                    if (receipt.status == ArtistBulkPlannedStatus.CANCELLED_NOT_SUBMITTED)
                                        "Cancelado, sin ejecutar" else "Preparado, sin ejecutar",
                                    color = TextSecondary, fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                            }
                        }
                        ArtistBulkPermissionPanel(
                            session = session, journal = journal, reader = reader,
                            selectionStore = selectionStore, preview = preview,
                            enabled = authorized && sheetOpen && !preparing,
                        )
                        if (!session.cancelled) {
                            TextButton(
                                onClick = {
                                    if (!preparing) {
                                        preparing = true
                                        coroutineScope.launch {
                                            try {
                                                val cancelled = withContext(Dispatchers.IO) {
                                                    journal.cancel(session.sessionId)
                                                }
                                                if (cancelled != null && sheetOpen) {
                                                    preparedSession = null
                                                    selectedFingerprints = emptySet()
                                                    acknowledgedPreparationOnly = false
                                                    sessionMessage = "Cancelación confirmada en Room. Puedes preparar otro borrador."
                                                } else if (sheetOpen) {
                                                    sessionMessage = "La cancelación no se confirmó en Room."
                                                }
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                if (sheetOpen) sessionMessage = "No se pudo persistir la cancelación."
                                            } finally {
                                                preparing = false
                                            }
                                        }
                                    }
                                },
                                enabled = !preparing,
                                modifier = Modifier.padding(horizontal = 20.dp)
                                    .heightIn(min = 48.dp).testTag("artist_bulk_cancel_draft"),
                            ) { Text("Cancelar borrador sin ejecutar", color = TertiaryCyan) }
                        }
                    }
                    sessionMessage?.let {
                        Text(it, color = TextSecondary, fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 20.dp)
                                .testTag("artist_bulk_session_error"))
                    }
                    if (visibleRows < filteredRows.size) {
                        TextButton(onClick = { visibleRows = (visibleRows + 12).coerceAtMost(filteredRows.size) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .testTag("artist_bulk_more")) { Text("Ver más créditos", color = TertiaryCyan) }
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                pending?.cancel(); pending = null; sheetOpen = false
                selectedFingerprints = emptySet()
                acknowledgedPreparationOnly = false
                preparedSession = null
                sessionMessage = null
            },
                modifier = Modifier.heightIn(min = 48.dp).testTag("artist_bulk_close")) {
                Text("Cerrar", color = TertiaryCyan)
            }
            TextButton(onClick = { requestPreview() },
                enabled = authorized && reader != null && inputs is ArtistBulkObservedInputs.Ready &&
                    panel !is ArtistBulkPanelState.Reading && !preparing,
                modifier = Modifier.heightIn(min = 48.dp).testTag("artist_bulk_refresh")) {
                Text("Recalcular", color = TertiaryCyan)
            }
        }
    }
}

@Composable
private fun ArtistBulkPreviewRowContent(
    row: ArtistBulkPreviewRow,
    selected: Boolean,
    canSelect: Boolean,
    onToggleSelection: (Boolean) -> Unit,
    onReview: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(row.credit, color = PureWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text("${row.albumIds.size} álbumes · ${row.trackCount} pistas · " +
            artistBulkDispositionLabel(row.disposition),
            color = TextSecondary, fontSize = 11.sp)
        if (artistBulkSelectableFingerprint(row) != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(
                    checked = selected,
                    enabled = canSelect,
                    onCheckedChange = onToggleSelection,
                    modifier = Modifier.testTag(
                        "artist_bulk_select_${row.diagnosticFingerprint.orEmpty().take(12)}",
                    ),
                )
                Text("Incluir en borrador (sin ejecutar)", color = TextSecondary, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 10.dp))
            }
        }
        if (row.disposition in setOf(
                ArtistBulkDisposition.ELIGIBLE_FOR_EXPLICIT_REVIEW,
                ArtistBulkDisposition.IDENTITY_REQUIRED,
                ArtistBulkDisposition.MEMBERSHIP_RECONFIRMATION,
                ArtistBulkDisposition.PROFILE_MISSING,
            )) {
            TextButton(onClick = onReview, modifier = Modifier.heightIn(min = 48.dp)
                .testTag("artist_bulk_review_${row.diagnosticFingerprint.orEmpty().take(12)}")) {
                Text("Abrir ficha para revisión individual", color = TertiaryCyan, fontSize = 12.sp)
            }
        }
    }
}
