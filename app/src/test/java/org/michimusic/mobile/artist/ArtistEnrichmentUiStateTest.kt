package org.michimusic.mobile.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.mobile.artist.artwork.ArtistArtworkCoverageUiState
import org.michimusic.mobile.artist.artwork.ArtistArtworkSelection
import org.michimusic.mobile.artist.artwork.ArtistArtworkSlotState
import org.michimusic.mobile.artist.artwork.ArtistArtworkUiState
import org.michimusic.mobile.artist.artwork.ArtistEnrichmentPrimaryAction
import org.michimusic.mobile.artist.artwork.ArtworkFocus
import org.michimusic.mobile.artist.artwork.ArtworkLoadStatus
import org.michimusic.mobile.artist.artwork.ArtworkProviderOutcome
import org.michimusic.mobile.artist.artwork.ArtworkProviderStatus
import org.michimusic.mobile.artist.artwork.ArtworkResolutionReport
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.artist.artwork.ArtworkSource

/**
 * K48-R07 §9.10: the summary is presentation data, so each visual situation is
 * provable without Compose, Room, Coil or the network.
 */
class ArtistEnrichmentUiStateTest {

    private fun selection(
        slot: ArtworkSlot,
        path: String?,
        status: ArtworkLoadStatus = ArtworkLoadStatus.READY,
        manuallyChosen: Boolean = false,
    ) = ArtistArtworkSelection(
        slot = slot,
        assetId = "asset-${slot.name}",
        confirmedMbid = "5f0f1b1a-0000-4000-8000-000000000001",
        localImagePath = path,
        focus = ArtworkFocus(),
        manuallyChosen = manuallyChosen,
        revision = 1L,
        licenseId = "CC-BY-SA-4.0",
        attribution = "Fotógrafo",
        source = ArtworkSource.COMMONS_P18,
        status = status,
    )

    /**
     * Mirrors exactly how the panel projects controller state into the coverage
     * engine: each slot state carries the status of its own selection.
     */
    private fun artworkUi(vararg selections: ArtistArtworkSelection): ArtistArtworkUiState {
        fun slotOf(slot: ArtworkSlot): ArtistArtworkSlotState {
            val selection = selections.firstOrNull { it.slot == slot }
                ?: return ArtistArtworkSlotState(slot = slot)
            return ArtistArtworkSlotState(
                slot = slot,
                selection = selection,
                status = selection.status,
            )
        }
        return ArtistArtworkUiState(
            thumb = slotOf(ArtworkSlot.ARTIST_THUMB),
            hero = slotOf(ArtworkSlot.ARTIST_HERO),
        )
    }

    private fun presentation(
        state: ArtistEnrichmentState = ArtistEnrichmentState(),
        artwork: ArtistArtworkUiState? = null,
        report: ArtworkResolutionReport? = null,
        identityConfirmed: Boolean = state.profile?.mbid?.isNotBlank() == true,
        verifiedQid: String? = state.profile?.wikidataQid,
        canPersist: Boolean = true,
        albumCount: Int = 1,
    ): ArtistEnrichmentUiState = ArtistEnrichmentUiState.from(
        artistCredit = "'Til Tuesday",
        state = if (canPersist && identityConfirmed && state.profile != null &&
            !state.persisted && state.persistedMbid == null
        ) state.copy(persisted = true, persistedMbid = state.profile.mbid)
        else state,
        coverage = ArtistArtworkCoverageUiState.from(
            uiState = artwork ?: artworkUi(*state.selections.values.toTypedArray()),
            report = report,
            identityConfirmed = identityConfirmed,
            verifiedQid = verifiedQid,
        ),
        canPersist = canPersist,
        albumCount = albumCount,
    )

    private fun profile(mbid: String? = "5f0f1b1a-0000-4000-8000-000000000001", qid: String? = null) =
        ArtistExternalProfile(
            mbid = mbid.orEmpty(),
            name = "'Til Tuesday",
            type = "Group",
            area = "United States",
            wikidataQid = qid,
        )

    @Test
    fun everyCatalogueThatDeclaresNoLicenceSaysSoInTheSummary() {
        // Both manual catalogues publish photographs without a licence. The summary
        // must name that state; an empty line reads as "missing data", which is a
        // different claim and the wrong one.
        val sources = listOf(ArtworkSource.THEAUDIODB, ArtworkSource.FANART_TV)
        sources.forEach { source ->
            val signed = selection(
                ArtworkSlot.ARTIST_THUMB,
                "/data/photo.jpg",
                manuallyChosen = true,
            ).copy(source = source, licenseId = null, licenseUrl = null, attribution = null)
            val presentation = presentation(
                ArtistEnrichmentState(selections = mapOf(ArtworkSlot.ARTIST_THUMB to signed)),
                artwork = artworkUi(signed),
            )
            assertEquals(
                "a $source photograph declares no licence",
                "Licencia no declarada",
                presentation.mosaic.license,
            )
        }
    }

