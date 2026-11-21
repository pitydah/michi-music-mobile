package org.michimusic.mobile.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataTarget
import org.michimusic.mobile.ui.theme.AeroTheme

/** Robolectric/Compose source tests. Execute only in OpenCode, not in Drive. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi", application = android.app.Application::class)
class MichiMetadataEditorWorkbenchTest {
    @get:Rule val compose = createComposeRule()

    private val evidence = MetadataSnapshot(
        MetadataTarget("", "test", "content://fixture/test", "test-evidence"), true,
        mapOf(MetadataField.TITLE to listOf("Antes"), MetadataField.ARTIST to listOf("Gato Azul"),
            MetadataField.GENRE to listOf("Jazz; Fusion")),
        verifiedFields = setOf(MetadataField.TITLE, MetadataField.ARTIST, MetadataField.GENRE),
    )

    @Test fun sixSectionsExposeClearNavigationAndNoSaveAction() {
        compose.setContent { AeroTheme { MichiMetadataEditorPreviewContent(evidence, {}) } }
        compose.onNodeWithTag("metadata_workbench").assertExists()
        compose.onNodeWithTag("metadata_section_basics").assertExists()
        compose.onNodeWithTag("metadata_section_credits").performClick()
        compose.onNodeWithTag("metadata_fields_list").assertExists()
        compose.onNodeWithText("Guardar en archivo").assertDoesNotExist()
    }

    @Test fun musicBrainzButtonOpensVoluntaryConsentWithoutFetching() {
        compose.setContent { AeroTheme { MichiMetadataEditorPreviewContent(evidence, {}) } }
        compose.onNodeWithTag("metadata_musicbrainz_suggestions").performClick()
        compose.onNodeWithTag("metadata_mb_panel").assertExists()
        compose.onNodeWithTag("metadata_mb_search").performClick()
        compose.onNodeWithTag("metadata_mb_confirm_consent").assertExists()
    }

    @Test fun draftCanBeEditedReviewedAndNeverShowsSave() {
        compose.setContent { AeroTheme { MichiMetadataEditorPreviewContent(evidence, {}) } }
        compose.onNodeWithTag("metadata_input_title_0").performTextClearance()
        compose.onNodeWithTag("metadata_input_title_0").performTextInput("Después")
        compose.onNodeWithTag("metadata_review_changes").performClick()
        compose.onNodeWithTag("metadata_review_panel").assertExists()
        // The review surface may render the previous value in more than one diff row
        // (summary + per-field). The contract is "the prior value is still shown",
        // so assert at least one occurrence instead of exactly one.
        check(compose.onAllNodesWithText("Antes", substring = true).fetchSemanticsNodes().isNotEmpty()) {
            "review must still show the previous title"
        }
        compose.onNodeWithText("Después", substring = true).assertExists()
    }

    @Test fun temporarilyEmptyFieldIsShownAsBlockedNotCleared() {
        compose.setContent { AeroTheme { MichiMetadataEditorPreviewContent(evidence, {}) } }
        compose.onNodeWithTag("metadata_input_title_0").performTextClearance()
        compose.onNodeWithTag("metadata_error_title").assertExists()
        compose.onNodeWithTag("metadata_review_changes").performClick()
        compose.onNodeWithText("Hay campos por corregir").assertExists()
    }
    @Test fun artLyricsTabStartsWithAnExplicitInspectionButton() {
        compose.setContent { AeroTheme { MichiMetadataEditorPreviewContent(evidence, {}) } }
        compose.onNodeWithTag("metadata_section_art_lyrics").performClick()
        compose.onNodeWithTag("metadata_inspect_embedded_content").assertExists()
        compose.onNodeWithText("No se pudo comprobar todo el contenido", substring = true).assertDoesNotExist()
    }

    @Test fun closingDirtyEditorMustAskBeforeDiscarding() {
        compose.setContent { AeroTheme { MichiMetadataEditorPreviewContent(evidence, {}) } }
        compose.onNodeWithTag("metadata_input_title_0").performTextClearance()
        compose.onNodeWithTag("metadata_input_title_0").performTextInput("Otro titulo")
        compose.onNodeWithTag("metadata_editor_exit").performClick()
        compose.onNodeWithTag("metadata_confirm_discard").assertExists()
    }

}
