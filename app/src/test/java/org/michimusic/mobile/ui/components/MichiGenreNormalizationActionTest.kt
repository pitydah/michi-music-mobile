package org.michimusic.mobile.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.core.KoinApplication
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.michimusic.core.models.LibraryScope
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.data.cache.TrackDao
import org.michimusic.mobile.library.LibraryScopeStore
import org.michimusic.mobile.library.MusicRootAuthority
import org.michimusic.mobile.playback.PlaybackSessionManager
import org.michimusic.mobile.playback.PlaybackSessionState
import org.michimusic.mobile.screens.AlbumsViewModel
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The normalization dialog is the only place where a user can disclose public
 * metadata to MusicBrainz or authorize a physical GENRE write. These tests prove
 * that opening it performs no request, that the exact outgoing fields are shown,
 * that a second explicit confirmation is required, and that the write path stays
 * hidden while tag writes are not Android-certified.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MichiGenreNormalizationActionTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var koinApp: KoinApplication
    private val scopeStore = mockk<LibraryScopeStore>(relaxed = true)
    private val rootAuthority = mockk<MusicRootAuthority>(relaxed = true)
    private val session = mockk<PlaybackSessionManager>(relaxed = true)
    private val albumsViewModel = mockk<AlbumsViewModel>(relaxed = true)
    private val trackDao = mockk<TrackDao>(relaxed = true)

    private val track = Track(
        id = "track-1",
        title = "Song Title",
        artist = "Artist Name",
        album = "Album Name",
        filepath = "content://michi/track-1",
        source = TrackSource.LOCAL,
        sourceId = "saf-A",
        size = 1234L,
        genre = "Rock",
    )

    @Before
    fun setUp() {
        every { scopeStore.activeScope } returns MutableStateFlow(LibraryScope.All)
        every { scopeStore.hydrated } returns MutableStateFlow(true)
        every { rootAuthority.revision } returns MutableStateFlow(0L)
        every { session.sessionState } returns MutableStateFlow(PlaybackSessionState())
        koinApp = koinApplication {
            modules(
                module {
                    single { scopeStore }
                    single { rootAuthority }
                    single { session }
                    single { trackDao }
                    viewModel { albumsViewModel }
                },
            )
        }
    }

    @After
    fun tearDown() {
        koinApp.close()
    }

    private fun setContent(tagsVerified: Boolean, genres: List<String>) {
        compose.setContent {
            val owner = remember {
                object : ViewModelStoreOwner {
                    override val viewModelStore = ViewModelStore()
                }
            }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                KoinIsolatedContext(koinApp) {
                    MichiGenreNormalizationAction(track, genres, tagsVerified)
                }
            }
        }
    }

    /**
     * Robolectric does not dispatch touch events into a `Dialog` window: the modal
     * window's input path is not simulated, so a touch `performClick()` inside the
     * genre editor sheet or its nested dialogs never reaches the child control
     * (verified with a pointer probe: the state only changes with
     * `performSemanticsAction`). The semantics action invokes the exact same
     * `onClick` lambda a real touch does, so the state machine is still proven here.
     * Physical touch dispatch and visual placement inside the dialog remain
     * device-only checks (NOT_RUN in Robolectric).
     */
    private fun SemanticsNodeInteraction.clickInsideDialog(): SemanticsNodeInteraction =
        performSemanticsAction(SemanticsActions.OnClick)

    @Test
    fun openingShowsExactPublicFieldsAndClosingSendsNothing() {
        setContent(tagsVerified = true, genres = listOf("Rock"))

        compose.onNodeWithTag("mi23_open_genre_normalization").performClick()

        compose.onNodeWithTag("mi26_genre_editor_title").assertTextEquals("Revisar géneros")
        compose.onNodeWithText("Género verificado: Rock").assertIsDisplayed()
        compose.onNodeWithText("Datos exactos de búsqueda:").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("recording: Song Title").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("artist: Artist Name").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("release: Album Name").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            "Solo se buscará por título, artista y, si existe, álbum. " +
                "Nunca se enviarán rutas, archivos, biblioteca, historial ni gustos.",
        ).performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("mi26_genre_editor_close").clickInsideDialog()
        compose.onNodeWithTag("mi26_genre_editor_title").assertDoesNotExist()
        compose.onNodeWithText("¿Consultar MusicBrainz?").assertDoesNotExist()
    }

    @Test
    fun externalLookupRequiresASecondExplicitConfirmation() {
        setContent(tagsVerified = true, genres = listOf("Rock"))

        compose.onNodeWithTag("mi23_open_genre_normalization").performClick()
        compose.onNodeWithTag("mi23_preview_external_search").performScrollTo().clickInsideDialog()

        // K47-NORM-05: the confirmation is an inline stage of the same modal, so
        // the exact public fields are measured on screen, not in a nested window.
        compose.onNodeWithTag("mi26_network_consent").assertIsDisplayed()
        compose.onNodeWithText("¿Consultar MusicBrainz?").assertIsDisplayed()
        compose.onNodeWithText("Se enviarán únicamente estos campos públicos:").assertIsDisplayed()
        // Only one copy: the editor body is replaced by the consent stage.
        compose.onAllNodesWithText("recording: Song Title").assertCountEquals(1)
        compose.onNodeWithText(
            "MusicBrainz puede registrar la IP y los parámetros de búsqueda. " +
                "No se enviarán otros datos.",
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Enviar esta vez").performScrollTo().assertIsDisplayed()
        // Cancelling leaves no queued request and returns to the editor body.
        compose.onNodeWithTag("mi26_network_consent_cancel").clickInsideDialog()
        compose.onNodeWithTag("mi26_network_consent").assertDoesNotExist()
        compose.onNodeWithText("Datos exactos de búsqueda:").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun draftPreviewIsFunctionalButNeverPromisesPersistence() {
        setContent(tagsVerified = true, genres = listOf("Post-Punk"))

        compose.onNodeWithTag("mi23_open_genre_normalization").performClick()
        compose.onNodeWithTag("mi26_genre_editor_preview_toggle").assertDoesNotExist()

        compose.onNodeWithTag("mi23_manual_genre").performScrollTo()
            .performTextReplacement("Coldwave")
        compose.onNodeWithTag("mi26_preview_add").performScrollTo().clickInsideDialog()

        compose.onNodeWithTag("mi26_genre_editor_preview_toggle").clickInsideDialog()
        compose.onNodeWithTag("mi26_genre_preview_summary")
            .assertTextEquals("Propuesto: Post-Punk · Coldwave")
        compose.onNodeWithText("Guardar").assertDoesNotExist()
    }

    @Test
    fun unverifiedTagsKeepEditingUnavailable() {
        setContent(tagsVerified = false, genres = emptyList())

        compose.onNodeWithTag("mi23_open_genre_normalization").performClick()

        // K47-NORM-02: unavailable is not an empty tag.
        compose.onNodeWithTag("mi26_genre_evidence").assertTextEquals("Aún no verificable")
        compose.onNodeWithText("En Biblioteca: Rock").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Género verificado: Sin género").assertDoesNotExist()
        compose.onNodeWithTag("mi26_genre_evidence_reason").performScrollTo()
            .clickInsideDialog()
        compose.onNodeWithTag("mi26_genre_evidence_reason_text")
            .assertTextEquals("No se registró un motivo para esta lectura.")
        compose.onNodeWithText("Original: sin datos verificados").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Guardar GENRE en archivo").assertDoesNotExist()
        compose.onNodeWithText("Reemplazar GENRE existente").assertDoesNotExist()
    }

    @Test
    fun previewShowsEveryCurrentValueAndSaysItDoesNotModifyTheFile() {
        setContent(tagsVerified = true, genres = listOf("Post-Punk", "New Wave", "Darkwave"))

        compose.onNodeWithTag("mi23_open_genre_normalization").performClick()

        compose.onNodeWithText("Previsualización: no modifica el archivo.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Original: Post-Punk · New Wave · Darkwave").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Propuesto: Post-Punk · New Wave · Darkwave").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            "Escritura de etiquetas deshabilitada hasta certificar MediaStore/SAF y los " +
                "formatos MP3/FLAC en Android. Esta vista solo previsualiza.",
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("mi23_request_write").assertDoesNotExist()
    }

    @Test
    fun addingAGenreKeepsTheExistingOnes() {
        setContent(tagsVerified = true, genres = listOf("Post-Punk", "New Wave"))

        compose.onNodeWithTag("mi23_open_genre_normalization").performClick()
        compose.onNodeWithTag("mi23_manual_genre").performScrollTo().performTextReplacement("Coldwave")
        compose.onNodeWithTag("mi26_preview_add").performScrollTo().clickInsideDialog()

        compose.onNodeWithText("Propuesto: Post-Punk · New Wave · Coldwave").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Añadido: Coldwave").performScrollTo().assertIsDisplayed()
        // Original evidence is never rewritten by the preview.
        compose.onNodeWithText("Original: Post-Punk · New Wave").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun removingOneGenreKeepsEveryOtherGenre() {
        setContent(tagsVerified = true, genres = listOf("Post-Punk", "New Wave", "Darkwave"))

        compose.onNodeWithTag("mi23_open_genre_normalization").performClick()
        compose.onAllNodesWithTag("mi26_preview_remove")[1].performScrollTo().clickInsideDialog()

        compose.onNodeWithText("Propuesto: Post-Punk · Darkwave").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Quitado: New Wave").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun replacingOneGenreKeepsTheOtherNMinusOne() {
        setContent(tagsVerified = true, genres = listOf("Post-Punk", "New Wave", "Darkwave"))

        compose.onNodeWithTag("mi23_open_genre_normalization").performClick()
        compose.onAllNodesWithTag("mi26_preview_select_replace")[2].performScrollTo().clickInsideDialog()
        compose.onNodeWithTag("mi23_manual_genre").performScrollTo().performTextReplacement("Coldwave")
        compose.onNodeWithTag("mi26_preview_replace").performScrollTo().clickInsideDialog()

        compose.onNodeWithText("Propuesto: Post-Punk · New Wave · Coldwave").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Añadido: Coldwave").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Quitado: Darkwave").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theFooterHidesSaveAndExplainsThatNothingIsWritten() {
        setContent(tagsVerified = true, genres = listOf("Rock"))

        compose.onNodeWithTag("mi23_open_genre_normalization").performClick()

        compose.onNodeWithTag("mi26_genre_editor_mode")
            .assertTextEquals("Solo previsualización")
        // K47-NORM-03: no inert Guardar and no long implementation message.
        compose.onNodeWithTag("mi26_genre_editor_primary").assertDoesNotExist()
        compose.onNodeWithTag("mi26_genre_editor_legend")
            .assertTextEquals("Estos cambios no se guardan en los archivos")
        compose.onNodeWithTag("mi26_genre_editor_close").assertIsDisplayed()
        compose.onNodeWithTag("mi26_genre_editor_footer").assertIsDisplayed()
    }

    @Test
    fun tagWritesAreNotCertifiedInProduction() {
        // The deliberate fail-closed gate is a product contract, not a test artifact.
        assertFalse(MichiGenreWriteCertification.WRITES_CERTIFIED)
    }
}
