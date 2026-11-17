package org.michimusic.mobile.artist.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K47-ART-01: an empty artwork slot must say *why* it is empty. These tests pin the
 * five causally different states that used to collapse into one generic sentence.
 */
class ArtistArtworkCoveragePolicyTest {

    private fun selection(
        slot: ArtworkSlot,
        manuallyChosen: Boolean = false,
        path: String? = "/data/user/0/app/files/artist/photo.webp",
    ) = ArtistArtworkSelection(
        slot = slot,
        assetId = "asset-${slot.name}",
        confirmedMbid = "8f5b1a3e-0000-0000-0000-000000000000",
        localImagePath = path,
        focus = ArtworkFocus(),
        manuallyChosen = manuallyChosen,
        revision = 1L,
        source = ArtworkSource.COMMONS_P18,
    )

    private fun slotState(
        slot: ArtworkSlot,
        status: ArtworkLoadStatus,
        selection: ArtistArtworkSelection? = null,
        reason: String? = null,
    ) = ArtistArtworkSlotState(slot = slot, selection = selection, status = status, reason = reason)

    private fun ui(
        thumb: ArtistArtworkSlotState = slotState(ArtworkSlot.ARTIST_THUMB, ArtworkLoadStatus.NOT_REQUESTED),
        hero: ArtistArtworkSlotState = slotState(ArtworkSlot.ARTIST_HERO, ArtworkLoadStatus.NOT_REQUESTED),
    ) = ArtistArtworkUiState(thumb = thumb, hero = hero)

    private fun report(
        vararg outcomes: ArtworkProviderOutcome,
        selections: Map<ArtworkSlot, ArtistArtworkSelection> = emptyMap(),
    ) = ArtworkResolutionReport(
        status = ArtworkLoadStatus.NO_IMAGE,
        outcomes = outcomes.toList(),
        selections = selections,
    )

    @Test
    fun noReportIsNotDiagnosedInsteadOfNoMatch() {
        val coverage = ArtistArtworkCoverageUiState.from(
            uiState = ui(),
            report = null,
            identityConfirmed = true,
            verifiedQid = "Q1234",
        )

        assertFalse(coverage.diagnosed)
        assertEquals(2, coverage.slots.size)
        assertTrue(coverage.slots.all { it.state == ArtistArtworkCoverageState.NOT_ATTEMPTED })
        assertTrue(coverage.slots.all { it.statusLabel == "Aún no diagnosticado" })
        assertTrue(coverage.providers.isEmpty())
    }

    @Test
    fun unconfirmedIdentityIsNotTheSameAsNoMatches() {
        val coverage = ArtistArtworkCoverageUiState.from(
            uiState = ui(),
            report = report(ArtworkProviderOutcome(ArtworkSource.COMMONS_P18, ArtworkProviderStatus.SKIPPED)),
            identityConfirmed = false,
            verifiedQid = null,
        )

        assertTrue(coverage.slots.all { it.state == ArtistArtworkCoverageState.IDENTITY_UNVERIFIED })
        assertTrue(coverage.slots.all { it.statusLabel == "Bloqueada por identidad" })
        assertEquals(ArtistEnrichmentPrimaryAction.ReviewIdentity, coverage.primaryAction)
    }

    @Test
    fun confirmedMbidWithoutQidAsksForIdentityVerification() {
        val coverage = ArtistArtworkCoverageUiState.from(
            uiState = ui(),
            report = report(ArtworkProviderOutcome(ArtworkSource.COMMONS_P18, ArtworkProviderStatus.SKIPPED)),
            identityConfirmed = true,
            verifiedQid = null,
        )

        assertTrue(coverage.slots.all { it.state == ArtistArtworkCoverageState.QID_MISSING })
        assertEquals(ArtistEnrichmentPrimaryAction.VerifyWikidataIdentity, coverage.primaryAction)
    }

