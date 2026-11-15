package org.michimusic.core.intelligence

/**
 * MI-26: two DIFFERENT contracts for the manual genre dialog.
 *
 * A read-only MusicBrainz lookup and a physical GENRE write used to share one
 * `stillOwns()` guard, so merely having the track loaded in the player (or
 * paused on it) blocked searching its metadata even though nothing would be
 * edited. Reading public metadata of a stable local file is not a mutation.
 *
 * [canLookup] therefore keeps every identity guard (same receipt, same URI,
 * same library scope, same root revision, dialog still open) and deliberately
 * ignores playback, handoff and control endpoints. [canMutateAudioFile] keeps
 * ALL the strict conditions, including an open player descriptor.
 *
 * Pure policy, no Android, no network. The caller still owns the one-use
 * [MichiGenreLookupAuthorization] permit for the exact outgoing payload.
 */
object MichiGenreAccessPolicy {
    data class Context(
        /** The receipt belongs to a local track (never a remote/peer row). */
        val trackLocal: Boolean,
        /** sourceId + trackId + URI + metadata receipt are unchanged. */
        val receiptMatches: Boolean,
        /** The current dialog URI is byte-identical to the receipt URI. */
        val uriMatches: Boolean,
        /** The selected library scope did not change after opening. */
        val scopeUnchanged: Boolean,
        /** Library root revision did not change after opening. */
        val revisionUnchanged: Boolean,
        /** The metadata dialog that owns the consent is still open. */
        val dialogOpen: Boolean,
        /** This exact track is the one loaded/playing in the local player. */
        val trackSelectedInPlayer: Boolean = false,
        /** A remote session is controlling playback. */
        val remoteSessionActive: Boolean = false,
        /** Playback was handed off to another endpoint. */
        val handoffActive: Boolean = false,
        /** The active control endpoint is this device. */
        val localEndpoint: Boolean = true,
    )

    fun denialReasonForLookup(context: Context): String? = when {
        !context.trackLocal -> "NOT_LOCAL_TRACK"
        !context.receiptMatches || !context.uriMatches -> "TRACK_RECEIPT_CHANGED"
        !context.scopeUnchanged -> "LIBRARY_SCOPE_CHANGED"
        !context.revisionUnchanged -> "LIBRARY_ROOT_CHANGED"
        !context.dialogOpen -> "DIALOG_CLOSED"
        else -> null
    }

    /**
     * Read-only lookup authorization. Playback, pause, another control endpoint or
     * a handoff never block reading public metadata for the same stable local file.
     */
    fun canLookup(context: Context): Boolean = denialReasonForLookup(context) == null

    fun denialReasonForMutation(context: Context): String? = when {
        denialReasonForLookup(context) != null -> denialReasonForLookup(context)
        !context.localEndpoint -> "NOT_LOCAL_ENDPOINT"
        context.handoffActive -> "PLAYBACK_HANDOFF_ACTIVE"
        context.remoteSessionActive -> "REMOTE_SESSION_ACTIVE"
        context.trackSelectedInPlayer -> "PLAYER_HOLDS_DESCRIPTOR"
        else -> null
    }

    /**
     * Physical file authorization. Every guard of [canLookup] PLUS exclusive local
     * ownership of the file. This contract must never be relaxed for a lookup.
     */
    fun canMutateAudioFile(context: Context): Boolean =
        denialReasonForMutation(context) == null
}
