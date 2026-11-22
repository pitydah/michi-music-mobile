package org.michimusic.mobile.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataTarget
import org.michimusic.data.metadata.LocalDraftWrite
import org.michimusic.data.metadata.MetadataLocalDraftStore
import org.michimusic.data.metadata.StoredMetadataProposal
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
        compose.onNodeWithTag("metadata_section_identifiers").performClick()
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

    @Test fun reviewAndFooterUseRealPluralsWithoutPlaceholderParentheses() {
        compose.setContent { AeroTheme { MichiMetadataEditorPreviewContent(evidence, {}) } }
        compose.onNodeWithTag("metadata_input_title_0").performTextClearance()
        compose.onNodeWithTag("metadata_input_title_0").performTextInput("Después")
        compose.onNodeWithTag("metadata_input_artist_0").performTextClearance()
        compose.onNodeWithTag("metadata_input_artist_0").performTextInput("Otra banda")
        compose.onNodeWithText("Borrador · 2 cambios", substring = true).assertExists()
        compose.onNodeWithTag("metadata_review_changes").performClick()
        compose.onNodeWithTag("metadata_review_panel").assertExists()
        compose.onNodeWithText("2 campos modificados en 1 canción").assertExists()
        compose.onNodeWithText("cambio(s)", substring = true).assertDoesNotExist()
        compose.onNodeWithText("campo(s)", substring = true).assertDoesNotExist()
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

    @Test fun genreToolAddsAndRemovesValuesInsideTheSameDraft() {
        val genres = MetadataSnapshot(
            MetadataTarget("", "test", "content://fixture/test", "test-evidence"), true,
            mapOf(
                MetadataField.TITLE to listOf("Antes"),
                MetadataField.ARTIST to listOf("Gato Azul"),
                MetadataField.GENRE to listOf("Post-Punk", "New Wave", "Darkwave"),
            ),
            verifiedFields = setOf(MetadataField.TITLE, MetadataField.ARTIST, MetadataField.GENRE),
        )
        compose.setContent { AeroTheme { MichiMetadataEditorPreviewContent(genres, {}, focusGenre = true) } }
        compose.onNodeWithTag("metadata_genre_tool").assertExists()
        compose.onNodeWithTag("metadata_genre_input").performTextInput("Coldwave")
        compose.onNodeWithTag("metadata_genre_add").performClick()
        compose.onNodeWithText("Coldwave").assertExists()
        compose.onNodeWithTag("metadata_input_genre_3").assertExists()
        compose.onNodeWithText("Post-Punk").assertExists()
        // Remove the middle value only: the multivalue order of the rest is preserved.
        compose.onNodeWithTag("metadata_value_menu_genre_1").performClick()
        compose.onNodeWithTag("metadata_remove_genre_1").performClick()
        compose.onNodeWithText("New Wave").assertDoesNotExist()
        compose.onNodeWithText("Post-Punk").assertExists()
        compose.onNodeWithText("Darkwave").assertExists()
        // Still the same editor surface, never the retired second genre modal.
        compose.onNodeWithTag("metadata_workbench").assertExists()
        compose.onNodeWithTag("mi26_genre_editor_title").assertDoesNotExist()
    }

    @Test fun genreToolOnUnverifiedTagsExplainsWhyEditingIsUnavailable() {
        val unverified = MetadataSnapshot(
            MetadataTarget("", "test", "content://fixture/test", "test-evidence"), true,
            mapOf(MetadataField.TITLE to listOf("Antes")),
            verifiedFields = setOf(MetadataField.TITLE),
        )
        compose.setContent { AeroTheme { MichiMetadataEditorPreviewContent(unverified, {}, focusGenre = true) } }
        compose.onNodeWithTag("metadata_genre_tool").assertExists()
        // ME-11V: the unverified state is a discrete status indicator, expanded only
        // on demand. The field may hold a local provisional value, never a certified one.
        compose.onNodeWithTag("metadata_field_status_genre")
            .assertContentDescriptionEquals("Propuesta provisional")
        compose.onNodeWithTag("metadata_provisional_genre_input").assertExists()
        compose.onNodeWithTag("metadata_genre_input").assertDoesNotExist()
        compose.onNodeWithTag("metadata_genre_add").assertDoesNotExist()
        compose.onNodeWithTag("metadata_section_info").performClick()
        compose.onNodeWithTag("metadata_section_info_dialog").assertExists()
        compose.onNodeWithText("Géneros:", substring = true).assertExists()
    }

    @Test fun closingDirtyEditorMustAskBeforeDiscarding() {
        compose.setContent { AeroTheme { MichiMetadataEditorPreviewContent(evidence, {}) } }
        compose.onNodeWithTag("metadata_input_title_0").performTextClearance()
        compose.onNodeWithTag("metadata_input_title_0").performTextInput("Otro titulo")
        compose.onNodeWithTag("metadata_editor_exit").performClick()
        compose.onNodeWithTag("metadata_confirm_discard").assertExists()
    }

    @Test fun focusedGenreOpensTheIntegratedToolWithoutASecondSheet() {
        compose.setContent {
            AeroTheme { MichiMetadataEditorPreviewContent(evidence, {}, focusGenre = true) }
        }
        compose.onNodeWithTag("metadata_genre_tool").assertExists()
        compose.onNodeWithTag("metadata_genre_input").assertExists()
        compose.onNodeWithTag("metadata_genre_add").assertExists()
        compose.onNodeWithTag("metadata_genre_musicbrainz").assertExists()
    }

    @Test fun verifiedSummaryAndReviewExplainTheProposalWithoutClaimingAWrite() {
        compose.setContent { AeroTheme { MichiMetadataEditorPreviewContent(evidence, {}) } }
        compose.onNodeWithText("Etiquetas físicas verificadas").assertExists()
        compose.onNodeWithText("CANCION · BIBLIOTECA LOCAL", substring = true).assertExists()
        compose.onNodeWithTag("metadata_input_title_0").performTextClearance()
        compose.onNodeWithTag("metadata_input_title_0").performTextInput("Después")
        compose.onNodeWithTag("metadata_review_changes").performClick()
        compose.onNodeWithText("PASO 2 DE 2").assertExists()
        compose.onNodeWithText("Antes de preparar").assertExists()
        compose.onNodeWithText("Preparar crea un candidato privado verificable",
            substring = true).assertExists()
    }

    @Test fun provisionalProposalNeverEntersTheCertifiedReview() {
        val partial = MetadataSnapshot(
            MetadataTarget("", "test", "content://fixture/test", "test-evidence"), true,
            mapOf(MetadataField.TITLE to listOf("Antes")),
            verifiedFields = setOf(MetadataField.TITLE),
        )
        compose.setContent { AeroTheme { MichiMetadataEditorPreviewContent(partial, {}) } }
        compose.onNodeWithTag("metadata_provisional_album_0").performTextInput("Álbum soñado")
        compose.onNodeWithText("Propuesta provisional · no se revisa ni se escribe").assertExists()
        compose.onNodeWithTag("metadata_input_title_0").performTextClearance()
        compose.onNodeWithTag("metadata_input_title_0").performTextInput("Después")
        compose.onNodeWithText("Borrador · 1 cambio", substring = true).assertExists()
        compose.onNodeWithTag("metadata_review_changes").performClick()
        compose.onNodeWithTag("metadata_review_provisional").assertExists()
        compose.onNodeWithText("No forman parte de esta revisión", substring = true).assertExists()
        compose.onNodeWithText("1 campo modificado en 1 canción").assertExists()
    }

    @Test fun artLyricsNoteDoesNotOverlapTheSectionHeader() {
        compose.setContent { AeroTheme { MichiMetadataEditorPreviewContent(evidence, {}) } }
        compose.onNodeWithTag("metadata_section_art_lyrics").performClick()
        val note = compose.onNodeWithText("Inspección voluntaria disponible", substring = true)
            .fetchSemanticsNode().boundsInRoot
        val inspection = compose.onNodeWithText("Inspección de arte y entradas de texto")
            .fetchSemanticsNode().boundsInRoot
        check(note.bottom <= inspection.top) {
            "The Art/Lyrics note must not overlap the next heading: note=$note inspection=$inspection"
        }
    }

    @Test fun returningToTheOriginalValueLeavesZeroEffectiveChanges() {
        val genre = MetadataSnapshot(
            MetadataTarget("", "test", "content://fixture/test", "test-evidence"), true,
            mapOf(MetadataField.TITLE to listOf("Antes"), MetadataField.GENRE to listOf("Rock")),
            verifiedFields = setOf(MetadataField.TITLE, MetadataField.GENRE),
        )
        compose.setContent { AeroTheme { MichiMetadataEditorPreviewContent(genre, {}, focusGenre = true) } }
        compose.onNodeWithTag("metadata_input_genre_0").performTextClearance()
        compose.onNodeWithTag("metadata_input_genre_0").performTextInput("Rock")
        compose.onNodeWithText("Borrador sin cambios").assertExists()
        compose.onNodeWithText("Borrador ·", substring = true).assertDoesNotExist()
    }

    @Test fun multipleValuesKeepLiteralSeparatorsAndOrder() {
        val withSeparators = MetadataSnapshot(
            MetadataTarget("", "test", "content://fixture/test", "test-evidence"), true,
            mapOf(
                MetadataField.TITLE to listOf("Antes"),
                MetadataField.GENRE to listOf("Jazz; Fusion", "Rock"),
            ),
            verifiedFields = setOf(MetadataField.TITLE, MetadataField.GENRE),
        )
        compose.setContent {
            AeroTheme { MichiMetadataEditorPreviewContent(withSeparators, {}, focusGenre = true) }
        }
        compose.onNodeWithText("Jazz; Fusion").assertExists()
        compose.onNodeWithText("Rock").assertExists()
        // The semicolon remains part of the first value, never a split delimiter.
        compose.onNodeWithTag("metadata_input_genre_0").assertExists()
    }

    @Test fun creditsWithNoValuesStartWithOneAddFieldAction() {
        compose.setContent { AeroTheme { MichiMetadataEditorPreviewContent(evidence, {}) } }
        compose.onNodeWithTag("metadata_section_credits").performClick()
        compose.onNodeWithText("Sin créditos añadidos").assertExists()
        compose.onNodeWithTag("metadata_provisional_composer_0").assertDoesNotExist()
        compose.onNodeWithTag("metadata_add_field").assertExists()
    }

    @Test fun emptyScalarIsEditableInOneTap() {
        val emptyTitle = evidence.copy(
            known = evidence.known - MetadataField.TITLE,
            verifiedFields = evidence.verifiedFields + MetadataField.TITLE,
            fieldObservations = mapOf(
                MetadataField.TITLE to org.michimusic.core.metadata.MetadataFieldObservation.VerifiedAbsent,
            ),
        )
        compose.setContent { AeroTheme { MichiMetadataEditorPreviewContent(emptyTitle, {}) } }
        compose.onNodeWithTag("metadata_input_title_0").performClick()
        compose.onNodeWithTag("metadata_input_title_0").assertIsFocused()
    }

    @Test fun capsuleEditorHasAStableMinimumTouchSurface() {
        var density = 1f
        compose.setContent {
            density = androidx.compose.ui.platform.LocalDensity.current.density
            AeroTheme { MichiMetadataEditorPreviewContent(evidence, {}) }
        }
        val bounds = compose.onNodeWithTag("metadata_capsule_title")
            .fetchSemanticsNode().boundsInRoot
        val heightDp = bounds.height / density
        check(heightDp in 49.5f..56.5f) {
            "Title capsule must keep the 50-56dp editable surface; was ${heightDp}dp"
        }
        check(compose.onAllNodesWithContentDescription("Eliminar Título")
                .fetchSemanticsNodes().isEmpty()) {
            "The destructive trash action must not be permanently visible"
        }
        val label = compose.onNodeWithTag("metadata_field_label_title")
            .fetchSemanticsNode().boundsInRoot
        val labelGap = (bounds.top - label.bottom) / density
        check(labelGap in 3f..8f) {
            "Label-to-capsule gap must stay near 5dp; was ${labelGap}dp"
        }
        val artistField = compose.onNodeWithTag("metadata_field_artist")
            .fetchSemanticsNode().boundsInRoot
        val fieldGap = (artistField.top - compose.onNodeWithTag("metadata_field_title")
            .fetchSemanticsNode().boundsInRoot.bottom) / density
        check(fieldGap in 10f..14f) {
            "Field-to-field gap must stay near 12dp; was ${fieldGap}dp"
        }
    }

    @Test fun destructiveDeleteNeedsConfirmationAndCancelPreservesTheDraft() {
        compose.setContent { AeroTheme { MichiMetadataEditorPreviewContent(evidence, {}) } }
        compose.onNodeWithTag("metadata_field_menu_title").performClick()
        compose.onNodeWithTag("metadata_menu_delete_title").performClick()
        compose.onNodeWithText("¿Eliminar Título?").assertExists()
        compose.onNodeWithTag("metadata_cancel_clear_title").performClick()
        compose.onNodeWithTag("metadata_input_title_0").assertExists()
        compose.onNodeWithTag("metadata_input_title_0").assertIsFocused()
        compose.onNodeWithText("Borrador ·", substring = true).assertDoesNotExist()

        compose.onNodeWithTag("metadata_field_menu_title").performClick()
        compose.onNodeWithTag("metadata_menu_delete_title").performClick()
        compose.onNodeWithTag("metadata_confirm_clear_title").performClick()
        compose.onNodeWithText("Se propone eliminar esta etiqueta", substring = true).assertExists()
        compose.onNodeWithText("Borrador · 1 cambio", substring = true).assertExists()
        compose.onNodeWithTag("metadata_editor_undo").performClick()
        compose.onNodeWithText("Borrador sin cambios").assertExists()
    }

    @Test fun restoreIsAvailableOnlyFromTheContextMenuAfterEditing() {
        compose.setContent { AeroTheme { MichiMetadataEditorPreviewContent(evidence, {}) } }
        compose.onNodeWithTag("metadata_input_title_0").performTextClearance()
        compose.onNodeWithTag("metadata_input_title_0").performTextInput("Otro")
        compose.onNodeWithTag("metadata_field_menu_title").performClick()
        compose.onNodeWithTag("metadata_menu_restore_title").performClick()
        compose.onNodeWithText("Borrador sin cambios").assertExists()
        compose.onNodeWithTag("metadata_input_title_0").assertExists()
    }

    @Test fun manyUnverifiedFieldsRenderOneNeutralSectionNotice() {
        val unverified = MetadataSnapshot(
            MetadataTarget("", "test", "content://fixture/test", "test-evidence"), true,
            mapOf(MetadataField.TITLE to listOf("Antes")),
            verifiedFields = setOf(MetadataField.TITLE),
        )
        compose.setContent { AeroTheme { MichiMetadataEditorPreviewContent(unverified, {}) } }
        compose.onNodeWithTag("metadata_section_credits").performClick()
        val notices = compose.onAllNodesWithText("Hay campos sin verificar", substring = true)
            .fetchSemanticsNodes()
        check(notices.size == 1) { "Expected one section notice, found ${notices.size}" }
        compose.onNodeWithText("El archivo no confirma este campo", substring = true)
            .assertDoesNotExist()
        compose.onNodeWithTag("metadata_section_info").performClick()
        compose.onNodeWithTag("metadata_section_info_dialog").assertExists()
    }

    @Test fun provisionalMultiValueFieldAcceptsAScratchValueWithoutBecomingDirty() {
        val partial = MetadataSnapshot(
            MetadataTarget("", "test", "content://fixture/test", "test-evidence"), true,
            mapOf(MetadataField.TITLE to listOf("Antes")),
            verifiedFields = setOf(MetadataField.TITLE),
        )
        compose.setContent { AeroTheme { MichiMetadataEditorPreviewContent(partial, {}) } }
        compose.onNodeWithTag("metadata_provisional_artist_0")
            .performTextInput("Artista provisional")
        compose.onNodeWithText("Propuesta provisional · no se revisa ni se escribe").assertExists()
        compose.onNodeWithText("Borrador ·", substring = true).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w600dp-h960dp-xhdpi")
    fun wideLayoutPairsRelatedFieldsWithoutHidingEitherValue() {
        val wideEvidence = evidence.copy(
            known = evidence.known + mapOf(
                MetadataField.ALBUM to listOf("Álbum de prueba"),
                MetadataField.ALBUM_ARTIST to listOf("Artista del álbum"),
            ),
            verifiedFields = evidence.verifiedFields + setOf(
                MetadataField.ALBUM, MetadataField.ALBUM_ARTIST,
            ),
        )
        compose.setContent { AeroTheme { MichiMetadataEditorPreviewContent(wideEvidence, {}) } }
        val album = compose.onNodeWithTag("metadata_field_album")
            .fetchSemanticsNode().boundsInRoot
        val albumArtist = compose.onNodeWithTag("metadata_field_album_artist")
            .fetchSemanticsNode().boundsInRoot
        check(albumArtist.left >= album.right) {
            "The second column must not overlap the first: album=$album artist=$albumArtist"
        }
        check(album.bottom > albumArtist.top && albumArtist.bottom > album.top) {
            "Album and album artist must share a horizontal band: album=$album artist=$albumArtist"
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun narrowWidthStacksTheVerificationStateUnderTheSectionTitle() {
        compose.setContent { AeroTheme { MichiMetadataEditorPreviewContent(evidence, {}) } }
        val title = compose.onNodeWithText("Información principal").fetchSemanticsNode().boundsInRoot
        val status = compose.onNodeWithText("● ORIGEN VERIFICADO").fetchSemanticsNode().boundsInRoot
        check(status.top >= title.bottom - 1f) {
            "At 320dp the state must stack below the title: title=$title status=$status"
        }
    }

    private class FakeDraftStore : MetadataLocalDraftStore {
        var saved: StoredMetadataProposal? = null
        override suspend fun save(
            expectedVersion: Long?,
            draft: StoredMetadataProposal,
        ): LocalDraftWrite {
            saved = draft
            return LocalDraftWrite.Saved(draft.draftId.ifBlank { "private-draft" }, 1L)
        }
        override suspend fun find(sourceId: String, trackId: String): StoredMetadataProposal? = null
        override suspend fun delete(draftId: String, expectedVersion: Long): Boolean = true
    }

    @Test fun saveDraftButtonPersistsEffectiveFieldsWithoutWritingAudio() {
        val store = FakeDraftStore()
        compose.setContent {
            AeroTheme {
                MichiMetadataEditorPreviewContent(
                    evidence, {},
                    expectedFileBytes = 1024L,
                    draftStore = store,
                )
            }
        }
        compose.onNodeWithTag("metadata_input_title_0").performTextClearance()
        compose.onNodeWithTag("metadata_input_title_0").performTextInput("Hard Rock")
        compose.onNodeWithTag("metadata_save_draft").performClick()
        compose.waitUntil(5_000) { store.saved != null }
        val saved = store.saved!!
        assertEquals("TITLE", saved.operations.single().field)
        assertEquals(listOf("Hard Rock"), saved.operations.single().values)
        compose.onNodeWithText("Borrador local guardado", substring = true).assertExists()
    }

}
