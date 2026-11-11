package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.theme.MichiTheme

@RunWith(AndroidJUnit4::class)
class PlaylistCollectionHeaderComposeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun densityActionRemainsTouchableAndDoesNotOverlapHeading() {
        var expanded by mutableStateOf(false)
        var width by mutableStateOf(320)
        var scale by mutableStateOf(2f)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, fontScale = scale)) {
                MichiTheme {
                    Box(Modifier.width(width.dp)) {
                        PlaylistCollectionHeader(
                            density = if (expanded) PlaylistTrackDensity.EXPANDED
                                else PlaylistTrackDensity.COMPACT,
                            onToggleDensity = { expanded = !expanded },
                        )
                    }
                }
            }
        }
        for ((w, fs) in listOf(320 to 2f, 360 to 1.5f, 393 to 1f)) {
            rule.runOnIdle { width = w; scale = fs }
            rule.waitForIdle()
            val title = rule.onNodeWithTag("playlist_detail_collection_title")
                .assertExists().fetchSemanticsNode().boundsInRoot
            val toggle = rule.onNodeWithTag("playlist_detail_density_toggle")
                .assertExists().assertHasClickAction().fetchSemanticsNode().boundsInRoot
            assertTrue("Expected >=48dp target at $w/$fs", toggle.height >= 96f)
            assertTrue("Title and action must not intersect at $w/$fs", !title.overlaps(toggle))
        }
        rule.onNodeWithTag("playlist_detail_density_toggle").performClick()
        rule.runOnIdle { assertEquals(true, expanded) }
    }
}
