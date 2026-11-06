package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.artist.ArtistExternalProfile

/**
 * Artist detail sections render only what the local library plus an explicitly
 * confirmed profile can prove: no second enrichment call to action, no album
 * counter, and no invented biography.
 */
@RunWith(AndroidJUnit4::class)
class ArtistDetailSectionsTest {
    @get:Rule val compose = createComposeRule()

    private val numericText = SemanticsMatcher("text is a bare count") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { annotated ->
            annotated.text.isNotEmpty() && annotated.text.all(Char::isDigit)
        } == true
    }

    private val confirmedProfile = ArtistExternalProfile(
        mbid = "0f2a1b3c-4d5e-4f60-8a9b-0c1d2e3f4a5b",
        name = "Midnight Oil",
        biography = "Midnight Oil es una banda de rock australiana formada en 1972.",
        biographySourceUrl = "https://es.wikipedia.org/wiki/Midnight_Oil",
    )

    @Test
    fun discographyHeaderHasNoCountWhileSongsHeaderKeepsItsCount() {
        compose.setContent {
            Column {
                ArtistDetailSectionHeader("DISCOGRAFÍA")
                ArtistDetailSectionHeader("CANCIONES", 4)
            }
        }

        compose.onNodeWithText("DISCOGRAFÍA").assertExists()
        compose.onNodeWithText("CANCIONES").assertExists()
        // The only bare number on screen is the songs count: the discography
        // section cannot show a library size that the product rule removed.
        compose.onAllNodes(numericText).assertCountEquals(1)
        compose.onNodeWithText("4").assertExists()
    }

    @Test
    fun missingBiographyShowsPlaceholderWithoutASecondEnrichmentAction() {
        compose.setContent {
            ArtistBiographySection(
                artistCredit = "Midnight Oil",
                profile = null,
                isStale = false,
                portrait = null,
            )
        }

        compose.onNodeWithTag("artist_detail_no_biography").assertExists()
        compose.onNodeWithText("Consultar Artist Enrichment").assertDoesNotExist()
        compose.onNodeWithText("Revisar en Artist Enrichment").assertDoesNotExist()
    }

    @Test
    fun staleProfileShowsTheNoticeWithoutDuplicatingTheHeaderAction() {
        compose.setContent {
            ArtistBiographySection(
                artistCredit = "Midnight Oil",
                profile = confirmedProfile,
                isStale = true,
                portrait = null,
            )
        }

        compose.onNodeWithTag("artist_detail_stale_notice").assertExists()
        compose.onNodeWithTag("artist_detail_biography").assertExists()
        compose.onNodeWithText("Fuente: Wikipedia").assertExists()
        compose.onNodeWithText("Consultar Artist Enrichment").assertDoesNotExist()
        compose.onNodeWithText("Revisar en Artist Enrichment").assertDoesNotExist()
    }

    @Test
    fun biographyWithoutASourceUrlNeverOffersAnInertSourceLink() {
        compose.setContent {
            ArtistBiographySection(
                artistCredit = "Midnight Oil",
                profile = confirmedProfile.copy(biographySourceUrl = null),
                isStale = false,
                portrait = null,
            )
        }

        compose.onNodeWithTag("artist_detail_biography").assertExists()
        compose.onNodeWithText("Fuente: Wikipedia").assertDoesNotExist()
    }

    @Test
    fun shortBiographyNeverShowsAnInertReadMoreControl() {
        compose.setContent {
            ArtistBiographySection(
                artistCredit = "Midnight Oil",
                profile = confirmedProfile,
                isStale = false,
                portrait = null,
            )
        }

        compose.onNodeWithText("Leer más").assertDoesNotExist()
    }

    @Test
    fun longBiographyRevealsTheDisclosureOnlyWhenTheTextOverflows() {
        val longText = (1..40).joinToString(" ") { "Una banda australiana con una historia extensa." }
        compose.setContent {
            Box(Modifier.width(220.dp)) {
                ArtistBiographySection(
                    artistCredit = "Midnight Oil",
                    profile = confirmedProfile.copy(biography = longText),
                    isStale = false,
                    portrait = null,
                )
            }
        }

        compose.onNodeWithText("Leer más").assertExists()
        compose.onNodeWithText("Leer más").performClick()
        compose.onNodeWithText("Leer menos").assertExists()
    }

    @Test
    fun providerFailureIsNotPresentedAsAnArtistWithoutText() {
        compose.setContent {
            Column {
                ArtistBiographySection(
                    artistCredit = "Midnight Oil",
                    profile = confirmedProfile.copy(
                        biography = null,
                        biographySourceUrl = null,
                        biographyOutcome = org.michimusic.mobile.artist.ArtistMetadataOutcome.HTTP_ERROR,
                    ),
                    isStale = false,
                    portrait = null,
                )
            }
        }

        compose.onNodeWithText("No fue posible consultar la biografía", substring = true).assertExists()
        compose.onNodeWithText("No hay biografía verificable", substring = true).assertDoesNotExist()
    }

    @Test
    fun artistWithoutTextShowsTheLegitimateNoBiographyCopy() {
        compose.setContent {
            ArtistBiographySection(
                artistCredit = "Midnight Oil",
                profile = confirmedProfile.copy(
                    biography = null,
                    biographySourceUrl = null,
                    biographyOutcome = org.michimusic.mobile.artist.ArtistMetadataOutcome.NO_TEXT,
                ),
                isStale = false,
                portrait = null,
            )
        }

        compose.onNodeWithText("No hay biografía verificable", substring = true).assertExists()
    }

    @Test
    fun rejectedPortraitLicenseIsExplainedWithoutAddingACallToAction() {
        compose.setContent {
            ArtistBiographySection(
                artistCredit = "Midnight Oil",
                profile = confirmedProfile.copy(
                    portrait = null,
                    portraitOutcome = org.michimusic.mobile.artist.ArtistMetadataOutcome.NO_ALLOWED_LICENSE,
                ),
                isStale = false,
                portrait = null,
            )
        }

        compose.onNodeWithTag("artist_detail_portrait_status").assertExists()
        compose.onNodeWithText("Sin fotografías verificables", substring = true).assertExists()
        compose.onNodeWithText("Consultar Artist Enrichment").assertDoesNotExist()
        compose.onNodeWithText("Revisar en Artist Enrichment").assertDoesNotExist()
    }

    @Test
    fun biographyDoesNotDisplayPhotoCreditsOrLicenseLinksInline() {
        val art = org.michimusic.mobile.artist.artwork.ArtistArtworkSelection(
            slot = org.michimusic.mobile.artist.artwork.ArtworkSlot.ARTIST_HERO,
            assetId = "sha256:" + "a".repeat(64),
            confirmedMbid = confirmedProfile.mbid,
            localImagePath = "/private/cache/verified-photo.jpg",
            focus = org.michimusic.mobile.artist.artwork.ArtworkFocus(),
            manuallyChosen = false,
            revision = 1L,
            sourcePageUrl = "https://commons.wikimedia.org/wiki/File:Example.jpg",
            licenseId = "CC BY-SA 2.0",
            licenseUrl = "https://creativecommons.org/licenses/by-sa/2.0/",
            attribution = "MarkScottAustinTX",
        )
        compose.setContent {
            Column {
                ArtistBiographySection(
                    artistCredit = "A Flock Of Seagulls",
                    profile = confirmedProfile,
                    isStale = false,
                    portrait = null,
                    artwork = art,
                )
                ArtistDetailSectionHeader("DISCOGRAFÍA")
            }
        }

        compose.onNodeWithTag("artist_detail_biography").assertExists()
        compose.onNodeWithText("Fuente: Wikipedia").assertExists()
        compose.onNodeWithText("DISCOGRAFÍA").assertExists()
        compose.onNodeWithTag("artist_detail_photo_attribution").assertDoesNotExist()
        compose.onNodeWithTag("artist_detail_photo_license").assertDoesNotExist()
        compose.onNodeWithText("Foto:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Consultar licencia de la fotografía").assertDoesNotExist()
        compose.onNodeWithTag("artist_detail_portrait_status").assertDoesNotExist()
    }

    @Test
    fun legacyPortraitDoesNotReintroducePhotoButtonsIntoBiography() {
        val portrait = org.michimusic.mobile.artist.CommonsPortrait(
            imageUrl = "https://upload.wikimedia.org/wikipedia/commons/a/ab/Example.jpg",
            commonsPageUrl = "https://commons.wikimedia.org/wiki/File:Example.jpg",
            license = "CC BY-SA 2.0",
            licenseUrl = "https://creativecommons.org/licenses/by-sa/2.0/",
            attribution = "Author",
        )
        compose.setContent {
            ArtistBiographySection(
                artistCredit = "Midnight Oil",
                profile = confirmedProfile.copy(portrait = portrait),
                isStale = false,
                portrait = portrait,
            )
        }

        compose.onNodeWithTag("artist_detail_photo_attribution").assertDoesNotExist()
        compose.onNodeWithTag("artist_detail_photo_license").assertDoesNotExist()
    }
}
