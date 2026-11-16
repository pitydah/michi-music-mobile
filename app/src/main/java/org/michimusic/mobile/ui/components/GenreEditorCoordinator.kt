package org.michimusic.mobile.ui.components

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.michimusic.core.intelligence.MichiGenreDisambiguationPolicy
import org.michimusic.core.intelligence.MichiGenreLookupAuthorization
import org.michimusic.core.intelligence.MichiGenreLookupOutcomePolicy
import org.michimusic.core.intelligence.MichiGenreNormalizationPolicy
import org.michimusic.core.intelligence.MichiGenrePreviewPolicy
import org.michimusic.data.intelligence.MichiGenreTagWriteGateway
import org.michimusic.data.repository.MichiGenrePostWriteReconciler
import org.michimusic.mobile.intelligence.MichiGenreMusicBrainzGateway

/**
 * Read-only ownership question the coordinator may not answer by itself: only the
 * host knows the playback session, the active library scope and the root revision.
 *
 * A `false` from [stillOwns] is a hard stop for every destructive operation; a
 * `false` from [canLookup] only revokes the network disclosure.
 */
internal interface GenreEditorAuthority {
    fun canLookup(): Boolean

    fun stillOwns(): Boolean
}

/** Provider seam so the coordinator can be exercised with fakes. */
internal interface GenreEditorGateway {
    suspend fun search(
        intent: MichiGenreNormalizationPolicy.SearchIntent,
        permit: MichiGenreLookupAuthorization.Permit?,
        durationMs: Long?,
    ): List<MichiGenreMusicBrainzGateway.Choice>

    suspend fun genresForSelectedRecording(
        selected: MichiGenreNormalizationPolicy.RecordingCandidate,
        permit: MichiGenreLookupAuthorization.Permit?,
    ): List<MichiGenreNormalizationPolicy.GenreProposal>
}

/** Write/platform seam; the real gateway already satisfies this shape. */
internal interface GenreEditorWriter {
    suspend fun pendingRecovery(): MichiGenreTagWriteGateway.RecoveryStatus

    suspend fun write(
        ticket: MichiGenreTagWriteGateway.Ticket,
        proposed: List<String>,
        userConfirmed: Boolean,
        replacementConfirmed: Boolean,
        writeGrantConfirmed: Boolean,
        stillOwns: () -> Boolean,
    ): MichiGenreTagWriteGateway.Result

    suspend fun restorePending(
        ticket: MichiGenreTagWriteGateway.Ticket,
        userConfirmed: Boolean,
        writeGrantConfirmed: Boolean,
        stillOwns: () -> Boolean,
        unknownBytesOverwriteConfirmed: Boolean = false,
        expectedUnknownBytes: Long? = null,
        expectedUnknownSha256: String? = null,
    ): MichiGenreTagWriteGateway.RecoveryResult
}

/** Post-write index seam. */
internal fun interface GenreEditorReconciler {
    suspend fun reconcile(
        ticket: MichiGenreTagWriteGateway.Ticket,
        verified: MichiGenreTagWriteGateway.Result.SavedVerified,
        stillOwns: () -> Boolean,
    ): MichiGenrePostWriteReconciler.Outcome
}

/**
 * K48-R06 state machine for the one genre editor.
 *
 * It owns the receipt-bound lookup permit, the revision fence that drops late HTTP
 * results and every destructive operation. It never renders, never touches Android
 * APIs and never reads the session: those arrive as [authority] answers and as an
 * explicit [GenreEditorSystemRequest] the host resolves with a real launcher.
 */
