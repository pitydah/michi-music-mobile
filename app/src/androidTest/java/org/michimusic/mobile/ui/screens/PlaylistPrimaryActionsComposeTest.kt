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

    @Test fun narrowAndLargeFontLayoutStacksActionsWithoutOverlap() {
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
        assertTrue("Primary and secondary actions must stack at 320dp / font 2x",
            playBounds.bottom <= shuffleBounds.top)
    }

    @Test fun responsivePolicyMatchesTheCanonicalAeroHero() {
        // Reuse the existing tested aeroHeroActionsVertical policy, not a new breakpoint.
        assertTrue(org.michimusic.mobile.ui.components.aeroHeroActionsVertical(320, 2f))
        assertTrue(org.michimusic.mobile.ui.components.aeroHeroActionsVertical(360, 1.3f))
        org.junit.Assert.assertFalse(
            org.michimusic.mobile.ui.components.aeroHeroActionsVertical(412, 1f),
        )
    }
}
