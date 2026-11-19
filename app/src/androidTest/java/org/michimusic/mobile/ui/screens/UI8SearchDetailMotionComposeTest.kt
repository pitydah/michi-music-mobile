package org.michimusic.mobile.ui.screens

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.theme.MichiTheme

/** Written for OpenCode's Android test runner; not executed in Drive. */
@RunWith(AndroidJUnit4::class)
class UI8SearchDetailMotionComposeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun artistAndAlbumReuseOneAccessibleDialogFrame() {
        rule.setContent {
            MichiTheme {
                UI8SearchDetailDialog(onDismiss = {}) { Text("Detalle musical") }
            }
        }
        rule.onNodeWithTag("ui8_search_detail_motion_frame").assertExists()
    }
}
