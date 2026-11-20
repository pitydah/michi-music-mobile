package org.michimusic.mobile.devices.aircat.experience

import org.michimusic.mobile.devices.aircat.AirCatTransferState

/**
 * A71 experience layer.
 *
 * These models are pure data: they carry no client, no grant, no lease and no
 * scope. The engine keeps owning every security-relevant object; the screen only
 * learns what it is allowed to draw. Splitting the two is what stops a Compose
 * file from becoming the place where a transfer is decided.
 */

/** Where the experience currently is. One screen per phase, no parallel state. */
internal enum class AirCatPhase { HUB, SOURCES, CATALOGUE, REVIEW, TRANSFER, RESULT }

/**
 * Six separate facts about a source, never one `ready` boolean (A71-02).
 *
 * A device can be discovered without being trusted, known without being
 * connected, connected without Home, and AirCat-capable without a valid grant.
 * Collapsing any two of those into one flag is how a screen ends up promising a
 * transfer the engine will refuse.
 */
internal data class AirCatSourceUi(
    val deviceId: String,
    val name: String,
    val kindLabel: String,
    val discovered: Boolean,
    val known: Boolean,
    val connected: Boolean,
    val homeEnrolled: Boolean,
    val airCatAdvertised: Boolean,
    val revoked: Boolean = false,
    val blockedReason: String? = null,
    // Michi Link canonical identity resolved from the trusted pairing registry.
    // `deviceId` remains the registry/discovery id used by the transfer coordinator.
    val canonicalMichiId: String? = null,
) {
    /** Connected, trusted by Home, advertises AirCat and was not revoked. */
    val canExplore: Boolean
        get() = !revoked && known && connected && homeEnrolled &&
            airCatAdvertised && blockedReason == null

    /** Reachable and AirCat-capable, but this phone holds no Home authority yet. */
    val needsHome: Boolean
        get() = !revoked && connected && !homeEnrolled && airCatAdvertised

    /** The one sentence the row is allowed to say about itself. */
    val statusLine: String
        get() = when {
            revoked -> "Autorización revocada"
            blockedReason != null -> blockedReason
            !connected && known -> "Conocido · sin conexión"
            !connected && discovered -> "Encontrado · sin conexión"
            connected && !known -> "Conectado · falta vinculación Michi Link"
            connected && !homeEnrolled -> "Conectado · Home no vinculado"
            connected && !airCatAdvertised -> "Conectado · sin transferencias AirCat"
            canExplore -> "Conectado · Home vinculado · AirCat disponible"
            else -> "Encontrado"
        }
}

/** One catalogue row. Identity is the remote id, never a list position. */
internal data class AirCatTrackUi(
    val remoteTrackId: String,
    val logicalTrackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val format: String,
    val size: Long,
    val sha256: String,
) {
    val albumKey: String get() = album.ifBlank { "Sin álbum" }
}

/** All the tracks of one album, for the group header and its tri-state box. */
internal data class AirCatAlbumUi(
    val album: String,
    val artist: String,
    val tracks: List<AirCatTrackUi>,
) {
    val totalBytes: Long get() = tracks.sumOf { it.size }
    val trackCount: Int get() = tracks.size
}

/** The catalogue of one source, with the groupings the screen offers. */
internal data class AirCatCatalogueUi(
    val sourceDeviceId: String,
    val sourceName: String,
    val tracks: List<AirCatTrackUi>,
) {
    val totalBytes: Long get() = tracks.sumOf { it.size }

    val albums: List<AirCatAlbumUi>
        get() = tracks
            .groupBy { it.albumKey }
            .map { (album, rows) ->
                AirCatAlbumUi(
                    album = album,
                    artist = rows.map { it.artist }.distinct().singleOrNull()
                        ?: rows.firstOrNull()?.artist.orEmpty(),
                    tracks = rows,
                )
            }
            .sortedBy { it.album.lowercase() }
}

