package org.michimusic.mobile.devices.aircat.experience

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.theme.MichiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A71-S7: one catalogue of 10 000 tracks, rendered and driven to its last row.
 *
 * The point is not a benchmark number. It is to prove three things a smaller
 * fixture cannot:
 * 1. the list virtualises — the composition holds a window of rows, not 10 000;
 * 2. scrolling to the end reaches the real last track, identified by its remote
 *    id rather than a list position;
 * 3. selecting that last track yields exactly one selected track, so the grant
 *    still covers the user's selection and nothing else.
 *
 * The timings and heap figures printed here are Robolectric JVM measurements.
 * They are recorded for the report as JVM evidence, never presented as device
 * performance numbers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp", application = Application::class)
class AirCatExperienceCatalogueScaleTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tenThousandTracksVirtualiseScrollToTheLastOneAndSelectOnlyIt() {
        val tracks = (0 until 10_000).map { index ->
            AirCatTrackUi(
                remoteTrackId = "rem-$index",
                logicalTrackId = "logical-$index",
                title = "Track $index",
                artist = "Artist ${index % 50}",
                album = "Album ${index / 100}",
                format = "flac",
                size = 1_048_576L,
                sha256 = "a".repeat(64),
            )
        }
        val catalogue = AirCatCatalogueUi(
            sourceDeviceId = "dev-1",
            sourceName = "Micro Server",
            tracks = tracks,
        )

        var selection by mutableStateOf(emptySet<String>())
        compose.setContent {
            MichiTheme {
                Box(Modifier.fillMaxSize()) {
                    AirCatExperienceContent(
                        state = AirCatExperienceState(
                            phase = AirCatPhase.CATALOGUE,
                            selectedSourceId = "dev-1",
                            sources = listOf(source()),
                            catalogue = catalogue,
                            selection = selection,
                        ),
                        onIntent = { intent ->
                            if (intent is AirCatIntent.ToggleTrack) {
                                selection = if (intent.remoteTrackId in selection) {
                                    selection - intent.remoteTrackId
                                } else {
                                    selection + intent.remoteTrackId
                                }
                            }
                        },
                    )
                }
            }
        }

        compose.waitForIdle()
        System.gc()
        val heapBefore = usedHeapBytes()
        val startedAt = System.nanoTime()

        val beforeScroll = composedTrackRows()
        assertTrue(
            "a 10 000-track LazyColumn composed $beforeScroll rows before scrolling",
            beforeScroll in 1..60,
        )

        // 100 albums, each composing one header plus its 100 tracks, so the last
        // track is the last of the 10100 items. The row is then identified by its
        // remote id, never by that index: the index only drives the scroll.
        val lastTrackIndex = 100 * 101 - 1
        compose.onNode(
            hasScrollToIndexAction() and hasAnyDescendant(hasTestTag("aircat_album_Album 0")),
        ).performScrollToIndex(lastTrackIndex)
        compose.waitForIdle()

        val scrollMs = (System.nanoTime() - startedAt) / 1_000_000
        val afterScroll = composedTrackRows()
        assertTrue(
            "after scrolling to the last row the list composed $afterScroll rows",
            afterScroll in 1..60,
        )
        assertTrue(
            "scrolling 10 000 tracks took ${scrollMs}ms, beyond any plausible JVM budget",
            scrollMs < 20_000,
        )
        compose.waitForIdle()
        compose.onNodeWithTag("aircat_track_rem-9999", useUnmergedTree = true).assertExists()
        assertTrue(
            "the last remote track is not displayed after scrolling to it",
            compose.onNodeWithTag("aircat_track_rem-9999", useUnmergedTree = true).isDisplayed(),
        )

        compose.onNodeWithTag("aircat_track_rem-9999", useUnmergedTree = true).performClick()
        compose.waitForIdle()

        // Exactly the last track, not the first and not an album-sized batch.
        compose.onNodeWithText("1 canción seleccionada · 1 MB").assertExists()
        assertEquals(
            "exactly one row must expose the selected state after tapping the last track",
            1,
            compose.onAllNodes(isToggleable() and isOn()).fetchSemanticsNodes().size,
        )

        System.gc()
        val heapAfter = usedHeapBytes()
        println(
            "A71_S7_CATALOGUE_10K tracks=10000 albums=100 " +
                "initialComposedRows=$beforeScroll composedRowsAtEnd=$afterScroll " +
                "scrollMs=$scrollMs jvmHeapDeltaBytes=${heapAfter - heapBefore}",
        )
    }

    @Test
    fun aFilterThatMatchesNothingStillOffersTheEmptyNotice() {
        val tracks = (0 until 10_000).map { index ->
            AirCatTrackUi(
                remoteTrackId = "rem-$index",
                logicalTrackId = "logical-$index",
                title = "Track $index",
                artist = "Artist ${index % 50}",
                album = "Album ${index / 100}",
                format = "flac",
                size = 1_048_576L,
                sha256 = "a".repeat(64),
            )
        }
        compose.setContent {
            MichiTheme {
                AirCatExperienceContent(
                    state = AirCatExperienceState(
                        phase = AirCatPhase.CATALOGUE,
                        selectedSourceId = "dev-1",
                        sources = listOf(source()),
                        catalogue = AirCatCatalogueUi("dev-1", "Micro Server", tracks),
                        query = "no-such-track-anywhere",
                    ),
                    onIntent = {},
                )
            }
        }

        compose.waitForIdle()
        compose.onNodeWithText("No hay pistas que coincidan con el filtro.").assertExists()
        assertEquals(
            "a filter matching nothing must compose no track rows",
            0,
            composedTrackRows(),
        )
        assertEquals(
            "a filter matching nothing must not select anything",
            1,
            compose.onAllNodesWithText("0 canciones seleccionadas · 0 MB").fetchSemanticsNodes().size,
        )
    }

    private fun composedTrackRows(): Int =
        compose.onAllNodesWithTag("aircat_track_selection").fetchSemanticsNodes().size

    private fun usedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun source() = AirCatSourceUi(
        deviceId = "dev-1",
        name = "Micro Server",
        kindLabel = "Servidor de música",
        discovered = false,
        known = true,
        connected = true,
        homeEnrolled = true,
        airCatAdvertised = true,
    )
}
