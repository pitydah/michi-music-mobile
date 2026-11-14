package org.michimusic.core.intelligence

/**
 * Consent policy for a POSSIBLE future external provider (including Gemini).
 * Pure Kotlin: NO HTTP, SDK, persistence, telemetry, network, token generation or
 * automatic disclosure. This decision is NOT a bearer capability. A real egress
 * gateway must independently validate the typed outbound DTO, exact bytes,
 * origin, destination and a truly single-use authorization at send time.
 */
object MichiSharingConsentPolicy {
    enum class PrivacyMode { MAXIMUM, LIMITED_SHARING, CUSTOM_SHARING }

    /** Permissions are separate from profile mode; no single 'share library' flag. */
    enum class DataScope {
        PUBLIC_AUDIO_INFORMATION,
        USER_SELECTED_GENRE_LABELS,
        USER_SELECTED_TRACK_METADATA,
        USER_SELECTED_PLAYLIST_ENTRIES,
        USER_SUPPLIED_PLAYLIST_REFERENCE,
        // Deliberately unshareable via this contract, regardless of mode:
        WHOLE_LIBRARY, LISTENING_HISTORY, TASTE_PROFILE, FAVORITES,
        FILE_PATHS_OR_URIS, AUDIO_BYTES, ARTWORK, CREDENTIALS,
        DEVICE_OR_PERSON_IDENTIFIERS, DIAGNOSTIC_LOGS,
    }

    enum class Denial {
        MAXIMUM_PRIVACY, PROVIDER_DISABLED, NOT_USER_INITIATED,
        PREVIEW_NOT_CONFIRMED, ONE_SHOT_CONFIRMATION_MISSING,
        EMPTY_OR_FORBIDDEN_SCOPE, SCOPE_NOT_ALLOWED_BY_MODE,
        USER_SELECTION_NOT_PROVEN, PAYLOAD_TOO_LARGE,
    }

    data class Request(
        val mode: PrivacyMode = PrivacyMode.MAXIMUM,
        val providerEnabled: Boolean = false,
        val userInitiated: Boolean = false,
        val exactPreviewConfirmed: Boolean = false,
        val oneShotConfirmed: Boolean = false,
        val dataScopes: Set<DataScope> = emptySet(),
        val selectionVerifiedByUi: Boolean = false,
        val selectedItemCount: Int = 0,
        val serializedByteCount: Int = 0,
    )

    sealed interface Decision {
        data class Denied(val reason: Denial) : Decision
        data class EligibleForOneSend(val permittedScopes: Set<DataScope>) : Decision
    }

    const val MAX_SELECTED_ITEMS = 25
    const val MAX_BYTES = 4_096

    private val limited = setOf(
        DataScope.PUBLIC_AUDIO_INFORMATION,
        DataScope.USER_SELECTED_GENRE_LABELS,
    )
    private val custom = limited + setOf(
        DataScope.USER_SELECTED_TRACK_METADATA,
        DataScope.USER_SELECTED_PLAYLIST_ENTRIES,
        DataScope.USER_SUPPLIED_PLAYLIST_REFERENCE,
    )

    fun evaluate(request: Request): Decision {
        if (request.mode == PrivacyMode.MAXIMUM) return Decision.Denied(Denial.MAXIMUM_PRIVACY)
        if (!request.providerEnabled) return Decision.Denied(Denial.PROVIDER_DISABLED)
        if (!request.userInitiated) return Decision.Denied(Denial.NOT_USER_INITIATED)
        if (!request.exactPreviewConfirmed) return Decision.Denied(Denial.PREVIEW_NOT_CONFIRMED)
        if (!request.oneShotConfirmed) return Decision.Denied(Denial.ONE_SHOT_CONFIRMATION_MISSING)
        if (request.dataScopes.isEmpty()) return Decision.Denied(Denial.EMPTY_OR_FORBIDDEN_SCOPE)
        val allowed = when (request.mode) {
            PrivacyMode.MAXIMUM -> emptySet() // checked above
            PrivacyMode.LIMITED_SHARING -> limited
            PrivacyMode.CUSTOM_SHARING -> custom
        }
        // Reject entire request, not just blocked fields. No partial leak on mixed payloads.
        if (!allowed.containsAll(request.dataScopes))
            return Decision.Denied(Denial.SCOPE_NOT_ALLOWED_BY_MODE)
        val privateSelection = request.dataScopes.any { it != DataScope.PUBLIC_AUDIO_INFORMATION }
        if (privateSelection && (!request.selectionVerifiedByUi || request.selectedItemCount < 1))
            return Decision.Denied(Denial.USER_SELECTION_NOT_PROVEN)
        if (request.selectedItemCount !in 0..MAX_SELECTED_ITEMS ||
            request.serializedByteCount !in 1..MAX_BYTES
        ) return Decision.Denied(Denial.PAYLOAD_TOO_LARGE)
        // Never return any music data or free-form prompt from the privacy policy.
        return Decision.EligibleForOneSend(request.dataScopes.toSet())
    }
}
