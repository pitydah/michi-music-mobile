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
import org.michimusic.mobile.devices.aircat.AirCatEnrolledHomeAuthority
import org.michimusic.mobile.devices.aircat.AirCatEnrolledHomeSource
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
        private const val SOURCE_A = "source-a"
        private const val SOURCE_B = "source-b"
        private const val HOME_ID = "home-authority-identifier"
        private const val PINNED_URL = "https://home.example:8443"

        private fun enrolledSource(id: String, url: String = PINNED_URL) = AirCatEnrolledHomeSource(
            sourceMichiId = id,
            homeId = HOME_ID,
            pinnedBaseUrl = url,
            tlsSpkiSha256 = "b".repeat(64),
        )

        private fun enrolledHome(sourceIds: List<String>) = AirCatEnrolledHomeAuthority(
            homeId = HOME_ID,
            authorityMichiId = HOME_ID,
            authorityPublicKeyBase64Url = "authority-public-key",
            sourceMichiIds = sourceIds,
            sourceBindingsSha256 = "c".repeat(64),
        )

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
    fun theTrustScreenListsEveryHomeAndItsPinnedEndpoint() {
        val home = enrolledHome(listOf(SOURCE_A, SOURCE_B))
        renderTrust(
            MichiLinkTrustState(
                loading = false,
                homes = listOf(home),
                sources = listOf(
                    enrolledSource(SOURCE_A),
                    enrolledSource(SOURCE_B, url = "https://home-b.example:8443"),
                ),
            ),
        )

        compose.onNodeWithTag("michi_link_trust_screen").assertIsDisplayed()
        compose.onNodeWithText("Administrar confianza").assertIsDisplayed()
        compose.onNodeWithTag("michi_link_trust_home_${home.homeId}").assertIsDisplayed()
        compose.onNodeWithText("2 orígenes registrados").assertIsDisplayed()
        compose.onNodeWithTag("michi_link_trust_source_$SOURCE_A").assertIsDisplayed()
        compose.onNodeWithText("Endpoint fijado: $PINNED_URL").assertIsDisplayed()
    }

    @Test
    fun askingToRevokeASourceCarriesTheExactSnapshotThatWasDisplayed() {
        var dispatched: MichiLinkTrustIntent? = null
        val source = enrolledSource(SOURCE_A)
        renderTrust(
            MichiLinkTrustState(loading = false, sources = listOf(source)),
            onIntent = { dispatched = it },
        )

        compose.onNodeWithTag("michi_link_trust_source_revoke_$SOURCE_A").performClick()

        assertEquals(MichiLinkTrustIntent.RequestSourceRevocation(source), dispatched)
    }

    @Test
    fun theSecondStepNamesTheImpactAndWaitsForTheExplicitConfirmation() {
        var dispatched: MichiLinkTrustIntent? = null
        val source = enrolledSource(SOURCE_A)
        renderTrust(
            MichiLinkTrustState(
                loading = false,
                sources = listOf(source),
                pending = MichiLinkTrustPending.Source(source),
            ),
            onIntent = { dispatched = it },
        )

        compose.onNodeWithTag("michi_link_trust_confirmation").assertIsDisplayed()
        compose.onNodeWithTag("michi_link_trust_impact").assertIsDisplayed()
        compose.onNodeWithText(
            "Se retirará la confianza Home local del origen ${airCatShortId(SOURCE_A)}.",
            substring = true,
        ).assertIsDisplayed()

        compose.onNodeWithTag("michi_link_trust_confirm").performClick()
        assertEquals(MichiLinkTrustIntent.ConfirmRevocation, dispatched)

        compose.onNodeWithTag("michi_link_trust_cancel").performClick()
        assertEquals(MichiLinkTrustIntent.CancelRevocation, dispatched)
    }

    @Test
    fun revokingAWholeHomeSaysExactlyHowManySourcesItCovers() {
        val home = enrolledHome(listOf(SOURCE_A, SOURCE_B))
        renderTrust(
            MichiLinkTrustState(
                loading = false,
                homes = listOf(home),
                sources = listOf(
                    enrolledSource(SOURCE_A),
                    enrolledSource(SOURCE_B, url = "https://home-b.example:8443"),
                ),
                pending = MichiLinkTrustPending.Home(home),
            ),
        )

        compose.onNodeWithText("y la confianza local de sus 2 orígenes registrados", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun onlyACommittedRevocationIsAllowedToClaimSuccess() {
        val notices = androidx.compose.runtime.mutableStateOf(
            MichiLinkTrustNotice(MichiLinkTrustOutcome.REVOKED, MichiLinkTrustSubject.SOURCE),
        )
        compose.setContent {
            MichiTheme {
                MichiLinkTrustContent(
                    state = MichiLinkTrustState(loading = false, notice = notices.value),
                    onIntent = {},
                    onLinkHome = {},
                    onClose = {},
                )
            }
        }

        compose.onNodeWithTag("michi_link_trust_notice").assertIsDisplayed()
        compose.onNodeWithText("quedó guardado", substring = true).assertIsDisplayed()

        notices.value = MichiLinkTrustNotice(
            MichiLinkTrustOutcome.HOME_CHANGED, MichiLinkTrustSubject.SOURCE,
        )
        compose.onNodeWithText("No se modificó nada", substring = true).assertIsDisplayed()

        notices.value = MichiLinkTrustNotice(
            MichiLinkTrustOutcome.ALREADY_REMOVED, MichiLinkTrustSubject.HOME,
        )
        compose.onNodeWithText("ya no existía", substring = true).assertIsDisplayed()

        notices.value = MichiLinkTrustNotice(
            MichiLinkTrustOutcome.PERSISTENCE_ERROR, MichiLinkTrustSubject.HOME,
        )
        compose.onNodeWithText("No pudimos confirmar que la revocación se guardara", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun anUnreadableKeystoreSaysThatNothingWasModified() {
        var dispatched: MichiLinkTrustIntent? = null
        renderTrust(
            MichiLinkTrustState(
                loading = false,
                loadFailure = MichiLinkTrustLoadFailure.KEYSTORE_UNAVAILABLE,
            ),
            onIntent = { dispatched = it },
        )

        compose.onNodeWithTag("michi_link_trust_read_failed").assertIsDisplayed()
        compose.onNodeWithText("No se modificó ninguna autorización.", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithTag("michi_link_trust_retry").performClick()

        assertEquals(MichiLinkTrustIntent.Refresh, dispatched)
    }

    @Test
    fun theTrustScreenOffersANewHomeWithoutRevokingAnything() {
        var linked = false
        renderTrust(
            MichiLinkTrustState(loading = false),
            onLinkHome = { linked = true },
        )

        compose.onNodeWithTag("michi_link_trust_empty").assertIsDisplayed()
        compose.onNodeWithTag("michi_link_trust_link_home").performClick()

        assertTrue(linked)
    }

    private fun renderTrust(
        state: MichiLinkTrustState,
        onIntent: (MichiLinkTrustIntent) -> Unit = {},
        onLinkHome: () -> Unit = {},
    ) {
        compose.setContent {
            MichiTheme {
                MichiLinkTrustContent(
                    state = state,
                    onIntent = onIntent,
                    onLinkHome = onLinkHome,
                    onClose = {},
                )
            }
        }
    }
}
