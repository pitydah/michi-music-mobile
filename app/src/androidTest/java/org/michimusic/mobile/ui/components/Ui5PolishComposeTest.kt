package org.michimusic.mobile.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.screens.HomeHeroItem
import org.michimusic.mobile.ui.screens.HomeHeroKind
import org.michimusic.mobile.ui.theme.MichiTheme

/** Real-font checks for the refined editorial hero and its action hierarchy. */
@RunWith(AndroidJUnit4::class)
class Ui5PolishComposeTest {
    @get:Rule val rule = createComposeRule()

    private data class Scenario(val width: Int, val scale: Float)
    private val testDensity = 2f
    private val minTargetPx = 48.dp.value * testDensity
    private val items = listOf(
        HomeHeroItem(
            key = "track:t1", kind = HomeHeroKind.TRACK, sourceId = "t1",
            title = "First Song", subtitle = "Artist A", meta = "12 canciones en tu colección",
            coverIds = emptyList(), fallbackMediaUri = null,
            trackIds = listOf("t1"), primaryLabel = "Escuchar",
        ),
        HomeHeroItem(
            key = "album:a1", kind = HomeHeroKind.ALBUM, sourceId = "a1",
            title = "Album A", subtitle = "Artist A", meta = "10 canciones · Álbum",
            coverIds = emptyList(), fallbackMediaUri = null,
            trackIds = listOf("t1"), primaryLabel = "Escuchar",
            primaryAccessibilityLabel = "Reproducir álbum Album A",
        ),
    )

    @Test
    fun editorialHeroKeepsActionsLegibleAndTouchableAcrossRepresentativeSizes() {
        val scenarios = listOf(Scenario(320, 1f), Scenario(360, 1f), Scenario(360, 1.5f), Scenario(480, 2f))
        var scenario by mutableStateOf(scenarios.first())
        rule.setContent {
            val configuration = Configuration().apply {
                screenWidthDp = scenario.width
                fontScale = scenario.scale
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density = testDensity, fontScale = scenario.scale),
            ) {
                MichiTheme {
                    Box(Modifier.width(scenario.width.dp)) {
                        AeroHomeHeroCarousel(
                            items = items,
                            onPrimary = {},
                            onPlayAll = {},
                            onShuffle = {},
                        )
                    }
                }
            }
        }

        for (current in scenarios) {
            rule.runOnIdle { scenario = current }
            rule.waitForIdle()
            listOf("m60_feature_play", "quick_play_button", "shuffle_button").forEach { tag ->
                val bounds = rule.onNodeWithTag(tag).assertExists().fetchSemanticsNode().boundsInRoot
                assertTrue("$tag width at $current", bounds.width >= minTargetPx)
                assertTrue("$tag height at $current", bounds.height >= minTargetPx)
                val layout = labelLayout("${tag}_label")
                assertFalse("$tag label ellipsis at $current", layout.isLineEllipsized(layout.lineCount - 1))
            }
            rule.onNodeWithTag("quick_play_button")
                .assertContentDescriptionEquals("Reproducir todas las canciones")
            rule.onNodeWithTag("home_hero_page_indicator")
                .assertExists()
        }
    }

    @Test
    fun libraryTabsAndSecondaryFiltersKeepDistinctCompactHierarchy() {
        val scenarios = listOf(Scenario(320, 1f), Scenario(360, 1.5f), Scenario(480, 2f))
        var scenario by mutableStateOf(scenarios.first())
        rule.setContent {
            val configuration = Configuration().apply {
                screenWidthDp = scenario.width
                fontScale = scenario.scale
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density = testDensity, fontScale = scenario.scale),
            ) {
                MichiTheme {
                    Box(Modifier.width(scenario.width.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AeroSegmentRail(compact = true) {
                                listOf(
                                    "library_tab_songs" to "Canciones",
                                    "library_tab_albums" to "Álbumes",
                                    "library_tab_artists" to "Artistas",
                                ).forEach { (tag, label) ->
                                    AeroSegmentedChoice(
                                        label = label,
                                        selected = tag == "library_tab_songs",
                                        onClick = {},
                                        modifier = Modifier.weight(1f),
                                        compact = true,
                                        testTag = tag,
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                listOf(
                                    "library_filter_all" to "Todo",
                                    "library_filter_lossless" to "Lossless",
                                    "library_filter_downloaded" to "Descargadas",
                                ).forEach { (tag, label) ->
                                    AeroFilterChip(
                                        label = label,
                                        selected = tag == "library_filter_all",
                                        onClick = {},
                                        testTag = tag,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        for (current in scenarios) {
            rule.runOnIdle { scenario = current }
            rule.waitForIdle()
            listOf(
                "library_tab_songs", "library_tab_albums", "library_tab_artists",
                "library_filter_all", "library_filter_lossless", "library_filter_downloaded",
            ).forEach { tag ->
                val bounds = rule.onNodeWithTag(tag).assertExists().fetchSemanticsNode().boundsInRoot
                assertTrue("$tag width at $current", bounds.width >= minTargetPx)
                assertTrue("$tag height at $current", bounds.height >= minTargetPx)
                val layout = labelLayout("${tag}_label")
                assertFalse("$tag label ellipsis at $current", layout.isLineEllipsized(layout.lineCount - 1))
            }
        }
    }

    private fun labelLayout(tag: String): TextLayoutResult {
        val node = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
        assertNotNull("Missing text layout action for $tag", action)
        val results = mutableListOf<TextLayoutResult>()
        action!!.invoke(results)
        return results.last()
    }
}