internal class GenreEditorCoordinator(
    initial: GenreEditorUiState,
    private val scope: CoroutineScope,
    private val authority: GenreEditorAuthority,
    private val gateway: GenreEditorGateway,
    private val writer: GenreEditorWriter,
    private val reconciler: GenreEditorReconciler,
    private val onLibraryRefresh: () -> Unit,
    private val onClose: () -> Unit,
    private val authorization: MichiGenreLookupAuthorization = MichiGenreLookupAuthorization(),
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<GenreEditorUiState> = _state.asStateFlow()

    /**
     * Fence for every published async result. It is incremented by each new request,
     * by cancellation, by closing and by unbinding, so a result produced for an older
     * receipt can never be published onto the current one.
     */
    private var revision = 0L
    private var lookupJob: Job? = null
    private var operationJob: Job? = null

    val current: GenreEditorUiState get() = _state.value

    fun dispatch(intent: GenreEditorIntent) {
        when (intent) {
            GenreEditorIntent.Close -> onClose()
            GenreEditorIntent.SearchSuggestions -> openConsentForStrictSearch()
            GenreEditorIntent.ConfirmNetworkRequest -> confirmConsent()
            GenreEditorIntent.CancelNetworkRequest ->
                _state.update { it.copy(pendingConsent = null) }
            GenreEditorIntent.CancelLookup -> cancelLookup()
            GenreEditorIntent.OfferRelaxedSearch -> openConsentForRelaxedSearch()
            is GenreEditorIntent.SelectRecording -> selectRecording(intent.recordingId)
            is GenreEditorIntent.AddGenre -> applyPreviewEdit(
                _state.value.preview.add(intent.value),
            )
            is GenreEditorIntent.RemoveGenre -> applyPreviewEdit(
                _state.value.preview.removeAt(intent.index),
            )
            is GenreEditorIntent.ReplaceGenre -> applyPreviewEdit(
                _state.value.preview.replaceAt(intent.index, intent.value),
            )
            is GenreEditorIntent.SelectReplaceTarget -> _state.update { state ->
                val value = state.preview.proposed.getOrNull(intent.index) ?: return@update state
                state.copy(replaceIndex = intent.index, draft = value)
            }
            is GenreEditorIntent.EditDraft -> _state.update { it.copy(draft = intent.value) }
            GenreEditorIntent.OpenPreview -> _state.update { it.copy(draftPreviewOpen = true) }
            GenreEditorIntent.TogglePreview ->
                _state.update { it.copy(draftPreviewOpen = !it.draftPreviewOpen) }
            GenreEditorIntent.ResetPreview -> resetPreview()
            GenreEditorIntent.ToggleTechnicalDetails ->
                _state.update { it.copy(technicalDetailsOpen = !it.technicalDetailsOpen) }
            GenreEditorIntent.ToggleEvidenceReason ->
                _state.update { it.copy(evidenceReasonOpen = !it.evidenceReasonOpen) }
            GenreEditorIntent.RequestWrite -> requestWrite()
            GenreEditorIntent.DismissWrite -> _state.update { it.copy(confirmWrite = false) }
            GenreEditorIntent.RequestRecovery -> requestRecovery()
            GenreEditorIntent.DismissRecovery -> _state.update { it.copy(confirmRecovery = false) }
            GenreEditorIntent.DismissUnknownRecovery -> _state.update {
                it.copy(confirmUnknownRecovery = false, unknownRecovery = null)
            }
            is GenreEditorIntent.ResolveSystemRequest -> resolveSystemRequest(intent.granted)
            GenreEditorIntent.SystemPermissionAlreadyHeld -> runPendingOperation()
            GenreEditorIntent.SystemRequestUnavailable -> _state.update {
                it.copy(
                    confirmWrite = false,
                    confirmRecovery = false,
                    confirmUnknownRecovery = false,
                    systemRequest = null,
                    message = when (it.systemRequest) {
                        GenreEditorSystemRequest.WRITE_PERMISSION ->
                            "No se pudo solicitar el permiso de escritura."
                        GenreEditorSystemRequest.RECOVERY_PERMISSION ->
                            "No se pudo solicitar permiso de recuperación."
                        GenreEditorSystemRequest.UNKNOWN_BYTES_RECOVERY_PERMISSION ->
                            "No fue posible solicitar permiso para la restauración extraordinaria."
                        null -> it.message
                    },
                )
            }
        }
    }

    /**
     * Keeps the derived write facet honest without rebuilding the state machine: the
     * host reports the current playback session after a handoff or a track change.
     */
    fun refreshAuthorityFacts(descriptorOpenInPlayer: Boolean) {
        _state.update {
            if (it.descriptorOpenInPlayer == descriptorOpenInPlayer) it
            else it.copy(descriptorOpenInPlayer = descriptorOpenInPlayer)
        }
    }

    /** Cold read of the local recovery journal. Never re-enters a destructive phase. */
    fun loadRecovery() {
        operationJob?.cancel()
        operationJob = scope.launch {
            val status = try {
                writer.pendingRecovery()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                MichiGenreTagWriteGateway.RecoveryStatus.Blocked("RECOVERY_CHECK_FAILED")
            }
            _state.update { it.copy(recovery = status) }
        }
    }

    /** The sheet was dismissed or replaced: nothing may be published afterwards. */
    fun unbind() {
        authorization.revoke()
        revision++
        lookupJob?.cancel()
        lookupJob = null
        operationJob?.cancel()
        operationJob = null
    }

    private fun openConsentForStrictSearch() {
        val state = _state.value
        val intent = state.searchIntent ?: return
        if (state.busy || !authority.canLookup() ||
            !MichiGenreNormalizationPolicy.isPreparedSearch(intent)
        ) return
        _state.update {
            it.copy(pendingConsent = GenreNetworkConsent.StrictSearch(intent.outgoingFields()))
        }
    }

    private fun openConsentForRelaxedSearch() {
        val state = _state.value
        val strict = state.searchIntent ?: return
        if (state.busy || !authority.canLookup()) return
        val relaxed = MichiGenreNormalizationPolicy.relaxedSearch(strict) ?: return
        _state.update {
            it.copy(
                relaxedSearchOffered = false,
                pendingConsent = GenreNetworkConsent.RelaxedSearch(relaxed.outgoingFields()),
            )
        }
    }

    /**
     * The consent stage is also the point where the earlier proposal set is dropped:
     * a new query can never inherit another query's verified genres.
     */
    private fun confirmConsent() {
        val consent = _state.value.pendingConsent ?: return
        _state.update { it.copy(pendingConsent = null) }
        when (consent) {
            is GenreNetworkConsent.StrictSearch -> {
                val strict = _state.value.searchIntent
                if (strict != null) dispatchSearch(strict)
            }
            is GenreNetworkConsent.RelaxedSearch -> {
                val strict = _state.value.searchIntent
                val relaxed = strict?.let(MichiGenreNormalizationPolicy::relaxedSearch)
                if (relaxed != null) dispatchSearch(relaxed)
            }
            is GenreNetworkConsent.RecordingGenres -> {
                if (_state.value.matches.any { it.recording.sameRecording(consent.candidate) }) {
                    dispatchRecordingLookup(consent.candidate)
                } else {
                    _state.update {
                        it.copy(message = "La grabación ya no pertenece a esta consulta.")
                    }
                }
            }
        }
    }

    private fun selectRecording(recordingId: String) {
        val state = _state.value
        if (state.busy || !authority.canLookup()) return
        val canonical = MichiGenreNormalizationPolicy.canonicalMbid(recordingId)
        val choice = state.matches.firstOrNull {
            canonical != null && it.recording.mbid == canonical
        } ?: state.matches.firstOrNull { it.recording.mbid == recordingId }
        if (choice == null) {
            _state.update { it.copy(message = "La grabación ya no pertenece a esta consulta.") }
            return
        }
        // Selection is NOT consent to a second HTTP request: it only opens the
        // recording-scoped consent stage.
        _state.update {
            it.copy(
                pendingConsent = GenreNetworkConsent.RecordingGenres(
                    candidate = choice.recording,
                    plausible = choice.plausible,
                ),
            )
        }
    }

    private fun dispatchSearch(requested: MichiGenreNormalizationPolicy.SearchIntent) {
        val state = _state.value
        if (!authority.canLookup() ||
            !MichiGenreNormalizationPolicy.isPreparedSearch(requested)
        ) {
            _state.update {
                it.copy(
                    message = MichiGenreLookupOutcomePolicy.message(
                        MichiGenreLookupOutcomePolicy.SearchOutcome.CONSENT_REVOKED,
                    ),
                )
            }
            return
        }
        revision++
        val requestedRevision = revision
        lookupJob?.cancel()
        _state.update {
            it.copy(
                busy = true,
                lookup = MichiGenreLookupPhase.PENDING,
                matches = emptyList(),
                selectedRecording = null,
                proposals = emptyList(),
                pendingConsent = null,
                relaxedSearchOffered = false,
                // A new query restarts the preview from the verified file tags.
                preview = MichiGenrePreviewPolicy.of(it.embeddedGenres),
                draft = "",
                replaceIndex = null,
            )
        }
        // Minted on the confirmed click, never after coroutine dispatch: closing the
        // sheet before dispatch revokes this exact grant.
        val approvedSearch = authorization.issueSearch(requested)
        if (approvedSearch == null) {
            _state.update {
                it.copy(
                    busy = false,
                    message = MichiGenreLookupOutcomePolicy.message(
                        MichiGenreLookupOutcomePolicy.SearchOutcome.CONSENT_REVOKED,
                    ),
                )
            }
            return
        }
        val durationMs = _state.value.target.track.duration.takeIf { it > 0 }
        lookupJob = scope.launch {
            try {
                val results = gateway.search(requested, approvedSearch, durationMs)
                if (requestedRevision == revision && authority.canLookup()) {
                    val outcome = MichiGenreLookupOutcomePolicy.search(
                        matchCount = results.size,
                        reviewableCount = results.count { it.plausible },
                    )
                    _state.update {
                        it.copy(
                            matches = results,
                            lookup = MichiGenreLookupPhase.RESULTS,
                            // Only a completed strict search with zero matches may offer
                            // the optional second query; it still needs a fresh consent.
                            relaxedSearchOffered =
                                outcome == MichiGenreLookupOutcomePolicy.SearchOutcome.NO_MATCH &&
                                    requested.album.isNotEmpty(),
                            message = MichiGenreLookupOutcomePolicy.message(outcome),
                        )
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (requestedRevision == revision) {
                    _state.update {
                        it.copy(
                            lookup = MichiGenreLookupPhase.ERROR,
                            message = MichiGenreLookupOutcomePolicy.message(
                                MichiGenreLookupOutcomePolicy.SearchOutcome.ERROR,
                            ),
                        )
                    }
                }
            } finally {
                if (requestedRevision == revision) {
                    _state.update { it.copy(busy = false) }
                    lookupJob = null
                }
            }
        }
    }

    private fun dispatchRecordingLookup(chosen: MichiGenreNormalizationPolicy.RecordingCandidate) {
        val state = _state.value
        val mbid = MichiGenreNormalizationPolicy.canonicalMbid(chosen.mbid)
        if (!authority.canLookup() || mbid == null ||
            state.matches.none { it.recording.sameRecording(chosen) }
        ) {
            _state.update { it.copy(message = "La grabación ya no pertenece a esta consulta.") }
            return
        }
        revision++
        val requestedRevision = revision
        lookupJob?.cancel()
        _state.update {
            it.copy(
                selectedRecording = chosen,
                proposals = emptyList(),
                busy = true,
                lookup = MichiGenreLookupPhase.PENDING,
            )
        }
        val approvedRecording = authorization.issueRecording(mbid)
        if (approvedRecording == null) {
            _state.update {
                it.copy(
                    busy = false,
                    message = MichiGenreLookupOutcomePolicy.message(
                        MichiGenreLookupOutcomePolicy.RecordingOutcome.CONSENT_REVOKED,
                    ),
                )
            }
            return
        }
        lookupJob = scope.launch {
            try {
                val received = gateway.genresForSelectedRecording(chosen, approvedRecording)
                if (requestedRevision == revision && authority.canLookup() &&
                    _state.value.selectedRecording.sameRecording(chosen)
                ) {
                    val approved = received.filter {
                        MichiGenreNormalizationPolicy.approvedProposal(chosen, it, chosen.mbid)
                    }
                    _state.update {
                        it.copy(
                            proposals = approved,
                            lookup = MichiGenreLookupPhase.RESULTS,
                            message = MichiGenreLookupOutcomePolicy.message(
                                MichiGenreLookupOutcomePolicy.recording(approved.size),
                            ),
                        )
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (requestedRevision == revision) {
                    _state.update {
                        it.copy(
                            lookup = MichiGenreLookupPhase.ERROR,
                            message = MichiGenreLookupOutcomePolicy.message(
                                MichiGenreLookupOutcomePolicy.RecordingOutcome.ERROR,
                            ),
                        )
                    }
                }
            } finally {
                if (requestedRevision == revision) {
                    _state.update { it.copy(busy = false) }
                    lookupJob = null
                }
            }
        }
    }

    private fun cancelLookup() {
        revision++
        lookupJob?.cancel()
        lookupJob = null
        _state.update {
            it.copy(
                busy = false,
                lookup = MichiGenreLookupPhase.CANCELLED,
                message = "Consulta cancelada. No se publicaron resultados tardíos.",
            )
        }
    }

    private fun applyPreviewEdit(edit: MichiGenrePreviewPolicy.Edit) {
        when (edit) {
            is MichiGenrePreviewPolicy.Edit.Accepted -> _state.update {
                it.copy(preview = edit.preview, draft = "", replaceIndex = null, message = null)
            }
            is MichiGenrePreviewPolicy.Edit.Rejected -> _state.update {
                it.copy(
                    message = when (edit.reason) {
                        "MAX_GENRES" -> "Se conservan como máximo 32 géneros verificados."
                        "DUPLICATE_GENRE" -> "Ese género ya está en la previsualización."
                        "NOT_A_MUSICAL_GENRE" ->
                            "Un marcador de ausencia o un contenedor no es un género musical."
                        "INVALID_INDEX" -> "Selecciona un género de la lista."
                        else -> "La etiqueta escrita no es válida."
                    },
                )
            }
        }
    }

    private fun resetPreview() {
        if (_state.value.busy) return
        _state.update {
            it.copy(
                preview = MichiGenrePreviewPolicy.of(it.embeddedGenres),
                draft = "",
                replaceIndex = null,
            )
        }
    }

    private fun requestWrite() {
        val state = _state.value
        if (!state.writesCertified || !state.tagsVerified || state.busy ||
            state.preview.structuralError != null || !state.preview.isModified
        ) return
        if (!authority.stillOwns()) {
            _state.update {
                it.copy(message = "Permiso denegado o biblioteca cambiada.")
            }
            return
        }
        _state.update { it.copy(confirmWrite = true) }
    }

    private fun requestRecovery() {
        val state = _state.value
        if (!state.writesCertified || state.busy) return
        if (state.pendingRecoveryForThisTrack == null || !authority.stillOwns()) {
            _state.update {
                it.copy(message = "No se restauró: cambió la biblioteca o el archivo.")
            }
            return
        }
        _state.update { it.copy(confirmRecovery = true) }
    }

    /**
     * The host resolved a platform request. A denied or stale grant never reaches the
     * destructive path, and the request is cleared before anything else runs.
     */
    private fun resolveSystemRequest(granted: Boolean) {
        val request = _state.value.systemRequest ?: return
        _state.update { it.copy(systemRequest = null) }
        if (!granted) {
            _state.update {
                it.copy(
                    confirmWrite = false,
                    confirmRecovery = false,
                    confirmUnknownRecovery = false,
                    unknownRecovery = null,
                    message = when (request) {
                        GenreEditorSystemRequest.WRITE_PERMISSION ->
                            "Permiso denegado o biblioteca cambiada."
                        GenreEditorSystemRequest.RECOVERY_PERMISSION ->
                            "Permiso de restauración denegado o contexto cambiado."
                        GenreEditorSystemRequest.UNKNOWN_BYTES_RECOVERY_PERMISSION ->
                            "Permiso de restauración denegado o contexto cambiado."
                    },
                )
            }
            return
        }
        runPendingOperation(request)
    }

    /**
     * Runs the exact operation the cleared [GenreEditorSystemRequest] described. Also
     * reachable when the platform grant is already held.
     */
    private fun runPendingOperation(request: GenreEditorSystemRequest? = _state.value.systemRequest) {
        val state = _state.value
        _state.update { it.copy(systemRequest = null) }
        when (request) {
            GenreEditorSystemRequest.WRITE_PERMISSION -> {
                if (state.confirmWrite && state.tagsVerified && state.writesCertified &&
                    authority.stillOwns()
                ) {
                    _state.update { it.copy(confirmWrite = false) }
                    performWrite()
                } else {
                    _state.update {
                        it.copy(
                            confirmWrite = false,
                            message = "Permiso denegado o biblioteca cambiada.",
                        )
                    }
                }
            }
            GenreEditorSystemRequest.RECOVERY_PERMISSION -> {
                if (state.pendingRecoveryForThisTrack != null && authority.stillOwns() &&
                    state.writesCertified
                ) {
                    _state.update { it.copy(confirmRecovery = false) }
                    restore(allowUnknownBytes = false)
                } else {
                    _state.update {
                        it.copy(
                            confirmRecovery = false,
                            message = "Permiso de restauración denegado o contexto cambiado.",
                        )
                    }
                }
            }
            GenreEditorSystemRequest.UNKNOWN_BYTES_RECOVERY_PERMISSION -> {
                if (state.pendingRecoveryForThisTrack != null && authority.stillOwns() &&
                    state.writesCertified && state.unknownRecovery != null
                ) {
                    _state.update { it.copy(confirmUnknownRecovery = false) }
                    restore(allowUnknownBytes = true)
                } else {
                    _state.update {
                        it.copy(
                            confirmUnknownRecovery = false,
                            unknownRecovery = null,
                            message = "Falta el permiso de escritura SAF. No se alteró el archivo.",
                        )
                    }
                }
            }
            null -> Unit
        }
    }

    /**
     * Host entry point for the two dialog confirm buttons: it decides whether the
     * platform has to grant a permission first, and never mutates the file here.
     */
    fun confirmWriteRequest() {
        val state = _state.value
        if (!state.confirmWrite) return
        if (!authority.stillOwns()) {
            _state.update {
                it.copy(confirmWrite = false, message = "Permiso denegado o biblioteca cambiada.")
            }
            return
        }
        _state.update { it.copy(systemRequest = GenreEditorSystemRequest.WRITE_PERMISSION) }
    }

    fun confirmRecoveryRequest(requirePermission: Boolean) {
        val state = _state.value
        if (!state.confirmRecovery) return
        if (!authority.stillOwns() || state.pendingRecoveryForThisTrack == null) {
            _state.update {
                it.copy(
                    confirmRecovery = false,
                    message = "No se restauró: cambió la biblioteca o el archivo.",
                )
            }
            return
        }
        if (requirePermission) {
            _state.update { it.copy(systemRequest = GenreEditorSystemRequest.RECOVERY_PERMISSION) }
        } else {
            _state.update { it.copy(confirmRecovery = false) }
            runPendingOperation(GenreEditorSystemRequest.RECOVERY_PERMISSION)
        }
    }

    fun confirmUnknownRecoveryRequest(requirePermission: Boolean) {
        val state = _state.value
        if (!state.confirmUnknownRecovery) return
        if (!authority.stillOwns() || state.pendingRecoveryForThisTrack == null) {
            _state.update {
                it.copy(
                    confirmUnknownRecovery = false,
                    unknownRecovery = null,
                    message = "La biblioteca cambió; no se restauró ningún archivo.",
                )
            }
            return
        }
        if (requirePermission) {
            _state.update {
                it.copy(systemRequest = GenreEditorSystemRequest.UNKNOWN_BYTES_RECOVERY_PERMISSION)
            }
        } else {
            _state.update { it.copy(confirmUnknownRecovery = false) }
            runPendingOperation(GenreEditorSystemRequest.UNKNOWN_BYTES_RECOVERY_PERMISSION)
        }
    }

    /** SAF write access already retained: the same guarded write runs immediately. */
    fun writeWithHeldPermission() {
        _state.update { it.copy(confirmWrite = false) }
        runPendingOperation(GenreEditorSystemRequest.WRITE_PERMISSION)
    }

    private fun performWrite() {
        val state = _state.value
        _state.update { it.copy(busy = true) }
        operationJob?.cancel()
        operationJob = scope.launch {
            try {
                val ticket = state.target.writeTicket()
                val message = when (
                    val result = writer.write(
                        ticket = ticket,
                        proposed = state.preview.proposed.toList(),
                        userConfirmed = true,
                        replacementConfirmed = true,
                        writeGrantConfirmed = true,
                        stillOwns = authority::stillOwns,
                    )
                ) {
                    is MichiGenreTagWriteGateway.Result.SavedVerified ->
                        reportVerifiedWrite(ticket, result)
                    is MichiGenreTagWriteGateway.Result.Rejected -> "No se guardó: ${result.code}"
                    is MichiGenreTagWriteGateway.Result.Failed ->
                        "Error de escritura: ${result.code}. Revisa la copia de seguridad."
                }
                val recovery = readRecovery()
                _state.update { it.copy(message = message, recovery = recovery) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                _state.update { it.copy(message = "No se confirmó la escritura.") }
            } finally {
                _state.update { it.copy(busy = false) }
                operationJob = null
            }
        }
    }

    private suspend fun reportVerifiedWrite(
        ticket: MichiGenreTagWriteGateway.Ticket,
        verified: MichiGenreTagWriteGateway.Result.SavedVerified,
    ): String {
        if (verified.format == "UNCHANGED") {
            return "El género ya coincide: ningún archivo fue modificado."
        }
        val indexed = reconciler.reconcile(ticket, verified, authority::stillOwns)
        // The normal library authority owns any MediaStore/SAF re-read; the DAO may
        // publish owner-exact evidence first but never invents a cache commit.
        onLibraryRefresh()
        return when (indexed) {
            MichiGenrePostWriteReconciler.Outcome.Indexed ->
                "Género guardado y verificado. Índice de esa pista actualizado; refrescando biblioteca."
            is MichiGenrePostWriteReconciler.Outcome.RefreshRequired ->
                "Archivo verificado; la biblioteca debe actualizar su índice (${indexed.reason})."
        }
    }

    private fun restore(allowUnknownBytes: Boolean) {
        val state = _state.value
        // Bind the extraordinary consent to the SHA-256 shown by the initial rejection:
        // a different file state must force a new preview and separate approval.
        val approvedProof = if (allowUnknownBytes) state.unknownRecovery else null
        _state.update {
            it.copy(busy = true, unknownRecovery = null)
        }
        operationJob?.cancel()
        operationJob = scope.launch {
            try {
                val result = writer.restorePending(
                    ticket = state.target.writeTicket(),
                    userConfirmed = true,
                    writeGrantConfirmed = true,
                    stillOwns = authority::stillOwns,
                    unknownBytesOverwriteConfirmed = allowUnknownBytes,
                    expectedUnknownBytes = approvedProof?.bytes,
                    expectedUnknownSha256 = approvedProof?.sha256,
                )
                val message = when (result) {
                    MichiGenreTagWriteGateway.RecoveryResult.RestoredAndVerified -> {
                        onLibraryRefresh()
                        "Archivo original restaurado y verificado. Actualizando biblioteca."
                    }
                    MichiGenreTagWriteGateway.RecoveryResult.OriginalAlreadyVerified -> {
                        onLibraryRefresh()
                        "El archivo original ya estaba intacto. Se cerró la recuperación y se actualiza la biblioteca."
                    }
                    MichiGenreTagWriteGateway.RecoveryResult.CleanupCompleted -> {
                        onLibraryRefresh()
                        "Se completó la limpieza de la operación anterior. Actualizando biblioteca."
                    }
                    MichiGenreTagWriteGateway.RecoveryResult.NoOriginalMutation ->
                        "Recibo previo a escritura cerrado. El archivo nunca entró en fase destructiva."
                    is MichiGenreTagWriteGateway.RecoveryResult.Rejected -> {
                        val currentBytes = result.currentBytes
                        val currentSha256 = result.currentSha256
                        if (result.code == "UNKNOWN_CURRENT_BYTES_REQUIRES_SECOND_CONFIRMATION" &&
                            currentBytes != null && currentBytes >= 0 &&
                            currentSha256?.matches(UNKNOWN_SHA256) == true
                        ) {
                            // One-shot proof for the specific unknown content seen here.
                            val unknownMessage = "El archivo contiene bytes inesperados. " +
                                "No se sobrescribió. Revisa la advertencia de recuperación."
                            _state.update {
                                it.copy(
                                    unknownRecovery = GenreEditorUnknownRecovery(
                                        bytes = currentBytes,
                                        sha256 = currentSha256,
                                    ),
                                    confirmUnknownRecovery = true,
                                    message = unknownMessage,
                                )
                            }
                            unknownMessage
                        } else "No se restauró: ${result.code}"
                    }
                    is MichiGenreTagWriteGateway.RecoveryResult.Failed ->
                        "Recuperación pendiente: ${result.code}. No borres los datos de la app."
                }
                val recovery = readRecovery()
                _state.update { it.copy(message = message, recovery = recovery) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                _state.update { it.copy(message = "No se pudo completar la recuperación.") }
            } finally {
                _state.update { it.copy(busy = false) }
                operationJob = null
            }
        }
    }

    private suspend fun readRecovery(): MichiGenreTagWriteGateway.RecoveryStatus = try {
        writer.pendingRecovery()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        MichiGenreTagWriteGateway.RecoveryStatus.Blocked("RECOVERY_CHECK_FAILED")
    }

    private fun MichiGenreEditorTarget.writeTicket() = MichiGenreTagWriteGateway.Ticket(
        sourceId = sourceId,
        trackId = trackId,
        uri = filepath,
        expectedGenres = embeddedGenres,
        expectedTitle = track.title,
        expectedArtist = track.artist,
        expectedAlbum = track.album,
        expectedSize = track.size,
    )

    private fun MichiGenreNormalizationPolicy.RecordingCandidate?.sameRecording(
        other: MichiGenreNormalizationPolicy.RecordingCandidate,
    ): Boolean {
        val left = this ?: return false
        return MichiGenreNormalizationPolicy.canonicalMbid(left.mbid) ==
            MichiGenreNormalizationPolicy.canonicalMbid(other.mbid)
    }

    /** Preview edits are additive by construction: the remaining values survive. */
    fun suggestedTag(name: String): String =
        MichiGenreDisambiguationPolicy.suggestedTag(name)

    private companion object {
        val UNKNOWN_SHA256 = Regex("[0-9a-f]{64}")
    }
}
