package org.michimusic.mobile.ui.screens

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.theme.MichiTheme

/** UI contract: two separate actions, with no second or duplicated playback. */
@RunWith(AndroidJUnit4::class)
class PlaylistPrimaryActionsComposeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun primaryAndSecondaryActionsAreIndependentAndAccessible() {
        var play by mutableIntStateOf(0)
        var shuffle by mutableIntStateOf(0)
        rule.setContent {
            MichiTheme {
                PlaylistPrimaryActions(
                    onPlay = { play++ },
                    onShuffle = { shuffle++ },
                )
            }
        }
        rule.onNodeWithTag("playlist_detail_play_all").assertExists()
            .assertHasClickAction().performClick()
        rule.onNodeWithTag("playlist_detail_shuffle_all").assertExists()
            .assertHasClickAction().performClick()
        rule.runOnIdle {
            assertEquals(1, play)
            assertEquals(1, shuffle)
        }
    }

    @Test fun narrowAndLargeFontLayoutKeepsActionsInOneRow() {
        val compactConfig = Configuration().apply {
            screenWidthDp = 320
            fontScale = 2f
        }
        rule.setContent {
            CompositionLocalProvider(LocalConfiguration provides compactConfig) {
                MichiTheme {
                    PlaylistPrimaryActions(onPlay = {}, onShuffle = {})
                }
            }
        }
        val playBounds = rule.onNodeWithTag("playlist_detail_play_all")
            .fetchSemanticsNode().boundsInRoot
        val shuffleBounds = rule.onNodeWithTag("playlist_detail_shuffle_all")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Primary and secondary actions must share one row at 320dp / font 2x",
            kotlin.math.abs(playBounds.top - shuffleBounds.top) <= 1f,
        )
        assertTrue(
            "The secondary action must start after the primary action",
            playBounds.right <= shuffleBounds.left,
        )
        assertTrue("Primary action must keep a 48dp target", playBounds.height >= 48f)
        assertTrue("Secondary action must keep a 48dp target", shuffleBounds.height >= 48f)
    }

    @Test fun responsivePolicyUsesCompactSingleRowForExtremeLayouts() {
        assertTrue(playlistPrimaryActionsCompact(320f, 2f))
        org.junit.Assert.assertFalse(playlistPrimaryActionsCompact(360f, 1.3f))
        org.junit.Assert.assertFalse(playlistPrimaryActionsCompact(393f, 1f))
        org.junit.Assert.assertFalse(playlistPrimaryActionsCompact(412f, 1f))
        assertTrue(playlistPrimaryActionsCompact(600f, 2f))
    }
}