/**
 * The projection of the engine's progress event. Numbers are copied, never
 * derived: a percentage the engine did not report is a claim the UI cannot back.
 */
internal data class AirCatTransferUi(
    val transferId: String,
    val state: AirCatTransferState,
    val completedTracks: Int,
    val totalTracks: Int,
    val transferredBytes: Long,
    val totalBytes: Long,
    val currentTrackTitle: String?,
    val paused: Boolean,
    val error: String?,
) {
    /**
     * Only a state the engine reached may be drawn as finished. `NO_FILES` is
     * terminal but is not a completed transfer, so it reports zero progress.
     */
    val fraction: Float
        get() = when {
            state == AirCatTransferState.NO_FILES -> 0f
            totalBytes <= 0L -> 0f
            else -> (transferredBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        }

    val isFinished: Boolean
        get() = state == AirCatTransferState.COMPLETED ||
            state == AirCatTransferState.NO_FILES ||
            state == AirCatTransferState.CANCELLED ||
            state == AirCatTransferState.FAILED
}

/** What the user is told when the operation ends, and nothing more. */
internal data class AirCatResultUi(
    val kind: Kind,
    val title: String,
    val detail: String,
    val tracks: Int = 0,
    val bytes: Long = 0L,
) {
    internal enum class Kind { COMPLETED, NO_FILES, CANCELLED, FAILED, UNCERTIFIED }
}

/**
 * The whole screen state. Immutable so a late engine callback can be compared
 * against the state it was started for instead of silently overwriting a screen
 * the user has already navigated away from.
 */
internal data class AirCatExperienceState(
    val phase: AirCatPhase = AirCatPhase.HUB,
    val sources: List<AirCatSourceUi> = emptyList(),
    val sourcesLoading: Boolean = false,
    val selectedSourceId: String? = null,
    val catalogue: AirCatCatalogueUi? = null,
    val catalogueLoading: Boolean = false,
    val selection: Set<String> = emptySet(),
    val query: String = "",
    val transfer: AirCatTransferUi? = null,
    val result: AirCatResultUi? = null,
    val availableBytes: Long? = null,
    val storageProbeFailed: Boolean = false,
    val fileEvidence: List<AirCatFileEvidenceUi> = emptyList(),
    // A missing or failing checkpoint store is not the same as "no evidence".
    val fileEvidenceUnavailable: Boolean = false,
    val message: String? = null,
    val starting: Boolean = false,
) {
    val selectedSource: AirCatSourceUi?
        get() = sources.firstOrNull { it.deviceId == selectedSourceId }

    /** Rows the filter keeps, in catalogue order. */
    val visibleTracks: List<AirCatTrackUi>
        get() {
            val rows = catalogue?.tracks.orEmpty()
            if (query.isBlank()) return rows
            val needle = query.trim().lowercase()
            return rows.filter {
                it.title.lowercase().contains(needle) ||
                    it.artist.lowercase().contains(needle) ||
                    it.album.lowercase().contains(needle)
            }
        }

    val visibleAlbums: List<AirCatAlbumUi>
        get() {
            val rows = visibleTracks
            return rows.groupBy { it.albumKey }
                .map { (album, tracks) ->
                    AirCatAlbumUi(
                        album = album,
                        artist = tracks.map { it.artist }.distinct().singleOrNull()
                            ?: tracks.firstOrNull()?.artist.orEmpty(),
                        tracks = tracks,
                    )
                }
                .sortedBy { it.album.lowercase() }
        }

    val selectedTracks: List<AirCatTrackUi>
        get() = catalogue?.tracks.orEmpty().filter { it.remoteTrackId in selection }

    val selectedBytes: Long
        get() = selectedTracks.sumOf { it.size }

    val canReview: Boolean
        get() = selection.isNotEmpty() && !starting

    /** A null storage snapshot is CHECKING/UNKNOWN, never transfer-ready. */
    val canConfirm: Boolean
        get() = canReview && availableBytes != null && storageRefusal == null &&
            org.michimusic.mobile.devices.aircat.AirCatStorageRequirement.requiredBytes(selectedBytes)
                ?.let { availableBytes >= it } == true

    /** Storage is judged with the engine's own numbers, not a copy of its policy. */
    val storageRefusal: String?
        get() {
            val available = availableBytes ?: return null
            val needed = org.michimusic.mobile.devices.aircat.AirCatStorageRequirement
                .requiredBytes(selectedBytes)
                ?: return "El tamaño seleccionado supera el límite admitido."
            if (selectedBytes <= 0L) return null
            if (available >= needed) return null
            return "No hay espacio suficiente: se requieren ${airCatHumanBytes(needed)} " +
                "(incluye reserva de seguridad) y hay ${airCatHumanBytes(available)} disponibles."
        }
}

/** Bytes for a human, with no invented precision. */
internal fun airCatHumanBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mib = bytes / (1024.0 * 1024.0)
    if (mib < 1024.0) return String.format(java.util.Locale.US, "%.0f MB", mib)
    return String.format(java.util.Locale.US, "%.1f GB", mib / 1024.0)
}

