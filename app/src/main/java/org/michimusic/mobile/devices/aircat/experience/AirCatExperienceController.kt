package org.michimusic.mobile.devices.aircat.experience

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.michimusic.link.LinkClient
import org.michimusic.mobile.devices.aircat.AirCatProgressEvent
import org.michimusic.mobile.devices.aircat.AirCatTransferState
import org.michimusic.mobile.devices.aircat.CatalogTrack
import org.michimusic.mobile.devices.aircat.CataloguePreview
import org.michimusic.mobile.devices.aircat.SelectionEntry

/**
 * What the experience needs from the engine, and nothing else.
 *
 * Declaring it as an interface is what makes the screen testable without a
 * socket, a keystore or a grant: the production implementation is the adapter
 * over [org.michimusic.mobile.devices.aircat.AirCatOperationCoordinator], and a
 * test double only has to answer these seven calls.
 */
internal interface AirCatExperienceEngine {
    suspend fun explore(targetDeviceId: String, currentClient: LinkClient?): Result<CataloguePreview>

    fun start(
        targetDeviceId: String,
        scope: CoroutineScope,
        currentClient: LinkClient?,
        onResult: (Boolean, String) -> Unit,
        selection: List<SelectionEntry>?,
    )

    val progress: StateFlow<AirCatProgressEvent?>

    fun pause(transferId: String): Boolean
    fun resume(transferId: String)
    fun cancel(transferId: String)
    fun isPaused(transferId: String): Boolean
}

/** Everything the user can ask the experience to do. */
internal sealed interface AirCatIntent {
    data object OpenSources : AirCatIntent
    data object OpenHub : AirCatIntent
    data object RefreshSources : AirCatIntent

    data class ChooseSource(val deviceId: String) : AirCatIntent
    data class QueryChanged(val query: String) : AirCatIntent

    data class ToggleTrack(val remoteTrackId: String) : AirCatIntent
    data class ToggleAlbum(val album: String) : AirCatIntent
    data object SelectAll : AirCatIntent
    data object ClearSelection : AirCatIntent

    data object Review : AirCatIntent
    data object Back : AirCatIntent
    data object RetryCatalogue : AirCatIntent

    data object Confirm : AirCatIntent
    data object Pause : AirCatIntent
    data object Resume : AirCatIntent
    data object Cancel : AirCatIntent
    data object DismissResult : AirCatIntent
}

/**
 * A71 screen state holder.
 *
 * It never performs a network call of its own: it asks the engine. It never
 * keeps a client, a grant or a lease: those belong to the operation that owns
 * them. And every asynchronous result is bound to the [epoch] it started in, so
 * a catalogue that arrives after the user picked another source — or after a
 * transfer finished — cannot repaint a screen it no longer describes.
 */