    @Test
    fun savedPhotosAreOfferedBeforeAnyNewVerificationIsRequested() {
        // K48-R12-04: work already done must not be hidden behind an identity or QID gate.
        val thumbSelection = selection(ArtworkSlot.ARTIST_THUMB)
        val heroSelection = selection(ArtworkSlot.ARTIST_HERO, manuallyChosen = true)
        val coverage = ArtistArtworkCoverageUiState.from(
            uiState = ui(
                thumb = slotState(ArtworkSlot.ARTIST_THUMB, ArtworkLoadStatus.READY, thumbSelection),
                hero = slotState(ArtworkSlot.ARTIST_HERO, ArtworkLoadStatus.READY, heroSelection),
            ),
            report = report(
                ArtworkProviderOutcome(ArtworkSource.COMMONS_P18, ArtworkProviderStatus.AVAILABLE, candidateCount = 1),
                selections = mapOf(
                    ArtworkSlot.ARTIST_THUMB to thumbSelection,
                    ArtworkSlot.ARTIST_HERO to heroSelection,
                ),
            ),
            identityConfirmed = false,
            verifiedQid = null,
        )

        assertEquals(ArtistEnrichmentPrimaryAction.ViewPhotos, coverage.primaryAction)
        assertTrue(coverage.slots.all { it.state.isSatisfied() })
    }

    @Test
    fun aSingleSavedPhotoStillAsksWhoseIdentityItBelongsTo() {
        val thumbSelection = selection(ArtworkSlot.ARTIST_THUMB)
        val coverage = ArtistArtworkCoverageUiState.from(
            uiState = ui(
                thumb = slotState(ArtworkSlot.ARTIST_THUMB, ArtworkLoadStatus.READY, thumbSelection),
            ),
            report = report(
                ArtworkProviderOutcome(ArtworkSource.COMMONS_P18, ArtworkProviderStatus.AVAILABLE, candidateCount = 1),
                selections = mapOf(ArtworkSlot.ARTIST_THUMB to thumbSelection),
            ),
            identityConfirmed = false,
            verifiedQid = null,
        )

        assertEquals(ArtistEnrichmentPrimaryAction.ReviewIdentity, coverage.primaryAction)
    }

    @Test
    fun anEmptySlotListNeverClaimsThePhotosAreThere() {
        val coverage = ArtistArtworkCoverageUiState.from(
            uiState = ui(),
            report = report(),
            identityConfirmed = true,
            verifiedQid = "Q1234",
        )

        assertFalse(coverage.primaryAction == ArtistEnrichmentPrimaryAction.ViewPhotos)
    }

    @Test
    fun bothReadySlotsReportAvailableAndOfferThePhotos() {
        val thumbSelection = selection(ArtworkSlot.ARTIST_THUMB)
        val heroSelection = selection(ArtworkSlot.ARTIST_HERO)
        val coverage = ArtistArtworkCoverageUiState.from(
            uiState = ui(
                thumb = slotState(ArtworkSlot.ARTIST_THUMB, ArtworkLoadStatus.READY, thumbSelection),
                hero = slotState(ArtworkSlot.ARTIST_HERO, ArtworkLoadStatus.READY, heroSelection),
            ),
            report = report(
                ArtworkProviderOutcome(ArtworkSource.COMMONS_P18, ArtworkProviderStatus.AVAILABLE, candidateCount = 1),
                selections = mapOf(
                    ArtworkSlot.ARTIST_THUMB to thumbSelection,
                    ArtworkSlot.ARTIST_HERO to heroSelection,
                ),
            ),
            identityConfirmed = true,
            verifiedQid = "Q1234",
        )

        assertTrue(coverage.slots.all { it.state == ArtistArtworkCoverageState.READY })
        assertTrue(coverage.slots.all { it.statusLabel == "Disponible" })
        assertEquals(ArtistEnrichmentPrimaryAction.ViewPhotos, coverage.primaryAction)
        assertNull(coverage.slots.first().nextAction)
    }

