package org.michimusic.core.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MichiSharingConsentPolicyTest {
    private val policy = MichiSharingConsentPolicy

    private fun approved(
        mode: MichiSharingConsentPolicy.PrivacyMode,
        scopes: Set<MichiSharingConsentPolicy.DataScope>,
    ) = MichiSharingConsentPolicy.Request(
        mode = mode,
        providerEnabled = true,
        userInitiated = true,
        exactPreviewConfirmed = true,
        oneShotConfirmed = true,
        dataScopes = scopes,
        selectionVerifiedByUi = true,
        selectedItemCount = 1,
        serializedByteCount = 250,
    )

    @Test fun maximumPrivacyDeniesEvenPublicOutbound() {
        val q = approved(MichiSharingConsentPolicy.PrivacyMode.MAXIMUM, setOf(MichiSharingConsentPolicy.DataScope.PUBLIC_AUDIO_INFORMATION))
        assertEquals(MichiSharingConsentPolicy.Decision.Denied(MichiSharingConsentPolicy.Denial.MAXIMUM_PRIVACY), policy.evaluate(q))
    }

    @Test fun userCanChooseLimitedSharingForSelectedGenresOnly() {
        val q = approved(MichiSharingConsentPolicy.PrivacyMode.LIMITED_SHARING, setOf(MichiSharingConsentPolicy.DataScope.USER_SELECTED_GENRE_LABELS))
        assertTrue(policy.evaluate(q) is MichiSharingConsentPolicy.Decision.EligibleForOneSend)
        assertEquals(MichiSharingConsentPolicy.Decision.Denied(MichiSharingConsentPolicy.Denial.SCOPE_NOT_ALLOWED_BY_MODE),
            policy.evaluate(q.copy(dataScopes = setOf(MichiSharingConsentPolicy.DataScope.USER_SELECTED_TRACK_METADATA))))
    }

    @Test fun customStillForbidsHistoryTokensAndWholeLibrary() {
        val q = approved(MichiSharingConsentPolicy.PrivacyMode.CUSTOM_SHARING, setOf(MichiSharingConsentPolicy.DataScope.USER_SELECTED_TRACK_METADATA))
        assertTrue(policy.evaluate(q) is MichiSharingConsentPolicy.Decision.EligibleForOneSend)
        for (blocked in listOf(MichiSharingConsentPolicy.DataScope.WHOLE_LIBRARY, MichiSharingConsentPolicy.DataScope.LISTENING_HISTORY,
                MichiSharingConsentPolicy.DataScope.TASTE_PROFILE, MichiSharingConsentPolicy.DataScope.FILE_PATHS_OR_URIS,
                MichiSharingConsentPolicy.DataScope.AUDIO_BYTES, MichiSharingConsentPolicy.DataScope.CREDENTIALS, MichiSharingConsentPolicy.DataScope.DEVICE_OR_PERSON_IDENTIFIERS)) {
            assertEquals(MichiSharingConsentPolicy.Decision.Denied(MichiSharingConsentPolicy.Denial.SCOPE_NOT_ALLOWED_BY_MODE),
                policy.evaluate(q.copy(dataScopes = setOf(MichiSharingConsentPolicy.DataScope.USER_SELECTED_TRACK_METADATA, blocked))))
        }
    }

    @Test fun providerOptInIsSeparateFromProfileAndOneShot() {
        val q = approved(MichiSharingConsentPolicy.PrivacyMode.CUSTOM_SHARING, setOf(MichiSharingConsentPolicy.DataScope.USER_SELECTED_PLAYLIST_ENTRIES))
        assertEquals(MichiSharingConsentPolicy.Decision.Denied(MichiSharingConsentPolicy.Denial.PROVIDER_DISABLED),
            policy.evaluate(q.copy(providerEnabled = false)))
        assertEquals(MichiSharingConsentPolicy.Decision.Denied(MichiSharingConsentPolicy.Denial.NOT_USER_INITIATED),
            policy.evaluate(q.copy(userInitiated = false)))
        assertEquals(MichiSharingConsentPolicy.Decision.Denied(MichiSharingConsentPolicy.Denial.PREVIEW_NOT_CONFIRMED),
            policy.evaluate(q.copy(exactPreviewConfirmed = false)))
        assertEquals(MichiSharingConsentPolicy.Decision.Denied(MichiSharingConsentPolicy.Denial.ONE_SHOT_CONFIRMATION_MISSING),
            policy.evaluate(q.copy(oneShotConfirmed = false)))
    }

    @Test fun metadataSelectionAndSizeMustBeBounded() {
        val q = approved(MichiSharingConsentPolicy.PrivacyMode.CUSTOM_SHARING, setOf(MichiSharingConsentPolicy.DataScope.USER_SELECTED_TRACK_METADATA))
        assertEquals(MichiSharingConsentPolicy.Decision.Denied(MichiSharingConsentPolicy.Denial.USER_SELECTION_NOT_PROVEN),
            policy.evaluate(q.copy(selectionVerifiedByUi = false)))
        assertEquals(MichiSharingConsentPolicy.Decision.Denied(MichiSharingConsentPolicy.Denial.USER_SELECTION_NOT_PROVEN),
            policy.evaluate(q.copy(selectedItemCount = 0)))
        assertEquals(MichiSharingConsentPolicy.Decision.Denied(MichiSharingConsentPolicy.Denial.PAYLOAD_TOO_LARGE),
            policy.evaluate(q.copy(selectedItemCount = policy.MAX_SELECTED_ITEMS + 1)))
        assertEquals(MichiSharingConsentPolicy.Decision.Denied(MichiSharingConsentPolicy.Denial.PAYLOAD_TOO_LARGE),
            policy.evaluate(q.copy(serializedByteCount = policy.MAX_BYTES + 1)))
    }

    @Test fun requestWithoutScopeNeverAuthorizesAnything() {
        val q = approved(MichiSharingConsentPolicy.PrivacyMode.CUSTOM_SHARING, emptySet())
        assertEquals(MichiSharingConsentPolicy.Decision.Denied(MichiSharingConsentPolicy.Denial.EMPTY_OR_FORBIDDEN_SCOPE), policy.evaluate(q))
    }
}
