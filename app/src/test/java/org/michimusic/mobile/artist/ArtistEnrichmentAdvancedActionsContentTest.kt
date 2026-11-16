package org.michimusic.mobile.artist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * K48-R07: the advanced actions are a standalone content component.
 *
 * The destructive removal must never be one tap away: it is collapsed, then it needs an
 * explicit confirmation that names the MBID and the number of albums. These are plain UI
 * contract tests over a state literal, with no controller, Koin or Room involved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ArtistEnrichmentAdvancedActionsContentTest {
    @get:Rule
    val compose = createComposeRule()

    private fun receipt() = EnrichmentDeleteReceipt(
        libraryId = "lib-1",
        artistCredit = "A-ha",
        albumIds = listOf("album-1", "album-2"),
        mbid = "a-ha-mbid",
        associationGroupKey = "group",
        sequence = 1L,
    )

    private fun render(
        delete: EnrichmentDeleteState = EnrichmentDeleteState.Idle,
        expanded: Boolean = true,
        refreshing: Boolean = false,
        onToggle: () -> Unit = {},
        onRefresh: () -> Unit = {},
        onRequestDelete: () -> Unit = {},
        onCancelDelete: () -> Unit = {},
        onConfirmDelete: () -> Unit = {},
    ) {
        compose.setContent {
            ArtistEnrichmentAdvancedActionsContent(
                artistCredit = "A-ha",
                mbid = "a-ha-mbid",
                albumCount = 2,
                refreshing = refreshing,
                delete = delete,
                expanded = expanded,
                onToggle = onToggle,
                onRefresh = onRefresh,
                onRequestDelete = onRequestDelete,
                onCancelDelete = onCancelDelete,
                onConfirmDelete = onConfirmDelete,
            )
        }
    }

    @Test
    fun collapsedAdvancedActionsRevealNoDestructiveControl() {
        render(expanded = false)
        compose.onNodeWithTag("artist_enrichment_advanced").assertIsDisplayed()
        compose.onNodeWithTag("artist_enrichment_refresh").assertDoesNotExist()
        compose.onNodeWithTag("artist_enrichment_clear").assertDoesNotExist()
        compose.onNodeWithTag("artist_enrichment_confirm_delete").assertDoesNotExist()
    }

    @Test
    fun expandingAndRemovingAreReportedToTheHost() {
        var toggles = 0
        var requests = 0
        render(expanded = false, onToggle = { toggles++ }, onRequestDelete = { requests++ })
        compose.onNodeWithTag("artist_enrichment_advanced").performClick()
        assertEquals(1, toggles)
        assertEquals(0, requests)
    }

    @Test
    fun theCollapsedLabelFlipsWhenExpanded() {
        var expanded by mutableStateOf(false)
        compose.setContent {
            ArtistEnrichmentAdvancedActionsContent(
                artistCredit = "A-ha", mbid = "a-ha-mbid", albumCount = 2,
                refreshing = false, delete = EnrichmentDeleteState.Idle, expanded = expanded,
                onToggle = { expanded = !expanded }, onRefresh = {}, onRequestDelete = {},
                onCancelDelete = {}, onConfirmDelete = {},
            )
        }
        compose.onNodeWithText("Opciones avanzadas").assertIsDisplayed()
        compose.onNodeWithTag("artist_enrichment_advanced").performClick()
        compose.onNodeWithText("Ocultar opciones avanzadas").assertIsDisplayed()
        compose.onNodeWithTag("artist_enrichment_clear").assertIsDisplayed()
    }

    @Test
    fun theRemovalAlwaysAsksForConfirmationFirst() {
        var requests = 0
        render(onRequestDelete = { requests++ })
        compose.onNodeWithTag("artist_enrichment_clear").assertIsEnabled()
        compose.onNodeWithText("Quitar datos enriquecidos…").assertIsDisplayed()
        compose.onNodeWithTag("artist_enrichment_clear").performClick()
        assertEquals(1, requests)
        // Requesting never confirms: the receipt is what actually deletes.
        compose.onNodeWithTag("artist_enrichment_delete_confirm").assertDoesNotExist()
    }

    @Test
    fun theConfirmationNamesExactlyWhatIsRemoved() {
        render(delete = EnrichmentDeleteState.Confirming(receipt()))
        compose.onNodeWithTag("artist_enrichment_confirm_delete").assertIsDisplayed()
        compose.onNodeWithText(
            "Se quitarán de A-ha los datos enriquecidos guardados (MBID a-ha-mbid, " +
                "biografía, fotografías y vínculos de 2 álbum(es)). " +
                "Tus archivos de audio, etiquetas y la biblioteca local no se modifican.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Esta acción no se puede deshacer desde aquí.").assertIsDisplayed()
        // The single-tap control disappears while the confirmation is pending.
        compose.onNodeWithTag("artist_enrichment_clear").assertDoesNotExist()
    }

    @Test
    fun cancellingAndConfirmingAreSeparateRoutes() {
        var cancels = 0
        var confirms = 0
        render(
            delete = EnrichmentDeleteState.Confirming(receipt()),
            onCancelDelete = { cancels++ },
            onConfirmDelete = { confirms++ },
        )
        compose.onNodeWithTag("artist_enrichment_delete_cancel").performClick()
        assertEquals(1, cancels)
        assertEquals(0, confirms)
        compose.onNodeWithTag("artist_enrichment_delete_confirm").performClick()
        assertEquals(1, cancels)
        assertEquals(1, confirms)
    }

    @Test
    fun aRunningDeletionDisablesTheTriggerAndSaysSo() {
        render(delete = EnrichmentDeleteState.Deleting(receipt()))
        compose.onNodeWithTag("artist_enrichment_clear").assertIsNotEnabled()
        compose.onNodeWithText("Borrando…").assertIsDisplayed()
    }

    @Test
    fun aRefreshInFlightDisablesOnlyTheRefreshControl() {
        var refreshes = 0
        render(refreshing = true, onRefresh = { refreshes++ })
        compose.onNodeWithTag("artist_enrichment_refresh").assertIsNotEnabled()
        compose.onNodeWithText("Actualizando…").assertIsDisplayed()
        // Removal stays available; a refresh never becomes a reason to block deletion.
        compose.onNodeWithTag("artist_enrichment_clear").assertIsEnabled()
        assertEquals(0, refreshes)
    }
}