    @Test
    fun manuallyChosenPhotoIsProtectedFromAutomaticReplacement() {
        val manual = selection(ArtworkSlot.ARTIST_THUMB, manuallyChosen = true)
        val coverage = ArtistArtworkCoverageUiState.from(
            uiState = ui(
                thumb = slotState(ArtworkSlot.ARTIST_THUMB, ArtworkLoadStatus.READY, manual),
                hero = slotState(
                    ArtworkSlot.ARTIST_HERO,
                    ArtworkLoadStatus.NO_IMAGE,
                ),
            ),
            report = report(ArtworkProviderOutcome(ArtworkSource.COMMONS_P18, ArtworkProviderStatus.NO_MATCH)),
            identityConfirmed = true,
            verifiedQid = "Q1234",
        )

        assertEquals(ArtistArtworkCoverageState.MANUAL_PROTECTED, coverage.slots[0].state)
        assertEquals("Disponible · elegida por ti", coverage.slots[0].statusLabel)
        assertEquals("Cambiar foto", coverage.slots[0].nextAction)
        assertEquals(ArtistArtworkCoverageState.NO_MATCH, coverage.slots[1].state)
        assertEquals(ArtistEnrichmentPrimaryAction.SearchMissingPhotos, coverage.primaryAction)
    }

    @Test
    fun rejectedLicenseIsNotReportedAsANetworkProblem() {
        val coverage = ArtistArtworkCoverageUiState.from(
            uiState = ui(
                thumb = slotState(ArtworkSlot.ARTIST_THUMB, ArtworkLoadStatus.REJECTED),
                hero = slotState(ArtworkSlot.ARTIST_HERO, ArtworkLoadStatus.REJECTED),
            ),
            report = report(ArtworkProviderOutcome(ArtworkSource.OPENVERSE, ArtworkProviderStatus.AVAILABLE, candidateCount = 3)),
            identityConfirmed = true,
            verifiedQid = "Q1234",
        )

        assertTrue(coverage.slots.all { it.state == ArtistArtworkCoverageState.LICENSE_REJECTED })
        assertEquals("Licencia no admisible", coverage.slots.first().statusLabel)
        assertEquals(ArtistEnrichmentPrimaryAction.ReviewLicense, coverage.primaryAction)
    }

    @Test
    fun providerErrorAndCacheErrorStayDistinct() {
        val failed = ArtistArtworkCoverageUiState.from(
            uiState = ui(
                thumb = slotState(ArtworkSlot.ARTIST_THUMB, ArtworkLoadStatus.PROVIDER_ERROR),
                hero = slotState(ArtworkSlot.ARTIST_HERO, ArtworkLoadStatus.PROVIDER_ERROR),
            ),
            report = report(ArtworkProviderOutcome(ArtworkSource.COMMONS_P18, ArtworkProviderStatus.FAILURE, "timeout")),
            identityConfirmed = true,
            verifiedQid = "Q1234",
        )
        assertEquals(ArtistArtworkCoverageState.NETWORK_FAILED, failed.slots.first().state)
        assertEquals("Error temporal", failed.slots.first().statusLabel)
        assertEquals(ArtistEnrichmentPrimaryAction.RetryProvider, failed.primaryAction)

        val cacheFailed = ArtistArtworkCoverageUiState.from(
            uiState = ui(
                thumb = slotState(ArtworkSlot.ARTIST_THUMB, ArtworkLoadStatus.CACHE_ERROR),
                hero = slotState(ArtworkSlot.ARTIST_HERO, ArtworkLoadStatus.CACHE_ERROR),
            ),
            report = report(ArtworkProviderOutcome(ArtworkSource.COMMONS_P18, ArtworkProviderStatus.AVAILABLE)),
            identityConfirmed = true,
            verifiedQid = "Q1234",
        )
        assertEquals(ArtistArtworkCoverageState.STORAGE_FAILED, cacheFailed.slots.first().state)
    }

