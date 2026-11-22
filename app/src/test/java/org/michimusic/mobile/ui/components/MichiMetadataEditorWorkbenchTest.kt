package org.michimusic.mobile.ui.components

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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

/** Robolectric/Compose source tests for the single two-action metadata editor. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi", application = android.app.Application::class)
class MichiMetadataEditorWorkbenchTest {
    @get:Rule val compose = createComposeRule()

    private val evidence = MetadataSnapshot(
        MetadataTarget("", "test", "content://fixture/test", "test-evidence"), true,
        mapOf(
            MetadataField.TITLE to listOf("Antes"),
            MetadataField.ARTIST to listOf("Gato Azul"),
            MetadataField.GENRE to listOf("Jazz; Fusion"),
        ),
        verifiedFields = setOf(MetadataField.TITLE, MetadataField.ARTIST, MetadataField.GENRE),
    )

    private fun setEditor(snapshot: MetadataSnapshot = evidence, focusGenre: Boolean = false) {
        compose.setContent {
            AeroTheme { MichiMetadataEditorPreviewContent(snapshot, {}, focusGenre = focusGenre) }
        }
    }

    private fun scrollTo(text: String) {
        compose.onNodeWithTag("metadata_fields_list")
            .performScrollToNode(hasText(text))
    }

    @Test
    fun fourSectionsAndOnlyTwoPrimaryActions() {
        setEditor()
        compose.onNodeWithTag("metadata_section_basics").assertExists()
        compose.onNodeWithTag("metadata_section_credits").assertExists()
        compose.onNodeWithTag("metadata_section_discography").assertExists()
        compose.onNodeWithTag("metadata_section_art_lyrics").assertExists()
        compose.onNodeWithTag("metadata_section_identifiers").assertDoesNotExist()
        compose.onNodeWithTag("metadata_section_advanced").assertDoesNotExist()
        compose.onNodeWithTag("metadata_save_draft").assertDoesNotExist()
        compose.onNodeWithTag("metadata_review_changes").assertDoesNotExist()
        compose.onNodeWithTag("metadata_open_private_preparation").assertDoesNotExist()
        compose.onNodeWithTag("metadata_revert_changes").assertIsNotEnabled()
        compose.onNodeWithTag("metadata_save_file").assertIsNotEnabled()
        compose.onNodeWithText("No se puede guardar este tipo de archivo todavía").assertExists()
        compose.onNodeWithTag("metadata_save_detail").performClick()
        compose.onNodeWithTag("metadata_save_detail_dialog").assertExists()
    }

    @Test
    fun basicsAlwaysShowsCommonFieldsIncludingComposerAndBpm() {
        setEditor()
        compose.onNodeWithText("Información principal").assertExists()
        compose.onNodeWithText("Título").assertExists()
        compose.onNodeWithText("Artistas").assertExists()
        compose.onNodeWithText("Álbum").assertExists()
        compose.onNodeWithText("Artistas del álbum").assertExists()
        compose.onNodeWithText("Géneros").assertExists()
        scrollTo("Compositores")
        compose.onNodeWithText("Compositores").assertExists()
        scrollTo("BPM")
        compose.onNodeWithText("BPM").assertExists()
    }

    @Test
    fun yearAndTrackDiscPairsUseCompactGeometry() {
        var density = 1f
        compose.setContent {
            density = androidx.compose.ui.platform.LocalDensity.current.density
            AeroTheme { MichiMetadataEditorPreviewContent(evidence, {}) }
        }
        scrollTo("Fecha de publicación")
        val date = compose.onNodeWithTag("metadata_field_release_date")
            .fetchSemanticsNode().boundsInRoot
        check(date.width / density <= 172f) {
            "The year/date field must stay compact; was ${date.width / density}dp including margins"
        }
        scrollTo("Pista")
        compose.onNodeWithTag("metadata_input_track_number_0").assertExists()
        compose.onNodeWithTag("metadata_input_track_total_0").assertExists()
        compose.onNodeWithText("Pista").assertExists()
        compose.onNodeWithText("Disco").assertExists()
        compose.onNodeWithText("Total de pistas", substring = true).assertDoesNotExist()
    }

    @Test
    fun creditsAlwaysShowsCommonFieldsAndComposerIsNotDuplicated() {
        setEditor()
        compose.onNodeWithTag("metadata_section_credits").performClick()
        compose.onNodeWithText("Intérpretes").assertExists()
        compose.onNodeWithText("Letristas").assertExists()
        compose.onNodeWithText("Productores").assertExists()
        compose.onNodeWithText("Ingeniería de sonido").assertExists()
        compose.onNodeWithTag("metadata_field_composer").assertDoesNotExist()
        compose.onNodeWithText("Sin créditos añadidos").assertDoesNotExist()
        scrollTo("+ Añadir campo")
        compose.onNodeWithTag("metadata_add_field").assertExists()
    }

    @Test
    fun discographyAlwaysShowsCommonFieldsEvenWhenEmpty() {
        setEditor()
        compose.onNodeWithTag("metadata_section_discography").performClick()
        compose.onNodeWithText("Sello discográfico").assertExists()
        compose.onNodeWithText("Número de catálogo").assertExists()
        scrollTo("ISRC")
        compose.onNodeWithText("ISRC").assertExists()
        compose.onNodeWithText("Sin datos de discografía añadidos").assertDoesNotExist()
    }

    @Test
    fun genresAreOrdinaryValuesWithoutResidentTools() {
        setEditor(focusGenre = true)
        compose.onNodeWithText("Jazz; Fusion").assertExists()
        compose.onNodeWithTag("metadata_genre_input").assertDoesNotExist()
        compose.onNodeWithTag("metadata_genre_add").assertDoesNotExist()
        compose.onNodeWithTag("metadata_genre_musicbrainz").assertDoesNotExist()
        compose.onNodeWithTag("metadata_genre_tool").assertDoesNotExist()
    }

    @Test
    fun multivalueAddAndRemoveKeepOrderAndSeparators() {
        setEditor(focusGenre = true)
        compose.onNodeWithTag("metadata_input_genre_0").performClick()
        compose.onNodeWithTag("metadata_add_value_genre").performClick()
        compose.onNodeWithTag("metadata_pending_genre_0").performTextInput("Coldwave")
        compose.onNodeWithTag("metadata_input_genre_1").assertExists()
        compose.onNodeWithText("Jazz; Fusion").assertExists()
        compose.onNodeWithText("Coldwave").assertExists()
        compose.onNodeWithText("1 modificado").assertExists()
        compose.onNodeWithTag("metadata_value_menu_genre_1").performClick()
        compose.onNodeWithTag("metadata_remove_genre_1").performClick()
        compose.onNodeWithText("Coldwave").assertDoesNotExist()
        compose.onNodeWithText("Jazz; Fusion").assertExists()
        compose.onNodeWithText("modificado", substring = true).assertDoesNotExist()
    }

    @Test
    fun returningToTheOriginalValueLeavesZeroEffectiveChanges() {
        val genre = MetadataSnapshot(
            MetadataTarget("", "test", "content://fixture/test", "test-evidence"), true,
            mapOf(
                MetadataField.TITLE to listOf("Antes"),
                MetadataField.GENRE to listOf("Rock"),
            ),
            verifiedFields = setOf(MetadataField.TITLE, MetadataField.GENRE),
        )
        setEditor(genre, focusGenre = true)
        compose.onNodeWithTag("metadata_input_genre_0").performTextClearance()
        compose.onNodeWithTag("metadata_input_genre_0").performTextInput("Rock")
        compose.onNodeWithText("modificado", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("metadata_revert_changes").assertIsNotEnabled()
    }

    @Test
    fun editingEnablesRevertButNeverEnablesSave() {
        setEditor()
        compose.onNodeWithTag("metadata_input_title_0").performTextClearance()
        compose.onNodeWithTag("metadata_input_title_0").performTextInput("Después")
        compose.onNodeWithText("1 modificado").assertExists()
        compose.onNodeWithTag("metadata_revert_changes").assertIsEnabled()
        compose.onNodeWithTag("metadata_save_file").assertIsNotEnabled()
    }

    @Test
    fun revertRestoresTheReadSnapshotWithoutWriting() {
        setEditor()
        compose.onNodeWithTag("metadata_input_title_0").performTextClearance()
        compose.onNodeWithTag("metadata_input_title_0").performTextInput("Después")
        compose.onNodeWithTag("metadata_revert_changes").performClick()
        compose.onNodeWithTag("metadata_confirm_revert").performClick()
        compose.onNodeWithTag("metadata_input_title_0").assertTextEquals("Antes")
        compose.onNodeWithText("modificado", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("metadata_revert_changes").assertIsNotEnabled()
    }

    @Test
    fun closingDirtyEditorAsksBeforeDiscarding() {
        setEditor()
        compose.onNodeWithTag("metadata_input_title_0").performTextClearance()
        compose.onNodeWithTag("metadata_input_title_0").performTextInput("Otro título")
        compose.onNodeWithTag("metadata_editor_exit").performClick()
        compose.onNodeWithTag("metadata_confirm_discard").assertExists()
        compose.onNodeWithTag("metadata_keep_editing").assertExists()
        compose.onNodeWithTag("metadata_save_and_exit").assertDoesNotExist()
    }

    @Test
    fun temporaryEmptyTextIsAnErrorNotAnExplicitDelete() {
        setEditor()
        compose.onNodeWithTag("metadata_input_title_0").performTextClearance()
        compose.onNodeWithTag("metadata_error_title").assertExists()
        compose.onNodeWithText("Se eliminará esta etiqueta", substring = true).assertDoesNotExist()
    }

    @Test
    fun invalidEmptyTitleCanStillBeReverted() {
        setEditor()
        compose.onNodeWithTag("metadata_input_title_0").performTextClearance()
        compose.onNodeWithTag("metadata_error_title").assertExists()
        compose.onNodeWithTag("metadata_revert_changes").assertIsEnabled()
        compose.onNodeWithTag("metadata_revert_changes").performClick()
        compose.onNodeWithTag("metadata_confirm_revert").performClick()
        compose.onNodeWithTag("metadata_input_title_0").assertTextEquals("Antes")
    }

    @Test
    fun emptyScalarIsEditableInOneTap() {
        val emptyTitle = evidence.copy(
            known = evidence.known - MetadataField.TITLE,
            verifiedFields = evidence.verifiedFields + MetadataField.TITLE,
            fieldObservations = mapOf(
                MetadataField.TITLE to
                    org.michimusic.core.metadata.MetadataFieldObservation.VerifiedAbsent,
            ),
        )
        setEditor(emptyTitle)
        compose.onNodeWithTag("metadata_input_title_0").performClick()
        compose.onNodeWithTag("metadata_input_title_0").assertIsFocused()
    }

    @Test
    fun capsuleKeepsAStableCompactTouchSurface() {
        var density = 1f
        compose.setContent {
            density = androidx.compose.ui.platform.LocalDensity.current.density
            AeroTheme { MichiMetadataEditorPreviewContent(evidence, {}) }
        }
        val bounds = compose.onNodeWithTag("metadata_capsule_title")
            .fetchSemanticsNode().boundsInRoot
        val heightDp = bounds.height / density
        check(heightDp in 49.5f..56.5f) {
            "Title capsule must keep the 50-56dp surface; was ${heightDp}dp"
        }
        val label = compose.onNodeWithTag("metadata_field_label_title")
            .fetchSemanticsNode().boundsInRoot
        val gap = (bounds.top - label.bottom) / density
        check(gap in 3f..8f) { "Label-to-capsule gap must stay near 5dp; was ${gap}dp" }
    }

    @Test
    fun contextualDeleteNeedsConfirmationAndCancelPreservesText() {
        setEditor()
        compose.onNodeWithTag("metadata_input_title_0").performClick()
        compose.onNodeWithTag("metadata_field_menu_title").performClick()
        compose.onNodeWithTag("metadata_menu_delete_title").performClick()
        compose.onNodeWithText("¿Eliminar Título?").assertExists()
        compose.onNodeWithTag("metadata_cancel_clear_title").performClick()
        compose.onNodeWithTag("metadata_input_title_0").assertTextEquals("Antes")
        compose.onNodeWithText("modificado", substring = true).assertDoesNotExist()
    }

    @Test
    fun addFieldSearchSelectsATypedIndependentRow() {
        setEditor()
        compose.onNodeWithTag("metadata_section_credits").performClick()
        scrollTo("+ Añadir campo")
        compose.onNodeWithTag("metadata_add_field").performClick()
        compose.onNodeWithTag("metadata_field_search").performTextInput("arreg")
        compose.onNodeWithTag("metadata_add_field_arranger").assertExists()
        compose.onNodeWithTag("metadata_add_field_remixer").assertDoesNotExist()
        compose.onNodeWithTag("metadata_add_field_arranger").performClick()
        scrollTo("Arreglistas")
        compose.onNodeWithTag("metadata_provisional_arranger_0").assertExists()
        compose.onNodeWithText("Remezcla").assertDoesNotExist()
    }

    @Test
    fun addingSecondValueCreatesAnIndependentCapsuleWithoutGrowingTheFirst() {
        val genres = MetadataSnapshot(
            MetadataTarget("", "test", "content://fixture/test", "test-evidence"), true,
            mapOf(
                MetadataField.TITLE to listOf("Antes"),
                MetadataField.GENRE to listOf("Post-Punk", "New Wave"),
            ),
            verifiedFields = setOf(MetadataField.TITLE, MetadataField.GENRE),
        )
        setEditor(genres, focusGenre = true)
        compose.onNodeWithTag("metadata_input_genre_0").performClick()
        val before = compose.onNodeWithTag("metadata_capsule_genre_0")
            .fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("metadata_add_value_genre").performClick()
        compose.onNodeWithTag("metadata_pending_genre_0").assertExists()
        compose.onNodeWithTag("metadata_pending_genre_0").performTextInput("Coldwave")
        compose.onNodeWithTag("metadata_capsule_genre_1").assertExists()
        val after = compose.onNodeWithTag("metadata_capsule_genre_0")
            .fetchSemanticsNode().boundsInRoot
        check(kotlin.math.abs(after.height - before.height) <= 1f) {
            "The existing capsule must not grow: before=${before.height} after=${after.height}"
        }
        compose.onNodeWithText("Post-Punk").assertExists()
        compose.onNodeWithText("New Wave").assertExists()
        compose.onNodeWithText("Coldwave").assertExists()
    }

    @Test
    fun emptyPendingValueCanBeDiscardedWithoutAnyEffectiveChange() {
        setEditor(focusGenre = true)
        compose.onNodeWithTag("metadata_input_genre_0").performClick()
        compose.onNodeWithTag("metadata_add_value_genre").performClick()
        compose.onNodeWithTag("metadata_pending_genre_0").assertExists()
        compose.onNodeWithText("modificado", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("metadata_save_file").assertIsNotEnabled()
        compose.onNodeWithTag("metadata_value_menu_genre_1").performClick()
        compose.onNodeWithTag("metadata_discard_pending_genre").performClick()
        compose.onNodeWithTag("metadata_pending_genre_0").assertDoesNotExist()
        compose.onNodeWithText("modificado", substring = true).assertDoesNotExist()
    }

    @Test
    fun artSectionNeverStartsWithInspectionProseOrButton() {
        setEditor()
        compose.onNodeWithTag("metadata_section_art_lyrics").performClick()
        compose.onNodeWithTag("metadata_inspect_embedded_content").assertDoesNotExist()
        compose.onNodeWithText("Inspección de arte y entradas de texto").assertDoesNotExist()
        compose.onNodeWithText("La escritura física aún no está certificada", substring = true)
            .assertDoesNotExist()
        scrollTo("Comentarios")
        compose.onNodeWithTag("metadata_provisional_comment_0").assertExists()
    }

    @Test
    fun musicBrainzLivesInTheSectionMenuNotInTheGenreField() {
        setEditor()
        compose.onNodeWithTag("metadata_section_menu").performClick()
        compose.onNodeWithTag("metadata_genre_open_tool").assertDoesNotExist()
        compose.onNodeWithTag("metadata_genre_musicbrainz").performClick()
        compose.onNodeWithTag("metadata_mb_panel").assertExists()
    }

    @Test
    fun unverifiedValuesAreLocalProposalsAndDoNotEnableSave() {
        val partial = MetadataSnapshot(
            MetadataTarget("", "test", "content://fixture/test", "test-evidence"), true,
            mapOf(MetadataField.TITLE to listOf("Antes")),
            verifiedFields = setOf(MetadataField.TITLE),
        )
        setEditor(partial)
        scrollTo("Álbum")
        compose.onNodeWithTag("metadata_provisional_album_0").performTextInput("Álbum soñado")
        compose.onNodeWithText("modificado", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("metadata_save_file").assertIsNotEnabled()
        compose.onNodeWithTag("metadata_revert_changes").assertIsEnabled()
    }

    @Test
    fun statusDetailIsOneSongLevelControl() {
        setEditor()
        compose.onAllNodesWithText("Etiquetas físicas verificadas").fetchSemanticsNodes().let {
            check(it.size == 1) { "Expected one song-level status, found ${it.size}" }
        }
        compose.onNodeWithTag("metadata_status_info").performClick()
        compose.onNodeWithTag("metadata_status_dialog").assertExists()
        compose.onNodeWithTag("metadata_section_info").assertDoesNotExist()
    }
}