    @Test
    fun `summary without identity says identity is pending`() {
        val ui = presentation()

        assertFalse(ui.identityConfirmed)
        assertEquals("Identidad musical pendiente", ui.identityLabel)
        assertNull(ui.identityDetail)
        assertEquals("Fotografías de 'Til Tuesday", ui.heading)
    }

    @Test fun candidateWithMbidButNoRoomConfirmationRemainsUnconfirmed() {
        val p = profile()
        val ui = presentation(
            state = ArtistEnrichmentState(profile = p, persisted = false),
            identityConfirmed = false,
        )
        assertFalse(ui.identityConfirmed)
        assertEquals("Identidad musical pendiente", ui.identityLabel)
        assertNull(ui.identityDetail)
    }

    @Test
    fun `confirmed MBID is stated and kept for details only`() {
        val ui = presentation(state = ArtistEnrichmentState(profile = profile()))

        assertTrue(ui.identityConfirmed)
        assertEquals("Identidad musical confirmada", ui.identityLabel)
        assertEquals("MBID: 5f0f1b1a-0000-4000-8000-000000000001", ui.identityDetail)
    }

    @Test
    fun `missing QID never invents a Wikidata detail`() {
        val ui = presentation(state = ArtistEnrichmentState(profile = profile(qid = null)))

        assertEquals("MBID: 5f0f1b1a-0000-4000-8000-000000000001", ui.identityDetail)
    }

    @Test
    fun `two pending photographs ask for both`() {
        // A confirmed MBID without a verified Wikidata QID is a pending cause, not a
        // hard failure: the reader is told exactly which pending cause remains.
        val ui = presentation(state = ArtistEnrichmentState(profile = profile()))

        assertEquals(ArtistPhotoAvailability.PENDING, ui.mosaic.availability)
        assertEquals(ArtistPhotoAvailability.PENDING, ui.card.availability)
        assertEquals("Pendiente de identidad Wikidata", ui.mosaic.statusLabel)
        assertEquals("Pendiente de identidad Wikidata", ui.card.statusLabel)
        assertFalse(ui.mosaic.hasThumbnail)
        assertFalse(ui.card.hasThumbnail)
        assertEquals("Faltan las dos fotografías.", ui.missingSummary)
        assertEquals("Foto de mosaico", ui.mosaic.title)
        assertEquals("Foto de ficha", ui.card.title)
    }

    @Test
    fun `an undiagnosed slot reads as pending instead of a diagnosis`() {
        // Identity confirmed and a valid QID, but no provider receipt yet: the slot is
        // pending, never "no match".
        val ui = presentation(
            state = ArtistEnrichmentState(profile = profile(qid = "Q1234")),
        )

        assertEquals(ArtistPhotoAvailability.PENDING, ui.mosaic.availability)
        assertEquals("Pendiente", ui.mosaic.statusLabel)
        assertFalse(ui.diagnosed)
    }

    @Test
    fun `one available photograph reports only the remaining gap`() {
        val thumb = selection(ArtworkSlot.ARTIST_THUMB, "/data/cache/thumb.webp")
        val ui = presentation(
            state = ArtistEnrichmentState(
                profile = profile(),
                selections = mapOf(ArtworkSlot.ARTIST_THUMB to thumb),
            ),
        )

        assertEquals(ArtistPhotoAvailability.AVAILABLE, ui.mosaic.availability)
        assertEquals("Disponible", ui.mosaic.statusLabel)
        assertTrue(ui.mosaic.hasThumbnail)
        assertEquals("/data/cache/thumb.webp", ui.mosaic.imagePath)
        assertEquals(ArtistPhotoAvailability.PENDING, ui.card.availability)
        assertEquals("Falta la foto de ficha.", ui.missingSummary)
        assertFalse(ui.allPhotosAvailable)
    }

