package org.michimusic.mobile.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.michimusic.core.metadata.MetadataEditPreview
import org.michimusic.core.metadata.MetadataField
import org.michimusic.mobile.ui.theme.AeroTheme

/** ME-11U: direct shared-field editing stays honest about mixed and unverified evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi", application = android.app.Application::class)
class BatchSharedFieldRowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun uniformEvidenceStartsFromTheCommonValueAndPropagatesTheEdit() {
        var captured: List<String>? = null
        compose.setContent {
            AeroTheme {
                BatchSharedFieldRow(
                    field = MetadataField.ALBUM,
                    selection = MetadataEditPreview.SelectionValue.Same(listOf("Álbum común")),
                    edited = null,
                    onEdit = { captured = it },
                )
            }
        }
        compose.onNodeWithText("COMÚN").assertExists()
        compose.onNodeWithTag("metadata_batch_shared_input_album_0")
            .performTextClearance()
        compose.onNodeWithTag("metadata_batch_shared_input_album_0")
            .performTextInput("Álbum propuesto")
        assertEquals(listOf("Álbum propuesto"), captured)
    }

    @Test fun mixedEvidenceUsesAnExplicitReplacementWithoutInventingACommonValue() {
        var captured: List<String>? = null
        compose.setContent {
            AeroTheme {
                BatchSharedFieldRow(
                    field = MetadataField.ALBUM,
                    selection = MetadataEditPreview.SelectionValue.Mixed,
                    edited = null,
                    onEdit = { captured = it },
                )
            }
        }
        compose.onNodeWithText("MIXTO").assertExists()
        compose.onNodeWithTag("metadata_batch_shared_input_album_0")
            .performTextInput("Nuevo valor")
        assertEquals(listOf("Nuevo valor"), captured)
    }

    @Test fun unverifiedEvidenceCannotBeEditedFromTheSharedField() {
        compose.setContent {
            AeroTheme {
                BatchSharedFieldRow(
                    field = MetadataField.ARTIST,
                    selection = MetadataEditPreview.SelectionValue.Unverified,
                    edited = null,
                    onEdit = {},
                )
            }
        }
        compose.onNodeWithText("NO VERIFICABLE").assertExists()
        compose.onNodeWithText("No todas las pistas", substring = true).assertExists()
        compose.onNodeWithTag("metadata_batch_shared_input_artist_0").assertDoesNotExist()
    }
}
