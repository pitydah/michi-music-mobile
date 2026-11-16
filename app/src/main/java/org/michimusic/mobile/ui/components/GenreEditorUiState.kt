package org.michimusic.mobile.ui.components

import org.michimusic.core.intelligence.MichiGenreNormalizationPolicy
import org.michimusic.core.intelligence.MichiGenrePreviewPolicy
import org.michimusic.data.intelligence.MichiGenreTagWriteGateway
import org.michimusic.data.repository.ExtendedTagUnavailableReason
import org.michimusic.mobile.intelligence.MichiGenreMusicBrainzGateway

/**
 * K48-R06 visible stage of the single genre editor.
 *
 * The stage is derived from observed evidence, it is never persisted and it never
 * modifies the file truth: a lookup in flight cannot turn "tags unverified" into
 * "no genre", and a preview cannot turn a proposal into a stored value.
 *
 * [LOCAL_LOADING] and the eleven states below keep the compatibility gate with the
 * twelve [MichiGenreEditorState] values of the existing contract: every stage maps
 * onto exactly one contract state, and every contract state is reachable.
 */
internal enum class GenreEditorPhase {
    LOCAL_LOADING,
    LOCAL_VERIFIED,
    LOCAL_UNVERIFIED,
    NETWORK_CONSENT,
    NETWORK_LOADING,
    NETWORK_RESULTS,
    NETWORK_EMPTY,
    NETWORK_ERROR,
    PREVIEW,
    PREVIEW_INVALID,
    WRITE_DISABLED,
}

/**
 * Every user action the editor accepts. The coordinator is the only consumer, so a
 * pure content composable never needs Koin, Room, Media3 or a network client: it
 * renders [GenreEditorUiState] and emits these intents.
 */
internal sealed interface GenreEditorIntent {
    data object Close : GenreEditorIntent

    /** Opens the inline consent stage for the strict title/artist/album search. */
    data object SearchSuggestions : GenreEditorIntent

    /** Explicit, one-shot authorization for the pending disclosure. */
    data object ConfirmNetworkRequest : GenreEditorIntent

    /** Dismisses the consent stage without any HTTP request. */
    data object CancelNetworkRequest : GenreEditorIntent

    /** Drops the in-flight lookup and publishes no late result. */
    data object CancelLookup : GenreEditorIntent

    /** Second, independent consent stage: search again without the album. */
    data object OfferRelaxedSearch : GenreEditorIntent

    /**
     * Selects a recording by its canonical MBID. The identifier is used instead of a
     * list position because duplicated titles are legitimate provider output.
     */
    data class SelectRecording(val recordingId: String) : GenreEditorIntent

    data class AddGenre(val value: String) : GenreEditorIntent

    data class RemoveGenre(val index: Int) : GenreEditorIntent

    data class ReplaceGenre(val index: Int, val value: String) : GenreEditorIntent

    /** Selects the position a later replacement applies to. */
    data class SelectReplaceTarget(val index: Int) : GenreEditorIntent

    data class EditDraft(val value: String) : GenreEditorIntent

    data object OpenPreview : GenreEditorIntent

    data object TogglePreview : GenreEditorIntent

    data object ResetPreview : GenreEditorIntent

    data object ToggleTechnicalDetails : GenreEditorIntent

    data object ToggleEvidenceReason : GenreEditorIntent

    /** Requests the platform write grant; the host owns the Android launcher. */
    data object RequestWrite : GenreEditorIntent

    data object DismissWrite : GenreEditorIntent

    data object RequestRecovery : GenreEditorIntent

    data object DismissRecovery : GenreEditorIntent

    /** Drops the one-shot unknown-bytes proof; nothing was authorized. */
    data object DismissUnknownRecovery : GenreEditorIntent

    /** The host resolved a platform request; [granted] is the launcher result. */
    data class ResolveSystemRequest(val granted: Boolean) : GenreEditorIntent

    /** A platform permission is already held, so the operation runs immediately. */
    data object SystemPermissionAlreadyHeld : GenreEditorIntent

    /** The platform request could not even be created (provider refused). */
    data object SystemRequestUnavailable : GenreEditorIntent
}

/**
 * Platform work the host must perform because the coordinator cannot own an Android
 * launcher. Consumed exactly once: dispatching [GenreEditorIntent.ResolveSystemRequest]
 * clears it, so a recomposition cannot repeat a destructive authorization.
 */
internal enum class GenreEditorSystemRequest {
    WRITE_PERMISSION,
    RECOVERY_PERMISSION,
    UNKNOWN_BYTES_RECOVERY_PERMISSION,
}

/** One-shot proof that the caller saw the exact unexpected bytes at this prompt. */
internal data class GenreEditorUnknownRecovery(
    val bytes: Long,
    val sha256: String,
)

/**
 * The single immutable editor state.
 *
 * There is exactly one write access facet ([status]`write`) and it is derived from
 * [MichiGenreEditorContract], so there is no second, competing "can write" flag.
 */