    @Test
    fun `both available photographs close the summary question`() {
        val thumb = selection(ArtworkSlot.ARTIST_THUMB, "/data/cache/thumb.webp")
        val hero = selection(ArtworkSlot.ARTIST_HERO, "/data/cache/hero.webp")
        val ui = presentation(
            state = ArtistEnrichmentState(
                profile = profile(),
                selections = mapOf(
                    ArtworkSlot.ARTIST_THUMB to thumb,
                    ArtworkSlot.ARTIST_HERO to hero,
                ),
            ),
        )

        assertTrue(ui.allPhotosAvailable)
        assertNull(ui.missingSummary)
        assertEquals("/data/cache/hero.webp", ui.card.imagePath)
        assertEquals("CC-BY-SA-4.0", ui.card.license)
    }

    @Test
    fun `manually chosen photograph is protected and named as chosen by the user`() {
        val thumb = selection(ArtworkSlot.ARTIST_THUMB, "/data/cache/mine.jpg", manuallyChosen = true)
        val ui = presentation(
            state = ArtistEnrichmentState(
                profile = profile(),
                selections = mapOf(ArtworkSlot.ARTIST_THUMB to thumb),
            ),
            report = ArtworkResolutionReport(
                status = ArtworkLoadStatus.PARTIAL,
                outcomes = listOf(
                    ArtworkProviderOutcome(ArtworkSource.COMMONS_P18, ArtworkProviderStatus.AVAILABLE),
                ),
            ),
        )

        assertEquals(ArtistPhotoAvailability.MANUALLY_CHOSEN, ui.mosaic.availability)
        assertEquals("Elegida por ti", ui.mosaic.statusLabel)
        assertTrue(ui.mosaic.hasThumbnail)
    }

    @Test
    fun `a satisfied label without verified bytes never claims the photograph exists`() {
        // The selection claims READY but no file was verified: the coverage engine
        // reports an incomplete download and no path is published.
        val broken = selection(ArtworkSlot.ARTIST_THUMB, null, status = ArtworkLoadStatus.READY)
        val ui = presentation(
            state = ArtistEnrichmentState(
                profile = profile(),
                selections = mapOf(ArtworkSlot.ARTIST_THUMB to broken),
            ),
        )

        assertFalse(ui.mosaic.hasThumbnail)
        assertNull(ui.mosaic.imagePath)
        assertEquals(ArtistPhotoAvailability.BLOCKED, ui.mosaic.availability)
        assertEquals("Descarga incompleta", ui.mosaic.statusLabel)
        assertNull(ui.mosaic.license)
    }

    @Test
    fun `network failure is a temporary error not a pending photograph`() {
        val failed = selection(ArtworkSlot.ARTIST_THUMB, null, status = ArtworkLoadStatus.PROVIDER_ERROR)
        val ui = presentation(
            state = ArtistEnrichmentState(
                profile = profile(),
                selections = mapOf(ArtworkSlot.ARTIST_THUMB to failed),
            ),
        )

        assertEquals(ArtistPhotoAvailability.TEMPORARY_ERROR, ui.mosaic.availability)
        assertEquals("Error temporal", ui.mosaic.statusLabel)
        assertNull(ui.mosaic.imagePath)
        assertNull(ui.mosaic.detail)
    }

    @Test
    fun `rejected licence blocks the slot with the real reason`() {
        val rejected = selection(ArtworkSlot.ARTIST_THUMB, null, status = ArtworkLoadStatus.REJECTED)
        val ui = presentation(
            state = ArtistEnrichmentState(
                profile = profile(),
                selections = mapOf(ArtworkSlot.ARTIST_THUMB to rejected),
            ),
        )

        assertEquals(ArtistPhotoAvailability.BLOCKED, ui.mosaic.availability)
        assertEquals("Licencia no admisible", ui.mosaic.statusLabel)
        assertFalse(ui.mosaic.isSatisfied)
        assertNull(ui.mosaic.imagePath)
    }

    @Test
    fun `an unverified identity blocks both slots with the identity reason`() {
        val ui = presentation()

        assertEquals("Bloqueada por identidad", ui.mosaic.statusLabel)
        assertEquals(ArtistPhotoAvailability.BLOCKED, ui.mosaic.availability)
    }

    @Test
    fun `a failed portrait search is stated instead of an empty candidate list`() {
        val ui = presentation(
            state = ArtistEnrichmentState(
                profile = profile(),
                portraitSearchFailure = ArtistPortraitFailure.LICENSE_UNVERIFIED,
            ),
        )

        assertEquals("No se pudo verificar la licencia.", ui.searchMessage)
        assertFalse(ui.searchActive)
    }

    @Test
    fun `an in-flight search is reported as active and blocks the primary action`() {
        val ui = presentation(
            state = ArtistEnrichmentState(profile = profile(), searchingPortraits = true),
        )

        assertTrue(ui.searchActive)
        assertTrue(ui.isBusy)
        assertEquals("Buscando fotografías con licencia verificada…", ui.searchMessage)
    }

