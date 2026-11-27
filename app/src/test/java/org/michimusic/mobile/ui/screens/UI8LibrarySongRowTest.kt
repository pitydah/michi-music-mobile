package org.michimusic.mobile.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.module.dsl.viewModel
import org.koin.compose.KoinIsolatedContext
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.michimusic.core.models.LibraryScope
import org.michimusic.core.models.Track
import org.michimusic.mobile.library.LibraryScopeResolution
import org.michimusic.mobile.library.LibraryScopeStore
import org.michimusic.mobile.library.MusicRootAuthority
import org.michimusic.mobile.screens.AlbumsViewModel
import org.michimusic.mobile.screens.GenreLibraryViewModel
import org.michimusic.mobile.screens.MediaLibraryState
import org.michimusic.mobile.ui.theme.MichiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * UI8 recovery: the Library -> Songs row must spend its width on identity, not
 * on empty gutters. These tests render the real [AlbumsScreen] with real Track
 * models and measure the semantics bounds the row actually lays out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w393dp-h900dp")
class UI8LibrarySongRowTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var koinApp: KoinApplication

    private val longTitle = "Una canción con un título suficientemente largo para probar el ancho"
    private val tracks = listOf(
        Track(
            id = "t-long",
            title = longTitle,
            artist = "Artista de prueba",
            album = "Álbum de prueba",
            duration = 225_000,
        ),
        Track(
            id = "t-short",
            title = "Otra canción",
            artist = "Artista",
            album = "Álbum",
            duration = 61_000,
        ),
    )

    @Before
    fun setUp() {
        val albumsViewModel = mockk<AlbumsViewModel>(relaxed = true)
        every { albumsViewModel.allTracks } returns MutableStateFlow(tracks)
        every { albumsViewModel.albums } returns MutableStateFlow(emptyList())
        every { albumsViewModel.isLoading } returns MutableStateFlow(false)
        every { albumsViewModel.mediaState } returns MutableStateFlow(MediaLibraryState.READY)
        every { albumsViewModel.playlists } returns MutableStateFlow(emptyList())
        every { albumsViewModel.downloadedTrackIds } returns MutableStateFlow(emptySet())

        val genreViewModel = mockk<GenreLibraryViewModel>(relaxed = true)
        val scopeStore = mockk<LibraryScopeStore>(relaxed = true)
        every { scopeStore.activeScope } returns MutableStateFlow<LibraryScope>(LibraryScope.All)
        every { scopeStore.resolution } returns MutableStateFlow(LibraryScopeResolution.Loading)
        val libraryRepository = mockk<org.michimusic.data.repository.MusicLibraryRepository>(relaxed = true)
        every { libraryRepository.observeLibraries() } returns flowOf(emptyList())
        val favorites = mockk<org.michimusic.data.repository.FavoritesRepository>(relaxed = true)
        every { favorites.observeFavoriteTrackIds() } returns flowOf(emptySet())
        val rootAuthority = mockk<MusicRootAuthority>(relaxed = true)
        val audio = mockk<org.michimusic.player.AudioController>(relaxed = true)
        every { audio.state } returns MutableStateFlow(org.michimusic.player.PlayerState())
        val session = mockk<org.michimusic.mobile.playback.PlaybackSessionManager>(relaxed = true)
        every { session.sessionState } returns MutableStateFlow(
            org.michimusic.mobile.playback.PlaybackSessionState(),
        )

        koinApp = koinApplication {
            modules(
                module {
                    viewModel { albumsViewModel }
                    viewModel { genreViewModel }
                    single { audio }
                    single { session }
                    single { scopeStore }
                    single { libraryRepository }
                    single { mockk<org.michimusic.data.cache.MichiDatabase>(relaxed = true) }
                    single { rootAuthority }
                    single { favorites }
                },
            )
        }
    }

    @After
    fun tearDown() {
        koinApp.close()
    }

    private fun render(fontScale: Float = 1f, widthDp: Int = 393) {
        compose.setContent {
            KoinIsolatedContext(koinApp) {
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                    MichiTheme {
                        Box(Modifier.width(widthDp.dp).height(900.dp)) {
                            AlbumsScreen()
                        }
                    }
                }
            }
        }
    }

    private fun assertRowGeometry(fontScale: Float) {
        val title = compose.onNodeWithText(longTitle, useUnmergedTree = true)
            .performScrollTo()
        title.assertIsDisplayed()
        val titleBounds = title.fetchSemanticsNode().boundsInRoot
        val artistBounds = compose.onNodeWithText(
            "Artista de prueba • Álbum de prueba",
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        val durationBounds = compose.onNodeWithText("3:45", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val menuBounds = compose.onNodeWithContentDescription("Opciones para $longTitle")
            .fetchSemanticsNode().boundsInRoot
        val rowNode = compose.onNode(
            hasClickAction() and hasAnyDescendant(hasText(longTitle)),
            useUnmergedTree = true,
        )
        val rowBounds = rowNode.fetchSemanticsNode().boundsInRoot
        val coverBounds = compose.onAllNodesWithTag("album_artwork_unavailable", useUnmergedTree = true)
            .onFirst().fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        val widthPx = compose.onRoot().fetchSemanticsNode().size.width.toFloat()
        fun dp(px: Float) = px / density
        val rowCenter = (rowBounds.top + rowBounds.bottom) / 2f
        val durationCenter = (durationBounds.top + durationBounds.bottom) / 2f
        val coverCenter = (coverBounds.top + coverBounds.bottom) / 2f
        val titleLeftDp = dp(titleBounds.left)
        // The weighted identity column spans from the title's left edge to the
        // duration minus the 8 dp Row spacing. It is independent of font metrics.
        val identityWidthDp = dp(durationBounds.left - titleBounds.left) - 8f
        val gapToMenuDp = dp(menuBounds.left - durationBounds.right)
        val menuRightMarginDp = dp(widthPx - menuBounds.right)
        val menuTargetDp = dp(menuBounds.bottom - menuBounds.top)
        val topPaddingDp = dp(titleBounds.top - rowBounds.top)
        val bottomPaddingDp = dp(rowBounds.bottom - artistBounds.bottom)
        val coverSizeDp = dp(coverBounds.right - coverBounds.left)
        println(
            "SONG-ROW fs=$fontScale rootW=${dp(widthPx)}dp " +
                "titleLeft=${titleLeftDp}dp identityWidth=${identityWidthDp}dp " +
                "gapDurationMenu=${gapToMenuDp}dp menuRight=${menuRightMarginDp}dp " +
                "menuTarget=${menuTargetDp}dp topPad=${topPaddingDp}dp " +
                "bottomPad=${bottomPaddingDp}dp cover=${coverSizeDp}dp",
        )

        // Old gutters were 20/10 dp ends and 12 dp gaps; the widened row must give
        // the identity column at least 215 dp at 393 dp.
        assertTrue(
            "the identity column lost the widened width at fs=$fontScale: $identityWidthDp dp",
            identityWidthDp >= 215f,
        )
        // Cover (44 dp) + row start (10 dp) + content inset (20 dp) + 8 dp gap.
        assertTrue(
            "the 44 dp cover/spacing contract moved: title starts at $titleLeftDp dp",
            titleLeftDp in 81f..83f,
        )
        assertTrue(
            "the cover changed size: $coverSizeDp dp instead of 44 dp",
            abs(coverSizeDp - 44f) <= 0.5f,
        )
        assertTrue(
            "the 8 dp vertical padding changed: top=$topPaddingDp bottom=$bottomPaddingDp",
            abs(topPaddingDp - 8f) <= 0.5f && abs(bottomPaddingDp - 8f) <= 0.5f,
        )
        assertTrue("the duration overlaps the identity column", titleBounds.right <= durationBounds.left + 0.5f)
        assertTrue("the menu overlaps the duration", gapToMenuDp >= 7.5f)
        assertTrue(
            "the menu is not near the right edge: $menuRightMarginDp dp margin",
            menuRightMarginDp in 13f..15f,
        )
        assertTrue("the menu touch target is ${menuTargetDp}dp; 48 dp is required", menuTargetDp >= 47.5f)
        assertTrue(
            "the duration is not vertically centered with the row: " +
                "duration=$durationCenter row=$rowCenter",
            abs(durationCenter - rowCenter) <= density,
        )
        assertTrue(
            "the 44 dp cover is not vertically centered: cover=$coverCenter row=$rowCenter",
            abs(coverCenter - rowCenter) <= density,
        )
    }

    @Test
    fun songRowGivesIdentityTheWidthAndKeepsDurationAndMenuSeparated() {
        render()
        assertRowGeometry(1f)
    }

    @Test
    fun songRowKeepsTheSameContractAtTwoHundredPercentFontScale() {
        render(fontScale = 2f)
        assertRowGeometry(2f)
    }
}
