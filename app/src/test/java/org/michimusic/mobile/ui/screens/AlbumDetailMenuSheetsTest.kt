package org.michimusic.mobile.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.Track
import org.michimusic.mobile.ui.theme.MichiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class AlbumDetailMenuSheetsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun menuTransitionsExclusivelyToQualityAndClosesWithoutResidualSheet() {
        compose.setContent {
            var activeModal by remember { mutableStateOf<AlbumDetailModal?>(AlbumDetailModal.MENU) }
            MichiTheme {
                AlbumDetailMenuSheetContent(
                    activeModal = activeModal,
                    tracks = listOf(Track(id = "track-1", title = "Tema", format = "mp3")),
                    orderedTracks = listOf(Track(id = "track-1", title = "Tema", format = "mp3")),
                    onModalChange = { activeModal = it },
                )
            }
        }

        compose.onNodeWithText("Opciones del álbum").assertIsDisplayed()
        compose.onNodeWithTag("album_quality_popup").assertDoesNotExist()

        compose.onNodeWithTag("album_menu_quality").assertHasClickAction().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Opciones del álbum").assertDoesNotExist()
        compose.onNodeWithTag("album_quality_popup").assertIsDisplayed()
        compose.onNodeWithText("Calidad de los archivos").assertIsDisplayed()

        compose.onNodeWithTag("album_quality_close").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Opciones del álbum").assertDoesNotExist()
        compose.onNodeWithTag("album_quality_popup").assertDoesNotExist()
        compose.onNodeWithText("Calidad de los archivos").assertDoesNotExist()
    }
}