    @Test
    fun `deleting blocks interaction and is exposed as delete state`() {
        val receipt = EnrichmentDeleteReceipt(
            libraryId = "lib-1",
            artistCredit = "'Til Tuesday",
            albumIds = listOf("album-1"),
            mbid = "5f0f1b1a-0000-4000-8000-000000000001",
            associationGroupKey = "group",
            sequence = 1L,
        )
        val ui = presentation(
            state = ArtistEnrichmentState(
                profile = profile(),
                delete = EnrichmentDeleteState.Deleting(receipt),
            ),
        )

        assertTrue(ui.isBusy)
        assertTrue(ui.delete is EnrichmentDeleteState.Deleting)
    }

    @Test
    fun `persistence failure is stated as a failure`() {
        val ui = presentation(
            state = ArtistEnrichmentState(
                profile = profile(),
                persistenceError = "No fue posible guardar los datos.",
            ),
        )

        assertTrue(ui.persistence.failed)
        assertEquals("No fue posible guardar los datos.", ui.persistence.message)
    }

    @Test
    fun `a profile without persistable scope says it was consulta only`() {
        val ui = presentation(
            state = ArtistEnrichmentState(profile = profile()),
            canPersist = false,
        )

        assertEquals(
            "Solo esta consulta. Cambia a una biblioteca individual para guardar.",
            ui.persistence.message,
        )
    }

    @Test
    fun `primary action label always comes from the typed action`() {
        val ui = presentation(state = ArtistEnrichmentState(profile = profile()))

        assertEquals(
            ui.primaryAction.label ?: "Buscar fotos faltantes",
            ui.primaryActionLabel,
        )
        assertTrue(ui.primaryAction != ArtistEnrichmentPrimaryAction.None)
    }

    @Test
    fun `album membership is only claimed when it is verifiable`() {
        assertEquals(false, presentation(state = ArtistEnrichmentState(profile = profile())).hasAlbumMembership)
        assertEquals(
            true,
            presentation(
                state = ArtistEnrichmentState(profile = profile()),
                albumCount = 3,
            ).hasAlbumMembership,
        )
        assertEquals(
            true,
            presentation(
                state = ArtistEnrichmentState(profile = profile(), unboundAlbumIds = listOf("a")),
            ).hasAlbumMembership,
        )
    }

    @Test
    fun `empty presentation renders no heading quotation bug`() {
        assertEquals("", ArtistEnrichmentUiState.Empty.artistName)
        // A credit that already starts with an apostrophe is not wrapped again.
        assertEquals("Fotografías de 'Til Tuesday", ArtistEnrichmentUiState.heading("'Til Tuesday"))
        assertEquals("Fotografías de 'Til Tuesday'", ArtistEnrichmentUiState.heading("Til Tuesday"))
    }

    /**
     * K48-R12-01: the only effects a primary action may run are local. Nothing in this
     * mapping can reach the provider, so a consent bypass would have to be introduced
     * deliberately as a new effect member.
     */
    @Test
    fun `no primary action starts a provider query on its own`() {
        assertEquals(
            ArtistEnrichmentPanelEffect.RevealIdentity,
            ArtistEnrichmentPrimaryAction.ReviewIdentity.panelEffect(),
        )
        assertEquals(
            ArtistEnrichmentPanelEffect.RevealIdentity,
            ArtistEnrichmentPrimaryAction.VerifyWikidataIdentity.panelEffect(),
        )
        // The three photo actions only open the picker; the disclosure lives there.
        assertEquals(
            ArtistEnrichmentPanelEffect.OpenPicker,
            ArtistEnrichmentPrimaryAction.SearchMissingPhotos.panelEffect(),
        )
        assertEquals(
            ArtistEnrichmentPanelEffect.OpenPicker,
            ArtistEnrichmentPrimaryAction.RetryProvider.panelEffect(),
        )
        assertEquals(
            ArtistEnrichmentPanelEffect.OpenPicker,
            ArtistEnrichmentPrimaryAction.ReviewLicense.panelEffect(),
        )
        assertEquals(
            ArtistEnrichmentPanelEffect.OpenPicker,
            ArtistEnrichmentPrimaryAction.ViewPhotos.panelEffect(),
        )
        assertEquals(
            ArtistEnrichmentPanelEffect.Nothing,
            ArtistEnrichmentPrimaryAction.None.panelEffect(),
        )
        // Every action is accounted for by exactly one of the three local effects.
    }
}
