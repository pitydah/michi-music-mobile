package org.michimusic.mobile.artist

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.artist.artwork.ArtistEnrichmentPrimaryAction
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.ui.theme.MichiTheme

/**
 * K48-R07 §9.5: a verified local photo must actually be visible. The previous summary
 * only described the slot as text, so this asserts the rendered thumbnail - and that a
 * slot without verified bytes renders a neutral monogram instead of something that
 * could pass for a recovered photograph.
 */
@RunWith(AndroidJUnit4::class)
class ArtistEnrichmentSummaryContentInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aVerifiedLocalPhotoIsDrawnAndAMissingOneIsNotFaked() {
        val file = verifiedPng()
        compose.setContent {
            MichiTheme {
                ArtistEnrichmentSummaryContent(
                    uiState = state(
                        mosaic = slot(ArtworkSlot.ARTIST_THUMB, "FOTO DE MOSAICO")
                            .copy(
                                availability = ArtistPhotoAvailability.AVAILABLE,
                                statusLabel = "Disponible",
                                imagePath = file.absolutePath,
                                attribution = "Test Author",
                                license = "CC BY 2.0",
                            ),
                        card = slot(ArtworkSlot.ARTIST_HERO, "FOTO DE FICHA"),
                    ),
                    onChoosePhoto = {},
                    onPrimaryAction = {},
                )
            }
        }

        compose.onNodeWithTag("artist_artwork_thumbnail_ARTIST_THUMB").assertIsDisplayed()
        compose.onNodeWithTag("artist_artwork_placeholder_ARTIST_THUMB").assertDoesNotExist()
        // Provenance is printed only next to a real image, and the author and the licence
        // get a line each so the licence is never the part that gets ellipsised.
        compose.onNodeWithText("Test Author").assertIsDisplayed()
        compose.onNodeWithText("CC BY 2.0").assertIsDisplayed()

        // The card has no verified bytes, so it must stay a monogram.
        compose.onNodeWithTag("artist_artwork_placeholder_ARTIST_HERO").assertIsDisplayed()
        compose.onNodeWithTag("artist_artwork_thumbnail_ARTIST_HERO").assertDoesNotExist()
        compose.onNodeWithText("Falta la foto de ficha.").assertIsDisplayed()
    }

    private fun verifiedPng(): File {
        val directory = File(context.filesDir, "artist-artwork").apply { mkdirs() }
        val file = File(directory, "summary-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun slot(slot: ArtworkSlot, title: String) = ArtistPhotoSlot(
        slot = slot,
        title = title,
        availability = ArtistPhotoAvailability.PENDING,
        statusLabel = "Pendiente",
    )

    private fun state(mosaic: ArtistPhotoSlot, card: ArtistPhotoSlot) = ArtistEnrichmentUiState(
        artistName = "Midnight Oil",
        heading = "Fotografías de 'Midnight Oil'",
        identityConfirmed = true,
        identityLabel = "Identidad musical confirmada",
        identityDetail = null,
        mosaic = mosaic,
        card = card,
        primaryAction = ArtistEnrichmentPrimaryAction.SearchMissingPhotos,
        primaryActionLabel = "Buscar fotos faltantes",
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
}
