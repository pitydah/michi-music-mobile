package org.michimusic.mobile.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.mobile.ui.theme.AeroTheme

/** The editor entry is offered only for provider-backed local rows, never as a placebo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi", application = android.app.Application::class)
class TrackActionMenuMetadataEntryTest {
    @get:Rule val compose = createComposeRule()

    private fun track(filepath: String, source: TrackSource = TrackSource.LOCAL) = Track(
        id = "track-1",
        title = "Tema de prueba",
        artist = "Artista",
        album = "Álbum",
        filepath = filepath,
        source = source,
    )

    @Test fun providerBackedLocalRowOffersBothReadAndEditEntryPoints() {
        compose.setContent {
            AeroTheme {
                TrackActionBottomSheet(track = track("content://media/external/audio/media/7"), onDismiss = {})
            }
        }
        compose.onNodeWithTag("track_action_sheet").assertExists()
        compose.onNodeWithText("Metadatos del archivo").assertExists()
        compose.onNodeWithText("Editar metadatos").assertExists()
    }

    @Test fun rowsWithoutProviderIdentityNeverOfferTheEditor() {
        compose.setContent {
            AeroTheme {
                TrackActionBottomSheet(track = track("https://example.invalid/remote.flac", TrackSource.STREAMING), onDismiss = {})
            }
        }
        compose.onNodeWithTag("track_action_sheet").assertExists()
        compose.onNodeWithText("Editar metadatos").assertDoesNotExist()
        compose.onNodeWithText("Metadatos del archivo").assertDoesNotExist()
    }
}
