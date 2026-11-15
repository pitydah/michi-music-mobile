package org.michimusic.core.intelligence

/**
 * MI-23 4.2: explicit, user-facing outcomes for a manual MusicBrainz lookup.
 *
 * The dialog must never collapse every result into a generic failure:
 * - no matches is not the same as an uncertain match;
 * - an uncertain match is not an error;
 * - a selected recording without genres is NOT an unknown genre in the file;
 * - a revoked permission produced no request at all.
 *
 * Pure policy: no network, no Android, no state. The UI maps each outcome to text.
 */
object MichiGenreLookupOutcomePolicy {
    enum class SearchOutcome {
        /** SIN_COINCIDENCIA */
        NO_MATCH,
        /** COINCIDENCIAS_INCIERTAS */
        MATCHES_UNCERTAIN,
        /** Coincidencias que el usuario todavía debe revisar y elegir. */
        MATCHES_REVIEWABLE,
        /** ERROR de consulta. */
        ERROR,
        /** CONSENTIMIENTO_REVOCADO: nothing was sent. */
        CONSENT_REVOKED,
    }

    enum class RecordingOutcome {
        /** GÉNEROS_DISPONIBLES */
        PROPOSALS_AVAILABLE,
        /** GRABACIÓN_SELECCIONADA_SIN_GÉNEROS */
        RECORDING_WITHOUT_GENRES,
        ERROR,
        CONSENT_REVOKED,
    }

    fun search(
        matchCount: Int,
        reviewableCount: Int,
        error: Boolean = false,
        consentRevoked: Boolean = false,
    ): SearchOutcome = when {
        consentRevoked -> SearchOutcome.CONSENT_REVOKED
        error -> SearchOutcome.ERROR
        matchCount <= 0 -> SearchOutcome.NO_MATCH
        reviewableCount <= 0 -> SearchOutcome.MATCHES_UNCERTAIN
        else -> SearchOutcome.MATCHES_REVIEWABLE
    }

    fun recording(
        proposalCount: Int,
        error: Boolean = false,
        consentRevoked: Boolean = false,
    ): RecordingOutcome = when {
        consentRevoked -> RecordingOutcome.CONSENT_REVOKED
        error -> RecordingOutcome.ERROR
        proposalCount <= 0 -> RecordingOutcome.RECORDING_WITHOUT_GENRES
        else -> RecordingOutcome.PROPOSALS_AVAILABLE
    }

    fun message(outcome: SearchOutcome): String = when (outcome) {
        SearchOutcome.NO_MATCH ->
            "Sin coincidencias para título, artista y álbum. Puedes intentar una segunda " +
                "búsqueda sin álbum; requiere una autorización nueva y explícita."
        SearchOutcome.MATCHES_UNCERTAIN ->
            "Hay coincidencias, pero ninguna es verificable con título, artista y duración. " +
                "Revísalas manualmente; ninguna se aplicará sola."
        SearchOutcome.MATCHES_REVIEWABLE ->
            "Coincidencias revisables. Nada cambia hasta que elijas una grabación."
        SearchOutcome.ERROR ->
            "No se pudo completar la consulta a MusicBrainz. No se modificó nada."
        SearchOutcome.CONSENT_REVOKED ->
            "La autorización de esta consulta fue revocada. No se envió ninguna solicitud."
    }

    fun message(outcome: RecordingOutcome): String = when (outcome) {
        RecordingOutcome.PROPOSALS_AVAILABLE ->
            "Géneros propuestos por MusicBrainz para la grabación elegida. " +
                "Selecciona uno para previsualizarlo; no se escribe nada."
        RecordingOutcome.RECORDING_WITHOUT_GENRES ->
            "MusicBrainz no tiene géneros para esta grabación. Eso no significa que el " +
                "archivo carezca de género."
        RecordingOutcome.ERROR ->
            "No se pudieron obtener géneros de la grabación. No se modificó nada."
        RecordingOutcome.CONSENT_REVOKED ->
            "La autorización de esta consulta fue revocada. No se envió ninguna solicitud."
    }
}