internal class AirCatExperienceController(
    private val scope: CoroutineScope,
    private val engine: AirCatExperienceEngine,
    private val sources: () -> List<AirCatSourceUi>,
    private val freeBytes: suspend () -> Long,
    private val currentClient: () -> LinkClient? = { null },
) {
    private val _state = MutableStateFlow(AirCatExperienceState())
    val state: StateFlow<AirCatExperienceState> = _state.asStateFlow()

    /** Bumped by every navigation, source change and confirmation. */
    private var epoch: Int = 0
    private var catalogueJob: Job? = null
    private var progressJob: Job? = null

    fun dispatch(intent: AirCatIntent) {
        when (intent) {
            AirCatIntent.OpenSources -> openSources()
            AirCatIntent.OpenHub -> {
                epoch += 1
                catalogueJob?.cancel()
                _state.value = _state.value.copy(
                    phase = AirCatPhase.HUB,
                    message = null,
                    query = "",
                )
            }
            AirCatIntent.RefreshSources -> {
                _state.value = _state.value.copy(sources = sources(), sourcesLoading = false)
            }
            is AirCatIntent.ChooseSource -> chooseSource(intent.deviceId)
            is AirCatIntent.QueryChanged ->
                _state.value = _state.value.copy(query = intent.query)
            is AirCatIntent.ToggleTrack -> toggleTrack(intent.remoteTrackId)
            is AirCatIntent.ToggleAlbum -> toggleAlbum(intent.album)
            AirCatIntent.SelectAll -> _state.value = _state.value.copy(
                selection = _state.value.visibleTracks.map { it.remoteTrackId }.toSet(),
            )
            AirCatIntent.ClearSelection -> _state.value = _state.value.copy(selection = emptySet())
            AirCatIntent.Review -> review()
            AirCatIntent.Back -> back()
            AirCatIntent.RetryCatalogue -> _state.value.selectedSourceId?.let { chooseSource(it) }
            AirCatIntent.Confirm -> confirm()
            AirCatIntent.Pause -> withTransfer { engine.pause(it) }
            AirCatIntent.Resume -> withTransfer { engine.resume(it) }
            AirCatIntent.Cancel -> withTransfer { engine.cancel(it) }
            AirCatIntent.DismissResult -> dismissResult()
        }
    }

    private inline fun withTransfer(block: (String) -> Unit) {
        val id = _state.value.transfer?.transferId ?: return
        block(id)
        // The engine publishes the authoritative state; refresh the controls we
        // mirror so a refused pause does not leave the button inverted.
        _state.value.transfer?.let { current ->
            _state.value = _state.value.copy(
                transfer = current.copy(paused = engine.isPaused(current.transferId)),
            )
        }
    }

    private fun openSources() {
        epoch += 1
        catalogueJob?.cancel()
        _state.value = _state.value.copy(
            phase = AirCatPhase.SOURCES,
            // The sources are projected synchronously by the caller, so the
            // picker must not spin waiting for a load that already happened.
            // Marking it busy here left the screen on a spinner forever and hid
            // the honest empty state ("no equipment found") underneath it.
            sources = sources(),
            sourcesLoading = false,
            message = null,
            query = "",
        )
    }

    private fun chooseSource(deviceId: String) {
        val source = sources().firstOrNull { it.deviceId == deviceId }
        if (source == null || !source.canExplore) {
            _state.value = _state.value.copy(
                phase = AirCatPhase.SOURCES,
                sources = sources(),
                message = source?.let { it.statusLine }
                    ?: "Ese equipo ya no está disponible.",
            )
            return
        }
        val generation = ++epoch
        catalogueJob?.cancel()
        _state.value = _state.value.copy(
            phase = AirCatPhase.CATALOGUE,
            selectedSourceId = deviceId,
            sources = sources(),
            catalogue = null,
            catalogueLoading = true,
            selection = emptySet(),
            query = "",
            message = null,
            result = null,
        )
        catalogueJob = scope.launch {
            val outcome = runCatching { engine.explore(deviceId, currentClient()) }
            if (generation != epoch) return@launch
            outcome.fold(
                onSuccess = { result ->
                    result.fold(
                        onSuccess = { preview ->
                            if (generation != epoch) return@fold
                            _state.value = _state.value.copy(
                                catalogue = preview.toUi(),
                                catalogueLoading = false,
                                message = if (preview.tracks.isEmpty()) {
                                    "Ese equipo no tiene música disponible para transferir."
                                } else {
                                    null
                                },
                            )
                        },
                        onFailure = { error ->
                            _state.value = _state.value.copy(
                                catalogueLoading = false,
                                message = airCatFailureMessage(error.message),
                            )
                        },
                    )
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    _state.value = _state.value.copy(
                        catalogueLoading = false,
                        message = airCatFailureMessage(error.message),
                    )
                },
            )
        }
    }

    private fun toggleTrack(remoteTrackId: String) {
        val state = _state.value
        val selection = state.selection.toMutableSet()
        if (!selection.add(remoteTrackId)) selection.remove(remoteTrackId)
        _state.value = state.copy(selection = selection)
    }

    private fun toggleAlbum(album: String) {
        val state = _state.value
        val rows = state.catalogue?.tracks.orEmpty().filter { it.albumKey == album }
        if (rows.isEmpty()) return
        val selection = state.selection.toMutableSet()
        val allSelected = rows.all { it.remoteTrackId in selection }
        rows.forEach { row ->
            if (allSelected) selection.remove(row.remoteTrackId) else selection.add(row.remoteTrackId)
        }
        _state.value = state.copy(selection = selection)
    }

    private fun review() {
        val state = _state.value
        if (!state.canReview) return
        val generation = ++epoch
        _state.value = state.copy(phase = AirCatPhase.REVIEW, message = null, availableBytes = null)
        scope.launch {
            val available = runCatching { freeBytes() }.getOrNull()
            if (generation != epoch) return@launch
            _state.value = _state.value.copy(availableBytes = available)
        }
    }

    private fun back() {
        val state = _state.value
        when (state.phase) {
            AirCatPhase.SOURCES, AirCatPhase.HUB -> Unit
            AirCatPhase.CATALOGUE -> {
                epoch += 1
                catalogueJob?.cancel()
                _state.value = state.copy(
                    phase = AirCatPhase.SOURCES,
                    catalogue = null,
                    catalogueLoading = false,
                    selectedSourceId = null,
                    selection = emptySet(),
                    query = "",
                )
            }
            AirCatPhase.REVIEW -> _state.value = state.copy(phase = AirCatPhase.CATALOGUE)
            AirCatPhase.TRANSFER, AirCatPhase.RESULT -> Unit
        }
    }

    private fun confirm() {
        val state = _state.value
        if (!state.canReview || state.starting) return
        if (state.storageRefusal != null) return
        val source = state.selectedSource ?: return
        if (state.catalogue == null) return
        val batch = state.selectedTracks.map {
            SelectionEntry(
                remoteTrackId = it.remoteTrackId,
                logicalTrackId = it.logicalTrackId,
                title = it.title,
                format = it.format,
                size = it.size,
                sha256 = it.sha256,
            )
        }
        // A batch of zero rows is a user decision, not an engine receipt: it
        // never leaves the screen and never becomes NO_FILES.
        if (batch.isEmpty()) return
        val generation = ++epoch
        _state.value = state.copy(
            phase = AirCatPhase.TRANSFER,
            starting = true,
            message = null,
            result = null,
            transfer = null,
        )
        observeProgress(generation)
        engine.start(
            targetDeviceId = source.deviceId,
            scope = scope,
            currentClient = currentClient(),
            selection = batch,
            onResult = { ok, message ->
                if (generation != epoch) return@start
                val latest = _state.value.transfer
                // A finished transfer already projected its own result from the
                // engine's ledger; a late callback must not replace it.
                if (latest?.isFinished == true) return@start
                _state.value = _state.value.copy(
                    starting = false,
                    phase = AirCatPhase.RESULT,
                    result = if (ok) {
                        AirCatResultUi(
                            kind = AirCatResultUi.Kind.COMPLETED,
                            title = "Transferencia completada",
                            detail = message,
                            tracks = batch.size,
                            bytes = batch.sumOf { it.size },
                        )
                    } else {
                        AirCatResultUi(
                            kind = AirCatResultUi.Kind.FAILED,
                            title = "No se pudo completar la transferencia",
                            detail = airCatFailureMessage(message),
                        )
                    },
                )
            },
        )
    }

    private fun observeProgress(generation: Int) {
        progressJob?.cancel()
        progressJob = scope.launch {
            engine.progress.collect { event ->
                if (generation != epoch) return@collect
                if (event == null) return@collect
                val projected = event.toUi(engine.isPaused(event.transferId))
                _state.value = _state.value.copy(starting = false, transfer = projected)
                if (projected.isFinished) {
                    _state.value = _state.value.copy(
                        phase = AirCatPhase.RESULT,
                        result = projected.toResult(),
                    )
                }
            }
        }
    }

    private fun dismissResult() {
        epoch += 1
        progressJob?.cancel()
        _state.value = AirCatExperienceState(
            phase = AirCatPhase.HUB,
            sources = sources(),
        )
    }

    private fun CataloguePreview.toUi() = AirCatCatalogueUi(
        sourceDeviceId = sourceDeviceId,
        sourceName = deviceName,
        tracks = tracks.map { it.toUi() },
    )

    private fun CatalogTrack.toUi() = AirCatTrackUi(
        remoteTrackId = remoteTrackId,
        logicalTrackId = logicalTrackId,
        title = title,
        artist = artist,
        album = album,
        format = format,
        size = size,
        sha256 = sha256,
    )
}

