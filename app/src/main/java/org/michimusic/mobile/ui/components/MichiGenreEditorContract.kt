package org.michimusic.mobile.ui.components

import org.michimusic.core.models.Track

/**
 * Single, pure contract for the shared genre editor (UI7 × MI-26, NORM-M1..M3).
 *
 * The editor is opened from two routes (Genres detail and the Now Playing action
 * sheet) but there is exactly ONE editor implementation and ONE receipt shape: a
 * duplicated `track.id` from another owner can never edit the wrong file.
 *
 * Nothing here reads files, calls providers, persists state or decides a musical
 * style. It only turns already-observed inputs into the explicit, mutually
 * distinguishable states the visual contract requires, so `UNVERIFIED` metadata is
 * never presented as "the file has no genre".
 */
enum class MichiGenreEditorOrigin { GENRES, NOW_PLAYING }

/** Immutable receipt honoured by both entry points. */
data class MichiGenreEditorTarget(
    val track: Track,
    val embeddedGenres: List<String>,
    val tagsVerified: Boolean,
    val origin: MichiGenreEditorOrigin,
) {
    val sourceId: String get() = track.sourceId
    val trackId: String get() = track.id
    val filepath: String get() = track.filepath
}

/** Contract §10.2.7: one headline state at a time, all of them distinguishable. */
enum class MichiGenreEditorState {
    UNVERIFIED_METADATA,
    VERIFIED_EMPTY_GENRE,
    VERIFIED_GENRES,
    PREVIEW_CHANGED,
    LOOKUP_PENDING,
    LOOKUP_CANCELLED,
    LOOKUP_ERROR,
    WRITE_DISABLED_UNCERTIFIED,
    WRITE_BLOCKED_ACTIVE_DESCRIPTOR,
    WRITE_AWAITING_ANDROID_PERMISSION,
    WRITE_VERIFIED,
    WRITE_FAILED_RECOVERY_PENDING,
}

enum class MichiGenreLookupPhase { IDLE, PENDING, RESULTS, CANCELLED, ERROR }

enum class MichiGenreWriteAccess {
    DISABLED_UNCERTIFIED,
    DISABLED_UNVERIFIED_EVIDENCE,
    BLOCKED_ACTIVE_DESCRIPTOR,
    AWAITING_ANDROID_PERMISSION,
    READY_CERTIFIED,
    VERIFIED,
    FAILED_RECOVERY_PENDING,
}

/** Evidence + lookup + write facets; the UI shows each without collapsing them. */
data class MichiGenreEditorStatus(
    val tagsVerified: Boolean,
    val verifiedGenres: List<String>,
    val previewModified: Boolean,
    val lookup: MichiGenreLookupPhase,
    val write: MichiGenreWriteAccess,
) {
    val state: MichiGenreEditorState
        get() = when {
            write == MichiGenreWriteAccess.FAILED_RECOVERY_PENDING ->
                MichiGenreEditorState.WRITE_FAILED_RECOVERY_PENDING
            lookup == MichiGenreLookupPhase.PENDING -> MichiGenreEditorState.LOOKUP_PENDING
            lookup == MichiGenreLookupPhase.CANCELLED -> MichiGenreEditorState.LOOKUP_CANCELLED
            lookup == MichiGenreLookupPhase.ERROR -> MichiGenreEditorState.LOOKUP_ERROR
            write == MichiGenreWriteAccess.VERIFIED -> MichiGenreEditorState.WRITE_VERIFIED
            write == MichiGenreWriteAccess.AWAITING_ANDROID_PERMISSION ->
                MichiGenreEditorState.WRITE_AWAITING_ANDROID_PERMISSION
            write == MichiGenreWriteAccess.BLOCKED_ACTIVE_DESCRIPTOR ->
                MichiGenreEditorState.WRITE_BLOCKED_ACTIVE_DESCRIPTOR
            !tagsVerified -> MichiGenreEditorState.UNVERIFIED_METADATA
            previewModified -> MichiGenreEditorState.PREVIEW_CHANGED
            verifiedGenres.isEmpty() -> MichiGenreEditorState.VERIFIED_EMPTY_GENRE
            write == MichiGenreWriteAccess.DISABLED_UNCERTIFIED ->
                MichiGenreEditorState.WRITE_DISABLED_UNCERTIFIED
            else -> MichiGenreEditorState.VERIFIED_GENRES
        }
}

object MichiGenreEditorContract {
    fun target(
        track: Track,
        embeddedGenres: List<String>,
        tagsVerified: Boolean,
        origin: MichiGenreEditorOrigin,
    ): MichiGenreEditorTarget = MichiGenreEditorTarget(
        track = track,
        embeddedGenres = embeddedGenres.distinct(),
        tagsVerified = tagsVerified,
        origin = origin,
    )