internal data class GenreEditorUiState(
    val target: MichiGenreEditorTarget,
    val evidenceReason: ExtendedTagUnavailableReason?,
    val libraryClassification: String?,
    val classificationHint: String,
    val localReadPending: Boolean = false,
    val lookup: MichiGenreLookupPhase = MichiGenreLookupPhase.IDLE,
    val preview: MichiGenrePreviewPolicy.Preview,
    val draft: String = "",
    val replaceIndex: Int? = null,
    val matches: List<MichiGenreMusicBrainzGateway.Choice> = emptyList(),
    val selectedRecording: MichiGenreNormalizationPolicy.RecordingCandidate? = null,
    val proposals: List<MichiGenreNormalizationPolicy.GenreProposal> = emptyList(),
    val pendingConsent: GenreNetworkConsent? = null,
    val relaxedSearchOffered: Boolean = false,
    val message: String? = null,
    val busy: Boolean = false,
    val technicalDetailsOpen: Boolean = false,
    val evidenceReasonOpen: Boolean = false,
    val draftPreviewOpen: Boolean = false,
    val recovery: MichiGenreTagWriteGateway.RecoveryStatus? = null,
    val confirmWrite: Boolean = false,
    val confirmRecovery: Boolean = false,
    val unknownRecovery: GenreEditorUnknownRecovery? = null,
    val confirmUnknownRecovery: Boolean = false,
    val systemRequest: GenreEditorSystemRequest? = null,
    val writesCertified: Boolean = MichiGenreWriteCertification.WRITES_CERTIFIED,
    val descriptorOpenInPlayer: Boolean = false,
) {
    val tagsVerified: Boolean get() = target.tagsVerified

    val embeddedGenres: List<String> get() = target.embeddedGenres

    val trackTitle: String get() = target.track.title

    val trackArtist: String get() = target.track.artist

    /** Prepared strict search, or null when the identity is not usable. */
    val searchIntent: MichiGenreNormalizationPolicy.SearchIntent? by lazy(LazyThreadSafetyMode.NONE) {
        MichiGenreNormalizationPolicy.prepareSearch(
            target.track.title,
            target.track.artist,
            target.track.album,
        )
    }

    val status: MichiGenreEditorStatus
        get() = MichiGenreEditorContract.status(
            tagsVerified = target.tagsVerified,
            verifiedGenres = target.embeddedGenres,
            previewModified = preview.isModified,
            lookup = lookup,
            writesCertified = writesCertified,
            descriptorOpenInPlayer = descriptorOpenInPlayer,
            recoveryPending = recovery is MichiGenreTagWriteGateway.RecoveryStatus.Pending,
        )

    /** The twelve-state contract value this state publishes today. */
    val contractState: MichiGenreEditorState get() = status.state

    /**
     * Visible stage. Consent outranks loading because it is the only thing the reader
     * can act on; write-disabled is the resting stage while certification is false.
     */
    val phase: GenreEditorPhase
        get() = when {
            localReadPending -> GenreEditorPhase.LOCAL_LOADING
            pendingConsent != null -> GenreEditorPhase.NETWORK_CONSENT
            lookup == MichiGenreLookupPhase.PENDING -> GenreEditorPhase.NETWORK_LOADING
            lookup == MichiGenreLookupPhase.ERROR -> GenreEditorPhase.NETWORK_ERROR
            lookup == MichiGenreLookupPhase.CANCELLED -> GenreEditorPhase.NETWORK_EMPTY
            lookup == MichiGenreLookupPhase.RESULTS && matches.isEmpty() &&
                proposals.isEmpty() -> GenreEditorPhase.NETWORK_EMPTY
            lookup == MichiGenreLookupPhase.RESULTS -> GenreEditorPhase.NETWORK_RESULTS
            preview.structuralError != null -> GenreEditorPhase.PREVIEW_INVALID
            preview.isModified -> GenreEditorPhase.PREVIEW
            !target.tagsVerified -> GenreEditorPhase.LOCAL_UNVERIFIED
            !writesCertified -> GenreEditorPhase.WRITE_DISABLED
            else -> GenreEditorPhase.LOCAL_VERIFIED
        }

    val lookupInFlight: Boolean get() = lookup == MichiGenreLookupPhase.PENDING

    /** The pending recovery entry belongs to THIS track and THIS uri, or is null. */
    val pendingRecoveryForThisTrack: MichiGenreTagWriteGateway.RecoveryStatus.Pending?
        get() = (recovery as? MichiGenreTagWriteGateway.RecoveryStatus.Pending)?.takeIf {
            it.sourceId == target.sourceId && it.trackId == target.trackId && it.uri == target.filepath
        }

    val primaryActionLabel: String
        get() = when {
            busy -> "Procesando…"
            else -> MichiGenreEditorContract.primaryActionLabel(status.write)
        }
}
