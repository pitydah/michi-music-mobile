package org.michimusic.mobile.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.data.repository.ALL_FOLDERS
import org.michimusic.data.repository.MediaFolder
import org.michimusic.mobile.ui.theme.MichiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class LibraryFolderDialogTest {
    @get:Rule val compose = createComposeRule()

    private val folders = listOf(
        MediaFolder(name = "music", trackCount = 10, musicTrackCount = 10),
        MediaFolder(name = "download", trackCount = 5, musicTrackCount = 1),
        MediaFolder(name = "whatsapp", trackCount = 2, musicTrackCount = 0),
    )

    @Test
    fun listsFoldersAndSoloMusicaSavesTheSmartDefault() {
        var saved: Set<String>? = emptySet()
        compose.setContent {
            MichiTheme {
                LibraryFolderDialogContent(
                    folders = folders,
                    initialSelection = null,
                    onDismiss = {},
                    onSave = { saved = it },
                )
            }
        }

        compose.onNodeWithText("music").assertIsDisplayed()
        compose.onNodeWithText("download").assertIsDisplayed()
        compose.onNodeWithText("whatsapp").assertIsDisplayed()
        compose.onNodeWithTag("library_folder_music_only").performClick()
        compose.onNodeWithTag("library_folder_save").performClick()

        assertEquals(setOf("music", "download"), saved)
    }

    @Test
    fun todasSavesTheExplicitWildcard() {
        var saved: Set<String>? = emptySet()
        compose.setContent {
            MichiTheme {
                LibraryFolderDialogContent(
                    folders = folders,
                    initialSelection = null,
                    onDismiss = {},
                    onSave = { saved = it },
                )
            }
        }

        compose.onNodeWithTag("library_folder_all").performClick()
        compose.onNodeWithTag("library_folder_save").performClick()

        assertEquals(setOf(ALL_FOLDERS), saved)
    }

    @Test
    fun togglingAFolderMakesTheSelectionExplicit() {
        var saved: Set<String>? = emptySet()
        compose.setContent {
            MichiTheme {
                LibraryFolderDialogContent(
                    folders = folders,
                    initialSelection = null,
                    onDismiss = {},
                    onSave = { saved = it },
                )
            }
        }

        compose.onNodeWithTag("library_folder_check_whatsapp").performClick()
        compose.onNodeWithTag("library_folder_save").performClick()

        assertEquals(setOf("music", "download", "whatsapp"), saved)
    }

    @Test
    fun resetClearsExplicitSelectionBackToAutomatic() {
        var saved: Set<String>? = emptySet()
        compose.setContent {
            MichiTheme {
                LibraryFolderDialogContent(
                    folders = folders,
                    initialSelection = setOf("music"),
                    onDismiss = {},
                    onSave = { saved = it },
                )
            }
        }

        compose.onNodeWithTag("library_folder_reset").performClick()
        compose.onNodeWithTag("library_folder_save").performClick()

        assertEquals(null, saved)
    }
}