private fun AirCatProgressEvent.toUi(paused: Boolean) = AirCatTransferUi(
    transferId = transferId,
    state = state,
    completedTracks = currentTrackIndex,
    totalTracks = totalTracks,
    transferredBytes = bytesTransferred,
    totalBytes = totalBytes,
    currentTrackTitle = activeTrackTitle,
    paused = paused,
    error = error,
)

private fun AirCatTransferUi.toResult(): AirCatResultUi = when (state) {
    AirCatTransferState.COMPLETED -> AirCatResultUi(
        kind = AirCatResultUi.Kind.COMPLETED,
        title = "Transferencia completada",
        detail = if (totalTracks > 0) {
            "$completedTracks de $totalTracks canciones recibidas"
        } else {
            "Transferencia completada"
        },
        tracks = completedTracks,
        bytes = transferredBytes,
    )
    AirCatTransferState.NO_FILES -> AirCatResultUi(
        kind = AirCatResultUi.Kind.NO_FILES,
        title = "No había archivos para transferir",
        detail = "La selección no tenía pistas que transferir.",
    )
    AirCatTransferState.CANCELLED -> AirCatResultUi(
        kind = AirCatResultUi.Kind.CANCELLED,
        title = "Transferencia cancelada",
        detail = "La música ya incorporada se conserva.",
        tracks = completedTracks,
        bytes = transferredBytes,
    )
    else -> AirCatResultUi(
        kind = AirCatResultUi.Kind.FAILED,
        title = "No se pudo completar la transferencia",
        detail = airCatFailureMessage(error),
        tracks = completedTracks,
        bytes = transferredBytes,
    )
}
