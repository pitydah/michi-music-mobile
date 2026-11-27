package org.michimusic.mobile.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextEquals
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
import org.michimusic.core.metadata.MetadataArtworkEvidence
import org.michimusic.core.metadata.MetadataArtworkLyricsProbe
import org.michimusic.mobile.ui.theme.AeroTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi", application = android.app.Application::class)
class MichiMetadataArtworkEditorTest {
    @get:Rule val compose = createComposeRule()

    private fun jpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.BLUE)
        val out = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out))
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun snapshot(withArtwork: Boolean): MetadataArtworkLyricsProbe.Snapshot {
        val bytes = jpeg(100, 80)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        val artworks = if (withArtwork) listOf(
            MetadataArtworkLyricsProbe.Artwork(
                0,
                MetadataArtworkEvidence.Picture(
                    MetadataArtworkEvidence.Format.ID3_APIC, 3, "image/jpeg",
                    bytes.size, sha, 100, 80,
                ),
                bytes,
            ),
        ) else emptyList()
        return MetadataArtworkLyricsProbe.Snapshot(
            MetadataArtworkLyricsProbe.Format.ID3_V23, artworks, emptyList(),
        )
    }

    private fun setSection(withArtwork: Boolean) {
        val snapshot = snapshot(withArtwork)
        val holder = mutableStateOf(
            MetadataArtLyricsUiState(load = MetadataArtLyricsUiState.Load.Ready(snapshot)),
        )
        compose.setContent {
            AeroTheme {
                MichiMetadataArtworkLyricsSection(
                    sourceUri = "content://fixture/test",
                    state = holder.value,
                    onChange = { holder.value = it },
                    canInspect = true,
                )
            }
        }
    }

    @Test
    fun existingArtworkShowsRealResolutionAndDifferentOperations() {
        setSection(true)
        compose.onNodeWithText("100 × 80 px", substring = true).assertExists()
        compose.onNodeWithTag("metadata_change_artwork").assertExists()
        compose.onNodeWithTag("metadata_remove_artwork").assertExists()
        compose.onNodeWithTag("metadata_artwork_resize").assertExists()
        compose.onNodeWithTag("metadata_add_artwork").assertDoesNotExist()
    }

    @Test
    fun pixelEditorProducesAnInMemoryProposal() {
        setSection(true)
        compose.onNodeWithTag("metadata_artwork_resize").performClick()
        compose.onNodeWithTag("metadata_artwork_width").assertTextEquals("100")
        compose.onNodeWithTag("metadata_artwork_height").assertTextEquals("80")
        compose.onNodeWithTag("metadata_artwork_width").performTextClearance()
        compose.onNodeWithTag("metadata_artwork_width").performTextInput("50")
        compose.onNodeWithTag("metadata_artwork_height").performTextClearance()
        compose.onNodeWithTag("metadata_artwork_height").performTextInput("40")
        compose.onNodeWithTag("metadata_artwork_apply").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Carátula propuesta", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Carátula propuesta", substring = true).assertExists()
    }

    @Test
    fun removeIsExplicitAndRequiredBeforeConfirmation() {
        setSection(true)
        compose.onNodeWithTag("metadata_remove_artwork").performClick()
        compose.onNodeWithTag("metadata_confirm_remove_artwork").assertExists()
        compose.onNodeWithTag("metadata_confirm_remove_artwork").performClick()
        compose.onNodeWithText("La carátula se quitará al guardar.").assertExists()
    }

    @Test
    fun addIsOnlyOfferedWhenNoArtworkExists() {
        setSection(false)
        compose.onNodeWithTag("metadata_add_artwork").assertExists()
        compose.onNodeWithTag("metadata_change_artwork").assertDoesNotExist()
        compose.onNodeWithTag("metadata_remove_artwork").assertDoesNotExist()
    }

    // Robolectric #8460: a width qualifier above 320dp plus a text field inside
    // a Dialog can loop during layout. The default 320dp viewport exercises the
    // same Workbench semantics without that framework bug.
    @Test
    @Config(qualifiers = "xhdpi")
    fun absentDescribedTextIsProposedThroughTheWorkbench() {
        setSection(false)
        compose.onNodeWithTag("metadata_add_described_text").performClick()
        compose.onNodeWithTag("metadata_add_described_content")
            .performTextInput("Comentario nuevo")
        compose.onNodeWithTag("metadata_add_described_confirm").performClick()
        compose.onNodeWithText("Nueva entrada", substring = true).assertExists()
        compose.onNodeWithTag("metadata_described_new_COMMENT_eng_").assertExists()
    }
}
