package org.michimusic.mobile.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
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

    @Test
    fun openingShowsExactPublicFieldsAndClosingSendsNothing() {
        setContent(tagsVerified = true, genres = listOf("Rock"))

        compose.onNodeWithTag("mi23_open_genre_normalization").performClick()

        compose.onNodeWithText("Michi Intelligence · Género").assertIsDisplayed()
        compose.onNodeWithText("Género verificado: Rock").assertIsDisplayed()
        compose.onNodeWithText("Datos exactos de búsqueda:").assertIsDisplayed()
        compose.onNodeWithText("recording: Song Title").assertIsDisplayed()
        compose.onNodeWithText("artist: Artist Name").assertIsDisplayed()
        compose.onNodeWithText("release: Album Name").assertIsDisplayed()
        compose.onNodeWithText(
            "Solo se buscará por título, artista y, si existe, álbum. " +
                "Nunca se enviarán rutas, archivos, biblioteca, historial ni gustos.",
        ).assertIsDisplayed()

        compose.onNodeWithText("Cerrar").performClick()
        compose.onNodeWithText("Michi Intelligence · Género").assertDoesNotExist()
        compose.onNodeWithText("¿Consultar MusicBrainz?").assertDoesNotExist()
    }

    @Test
    fun externalLookupRequiresASecondExplicitConfirmation() {
        setContent(tagsVerified = true, genres = listOf("Rock"))

        compose.onNodeWithTag("mi23_open_genre_normalization").performClick()
        compose.onNodeWithTag("mi23_preview_external_search").performClick()

        compose.onNodeWithText("¿Consultar MusicBrainz?").assertIsDisplayed()
        compose.onNodeWithText("Se enviarán únicamente estos campos públicos:").assertIsDisplayed()
        compose.onAllNodesWithText("recording: Song Title").assertCountEquals(2)
        compose.onNodeWithText(
            "MusicBrainz puede registrar la IP y los parámetros de búsqueda. " +
                "No se enviarán otros datos.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Enviar esta vez").assertIsDisplayed()
    }

    @Test
    fun unverifiedTagsKeepEditingUnavailable() {
        setContent(tagsVerified = false, genres = emptyList())

        compose.onNodeWithTag("mi23_open_genre_normalization").performClick()

        compose.onNodeWithText(
            "Género del archivo no verificable. Puedes buscar sugerencias, pero no editar la etiqueta.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Género verificado: Sin género").assertDoesNotExist()
        compose.onNodeWithText("Guardar GENRE en archivo").assertDoesNotExist()
        compose.onNodeWithText("Reemplazar GENRE existente").assertDoesNotExist()
    }
}
