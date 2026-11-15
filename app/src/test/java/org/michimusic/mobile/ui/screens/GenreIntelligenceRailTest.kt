package org.michimusic.mobile.ui.screens

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.intelligence.MichiIntelligencePolicy
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * K47-MI-01 / K47-MI-02: the intelligence rail is a 72-80 dp lane that becomes a
 * compact list, never a second screen or a full card. The row that is playing
 * stays visible and is marked as playing instead of disappearing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GenreIntelligenceRailTest {
    @get:Rule
    val compose = createComposeRule()

    private val first = GenreIntelligencePick(
        key = MichiIntelligencePolicy.TrackKey("saf-A", "track-1"),
        title = "Pictures of You",
        reason = "Por tu afinidad con este artista",
    )
    private val second = GenreIntelligencePick(
        key = MichiIntelligencePolicy.TrackKey("saf-A", "track-2"),
        title = "Voices Carry",
        reason = "Por tu afinidad con este género",
    )

    private fun setRail(
        state: GenreIntelligenceUiState,
        onToggleExpanded: () -> Unit = {},
        onPlayPick: (MichiIntelligencePolicy.TrackKey) -> Unit = {},
        onPlaySelection: () -> Unit = {},
        onShowReason: (MichiIntelligencePolicy.TrackKey) -> Unit = {},
    ) {
        compose.setContent {
            GenreIntelligenceRail(
                state = state,
                onToggleExpanded = onToggleExpanded,
                onPlayPick = onPlayPick,
                onPlaySelection = onPlaySelection,
                onShowReason = onShowReason,
            )
        }
    }

    private fun collapsed(vararg picks: GenreIntelligencePick) = GenreIntelligenceUiState(
        genreTitle = "New Wave",
        expanded = false,
        picks = picks.toList(),
    )

    private fun expanded(vararg picks: GenreIntelligencePick) = GenreIntelligenceUiState(
        genreTitle = "New Wave",
        expanded = true,
        picks = picks.toList(),
    )

    @Test
    fun collapsedLaneUsesTheCompactContractAndKeepsPicksHidden() {
        setRail(collapsed(first))
        compose.onNodeWithTag("genre_intelligence_collapsed")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(72.dp)
        compose.onNodeWithText("Para ti · 1 sugerencia").assertIsDisplayed()
        compose.onNodeWithText("Basado en tus escuchas").assertIsDisplayed()
        compose.onNodeWithTag("genre_intelligence_track_0").assertDoesNotExist()
        compose.onNodeWithTag("genre_intelligence_play_mix").assertDoesNotExist()
    }

    @Test
    fun expandAndCollapseToggleTheLane() {
        val expandedState = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            GenreIntelligenceRail(
                state = collapsed(first).copy(expanded = expandedState.value),
                onToggleExpanded = { expandedState.value = !expandedState.value },
                onPlayPick = {},
                onPlaySelection = {},
                onShowReason = {},
            )
        }
        compose.onNodeWithTag("genre_intelligence_expand").performClick()
        compose.onNodeWithTag("genre_intelligence_collapse").assertIsDisplayed()
        compose.onNodeWithTag("genre_intelligence_track_0").assertIsDisplayed()
        compose.onNodeWithTag("genre_intelligence_collapse").performClick()
        compose.onNodeWithTag("genre_intelligence_expand").assertIsDisplayed()
        compose.onNodeWithTag("genre_intelligence_track_0").assertDoesNotExist()
    }

    @Test
    fun expandedRailShowsThePicksAndTheSinglePlayControl() {
        setRail(expanded(first))
        compose.onNodeWithText("Para ti en New Wave").assertIsDisplayed()
        compose.onNodeWithTag("genre_intelligence_track_0").assertIsDisplayed()
        compose.onNodeWithText("Pictures of You").assertIsDisplayed()
        // One suggestion plays directly from its row; the selection CTA is only
        // offered for a real selection of two or more.
        compose.onNodeWithTag("genre_intelligence_play_mix").assertDoesNotExist()
    }

    @Test
    fun selectionCtaAppearsWithTwoPicksAndPlaysTheSnapshot() {
        var selections = 0
        setRail(expanded(first, second), onPlaySelection = { selections++ })
        compose.onNodeWithTag("genre_intelligence_play_mix").assertIsDisplayed().performClick()
        assertEquals(1, selections)
    }

    @Test
    fun playingRowStaysVisibleAndReportsPlaying() {
        setRail(expanded(first, second).copy(playingKey = first.key))
        compose.onNodeWithTag("genre_intelligence_track_0").assertIsDisplayed()
        compose.onNodeWithText("Reproduciendo · Por tu afinidad con este artista")
            .assertIsDisplayed()
    }

    @Test
    fun thePlayingRowOffersNoControlThatWouldRestartIt() {
        var plays = 0
        setRail(
            expanded(first, second).copy(playingKey = first.key),
            onPlayPick = { plays++ },
        )
        compose.onNodeWithTag("genre_intelligence_track_play_0")
            .assertIsDisplayed()
            .assertHasNoClickAction()
        compose.onNodeWithTag("genre_intelligence_track_play_1")
            .assertIsDisplayed()
            .assertHasClickAction()

        compose.onNodeWithTag("genre_intelligence_track_play_0").performClick()
        assertEquals(0, plays)
    }

    @Test
    fun theSelectionCtaBecomesInformationalWhileTheMixPlays() {
        var selections = 0
        setRail(
            expanded(first, second).copy(playingKey = first.key),
            onPlaySelection = { selections++ },
        )
        compose.onNodeWithTag("genre_intelligence_play_mix")
            .assertIsDisplayed()
            .assertTextEquals("Reproduciendo selección (2)")
            .assertHasNoClickAction()
        assertEquals(0, selections)
    }

    /**
     * K48-S3: pausing or finishing the queue must stop the "Reproduciendo" claim.
     * The old derivation only compared the current track, so a paused pick kept a
     * cyan playing label and an equalizer that no longer described reality.
     */
    private fun track(id: String) = org.michimusic.core.models.Track(
        id = id,
        title = "Voices Carry",
        artist = "'Til Tuesday",
        album = "Voices Carry",
        filepath = "/music/$id.mp3",
        source = org.michimusic.core.models.TrackSource.LOCAL,
        sourceId = "saf-A",
    )

    @Test
    fun aPausedPickIsNoLongerLabelledAsPlaying() {
        assertEquals(
            null,
            genreIntelligencePlayingKey(track("track-1"), isPlaying = false, picks = listOf(first, second)),
        )
    }

    @Test
    fun aPlayingPickInTheRailKeepsItsKey() {
        assertEquals(
            first.key,
            genreIntelligencePlayingKey(track("track-1"), isPlaying = true, picks = listOf(first, second)),
        )
    }

    @Test
    fun aTrackOutsideTheRailIsNeverMarkedAsPlaying() {
        assertEquals(
            null,
            genreIntelligencePlayingKey(track("track-9"), isPlaying = true, picks = listOf(first, second)),
        )
        assertEquals(null, genreIntelligencePlayingKey(null, isPlaying = true, picks = listOf(first)))
    }

    @Test
    fun reasonActionAndWarningStayReachable() {
        var reasonKey: MichiIntelligencePolicy.TrackKey? = null
        setRail(
            expanded(first, second).copy(warning = "Las canciones cambiaron."),
            onShowReason = { reasonKey = it },
        )
        compose.onNodeWithTag("genre_intelligence_warning").assertIsDisplayed()
        compose.onNodeWithTag("genre_intelligence_reason").assertIsDisplayed()
        compose.onNodeWithTag("genre_intelligence_track_reason_0").performClick()
        assertEquals(first.key, reasonKey)
    }
}
