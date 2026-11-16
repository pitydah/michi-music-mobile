package org.michimusic.mobile.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.intelligence.MichiGenrePreviewPolicy
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.data.repository.ExtendedTagUnavailableReason
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * K48-R06: [GenreEditorContent] must render any example state with no Koin, Room,
 * Media3 or network wiring at all. These are plain UI-contract tests: a state literal
 * in, one intent out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GenreEditorContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val intents = mutableListOf<GenreEditorIntent>()

    @Test
    fun aVerifiedPreviewOnlyEditorPromisesNothingAboutTheFile() {
        render(state(genres = listOf("New Wave")))

        compose.onNodeWithTag("mi26_genre_evidence")
            .assertTextEquals("Género verificado: New Wave")
        compose.onNodeWithTag("mi26_genre_editor_legend")
            .assertExists()
        // No inert save button exists while writes are uncertified.
        compose.onNodeWithTag("mi26_genre_editor_primary").assertDoesNotExist()
    }

    @Test
    fun anUnverifiedFileSaysItCannotProveTheTag() {
        render(
            state(
                genres = emptyList(),
                tagsVerified = false,
                evidenceReason = ExtendedTagUnavailableReason.SIZE_MISMATCH,
            ),
        )

        compose.onNodeWithTag("mi26_genre_evidence").assertTextEquals("Aún no verificable")
        compose.onNodeWithTag("mi26_genre_library_classification").assertExists()
        compose.onNodeWithTag("mi26_genre_evidence_reason").assertExists()
    }

    @Test
    fun theConsentStageReplacesTheBodyWithoutSendingAnything() {
        render(
            state(
                genres = listOf("New Wave"),
                consent = GenreNetworkConsent.StrictSearch(
                    mapOf("recording" to "Voices Carry", "artist" to "'Til Tuesday"),
                ),
            ),
        )

        compose.onNodeWithTag("mi26_network_consent").assertExists()
        compose.onNodeWithTag("mi26_network_consent_cancel").assertExists()
        compose.onNodeWithTag("mi23_preview_external_search").assertDoesNotExist()
        assertTrue(intents.isEmpty())
    }

    @Test
    fun theRemoveAffordanceEmitsTheExactPosition() {
        render(state(genres = listOf("New Wave", "Synth-pop")))

        // Inside a Dialog window Robolectric swallows raw touch input; the semantic
        // OnClick action is the same onClick the person triggers.
        compose.onAllNodesWithTag("mi26_preview_remove")[0]
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf<GenreEditorIntent>(GenreEditorIntent.RemoveGenre(0)), intents)
    }

    @Test
    fun thePreviewShowsTheVerifiedOriginalAndTheProposal() {
        render(
            state(
                genres = listOf("New Wave"),
                proposed = listOf("New Wave", "Coldwave"),
            ),
        )

        compose.onNodeWithTag("mi26_preview_only_notice").assertExists()
        compose.onNodeWithTag("mi26_preview_original")
            .assertTextEquals("Original: New Wave")
        compose.onNodeWithTag("mi26_preview_proposed")
            .assertTextEquals("Propuesto: New Wave · Coldwave")
    }

    private fun render(state: GenreEditorUiState) {
        compose.setContent {
            GenreEditorContent(uiState = state, onIntent = { intents.add(it) })
        }
    }

    private fun state(
        genres: List<String>,
        tagsVerified: Boolean = true,
        evidenceReason: ExtendedTagUnavailableReason? = null,
        consent: GenreNetworkConsent? = null,
        proposed: List<String>? = null,
    ) = GenreEditorUiState(
        target = MichiGenreEditorContract.target(
            track = TRACK,
            embeddedGenres = genres,
            tagsVerified = tagsVerified,
            origin = MichiGenreEditorOrigin.GENRES,
        ),
        evidenceReason = evidenceReason,
        libraryClassification = "New Wave",
        classificationHint = "Clasificación del índice local, no prueba de la etiqueta física.",
        preview = MichiGenrePreviewPolicy.Preview(
            original = MichiGenrePreviewPolicy.of(genres).original,
            proposed = proposed ?: genres,
        ),
        pendingConsent = consent,
    )

    private companion object {
        val TRACK = Track(
            id = "track-1",
            title = "Voices Carry",
            artist = "'Til Tuesday",
            album = "Voices Carry",
            duration = 240_000,
            size = 8_192,
            filepath = "content://media/external/audio/media/1",
            source = TrackSource.LOCAL,
            sourceId = "local",
            genre = "New Wave",
        )
    }
}