    @Test
    fun aReadyStatusWithoutLocalBytesIsNotPresentedAsAvailable() {
        val coverage = ArtistArtworkCoverageUiState.from(
            uiState = ui(
                thumb = slotState(
                    ArtworkSlot.ARTIST_THUMB,
                    ArtworkLoadStatus.READY,
                    selection(ArtworkSlot.ARTIST_THUMB, path = null),
                ),
                hero = slotState(ArtworkSlot.ARTIST_HERO, ArtworkLoadStatus.NO_IMAGE),
            ),
            report = report(ArtworkProviderOutcome(ArtworkSource.COMMONS_P18, ArtworkProviderStatus.AVAILABLE)),
            identityConfirmed = true,
            verifiedQid = "Q1234",
        )

        assertEquals(ArtistArtworkCoverageState.DOWNLOAD_FAILED, coverage.slots[0].state)
    }

    @Test
    fun providersKeepContractOrderAndLabels() {
        val coverage = ArtistArtworkCoverageUiState.from(
            uiState = ui(),
            report = report(
                ArtworkProviderOutcome(ArtworkSource.OPENVERSE, ArtworkProviderStatus.SKIPPED),
                ArtworkProviderOutcome(ArtworkSource.COMMONS_P180, ArtworkProviderStatus.NO_MATCH),
                ArtworkProviderOutcome(ArtworkSource.COMMONS_P18, ArtworkProviderStatus.AVAILABLE, candidateCount = 2),
            ),
            identityConfirmed = true,
            verifiedQid = "Q1234",
        )

        assertEquals(
            listOf("Wikidata P18", "Commons P180", "Openverse · sólo revisión manual"),
            coverage.providers.map { it.label },
        )
        assertEquals("Con resultados", coverage.providers[0].statusLabel)
        assertEquals("Sin coincidencias", coverage.providers[1].statusLabel)
        assertEquals("No intentado", coverage.providers[2].statusLabel)
    }

    @Test
    fun providerReasonsNeverLeakPathsOrUrls() {
        val coverage = ArtistArtworkCoverageUiState.from(
            uiState = ui(),
            report = report(
                ArtworkProviderOutcome(
                    ArtworkSource.COMMONS_P18,
                    ArtworkProviderStatus.FAILURE,
                    "HTTP 503 from https://commons.wikimedia.org/w/api.php",
                ),
                ArtworkProviderOutcome(
                    ArtworkSource.COMMONS_P180,
                    ArtworkProviderStatus.FAILURE,
                    "content://media/external/audio/media/42 missing",
                ),
                ArtworkProviderOutcome(
                    ArtworkSource.WIKIPEDIA_PAGEIMAGE,
                    ArtworkProviderStatus.FAILURE,
                    "El proveedor no respondió",
                ),
            ),
            identityConfirmed = true,
            verifiedQid = "Q1234",
        )

        assertNull(coverage.providers[0].reason)
        assertNull(coverage.providers[1].reason)
        assertEquals("El proveedor no respondió", coverage.providers[2].reason)
    }

    @Test
    fun aProviderFailureIsNamedAsNetworkInsteadOfNoMatch() {
        val coverage = ArtistArtworkCoverageUiState.from(
            uiState = ui(),
            report = report(
                ArtworkProviderOutcome(ArtworkSource.COMMONS_P18, ArtworkProviderStatus.FAILURE, "timeout"),
            ),
            identityConfirmed = true,
            verifiedQid = "Q1234",
        )

        assertTrue(coverage.slots.all { it.state == ArtistArtworkCoverageState.NETWORK_FAILED })
        assertEquals("Error temporal", coverage.slots.first().statusLabel)
    }

    @Test
    fun anAvailableProviderWithoutLocalBytesIsADownloadFailure() {
        val coverage = ArtistArtworkCoverageUiState.from(
            uiState = ui(),
            report = report(
                ArtworkProviderOutcome(ArtworkSource.COMMONS_P18, ArtworkProviderStatus.AVAILABLE),
            ),
            identityConfirmed = true,
            verifiedQid = "Q1234",
        )

        assertTrue(coverage.slots.all { it.state == ArtistArtworkCoverageState.DOWNLOAD_FAILED })
    }

