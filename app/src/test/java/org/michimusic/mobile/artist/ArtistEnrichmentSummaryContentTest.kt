package org.michimusic.mobile.artist

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.artist.artwork.ArtistEnrichmentPrimaryAction
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * K48-R07: [ArtistEnrichmentSummaryContent] must answer the photo question with no Koin,
 * Room, Media3 or network at all. The previous panel only printed text here, which is the
 * defect this contract exists to prevent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ArtistEnrichmentSummaryContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val actions = mutableListOf<ArtistEnrichmentPrimaryAction>()
    private var chooseRequests = 0

    private fun render(state: ArtistEnrichmentUiState) {
        compose.setContent {
            ArtistEnrichmentSummaryContent(
                uiState = state,
                onChoosePhoto = { chooseRequests++ },
                onPrimaryAction = { actions += it },
            )
        }
    }

    @Test
    fun twoMissingPhotosSayBothAreMissingAndShowTwoSlots() {
        render(state(mosaic = pending(ArtworkSlot.ARTIST_THUMB), card = pending(ArtworkSlot.ARTIST_HERO)))

        compose.onNodeWithTag("artist_artwork_missing_summary")
            .assertTextEquals("Faltan las dos fotografías.")
        compose.onNodeWithTag("artist_artwork_placeholder_ARTIST_THUMB").assertIsDisplayed()
        compose.onNodeWithTag("artist_artwork_placeholder_ARTIST_HERO").assertIsDisplayed()
    }

    @Test
    fun aSlotWithoutVerifiedBytesNeverRendersAThumbnail() {
        render(state(mosaic = pending(ArtworkSlot.ARTIST_THUMB), card = pending(ArtworkSlot.ARTIST_HERO)))

        compose.onNodeWithTag("artist_artwork_thumbnail_ARTIST_THUMB").assertDoesNotExist()
        compose.onNodeWithTag("artist_artwork_thumbnail_ARTIST_HERO").assertDoesNotExist()
    }

    @Test
    fun thePrimaryActionRunsTheActionTheUserRead() {
        val review = state(
            mosaic = pending(ArtworkSlot.ARTIST_THUMB),
            card = pending(ArtworkSlot.ARTIST_HERO),
            primaryAction = ArtistEnrichmentPrimaryAction.ReviewIdentity,
            primaryActionLabel = "Revisar identidad",
        )
        render(review)

        compose.onNodeWithText("Revisar identidad").assertIsDisplayed()
        compose.onNodeWithTag("artist_artwork_primary_action").performClick()

        assertEquals(listOf(ArtistEnrichmentPrimaryAction.ReviewIdentity), actions)
    }

    @Test
    fun anEmptyActionDisablesThePrimaryButton() {
        render(
            state(
                mosaic = pending(ArtworkSlot.ARTIST_THUMB),
                card = pending(ArtworkSlot.ARTIST_HERO),
                primaryAction = ArtistEnrichmentPrimaryAction.None,
                primaryActionLabel = "",
            ),
        )

        compose.onNodeWithTag("artist_artwork_primary_action").assertIsNotEnabled()
    }

    @Test
    fun aMissingSlotOffersItsOwnExplicitChoice() {
        render(state(mosaic = pending(ArtworkSlot.ARTIST_THUMB), card = pending(ArtworkSlot.ARTIST_HERO)))

        compose.onNodeWithTag("artist_artwork_slot_action_ARTIST_THUMB").performClick()
        compose.onNodeWithTag("artist_artwork_slot_action_ARTIST_HERO").performClick()

        assertEquals(2, chooseRequests)
    }

    @Test
    fun aChosenPhotoIsNamedAsChosenAndStopsAskingForAnother() {
        render(
            state(
                mosaic = pending(ArtworkSlot.ARTIST_THUMB).copy(
                    availability = ArtistPhotoAvailability.MANUALLY_CHOSEN,
                    statusLabel = "Elegida por ti",
                    attribution = "Test Author",
                    license = "CC BY 2.0",
                ),
                card = pending(ArtworkSlot.ARTIST_HERO),
            ),
        )

        compose.onNodeWithTag("artist_artwork_slot_state_ARTIST_THUMB")
            .assertTextEquals("Elegida por ti")
        // A chosen photo does not offer the same choice again, and its provenance is
        // only ever printed next to a real image, never as a substitute for one.
        compose.onNodeWithTag("artist_artwork_slot_action_ARTIST_THUMB").assertDoesNotExist()
        compose.onNodeWithTag("artist_artwork_missing_summary")
            .assertTextEquals("Falta la foto de ficha.")
    }

    @Test
    fun aBlockedSlotKeepsItsRealReasonVisible() {
        render(
            state(
                mosaic = pending(ArtworkSlot.ARTIST_THUMB).copy(
                    availability = ArtistPhotoAvailability.BLOCKED,
                    statusLabel = "Bloqueada por identidad",
                    detail = "Confirma la identidad musical para buscar fotografías.",
                ),
                card = pending(ArtworkSlot.ARTIST_HERO),
            ),
        )

        compose.onNodeWithText("Confirma la identidad musical para buscar fotografías.")
            .assertIsDisplayed()
        compose.onNodeWithTag("artist_artwork_slot_state_ARTIST_THUMB")
            .assertTextEquals("Bloqueada por identidad")
    }

    private fun state(
        mosaic: ArtistPhotoSlot,
        card: ArtistPhotoSlot = pending(ArtworkSlot.ARTIST_HERO),
        primaryAction: ArtistEnrichmentPrimaryAction =
            ArtistEnrichmentPrimaryAction.SearchMissingPhotos,
        primaryActionLabel: String = "Buscar fotos faltantes",
    ) = ArtistEnrichmentUiState(
        artistName = "Til Tuesday",
        heading = "Fotografías de 'Til Tuesday",
        identityConfirmed = false,
        identityLabel = "Identidad musical pendiente",
        identityDetail = null,
        mosaic = mosaic,
        card = card,
        primaryAction = primaryAction,
        primaryActionLabel = primaryActionLabel,
        searchActive = false,
        searchMessage = null,
        coverageRows = emptyList(),
        providerRows = emptyList(),
        diagnosed = false,
        delete = EnrichmentDeleteState.Idle,
        persistence = ArtistPersistencePresentation(message = null, failed = false),
        canPersist = true,
        hasAlbumMembership = true,
        sections = ArtistEnrichmentSection.NONE,
        albumCount = 1,
    )

    private fun pending(slot: ArtworkSlot) = ArtistPhotoSlot(
        slot = slot,
        title = if (slot == ArtworkSlot.ARTIST_THUMB) "FOTO DE MOSAICO" else "FOTO DE FICHA",
        availability = ArtistPhotoAvailability.PENDING,
        statusLabel = "Pendiente",
    )
}