/** Count sentence used by the footer and the review screen. */
internal fun airCatSelectionSummary(count: Int, bytes: Long): String {
    val noun = if (count == 1) "canción seleccionada" else "canciones seleccionadas"
    return "$count $noun · ${airCatHumanBytes(bytes)}"
}

/**
 * The user-facing sentence for an engine refusal (A71-10).
 *
 * The engine's own messages name codes (`AIRC_*`) and internal objects. Showing
 * them verbatim leaks implementation detail and, worse, invites the user to
 * treat a security refusal as a glitch to work around.
 */
internal fun airCatFailureMessage(raw: String?): String {
    val text = raw.orEmpty()
    return when {
        text.isBlank() -> "La operación no se pudo completar. Intentá de nuevo."
        text.contains("AIRC_HOME_GRANT", true) || text.contains("AIRC_GRANT", true) ||
            text.contains("requiere vinculación Home", true) ->
            "Vinculá este equipo a Home para recibir música."
        text.contains("AIRC_HOME_REVOKED", true) || text.contains("revocado", true) ->
            "Este equipo ya no está autorizado."
        text.contains("AIRC_TLS_PIN", true) || text.contains("ubicación segura", true) ||
            text.contains("identidad segura", true) ->
            "La identidad segura del equipo cambió. Revisá su vinculación."
        text.contains("AIRC_GRANT_EXPIRED", true) || text.contains("vencid", true) ->
            "La autorización venció. Volvé a confirmar la transferencia."
        text.contains("catálogo cambió", true) || text.contains("AIRC_MANIFEST", true) ||
            text.contains("AIRC_RECOVERY_NOT_IN_SIGNED_MANIFEST", true) ->
            "El catálogo cambió. Revisá nuevamente tu selección."
        text.contains("AIRC_STORAGE_FULL", true) || text.contains("espacio", true) ->
            "No hay espacio suficiente para recibir estos archivos."
        text.contains("AIRC_HASH_MISMATCH", true) || text.contains("integr", true) ->
            "El archivo no superó la comprobación de integridad."
        text.contains("incorporarse", true) || text.contains("biblioteca", true) ->
            "El archivo se recibió, pero no pudo incorporarse correctamente."
        text.contains("ocupad", true) || text.contains("en curso", true) ->
            "Ya existe una transferencia activa."
        text.contains("conexión", true) || text.contains("network", true) ->
            "Se interrumpió la conexión. Puedes revisar la recuperación."
        text.contains("AirCat", true) && text.contains("no ofrece", true) ->
            "Este equipo todavía no ofrece transferencias AirCat."
        else -> "La operación no se pudo completar. Intentá de nuevo."
    }
}