    @Test
    fun missingQidIsDiagnosedEvenWithoutAProviderReceipt() {
        val coverage = ArtistArtworkCoverageUiState.from(
            uiState = ui(),
            report = null,
            identityConfirmed = true,
            verifiedQid = null,
        )

        assertFalse(coverage.diagnosed)
        assertTrue(coverage.slots.all { it.state == ArtistArtworkCoverageState.QID_MISSING })
        assertEquals(ArtistEnrichmentPrimaryAction.VerifyWikidataIdentity, coverage.primaryAction)
    }

    @Test
    fun aLaterFailedSearchNeverReplacesAManualPhoto() {
        val manual = selection(ArtworkSlot.ARTIST_THUMB, manuallyChosen = true)
        val coverage = ArtistArtworkCoverageUiState.from(
            uiState = ui(thumb = slotState(ArtworkSlot.ARTIST_THUMB, ArtworkLoadStatus.READY, manual)),
            report = report(
                ArtworkProviderOutcome(ArtworkSource.OPENVERSE, ArtworkProviderStatus.FAILURE, "offline"),
            ),
            identityConfirmed = true,
            verifiedQid = "Q1234",
        )

        assertEquals(ArtistArtworkCoverageState.MANUAL_PROTECTED, coverage.slots[0].state)
        assertTrue(coverage.slots[0].state.isSatisfied())
    }

    @Test
    fun bothManualPhotosSatisfyTheSlotsAndOfferTheGallery() {
        val coverage = ArtistArtworkCoverageUiState.from(
            uiState = ui(
                thumb = slotState(
                    ArtworkSlot.ARTIST_THUMB, ArtworkLoadStatus.READY,
                    selection(ArtworkSlot.ARTIST_THUMB, manuallyChosen = true),
                ),
                hero = slotState(
                    ArtworkSlot.ARTIST_HERO, ArtworkLoadStatus.READY,
                    selection(ArtworkSlot.ARTIST_HERO, manuallyChosen = true),
                ),
            ),
            report = null,
            identityConfirmed = true,
            verifiedQid = "Q1234",
        )

        assertTrue(coverage.slots.all { it.state.isSatisfied() })
        assertEquals(ArtistEnrichmentPrimaryAction.ViewPhotos, coverage.primaryAction)
    }

    @Test
    fun aFetchingSlotIsInProgressAndNotAFailure() {
        val coverage = ArtistArtworkCoverageUiState.from(
            uiState = ui(thumb = slotState(ArtworkSlot.ARTIST_THUMB, ArtworkLoadStatus.FETCHING)),
            report = null,
            identityConfirmed = true,
            verifiedQid = "Q1234",
        )

        assertEquals(ArtistArtworkCoverageState.NOT_ATTEMPTED, coverage.slots[0].state)
        assertTrue(coverage.slots[0].reason.orEmpty().contains("sigue en curso"))
        assertFalse(coverage.slots[0].state.isSatisfied())
    }
    /** K48-R03: the label is derived from the action, so they cannot drift apart. */
    @Test
    fun everyPrimaryActionAnnouncesItsOwnOperation() {
        assertEquals("Revisar identidad", ArtistEnrichmentPrimaryAction.ReviewIdentity.label)
        assertEquals(
            "Verificar identidad para fotos",
            ArtistEnrichmentPrimaryAction.VerifyWikidataIdentity.label,
        )
        assertEquals(
            "Buscar fotos faltantes",
            ArtistEnrichmentPrimaryAction.SearchMissingPhotos.label,
        )
        assertEquals(
            "Reintentar cuando haya conexión",
            ArtistEnrichmentPrimaryAction.RetryProvider.label,
        )
        assertEquals("Elegir otra fotografía", ArtistEnrichmentPrimaryAction.ReviewLicense.label)
        assertEquals("Ver fotos", ArtistEnrichmentPrimaryAction.ViewPhotos.label)
        assertEquals(null, ArtistEnrichmentPrimaryAction.None.label)
    }

}
