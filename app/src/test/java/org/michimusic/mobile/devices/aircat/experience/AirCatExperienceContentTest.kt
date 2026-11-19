package org.michimusic.mobile.devices.aircat.experience

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.devices.aircat.AirCatTransferState
import org.michimusic.mobile.ui.theme.MichiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A71: what each screen promises, proven on the composable alone.
 *
 * No image is rendered here on purpose: a real AsyncImage would create Coil's
 * singleton image loader and break every later test that builds the app graph.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp", application = Application::class)
class AirCatExperienceContentTest {

    @get:Rule
    val compose = createComposeRule()

    private companion object {
        private val DIGEST = "a".repeat(64)

        private fun track(id: String, album: String) = AirCatTrackUi(
            remoteTrackId = id,
            logicalTrackId = "logical-$id",
            title = id,
            artist = "Artist",
            album = album,
            format = "flac",
            size = 100L,
            sha256 = DIGEST,
        )

        private fun catalogue() = AirCatCatalogueUi(
            sourceDeviceId = "dev-1",
            sourceName = "Micro Server",
            tracks = listOf(track("rem-1", "Album A"), track("rem-2", "Album A")),
        )

        private fun source(id: String, canExplore: Boolean = true) = AirCatSourceUi(
            deviceId = id,
            name = "Source $id",
            kindLabel = "Servidor de música",
            discovered = false,
            known = true,
            connected = canExplore,
            homeEnrolled = canExplore,
            airCatAdvertised = canExplore,
        )
    }

    private fun render(state: AirCatExperienceState, onIntent: (AirCatIntent) -> Unit = {}) {
        compose.setContent {
            MichiTheme {
                AirCatExperienceContent(state = state, onIntent = onIntent)
            }
        }
    }

    @Test
    fun theHubInvitesToReceiveMusicAndTransfersNothingByItself() {
        var dispatched: AirCatIntent? = null
        render(AirCatExperienceState(phase = AirCatPhase.HUB)) { dispatched = it }

        compose.onNodeWithTag("aircat_hub_screen").assertIsDisplayed()
        compose.onNodeWithTag("aircat_receive_button").assertIsDisplayed().performClick()

        assertEquals(AirCatIntent.OpenSources, dispatched)
    }

    @Test
    fun theSourcePickerOnlyOffersToExploreATeamThatCanExplore() {
        render(
            AirCatExperienceState(
                phase = AirCatPhase.SOURCES,
                sources = listOf(
                    source("dev-1"),
                    source("dev-2", canExplore = false),
                ),
            ),
        )

        compose.onNodeWithTag("aircat_source_picker").assertIsDisplayed()
        compose.onNodeWithTag("aircat_explore_dev-1").assertIsDisplayed()
        compose.onNodeWithTag("aircat_explore_dev-2").assertDoesNotExist()
    }

    @Test
    fun theCatalogueShowsOneSelectableRowPerTrack() {
        render(
            AirCatExperienceState(
                phase = AirCatPhase.CATALOGUE,
                selectedSourceId = "dev-1",
                sources = listOf(source("dev-1")),
                catalogue = catalogue(),
            ),
        )

        compose.onNodeWithTag("aircat_catalog_screen").assertIsDisplayed()
        compose.onNodeWithTag("aircat_album_Album A").assertIsDisplayed()
        assertEquals(
            2,
            compose.onAllNodesWithTag("aircat_track_selection").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun theSelectionFooterBlocksContinueUntilSomethingIsSelected() {
        render(
            AirCatExperienceState(
                phase = AirCatPhase.CATALOGUE,
                selectedSourceId = "dev-1",
                sources = listOf(source("dev-1")),
                catalogue = catalogue(),
            ),
        )

        compose.onNodeWithTag("aircat_selection_footer").assertIsDisplayed()
        compose.onNodeWithText("Continuar").assertIsNotEnabled()
    }

    @Test
    fun theReviewNamesThisPhoneAsTheDestination() {
        render(
            AirCatExperienceState(
                phase = AirCatPhase.REVIEW,
                selectedSourceId = "dev-1",
                sources = listOf(source("dev-1")),
                catalogue = catalogue(),
                selection = setOf("rem-1"),
                availableBytes = 10_000L,
            ),
        )

        compose.onNodeWithTag("aircat_review_screen").assertIsDisplayed()
        compose.onNodeWithText("Este teléfono · Biblioteca sincronizada").assertIsDisplayed()
        compose.onNodeWithTag("aircat_confirm_button").assertIsDisplayed()
    }

    @Test
    fun theTransferScreenShowsRealCountersAndNoFinishedLanguage() {
        render(
            AirCatExperienceState(
                phase = AirCatPhase.TRANSFER,
                selectedSourceId = "dev-1",
                sources = listOf(source("dev-1")),
                catalogue = catalogue(),
                transfer = AirCatTransferUi(
                    transferId = "xfer-1",
                    state = AirCatTransferState.TRANSFERRING,
                    completedTracks = 1,
                    totalTracks = 3,
                    transferredBytes = 100L,
                    totalBytes = 300L,
                    currentTrackTitle = "rem-2",
                    paused = false,
                    error = null,
                ),
            ),
        )

        compose.onNodeWithTag("aircat_transfer_details").assertIsDisplayed()
        compose.onNodeWithTag("aircat_transfer_fraction").assertIsDisplayed()
        assertTrue(
            "a running transfer must not announce a finished one",
            compose.onAllNodesWithTag("aircat_result_screen").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun aNoFilesResultIsNeutralAndCanBeClosed() {
        var dispatched: AirCatIntent? = null
        render(
            AirCatExperienceState(
                phase = AirCatPhase.RESULT,
                sources = listOf(source("dev-1")),
                result = AirCatResultUi(
                    kind = AirCatResultUi.Kind.NO_FILES,
                    title = "No había archivos para transferir",
                    detail = "La selección no tenía pistas que transferir.",
                ),
            ),
        ) { dispatched = it }

        compose.onNodeWithTag("aircat_result_screen").assertIsDisplayed()
        compose.onNodeWithText("No había archivos para transferir").assertIsDisplayed()
        compose.onNodeWithTag("aircat_result_close").performClick()

        assertEquals(AirCatIntent.DismissResult, dispatched)
    }

    @Test
    fun theTrustScreenListsTheHomeAuthorityAndLetsEachSourceGo() {
        var revoked: String? = null
        compose.setContent {
            MichiTheme {
                MichiLinkTrustContent(
                    homeId = "home-authority-identifier",
                    sources = listOf(source("dev-1")),
                    onRevokeSource = { revoked = it },
                    onLinkHome = {},
                )
            }
        }

        compose.onNodeWithTag("michi_link_trust_screen").assertIsDisplayed()
        compose.onNodeWithText("Administrar confianza").assertIsDisplayed()
        compose.onNodeWithText("Desvincular").performClick()

        assertEquals("dev-1", revoked)
    }
}
