package org.michimusic.mobile.devices.aircat.experience

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.devices.aircat.AirCatEnrolledHomeAuthority
import org.michimusic.mobile.devices.aircat.AirCatEnrolledHomeSource
import org.michimusic.mobile.devices.aircat.AirCatTransferState
import org.michimusic.mobile.ui.theme.MichiTheme
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A71-S7: the experience surface at every width and font scale it promises.
 *
 * The widths are the four the acceptance contract names (360, 393, 430, 600 dp)
 * and the scales are 1.0 plus the three the contract names (1.3, 1.6, 2.0). Each
 * case is measured by Compose itself: the harness is given a *required* width, so
 * a window narrower than the requested width cannot silently clamp the result and
 * turn a 600 dp case into a 411 dp measurement.
 *
 * Two invariants are asserted for every case:
 * 1. every clickable node the composition produced is at least 48 dp tall;
 * 2. no clickable node extends past the requested width (no horizontal clipping).
 *
 * This is a layout/accessibility check, not a screenshot test: pixels are
 * captured on the instrumented device by [org.michimusic.mobile.devices.aircat
 * .experience.AirCatExperienceOnDeviceCaptureTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp", application = Application::class)
class AirCatExperienceAdaptiveLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private val widths = listOf(360, 393, 430, 600)
    private val fontScales = listOf(1.0f, 1.3f, 1.6f, 2.0f)

    @Test
    fun theHubHoldsEveryWidthAndScale() =
        verifyAirCatSurface(rootTag = "aircat_hub_screen", state = hubState())

    @Test
    fun theSourcePickerHoldsEveryWidthAndScale() =
        verifyAirCatSurface(rootTag = "aircat_source_picker", state = sourcesState())

    @Test
    fun theCatalogueHoldsEveryWidthAndScale() =
        verifyAirCatSurface(rootTag = "aircat_catalog_screen", state = catalogueState())

    @Test
    fun theReviewHoldsEveryWidthAndScale() =
        verifyAirCatSurface(rootTag = "aircat_review_screen", state = reviewState())

    @Test
    fun theTransferHoldsEveryWidthAndScale() =
        verifyAirCatSurface(rootTag = "aircat_transfer_details", state = transferState())

    @Test
    fun theResultHoldsEveryWidthAndScale() =
        verifyAirCatSurface(rootTag = "aircat_result_screen", state = resultState())

    @Test
    fun theTrustInventoryHoldsEveryWidthAndScale() =
        verifyTrustSurface(pending = false)

    @Test
    fun theTrustConfirmationHoldsEveryWidthAndScale() =
        verifyTrustSurface(pending = true)

    /**
     * Renders one AirCat phase and walks the 4x4 matrix. The state is re-driven
     * through a mutable holder because a Compose rule accepts a single
     * `setContent`; changing the holder recomposes the same, real content.
     */
    private fun verifyAirCatSurface(rootTag: String, state: AirCatExperienceState) {
        var width by mutableStateOf(widths.first())
        var scale by mutableStateOf(fontScales.first())

        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                MichiTheme {
                    Box(
                        Modifier
                            .requiredWidth(width.dp)
                            .requiredHeight(1200.dp)
                            .background(SurfaceObsidian)
                            .testTag(HARNESS),
                    ) {
                        AirCatExperienceContent(state = state, onIntent = {})
                    }
                }
            }
        }

        val density = compose.density.density
        val records = mutableListOf<String>()
        for (candidateWidth in widths) {
            for (candidateScale in fontScales) {
                compose.runOnIdle {
                    width = candidateWidth
                    scale = candidateScale
                }
                compose.waitForIdle()

                compose.onNodeWithTag(rootTag).assertExists()

                val harness = compose.onNodeWithTag(HARNESS).fetchSemanticsNode()
                val expectedPx = (candidateWidth * density).roundToInt()
                assertEquals(
                    "the composition measured $candidateWidth dp",
                    expectedPx,
                    harness.size.width,
                )

                val clickables = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
                assertTrue(
                    "$rootTag at ${candidateWidth}dp/${candidateScale}x produced no clickable node",
                    clickables.isNotEmpty(),
                )
                clickables.forEach { node ->
                    val heightDp = node.size.height / density
                    assertTrue(
                        "$rootTag at ${candidateWidth}dp/${candidateScale}x: clickable " +
                            "${node.config} is ${heightDp}dp tall, below the 48dp target",
                        heightDp + 0.5f >= 48f,
                    )
                    // Bounds are relative to the harness, not to the 411dp test
                    // window: at 600dp the harness itself starts at a negative
                    // root offset because Compose centres the overflow.
                    val rightDp = (node.boundsInRoot.right - harness.boundsInRoot.left) / density
                    val leftDp = (node.boundsInRoot.left - harness.boundsInRoot.left) / density
                    assertTrue(
                        "$rootTag at ${candidateWidth}dp/${candidateScale}x: clickable " +
                            "${node.config} spans ${leftDp}dp..${rightDp}dp, outside the " +
                            "requested width",
                        leftDp >= -0.5f && rightDp <= candidateWidth + 0.5f,
                    )
                }
                records += "${candidateWidth}dp/${candidateScale}x:${clickables.size}"
            }
        }
        println("A71_S7_LAYOUT $rootTag ${records.joinToString(",")}")
    }

    private fun verifyTrustSurface(pending: Boolean) {
        var width by mutableStateOf(widths.first())
        var scale by mutableStateOf(fontScales.first())

        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                MichiTheme {
                    Box(
                        Modifier
                            .requiredWidth(width.dp)
                            .requiredHeight(1200.dp)
                            .background(SurfaceObsidian)
                            .testTag(HARNESS),
                    ) {
                        MichiLinkTrustContent(
                            state = trustState(pending),
                            onIntent = {},
                            onLinkHome = {},
                            onClose = {},
                        )
                    }
                }
            }
        }

        val density = compose.density.density
        val records = mutableListOf<String>()
        for (candidateWidth in widths) {
            for (candidateScale in fontScales) {
                compose.runOnIdle {
                    width = candidateWidth
                    scale = candidateScale
                }
                compose.waitForIdle()

                compose.onNodeWithTag("michi_link_trust_screen").assertExists()
                val harness = compose.onNodeWithTag(HARNESS).fetchSemanticsNode()
                assertEquals(
                    "the composition measured $candidateWidth dp",
                    (candidateWidth * density).roundToInt(),
                    harness.size.width,
                )

                val clickables = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
                assertTrue(
                    "trust at ${candidateWidth}dp/${candidateScale}x produced no clickable node",
                    clickables.isNotEmpty(),
                )
                clickables.forEach { node ->
                    val heightDp = node.size.height / density
                    assertTrue(
                        "trust at ${candidateWidth}dp/${candidateScale}x: clickable " +
                            "${node.config} is ${heightDp}dp tall, below the 48dp target",
                        heightDp + 0.5f >= 48f,
                    )
                    val rightDp = (node.boundsInRoot.right - harness.boundsInRoot.left) / density
                    val leftDp = (node.boundsInRoot.left - harness.boundsInRoot.left) / density
                    assertTrue(
                        "trust at ${candidateWidth}dp/${candidateScale}x: clickable " +
                            "${node.config} spans ${leftDp}dp..${rightDp}dp, outside the " +
                            "requested width",
                        leftDp >= -0.5f && rightDp <= candidateWidth + 0.5f,
                    )
                }
                records += "${candidateWidth}dp/${candidateScale}x:${clickables.size}"
            }
        }
        println("A71_S7_LAYOUT trust_pending=$pending ${records.joinToString(",")}")
    }

    private fun hubState() = AirCatExperienceState(phase = AirCatPhase.HUB)

    private fun sourcesState() = AirCatExperienceState(
        phase = AirCatPhase.SOURCES,
        sources = listOf(source("dev-1"), source("dev-2", canExplore = false)),
    )

    private fun catalogueState() = AirCatExperienceState(
        phase = AirCatPhase.CATALOGUE,
        selectedSourceId = "dev-1",
        sources = listOf(source("dev-1")),
        catalogue = catalogue(),
        selection = setOf("rem-1"),
    )

    private fun reviewState() = AirCatExperienceState(
        phase = AirCatPhase.REVIEW,
        selectedSourceId = "dev-1",
        sources = listOf(source("dev-1")),
        catalogue = catalogue(),
        selection = setOf("rem-1"),
        availableBytes = 10_000L,
    )

    private fun transferState() = AirCatExperienceState(
        phase = AirCatPhase.TRANSFER,
        selectedSourceId = "dev-1",
        sources = listOf(source("dev-1")),
        catalogue = catalogue(),
        selection = setOf("rem-1"),
        transfer = AirCatTransferUi(
            transferId = "transfer-1",
            state = AirCatTransferState.TRANSFERRING,
            completedTracks = 1,
            totalTracks = 2,
            transferredBytes = 100L,
            totalBytes = 200L,
            currentTrackTitle = "rem-2",
            paused = false,
            error = null,
        ),
    )

    private fun resultState() = AirCatExperienceState(
        phase = AirCatPhase.RESULT,
        result = AirCatResultUi(
            kind = AirCatResultUi.Kind.COMPLETED,
            title = "Música recibida",
            detail = "2 canciones incorporadas a la biblioteca.",
            tracks = 2,
            bytes = 200L,
        ),
    )

    private fun trustState(pending: Boolean): MichiLinkTrustState {
        val source = AirCatEnrolledHomeSource(
            sourceMichiId = "source-a",
            homeId = HOME_ID,
            pinnedBaseUrl = PINNED_URL,
            tlsSpkiSha256 = "b".repeat(64),
        )
        val state = MichiLinkTrustState(
            loading = false,
            homes = listOf(
                AirCatEnrolledHomeAuthority(
                    homeId = HOME_ID,
                    authorityMichiId = HOME_ID,
                    authorityPublicKeyBase64Url = "authority-public-key",
                    sourceMichiIds = listOf("source-a"),
                    sourceBindingsSha256 = "c".repeat(64),
                ),
            ),
            sources = listOf(source),
        )
        return if (!pending) {
            state
        } else {
            state.copy(pending = MichiLinkTrustPending.Source(source))
        }
    }

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

    private fun track(id: String, album: String) = AirCatTrackUi(
        remoteTrackId = id,
        logicalTrackId = "logical-$id",
        title = id,
        artist = "Artist",
        album = album,
        format = "flac",
        size = 100L,
        sha256 = "a".repeat(64),
    )

    private fun catalogue() = AirCatCatalogueUi(
        sourceDeviceId = "dev-1",
        sourceName = "Micro Server",
        tracks = listOf(track("rem-1", "Album A"), track("rem-2", "Album A")),
    )

    private companion object {
        const val HARNESS = "a71_layout_harness"
        const val HOME_ID = "home-authority-identifier"
        const val PINNED_URL = "https://home.example:8443"
    }
}
