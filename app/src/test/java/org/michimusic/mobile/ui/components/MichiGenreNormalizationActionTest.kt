package org.michimusic.mobile.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.mobile.library.MusicRootAuthority
import org.michimusic.mobile.ui.metadata.LocalMetadataEditorLauncher
import org.michimusic.mobile.ui.metadata.MetadataEditorLaunchRequest
import org.michimusic.mobile.ui.metadata.MetadataEditorOrigin
import org.michimusic.mobile.ui.theme.AeroTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ME-11S: genre normalization is no longer a second editor or a nested modal.
 * The action only hands a typed launch request to the single editor destination,
 * directly at the integrated genre tool. These tests pin that contract, the
 * absence of any second sheet, and the fail-closed write certification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MichiGenreNormalizationActionTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var koinApp: KoinApplication
    private val rootAuthority = mockk<MusicRootAuthority>(relaxed = true)
    private val requests = mutableListOf<MetadataEditorLaunchRequest>()

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

    @Test
    fun localTrackLaunchesTheSingleEditorAtTheGenreTool() {
        setContent(track)

        compose.onNodeWithTag("mi23_open_genre_normalization").performClick()

        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals(MetadataEditorOrigin.FILE_INFO, request.origin)
        assertEquals("saf-A", request.sourceId)
        assertEquals("track-1", request.trackId)
        assertEquals(track.filepath, request.trackUri)
        assertEquals(track.size, request.expectedSize)
        assertEquals(7L, request.scopeGeneration)
        assertTrue(request.focusGenre)
        assertFalse(request.isBatch)
        // The retired second modal never opens; there is exactly one destination.
        compose.onNodeWithTag("mi26_genre_editor_title").assertDoesNotExist()
    }

    @Test
    fun remoteTrackOffersNoEditorEntryPoint() {
        setContent(track.copy(source = TrackSource.STREAMING, filepath = "https://example/song"))

        compose.onNodeWithTag("mi23_open_genre_normalization").assertDoesNotExist()
        assertNull(requests.firstOrNull())
    }

    @Test
    fun openingTheEntryPointPerformsNoLookupAndShowsNoNestedSheet() {
        setContent(track)

        compose.onNodeWithText("Normalizar géneros ›").performClick()

        assertEquals(1, requests.size)
        // Consent and search belong to the single editor, never to this action.
        compose.onNodeWithTag("mi26_network_consent").assertDoesNotExist()
        compose.onNodeWithTag("mi26_genre_editor_footer").assertDoesNotExist()
    }

    @Test
    fun tagWritesAreNotCertifiedInProduction() {
        // The deliberate fail-closed gate is a product contract, not a test artifact.
        assertFalse(MichiGenreWriteCertification.WRITES_CERTIFIED)
    }

    private fun setContent(track: Track) {
        every { rootAuthority.revision } returns MutableStateFlow(7L)
        koinApp = koinApplication { modules(module { single { rootAuthority } }) }
        compose.setContent {
            KoinIsolatedContext(koinApp) {
                CompositionLocalProvider(
                    LocalMetadataEditorLauncher provides { request -> requests += request },
                ) {
                    AeroTheme {
                        MichiGenreNormalizationAction(
                            track = track,
                            embeddedGenres = listOf("Rock"),
                            tagsVerified = true,
                        )
                    }
                }
            }
        }
    }
}
