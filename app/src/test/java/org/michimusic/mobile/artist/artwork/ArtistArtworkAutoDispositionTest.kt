package org.michimusic.mobile.artist.artwork

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistArtworkAutoDispositionTest {
    @Test fun completeOnlyWhenBothSlotsAreReady() {
        assertEquals(AutoArtworkNextAction.COMPLETE,
            ArtistArtworkAutoDisposition.decide(ArtworkLoadStatus.READY, 0))
        assertEquals(AutoArtworkNextAction.DEFER_NO_IMAGE,
            ArtistArtworkAutoDisposition.decide(ArtworkLoadStatus.PARTIAL, 0))
    }

    @Test fun missingOrRejectedArtworkIsDeferredNotSilentlyFinished() {
        for (status in listOf(ArtworkLoadStatus.NO_IMAGE, ArtworkLoadStatus.REJECTED,
            ArtworkLoadStatus.PARTIAL)) {
            assertEquals(AutoArtworkNextAction.DEFER_NO_IMAGE,
                ArtistArtworkAutoDisposition.decide(status, 0))
        }
    }

    @Test fun providerFailuresRetryOnlyWithinFiniteWorkManagerBudget() {
        for (status in listOf(ArtworkLoadStatus.PROVIDER_ERROR, ArtworkLoadStatus.CACHE_ERROR,
            ArtworkLoadStatus.FETCHING, ArtworkLoadStatus.NOT_REQUESTED)) {
            assertEquals(AutoArtworkNextAction.RETRY_WITH_WORK_MANAGER,
                ArtistArtworkAutoDisposition.decide(status, 0))
            assertEquals(AutoArtworkNextAction.RETRY_WITH_WORK_MANAGER,
                ArtistArtworkAutoDisposition.decide(status, 1))
            assertEquals(AutoArtworkNextAction.DEFER_PROVIDER_FAILURE,
                ArtistArtworkAutoDisposition.decide(status, 2))
        }
    }

    @Test fun partialWithTemporaryProviderFailureNeverGetsNoImageSevenDayWait() {
        for (attempt in 0..2) {
            val expected = if (attempt < 2) AutoArtworkNextAction.RETRY_WITH_WORK_MANAGER
                else AutoArtworkNextAction.DEFER_PROVIDER_FAILURE
            assertEquals(expected,
                ArtistArtworkAutoDisposition.decide(ArtworkLoadStatus.PARTIAL, attempt,
                    transientFailure = true))
        }
    }

    @Test fun noImageReportWithDownloadErrorIsTransportFailureNotConfirmedAbsence() {
        assertEquals(AutoArtworkNextAction.RETRY_WITH_WORK_MANAGER,
            ArtistArtworkAutoDisposition.decide(ArtworkLoadStatus.NO_IMAGE, 0, transientFailure = true))
        assertEquals(AutoArtworkNextAction.DEFER_PROVIDER_FAILURE,
            ArtistArtworkAutoDisposition.decide(ArtworkLoadStatus.NO_IMAGE, 2, transientFailure = true))
    }

    @Test fun actualProviderNoMatchDoesNotRetryForever() {
        for (status in listOf(ArtworkLoadStatus.PARTIAL, ArtworkLoadStatus.NO_IMAGE)) {
            assertEquals(AutoArtworkNextAction.DEFER_NO_IMAGE,
                ArtistArtworkAutoDisposition.decide(status, 0, transientFailure = false))
        }
    }

    @Test fun completedImageAndIdentityMismatchOverrideTransientStatus() {
        assertEquals(AutoArtworkNextAction.COMPLETE,
            ArtistArtworkAutoDisposition.decide(ArtworkLoadStatus.READY, 0, transientFailure = true))
        assertEquals(AutoArtworkNextAction.NEEDS_IDENTITY,
            ArtistArtworkAutoDisposition.decide(ArtworkLoadStatus.IDENTITY_MISMATCH, 1, transientFailure = true))
    }

    @Test fun identityMismatchDoesNotRetryExternalProviders() {
        assertEquals(AutoArtworkNextAction.NEEDS_IDENTITY,
            ArtistArtworkAutoDisposition.decide(ArtworkLoadStatus.IDENTITY_MISMATCH, 0))
    }
}
