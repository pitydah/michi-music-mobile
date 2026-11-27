package org.michimusic.mobile.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.screens.PlaylistItem
import org.michimusic.mobile.screens.PlaylistsViewModel
import org.michimusic.mobile.ui.theme.MichiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * UI8 recovery: the catalog's existing three-dot menu must expose the already
 * shipped appearance editor for every playlist, in both Lista and Tarjetas.
 *
 * These tests render the real [PlaylistScreen], use its real
 * [PlaylistVisualStore]/prefs path, and verify that confirming persists the
 * existing private image token and crop values for the opened playlist. They do
 * not reimplement the editor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class UI8PlaylistCatalogAppearanceTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("michi_settings", 0)
    private val historyDao = mockk<org.michimusic.data.cache.AppDao>(relaxed = true)

    @Before
    fun clearStore() {
        prefs.edit().clear().commit()
    }

    @After
    fun cleanStore() {
        prefs.edit().clear().commit()
    }

    private fun playlist(id: String, name: String, count: Int) =
        PlaylistItem(id = id, name = name, trackCount = count)

    private fun render(
        playlists: List<PlaylistItem>,
        widthDp: Int = 393,
        fontScale: Float = 1.0f,
    ) {
        val viewModel = mockk<PlaylistsViewModel>(relaxed = true)
        every { viewModel.playlists } returns MutableStateFlow(playlists)
        every { viewModel.isLoading } returns MutableStateFlow(false)
        every { viewModel.isCreatingPlaylist } returns MutableStateFlow(false)
        every { viewModel.createPlaylistError } returns MutableStateFlow(null)
        every { viewModel.isRenamingPlaylist } returns MutableStateFlow(false)
        every { viewModel.isDeletingPlaylist } returns MutableStateFlow(false)
        val audio = mockk<org.michimusic.player.AudioController>(relaxed = true)
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                MichiTheme {
                    Box(Modifier.width(widthDp.dp).height(900.dp)) {
                        PlaylistScreen(
                            onNavigateToPlaylist = {},
                            viewModel = viewModel,
                            audioController = audio,
                            historyDao = historyDao,
                        )
                    }
                }
            }
        }
    }

    private fun openMenu(id: String) {
        compose.onNodeWithTag("playlist_more_$id").performClick()
        compose.waitForIdle()
    }

    private fun openAppearanceEditor(id: String) {
        openMenu(id)
        compose.onNodeWithTag("playlist_menu_appearance_$id").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("playlist_visual_editor").assertIsDisplayed()
        compose.onNodeWithText("Editar apariencia de playlist").assertIsDisplayed()
    }

    private fun seedPrivateImage(id: String, field: String): String {
        val importer = PlaylistPrivateImageImporter(context)
        val bytes = PlaylistTestImages.png64x32
        val imported = importer.importFromStream(
            id, field, "image/png", bytes.size.toLong(), ByteArrayInputStream(bytes),
        ) as PlaylistImageImportResult.Success
        prefs.edit().putString(playlistVisualKey(id, field), imported.token).commit()
        return imported.token
    }

    @Test
    fun emptyUnpinnedAndFullPinnedPlaylistsBothOfferEveryMenuAction() {
        runBlocking { PlaylistVisualStore(context).setPinned("p-full", true) }
        render(
            listOf(
                playlist("p-empty", "Vacía", 0),
                playlist("p-full", "Con canciones", 12),
            ),
        )

        openMenu("p-empty")
        listOf(
            "playlist_menu_rename_p-empty",
            "playlist_menu_pin_p-empty",
            "playlist_menu_appearance_p-empty",
            "playlist_menu_manage_p-empty",
            "playlist_menu_delete_p-empty",
        ).forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
        compose.onNodeWithText("Fijar").assertIsDisplayed()
        compose.onNodeWithTag("playlist_menu_appearance_p-empty").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("playlist_visual_editor").assertIsDisplayed()
        compose.onNodeWithTag("playlist_visual_editor_cancel").performScrollTo().performClick()
        compose.waitForIdle()

        openMenu("p-full")
        compose.onNodeWithTag("playlist_menu_appearance_p-full").assertIsDisplayed()
        compose.onNodeWithText("Desfijar").assertIsDisplayed()
        compose.onNodeWithTag("playlist_menu_appearance_p-full").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("playlist_visual_editor").assertIsDisplayed()
    }

    @Test
    fun cardsModeOffersTheSameAppearanceEntryForTheFrontPlaylist() {
        runBlocking { PlaylistVisualStore(context).setPinned("p-cards", true) }
        render(
            listOf(
                playlist("p-cards", "Tarjetas", 5),
                playlist("p-other", "Otra", 2),
            ),
        )
        compose.onNodeWithTag("playlist_view_mode_toggle").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("playlist_catalog_cards").assertIsDisplayed()

        openAppearanceEditor("p-cards")
        compose.onNodeWithTag("playlist_visual_editor_cover_preview").performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("playlist_visual_editor_banner_preview").performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("playlist_visual_editor_cancel").performScrollTo()
            .performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("playlist_visual_editor").assertCountEquals(0)
    }

    @Test
    fun cancelLeavesTheStoredCoverAndBannerUntouched() {
        val bannerToken = seedPrivateImage("p-cancel", "banner")
        prefs.edit()
            .putFloat(playlistVisualKey("p-cancel", "banner_scale"), 1f)
            .commit()
        render(listOf(playlist("p-cancel", "Cancelar", 4)))

        openAppearanceEditor("p-cancel")
        compose.waitUntil(5_000) {
            runCatching {
                compose.onNodeWithTag("playlist_visual_editor_banner_adjust").assertIsEnabled()
                true
            }.getOrDefault(false)
        }
        compose.onNodeWithTag("playlist_visual_editor_banner_adjust")
            .performScrollTo().performClick()
        compose.onNodeWithTag("playlist_visual_editor_banner_scale")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(2.5f) }
        compose.onNodeWithTag("playlist_visual_editor_cancel")
            .performScrollTo().performClick()
        compose.waitForIdle()

        compose.onAllNodesWithTag("playlist_visual_editor").assertCountEquals(0)
        assertEquals(1f, prefs.getFloat(playlistVisualKey("p-cancel", "banner_scale"), 1f), 0.001f)
        assertEquals(bannerToken, prefs.getString(playlistVisualKey("p-cancel", "banner"), null))
    }

    @Test
    fun confirmingCoverAndBannerPersistsBothAndSurvivesNewStoreInstances() {
        val coverToken = seedPrivateImage("p-save", "cover")
        val bannerToken = seedPrivateImage("p-save", "banner")
        val revisionBefore = PlaylistVisualStore.revision.value
        render(listOf(playlist("p-save", "Guardar", 8)))

        openAppearanceEditor("p-save")
        compose.waitUntil(5_000) {
            runCatching {
                compose.onNodeWithTag("playlist_visual_editor_cover_adjust").assertIsEnabled()
                compose.onNodeWithTag("playlist_visual_editor_banner_adjust").assertIsEnabled()
                true
            }.getOrDefault(false)
        }

        compose.onNodeWithTag("playlist_visual_editor_cover_adjust")
            .performScrollTo().performClick()
        compose.onNodeWithTag("playlist_visual_editor_cover_scale")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        compose.onNodeWithTag("playlist_visual_editor_banner_adjust")
            .performScrollTo().performClick()
        compose.onNodeWithTag("playlist_visual_editor_banner_scale")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1.6f) }
        compose.onNodeWithTag("playlist_visual_editor_confirm")
            .performScrollTo().performClick()

        compose.waitUntil(5_000) {
            prefs.getFloat(playlistVisualKey("p-save", "banner_scale"), 1f) >= 1.5f
        }
        compose.onAllNodesWithTag("playlist_visual_editor").assertCountEquals(0)

        assertEquals(2f, prefs.getFloat(playlistVisualKey("p-save", "cover_scale"), 1f), 0.001f)
        assertEquals(1.6f, prefs.getFloat(playlistVisualKey("p-save", "banner_scale"), 1f), 0.001f)
        assertEquals(coverToken, prefs.getString(playlistVisualKey("p-save", "cover"), null))
        assertEquals(bannerToken, prefs.getString(playlistVisualKey("p-save", "banner"), null))

        // A fresh store is how catalog, detail and Home read persistence after
        // reopening the app: the same values and the same private image tokens.
        val reopened = PlaylistVisualStore(context)
        val persisted = runBlocking { reopened.read("p-save") }
        assertEquals(2f, persisted.coverScale, 0.001f)
        assertEquals(1.6f, persisted.bannerScale, 0.001f)
        assertEquals(coverToken, prefs.getString(playlistVisualKey("p-save", "cover"), null))
        assertEquals(bannerToken, prefs.getString(playlistVisualKey("p-save", "banner"), null))
        assertTrue(
            "a successful catalog edit must notify the shared catalog/detail revision",
            PlaylistVisualStore.revision.value > revisionBefore,
        )
    }

    @Test
    fun staleCatalogDraftReportsConflictWithoutOverwritingAnotherScreensChange() {
        val coverToken = seedPrivateImage("p-conflict", "cover")
        val bannerToken = seedPrivateImage("p-conflict", "banner")
        render(listOf(playlist("p-conflict", "Conflicto", 6)))

        openAppearanceEditor("p-conflict")
        compose.waitUntil(5_000) {
            runCatching {
                compose.onNodeWithTag("playlist_visual_editor_cover_adjust").assertIsEnabled()
                compose.onNodeWithTag("playlist_visual_editor_banner_adjust").assertIsEnabled()
                true
            }.getOrDefault(false)
        }

        // Another screen/store instance changes the banner while this draft is open.
        runBlocking {
            PlaylistVisualStore(context).setVisualAdjustment("p-conflict", "banner", 0.2f, 0.8f, 2f)
        }
        compose.waitUntil(5_000) {
            prefs.getFloat(playlistVisualKey("p-conflict", "banner_scale"), 1f) == 2f
        }
        compose.waitForIdle()

        compose.onNodeWithTag("playlist_visual_editor_cover_adjust")
            .performScrollTo().performClick()
        compose.onNodeWithTag("playlist_visual_editor_cover_scale")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1.8f) }
        compose.onNodeWithTag("playlist_visual_editor_confirm")
            .performScrollTo().performClick()

        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("playlist_visual_editor_error").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("playlist_visual_editor").assertIsDisplayed()
        compose.onNodeWithTag("playlist_visual_editor_error").performScrollTo().assertIsDisplayed()
        // The external banner change wins; the stale draft's cover scale is not written.
        assertEquals(2f, prefs.getFloat(playlistVisualKey("p-conflict", "banner_scale"), 1f), 0.001f)
        assertEquals(1f, prefs.getFloat(playlistVisualKey("p-conflict", "cover_scale"), 1f), 0.001f)
        assertEquals(coverToken, prefs.getString(playlistVisualKey("p-conflict", "cover"), null))
        assertEquals(bannerToken, prefs.getString(playlistVisualKey("p-conflict", "banner"), null))

        compose.onNodeWithTag("playlist_visual_editor_cancel")
            .performScrollTo().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("playlist_visual_editor").assertCountEquals(0)
    }

    @Test
    fun existingRenameAndPinActionsStillWorkBesideAppearance() {
        render(listOf(playlist("p-actions", "Acciones", 3)))

        openMenu("p-actions")
        compose.onNodeWithTag("playlist_menu_appearance_p-actions").assertIsDisplayed()
        compose.onNodeWithTag("playlist_menu_rename_p-actions").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("rename_playlist_dialog").assertIsDisplayed()
        compose.onNodeWithTag("rename_playlist_dialog_cancel_button").performClick()
        compose.waitForIdle()

        openMenu("p-actions")
        compose.onNodeWithTag("playlist_menu_pin_p-actions").performClick()
        compose.waitForIdle()
        assertTrue(runBlocking { PlaylistVisualStore(context).read("p-actions").pinned })
        openMenu("p-actions")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Desfijar").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("playlist_menu_appearance_p-actions").assertIsDisplayed()
    }
}