    fun status(
        tagsVerified: Boolean,
        verifiedGenres: List<String>,
        previewModified: Boolean,
        lookup: MichiGenreLookupPhase,
        writesCertified: Boolean,
        descriptorOpenInPlayer: Boolean,
        awaitingAndroidPermission: Boolean = false,
        recoveryPending: Boolean = false,
        writeVerified: Boolean = false,
    ): MichiGenreEditorStatus = MichiGenreEditorStatus(
        tagsVerified = tagsVerified,
        verifiedGenres = verifiedGenres,
        previewModified = previewModified,
        lookup = lookup,
        write = when {
            recoveryPending -> MichiGenreWriteAccess.FAILED_RECOVERY_PENDING
            writeVerified -> MichiGenreWriteAccess.VERIFIED
            awaitingAndroidPermission -> MichiGenreWriteAccess.AWAITING_ANDROID_PERMISSION
            // The certification gate outranks every other reason: no path may
            // present a physical write as available while it is false.
            !writesCertified -> MichiGenreWriteAccess.DISABLED_UNCERTIFIED
            !tagsVerified -> MichiGenreWriteAccess.DISABLED_UNVERIFIED_EVIDENCE
            // Now Playing may look up while the file is open, but never write it.
            descriptorOpenInPlayer -> MichiGenreWriteAccess.BLOCKED_ACTIVE_DESCRIPTOR
            else -> MichiGenreWriteAccess.READY_CERTIFIED
        },
    )

    /** Honest evidence line: absence is never inferred from unverified metadata. */
    fun evidenceLabel(tagsVerified: Boolean, genres: List<String>): String = when {
        !tagsVerified ->
            "Metadatos del archivo sin verificar: no se puede afirmar que no tenga género."
        genres.isEmpty() -> "Género verificado: el archivo no declara ningún género."
        else -> "Género verificado: ${genres.joinToString(" · ")}"
    }

    fun originalLabel(tagsVerified: Boolean, genres: List<String>): String = when {
        !tagsVerified -> "Original: sin datos verificados"
        genres.isEmpty() -> "Original: ninguno (verificado)"
        else -> "Original: ${genres.joinToString(" · ")}"
    }

    fun proposedLabel(tagsVerified: Boolean, proposed: List<String>): String = when {
        proposed.isNotEmpty() -> "Propuesto: ${proposed.joinToString(" · ")}"
        tagsVerified -> "Propuesto: ninguno (verificado)"
        else -> "Propuesto: ninguno (sin datos verificados)"
    }

    fun modeBadge(writesCertified: Boolean): String =
        if (writesCertified) "Edición certificada" else "Solo previsualización"

    fun primaryActionLabel(write: MichiGenreWriteAccess): String = when (write) {
        MichiGenreWriteAccess.DISABLED_UNCERTIFIED -> "No disponible: escritura sin certificar"
        MichiGenreWriteAccess.DISABLED_UNVERIFIED_EVIDENCE -> "No disponible: metadatos sin verificar"
        MichiGenreWriteAccess.BLOCKED_ACTIVE_DESCRIPTOR -> "No disponible: archivo en reproducción"
        MichiGenreWriteAccess.AWAITING_ANDROID_PERMISSION -> "Esperando permiso de Android"
        MichiGenreWriteAccess.READY_CERTIFIED -> "Guardar cambios"
        MichiGenreWriteAccess.VERIFIED -> "Cambios guardados"
        MichiGenreWriteAccess.FAILED_RECOVERY_PENDING -> "Recuperación pendiente"
    }

    fun primaryActionEnabled(write: MichiGenreWriteAccess): Boolean =
        write == MichiGenreWriteAccess.READY_CERTIFIED

    /** Short, accessible reason shown next to a disabled primary action. */
    fun primaryActionReason(write: MichiGenreWriteAccess): String? = when (write) {
        MichiGenreWriteAccess.DISABLED_UNCERTIFIED ->
            "Ningún archivo se modificará hasta certificar la escritura en Android."
        MichiGenreWriteAccess.DISABLED_UNVERIFIED_EVIDENCE ->
            "No hay prueba verificada del estado previo de la etiqueta."
        MichiGenreWriteAccess.BLOCKED_ACTIVE_DESCRIPTOR ->
            "La pista se está reproduciendo y el archivo está abierto."
        MichiGenreWriteAccess.AWAITING_ANDROID_PERMISSION ->
            "Android debe autorizar la edición de este archivo."
        MichiGenreWriteAccess.READY_CERTIFIED -> null
        MichiGenreWriteAccess.VERIFIED -> null
        MichiGenreWriteAccess.FAILED_RECOVERY_PENDING ->
            "Restaura la copia original antes de otra escritura."
    }
}
